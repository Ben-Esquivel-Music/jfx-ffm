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

package test.com.sun.javafx.webkit.prism;

import static org.junit.jupiter.api.Assumptions.abort;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Whether this build has a GPU pipeline's library, by the rule of the graphics module's {@code MTLNatives}: a
 * build that produced other javafx.graphics natives but not the pipeline's is broken and fails; only a build with
 * no graphics natives at all is skipped. The library is never loaded here: the child JVM loads it.
 */
final class GpuOffscreenGate {

    private static final List<String> GRAPHICS_LIBRARIES = List.of("javafx_iio", "glass", "prism_es2", "prism_mtl");

    private GpuOffscreenGate() {
    }

    /** The library of the pipeline that the child will load. */
    static Path require(WebPrismGpuOffscreenChild.Pipeline pipeline) {
        String libraryFile = System.mapLibraryName(pipeline.library());
        List<String> siblingFiles = GRAPHICS_LIBRARIES.stream()
                .filter(name -> !name.equals(pipeline.library()))
                .map(System::mapLibraryName)
                .toList();
        List<Path> entries = libraryPathEntries();
        List<Path> builtWithoutIt = new ArrayList<>();
        for (Path directory : entries) {
            if (Files.isRegularFile(directory.resolve(libraryFile))) {
                return directory.resolve(libraryFile);
            }
            if (siblingFiles.stream().anyMatch(name -> Files.isRegularFile(directory.resolve(name)))) {
                builtWithoutIt.add(directory);
            }
        }
        if (!builtWithoutIt.isEmpty()) {
            throw new AssertionError("this build produced javafx.graphics natives but not the " + pipeline
                    + " pipeline's: " + builtWithoutIt + " hold other libraries of that module and no " + libraryFile
                    + ". Skipping here would report a green run with no " + pipeline + " check.");
        }
        abort("this build has no javafx.graphics natives: no " + libraryFile + " on java.library.path " + entries
                + ". Build them with \"mvn -pl modules/javafx.graphics test\", without -DskipNative=true.");
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
