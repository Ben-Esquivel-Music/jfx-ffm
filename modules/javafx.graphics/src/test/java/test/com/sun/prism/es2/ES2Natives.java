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

package test.com.sun.prism.es2;

import com.sun.prism.es2.ES2NativeShim;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assumptions.abort;

/**
 * The one decision the ES2 tests make about the native layer: is the {@code prism_es2} library present
 * in this JVM?
 * <p>
 * This is {@code test.com.sun.pisces.PiscesNatives} for the ES2 library, with one difference that
 * matters: {@code prism_es2} is <em>optional</em>. {@code INCLUDE_ES2} defaults off on Windows (see
 * {@code modules/javafx.graphics/native/CMakeLists.txt}) and can be turned off on Linux and macOS, so a
 * build with no {@code prism_es2} is a legitimate configuration, not the broken build that a missing
 * {@code prism_sw} or {@code prism_d3d} would be. Nothing here can tell "ES2 was not included" from "the
 * ES2 native target broke", and a spurious failure on every default Windows build is the worse error, so
 * the absence of {@code prism_es2} is always a skip. What is never a skip is a {@code prism_es2} that is
 * present and does not load, bind or match its ABI version: that is a broken build and has to fail, which
 * is how the binding tests keep meaning something on a build that did include ES2.
 * <p>
 * The rule: <em>skip when no {@code prism_es2} exists on {@code java.library.path}; otherwise it has to
 * load and bind.</em> The surefire {@code argLine} of {@code modules/javafx.graphics/pom.xml} sets
 * {@code java.library.path} to this module's own {@code target/native/bin}, so that path is where the
 * build's output is looked for. Build the library with {@code -DINCLUDE_ES2=true} to run these tests.
 */
public final class ES2Natives {

    private static final String LIBRARY_NAME = "prism_es2";

    /** {@code prism_es2.dll}, {@code libprism_es2.so} or {@code libprism_es2.dylib}. */
    private static final String LIBRARY_FILE = System.mapLibraryName(LIBRARY_NAME);

    /**
     * Other libraries the javafx.graphics CMake build writes into the same directory on every platform.
     * Any of them present with no {@code prism_es2} means the natives were built but ES2 was left out -
     * still a skip, because ES2 is optional, but a more specific one.
     */
    private static final List<String> SIBLING_FILES = List.of(System.mapLibraryName("prism_common"),
            System.mapLibraryName("prism_sw"), System.mapLibraryName("glass"),
            System.mapLibraryName("javafx_font"), System.mapLibraryName("javafx_iio"),
            System.mapLibraryName("decora_sse"));

    private static boolean decided;
    private static String skipReason;
    private static String failureMessage;
    private static Throwable failureCause;

    private ES2Natives() {
    }

    /**
     * Loads {@code prism_es2}, or skips the calling test when this build did not include it.
     * <p>
     * Decided once per JVM and then replayed: the library, the lookup and any failure are per class
     * loader, and the ES2 test classes share a surefire fork, so a second caller has to get the first
     * caller's verdict rather than a second load attempt.
     *
     * @throws AssertionError if a {@code prism_es2} is present and cannot be used, which is a broken
     *         build; the message names every candidate on {@code java.library.path}
     */
    public static synchronized void require() {
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
    }

    private static void decide() {
        List<Path> entries = libraryPathEntries();
        List<Path> reachable = new ArrayList<>();
        boolean sawOtherNatives = false;
        for (Path dir : entries) {
            if (Files.isRegularFile(dir.resolve(LIBRARY_FILE))) {
                reachable.add(dir.resolve(LIBRARY_FILE));
            } else if (SIBLING_FILES.stream().anyMatch(name -> Files.isRegularFile(dir.resolve(name)))) {
                sawOtherNatives = true;
            }
        }
        if (reachable.isEmpty()) {
            skipReason = sawOtherNatives
                    ? "this build has javafx.graphics natives but no " + LIBRARY_FILE + " on"
                            + " java.library.path " + entries + ": the ES2 pipeline is optional and was"
                            + " not included in this build (INCLUDE_ES2 defaults off on Windows). Build"
                            + " it with \"-DINCLUDE_ES2=true\" to run these tests."
                    : "this build has no javafx.graphics natives: no " + LIBRARY_FILE + " on"
                            + " java.library.path " + entries + ", so there is nothing here to test."
                            + " Build them with \"mvn -pl modules/javafx.graphics test"
                            + " -DINCLUDE_ES2=true\", that is, without -DskipNative=true.";
            return;
        }
        try {
            ES2NativeShim.loadLibrary();
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            failureMessage = failureText(reachable, e);
            failureCause = e;
        }
    }

    private static String failureText(List<Path> reachable, Throwable cause) {
        StringBuilder text = new StringBuilder(512);
        text.append("the prism_es2 library is present but unusable, which is a broken build and never an")
                .append(" environment fact: ").append(cause).append('\n');
        for (Path library : reachable) {
            text.append("  present on java.library.path: ").append(library).append(describe(library))
                    .append('\n');
        }
        text.append("Rebuild the natives with \"mvn -pl modules/javafx.graphics process-classes")
                .append(" -DINCLUDE_ES2=true\" and check that the library exports every es2_* symbol the")
                .append(" facade binds.");
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
