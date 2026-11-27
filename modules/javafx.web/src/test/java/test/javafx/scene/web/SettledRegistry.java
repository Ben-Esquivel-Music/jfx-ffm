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

package test.javafx.scene.web;

import com.sun.webkit.Disposer;
import com.sun.webkit.WebKitNativeShim;
import com.sun.webkit.WebPageShim;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;

/**
 * The size of the registry of Java objects that native code names, once it has settled: the
 * Disposer releases native peers after a JVM collection has queued them, and a JavaScriptCore
 * collection finalizes script wrappers; the size is read until three reads agree.
 */
final class SettledRegistry {

    private static final int MAX_SETTLE_ROUNDS = 12;
    private static final int AGREEING_READS = 2;

    private SettledRegistry() {
    }

    static int size() throws Exception {
        List<Integer> reads = new ArrayList<>();
        int agreeing = 0;
        for (int round = 0; round < MAX_SETTLE_ROUNDS; round++) {
            CountDownLatch disposed = new CountDownLatch(1);
            Disposer.addRecord(new Object(), disposed::countDown);
            for (int attempt = 0; attempt < MAX_SETTLE_ROUNDS && !disposed.await(500, TimeUnit.MILLISECONDS);
                    attempt++) {
                System.gc();
            }
            FutureTask<Void> collection = new FutureTask<>(WebPageShim::collectJavaScriptGarbage, null);
            Platform.runLater(collection);
            collection.get(10, TimeUnit.SECONDS);
            int read = WebKitNativeShim.registrySize();
            agreeing = !reads.isEmpty() && read == reads.getLast() ? agreeing + 1 : 0;
            reads.add(read);
            if (agreeing == AGREEING_READS) {
                return read;
            }
        }
        throw new AssertionError("the registry did not settle: " + reads);
    }
}
