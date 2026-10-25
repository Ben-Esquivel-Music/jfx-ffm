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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assumptions.abort;

/**
 * The capture-or-verify flow of the platform font goldens, over the file format of {@link FontGoldens}. Each
 * platform supplies its guards: a binding witness that needs no native and runs in both modes, the machine gate,
 * a capture guard that refuses anything but the JNI build, a pristine-process guard for corpora that run in the
 * test JVM, and the {@code capture.}/{@code info.} keys.
 */
record FontGoldenHarness(Runnable bindingWitness, Supplier<Map<String, String>> gate, Runnable captureGuard,
                         Runnable pristineGuard, Consumer<GoldenMap> captureInfo) {

    /** A sorted map that rejects a second value for a key: a golden key written twice is a corpus bug. */
    static final class GoldenMap extends TreeMap<String, String> {

        GoldenMap() {
        }

        GoldenMap(Map<String, String> initial) {
            for (Map.Entry<String, String> entry : initial.entrySet()) {
                put(entry.getKey(), entry.getValue());
            }
        }

        @Override
        public String put(String key, String value) {
            if (containsKey(key)) {
                throw new IllegalStateException("golden key written twice: " + key);
            }
            return super.put(key, value == null ? FontGoldens.NULL : value);
        }
    }

    /** The body of a capture: fills {@code out}. */
    @FunctionalInterface
    interface Corpus {
        void capture(GoldenMap out) throws Exception;
    }

    /** A check that the values a golden holds can tell right from the wrong implementations it guards against. */
    @FunctionalInterface
    interface Coverage {
        void assertDiscriminating(Map<String, String> values);
    }

    /**
     * The gate runs before the corpus: on another machine the pinned files may not exist at all. A capture
     * ({@code -Djfx.font.golden.capture=true}) asserts the corpus is discriminating before writing, and then
     * aborts: a capture verifies nothing.
     *
     * @param inProcess whether the corpus runs in this JVM, and therefore needs the pristine-process guard
     */
    void captureOrVerify(Class<?> test, String golden, List<String> header, boolean inProcess, Corpus corpus,
                         Coverage coverage) throws Exception {
        bindingWitness.run();
        Map<String, String> machine = gate.get();
        if (FontGoldens.captureRequested()) {
            captureGuard.run();
            if (inProcess) {
                pristineGuard.run();
            }
            GoldenMap out = new GoldenMap(machine);
            captureInfo.accept(out);
            corpus.capture(out);
            coverage.assertDiscriminating(out);
            FontGoldens.write(golden, out, header);
            abort("golden captured to " + golden + "; a capture run verifies nothing");
        }
        FontGoldens loaded = FontGoldens.load(golden, test);
        loaded.assumeSameMachine(machine, test);
        if (inProcess) {
            pristineGuard.run();
        }
        GoldenMap out = new GoldenMap(machine);
        corpus.capture(out);
        coverage.assertDiscriminating(goldenValues(golden));
        loaded.assertSameContent(out);
    }

    /** All entries of the golden resource, including {@code machine.}, {@code info.} and {@code capture.} keys. */
    static Map<String, String> goldenValues(String golden) {
        try (InputStream in = FontGoldens.class.getResourceAsStream(golden)) {
            if (in == null) {
                throw new AssertionError("no golden file " + golden + " on the classpath");
            }
            return FontGoldens.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
