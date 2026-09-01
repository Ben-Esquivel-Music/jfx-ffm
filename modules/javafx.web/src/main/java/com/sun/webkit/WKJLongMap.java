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

package com.sun.webkit;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * A concurrent map from primitive {@code long} keys to objects, for the {@code wkj_ref} registry in
 * {@link WebKitNative}.
 * <p>
 * A {@code ConcurrentHashMap<Long, V>} would box a {@code Long} on every operation, and
 * {@code lookup} sits on the hottest upcall path in this module - once per drawing primitive per
 * frame through {@code GraphicsUpcalls}, once per DOM listener dispatch, once per theme paint -
 * with ids that are monotonic from one and leave the {@code Long} cache almost immediately. This
 * map reads without allocating and without locking; writes, which happen only when a peer is
 * created or disposed, serialize on the map's own monitor.
 * <p>
 * The design is a fixed subset of {@code ConcurrentHashMap}'s: power-of-two bin array, the key
 * spread by folding the high half of the {@code long} and then the high half of the {@code int}
 * hash into the low bits, immutable nodes, and volatile publication of every bin head. A reader
 * that follows a bin head therefore sees a list that was complete when that head was stored;
 * removal and resizing build new lists rather than mutating one a reader may be walking. A read
 * racing a write may see the map either before or after that write, which is the same guarantee
 * {@code ConcurrentHashMap} gives.
 */
final class WKJLongMap<V> {

    /** Initial bin count. Must be a power of two, like every later size. */
    private static final int INITIAL_CAPACITY = 64;

    /** The bin count above which the table stops doubling: {@code 1 << 30} bins. */
    private static final int MAXIMUM_CAPACITY = 1 << 30;

    /** One immutable chain link; {@code next} is frozen at construction. */
    private static final class Node<V> {

        final long key;
        final V value;
        final Node<V> next;

        Node(long key, V value, Node<V> next) {
            this.key = key;
            this.value = value;
            this.next = next;
        }
    }

    /**
     * The current bins. Element reads and writes through {@link AtomicReferenceArray} are volatile,
     * which is what publishes a rebuilt chain to readers; the field itself is volatile so that a
     * resize publishes the new array the same way. The array a resize replaces is left intact, so a
     * reader still walking it sees the complete mappings of the moment it fetched the field.
     */
    private volatile AtomicReferenceArray<Node<V>> table =
            new AtomicReferenceArray<>(INITIAL_CAPACITY);

    /** Live mapping count. Written only under the monitor; volatile for the lock-free reader. */
    private volatile int size;

    /** Doubling point of the current table: three quarters of its bin count. */
    private int threshold = INITIAL_CAPACITY - (INITIAL_CAPACITY >>> 2);

    /**
     * Returns the value mapped to a key. Lock-free and allocation-free.
     *
     * @param key the key
     * @return the value, or {@code null} if the key is absent
     */
    V get(long key) {
        AtomicReferenceArray<Node<V>> bins = table;
        for (Node<V> node = bins.get(bin(key, bins.length())); node != null; node = node.next) {
            if (node.key == key) {
                return node.value;
            }
        }
        return null;
    }

    /**
     * Maps a key to a value, replacing any previous mapping.
     *
     * @param key the key
     * @param value the value, must not be {@code null}
     */
    synchronized void put(long key, V value) {
        Objects.requireNonNull(value, "a null value is indistinguishable from an absent key");
        if (size >= threshold) {
            grow();
        }
        AtomicReferenceArray<Node<V>> bins = table;
        int index = bin(key, bins.length());
        Node<V> head = bins.get(index);
        Node<V> stripped = without(head, key);
        if (stripped == head) {
            size = size + 1;
        }
        bins.set(index, new Node<>(key, value, stripped));
    }

    /**
     * Removes a mapping only if the key currently maps to the expected value, which is the
     * two-argument {@code ConcurrentHashMap.remove} the registry's release path relies on: a stale
     * releaser must not remove the entry a later owner re-created.
     *
     * @param key the key
     * @param expected the value the key must currently map to
     * @return true if the mapping was removed
     */
    synchronized boolean remove(long key, Object expected) {
        AtomicReferenceArray<Node<V>> bins = table;
        int index = bin(key, bins.length());
        Node<V> head = bins.get(index);
        for (Node<V> node = head; node != null; node = node.next) {
            if (node.key == key) {
                if (!Objects.equals(node.value, expected)) {
                    return false;
                }
                bins.set(index, without(head, key));
                size = size - 1;
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the number of live mappings.
     *
     * @return the mapping count
     */
    int size() {
        return size;
    }

    /*
     * The chain with the given key's node left out, sharing the untouched suffix; the head itself
     * when the key is absent, which is what put and remove branch on. The rebuilt prefix comes back
     * reversed, which changes nothing a caller can observe.
     */
    private static <V> Node<V> without(Node<V> head, long key) {
        for (Node<V> node = head; node != null; node = node.next) {
            if (node.key == key) {
                Node<V> rebuilt = node.next;
                for (Node<V> keep = head; keep != node; keep = keep.next) {
                    rebuilt = new Node<>(keep.key, keep.value, rebuilt);
                }
                return rebuilt;
            }
        }
        return head;
    }

    /*
     * Doubles the table. New nodes are built for the new bins so that the old array, which a
     * reader may still be walking, keeps its complete chains; the volatile store to `table` is
     * what makes the new array visible.
     */
    private void grow() {
        AtomicReferenceArray<Node<V>> old = table;
        int oldLength = old.length();
        if (oldLength >= MAXIMUM_CAPACITY) {
            threshold = Integer.MAX_VALUE;
            return;
        }
        int newLength = oldLength << 1;
        AtomicReferenceArray<Node<V>> grown = new AtomicReferenceArray<>(newLength);
        for (int i = 0; i < oldLength; i++) {
            for (Node<V> node = old.get(i); node != null; node = node.next) {
                int index = bin(node.key, newLength);
                grown.set(index, new Node<>(node.key, node.value, grown.get(index)));
            }
        }
        threshold = newLength - (newLength >>> 2);
        table = grown;
    }

    /*
     * Long.hashCode folds the high word into the low one; the extra shift then folds the high half
     * of that int into the bits a power-of-two mask keeps, ConcurrentHashMap.spread's trick, so
     * keys that differ only above bit 16 still land in different bins.
     */
    private static int bin(long key, int length) {
        int h = (int) (key ^ (key >>> 32));
        return (h ^ (h >>> 16)) & (length - 1);
    }
}
