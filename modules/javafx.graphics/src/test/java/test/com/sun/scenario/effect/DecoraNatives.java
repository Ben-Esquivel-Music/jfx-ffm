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

package test.com.sun.scenario.effect;

import com.sun.scenario.effect.impl.sw.RendererDelegate;
import com.sun.scenario.effect.impl.sw.sse.SSERendererDelegate;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assumptions.abort;

/**
 * The one decision the Decora SSE-versus-Java tests make about the native layer: is the
 * {@code decora_sse} library expected in this JVM, or is this a build that legitimately has none?
 * <p>
 * This module compiles {@code decora_sse} from source ({@code modules/javafx.graphics/native}, bound
 * to {@code process-classes}), so a library that is reachable and does not work is a broken build,
 * never an environment fact. This is {@code test.com.sun.pisces.PiscesNatives} adapted to the
 * {@code decora_sse} library: <em>skip only when no {@code decora_sse} exists on
 * {@code java.library.path} and no other javafx.graphics native library does either; otherwise it
 * has to load and work.</em>
 * <p>
 * The library is loaded the way production loads it: the static initializer of
 * {@link SSERendererDelegate} calls {@code NativeLibLoader.loadLibrary("decora_sse")} and its
 * constructor asks the library whether SSE2 is available.
 */
public final class DecoraNatives {

    private static final String LIBRARY_NAME = "decora_sse";

    /** {@code decora_sse.dll}, {@code libdecora_sse.so} or {@code libdecora_sse.dylib}. */
    private static final String LIBRARY_FILE = System.mapLibraryName(LIBRARY_NAME);

    /**
     * Other libraries the javafx.graphics CMake build writes into the same directory. Any of them next
     * to no {@code decora_sse} is a broken build, not a tree without natives. The list is a union of
     * witnesses, so an entry a platform does not build only shrinks it: {@code javafx_font} is built on
     * Linux and macOS only, Windows having had no {@code font} target since {@code directwrite.cpp}
     * was deleted.
     */
    private static final List<String> SIBLING_FILES = List.of(
            System.mapLibraryName("glass"), System.mapLibraryName("javafx_font"),
            System.mapLibraryName("javafx_iio"), System.mapLibraryName("prism_sw"));

    private static boolean decided;
    private static String skipReason;
    private static String failureMessage;
    private static Throwable failureCause;
    private static RendererDelegate sseDelegate;

    private DecoraNatives() {
    }

    /**
     * Loads {@code decora_sse} and returns the SSE renderer delegate, or skips the calling test when
     * this build has no javafx.graphics natives anywhere.
     * <p>
     * Decided once per JVM and then replayed: the library and any failure are per class loader, and
     * the Decora test classes share a surefire fork, so a second caller has to get the first caller's
     * verdict rather than a second load attempt.
     *
     * @return the {@link SSERendererDelegate} that names the native peers
     * @throws AssertionError if a {@code decora_sse} is reachable and cannot be used, which is a
     *         broken build; the message names every candidate on {@code java.library.path}
     */
    public static synchronized RendererDelegate requireSse() {
        if (!decided) {
            decide();
            decided = true;
        }
        if (failureMessage != null) {
            throw new AssertionError(failureMessage, failureCause);
        }
        if (skipReason != null) {
            abort(skipReason);
        }
        return sseDelegate;
    }

    private static void decide() {
        List<Path> entries = libraryPathEntries();
        List<Path> reachable = new ArrayList<>();
        List<Path> builtWithoutIt = new ArrayList<>();
        for (Path dir : entries) {
            if (Files.isRegularFile(dir.resolve(LIBRARY_FILE))) {
                reachable.add(dir.resolve(LIBRARY_FILE));
            } else if (SIBLING_FILES.stream().anyMatch(name -> Files.isRegularFile(dir.resolve(name)))) {
                builtWithoutIt.add(dir);
            }
        }
        if (reachable.isEmpty()) {
            if (!builtWithoutIt.isEmpty()) {
                failureMessage = "this build produced javafx.graphics natives but not the one under test: "
                        + builtWithoutIt + " hold other libraries of this module and no " + LIBRARY_FILE
                        + ", so the native build ran and reported success without building the library"
                        + " these tests exist for. A renamed target, a condition that skipped it or a"
                        + " changed output directory is a broken build, not a tree that has no natives,"
                        + " and skipping here would report zero Decora parity tests and a green run.";
                return;
            }
            skipReason = "this build has no javafx.graphics natives: no " + LIBRARY_FILE
                    + " on java.library.path " + entries + ", so there is nothing here to compare"
                    + " against. Build them with \"mvn -pl modules/javafx.graphics test\", that is,"
                    + " without -DskipNative=true.";
            return;
        }
        try {
            sseDelegate = new SSERendererDelegate();
        } catch (UnsupportedOperationException e) {
            // The library loaded and reports that this processor lacks SSE2: production falls back
            // to the Java peers here too, so there is no native output to compare against.
            skipReason = "decora_sse loaded but reports no SSE2 support on this processor: " + e;
        } catch (LinkageError | RuntimeException e) {
            failureMessage = failureText(reachable, e);
            failureCause = e;
        }
    }

    private static String failureText(List<Path> reachable, Throwable cause) {
        StringBuilder text = new StringBuilder(512);
        text.append("the decora_sse library is reachable but unusable, which is a broken build and never")
                .append(" an environment fact: ").append(cause).append('\n');
        for (Path library : reachable) {
            text.append("  reachable on java.library.path: ").append(library).append(describe(library))
                    .append('\n');
        }
        text.append("Rebuild the natives with \"mvn -pl modules/javafx.graphics process-classes\" and")
                .append(" check that the library exports every Java_com_sun_scenario_effect_impl_sw_sse_*")
                .append(" symbol the SSE peers declare.");
        return text.toString();
    }

    /** {@code " (N bytes, modified T)"}, or an empty string when the file cannot be inspected. */
    private static String describe(Path library) {
        try {
            return " (" + Files.size(library) + " bytes, modified " + Files.getLastModifiedTime(library)
                    + ")";
        } catch (IOException e) {
            return "";
        }
    }

    /** The {@code java.library.path} entries, in the order the loader tries them. */
    private static List<Path> libraryPathEntries() {
        List<Path> entries = new ArrayList<>();
        for (String entry : System.getProperty("java.library.path", "").split(File.pathSeparator)) {
            if (entry.isEmpty()) {
                continue;
            }
            try {
                entries.add(Path.of(entry));
            } catch (InvalidPathException e) {
                // An entry this platform cannot even parse holds no library; the next one might.
            }
        }
        return entries;
    }
}
