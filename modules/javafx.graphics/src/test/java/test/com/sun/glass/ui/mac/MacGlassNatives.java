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

import com.sun.javafx.PlatformUtil;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assumptions.abort;

/**
 * The one decision the macOS Glass binding tests make about the native layer: is {@code libglass} expected in this
 * build, or does this build legitimately have no natives? The rule of {@code test.com.sun.prism.mtl.MTLNatives}: skip
 * on any platform but macOS; on macOS skip only when no {@code libglass.dylib} exists on {@code java.library.path}
 * and no other javafx.graphics native library does either. A present library is not loaded here: the test loads it,
 * and a library that does not load or bind fails there.
 */
final class MacGlassNatives {

    static final String LIBRARY_FILE = System.mapLibraryName("glass");

    private static final List<String> SIBLING_FILES = List.of(System.mapLibraryName("prism_mtl"),
            System.mapLibraryName("javafx_iio"), System.mapLibraryName("prism_es2"));

    private MacGlassNatives() {
    }

    /**
     * @return the {@code libglass.dylib} the test will load
     * @throws AssertionError if this build produced other javafx.graphics natives but not {@code glass}
     */
    static Path require() {
        if (!PlatformUtil.isMac()) {
            abort("the macOS glass library is macOS-only; nothing to test on this platform");
        }
        List<Path> entries = libraryPathEntries();
        List<Path> builtWithoutIt = new ArrayList<>();
        for (Path directory : entries) {
            if (Files.isRegularFile(directory.resolve(LIBRARY_FILE))) {
                return directory.resolve(LIBRARY_FILE);
            }
            if (SIBLING_FILES.stream().anyMatch(name -> Files.isRegularFile(directory.resolve(name)))) {
                builtWithoutIt.add(directory);
            }
        }
        if (!builtWithoutIt.isEmpty()) {
            throw new AssertionError("this build produced javafx.graphics natives but not the one under test: "
                    + builtWithoutIt + " hold other libraries of this module and no " + LIBRARY_FILE + ", so the"
                    + " native build reported success without building glass. Skipping here would report zero"
                    + " binding tests and a green run.");
        }
        abort("this build has no javafx.graphics natives: no " + LIBRARY_FILE + " on java.library.path " + entries
                + ". Build them with \"mvn -pl modules/javafx.graphics test\", that is, without -DskipNative=true.");
        return null;
    }

    private static List<Path> libraryPathEntries() {
        List<Path> entries = new ArrayList<>();
        for (String entry : System.getProperty("java.library.path", "").split(File.pathSeparator)) {
            if (entry.isEmpty()) {
                continue;
            }
            try {
                entries.add(Path.of(entry));
            } catch (InvalidPathException e) {
                // An entry this platform cannot parse holds no library; the next one might.
            }
        }
        return entries;
    }
}
