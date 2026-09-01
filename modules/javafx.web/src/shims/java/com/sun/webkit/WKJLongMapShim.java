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

/**
 * Exposes the package-private {@link WKJLongMap} to its unit test, one fresh map per shim instance.
 */
public final class WKJLongMapShim {

    private final WKJLongMap<Object> map = new WKJLongMap<>();

    /**
     * See {@code WKJLongMap.get}.
     *
     * @param key the key
     * @return the value, or {@code null}
     */
    public Object get(long key) {
        return map.get(key);
    }

    /**
     * See {@code WKJLongMap.put}.
     *
     * @param key the key
     * @param value the value
     */
    public void put(long key, Object value) {
        map.put(key, value);
    }

    /**
     * See {@code WKJLongMap.remove}.
     *
     * @param key the key
     * @param expected the value the key must currently map to
     * @return true if the mapping was removed
     */
    public boolean remove(long key, Object expected) {
        return map.remove(key, expected);
    }

    /**
     * See {@code WKJLongMap.size}.
     *
     * @return the mapping count
     */
    public int size() {
        return map.size();
    }
}
