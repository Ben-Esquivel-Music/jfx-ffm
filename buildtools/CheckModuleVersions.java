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

import java.io.IOException;
import java.io.InputStream;
import java.lang.module.InvalidModuleDescriptorException;
import java.lang.module.ModuleDescriptor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Checks that built JavaFX module descriptors carry the module version of the build.
 * <p>
 * Run it with the JDK source launcher, which needs nothing but the JDK:
 * <pre>
 *     java CheckModuleVersions.java &lt;expected-version&gt; &lt;location&gt;...
 * </pre>
 * Each location is either a directory that holds a {@code module-info.class} (such as
 * {@code target/classes}) or a modular jar. For each descriptor it checks that
 * <ul>
 *   <li>the module version is the expected version, and</li>
 *   <li>every {@code requires} of a JavaFX module ({@code javafx.*}, {@code jfx.*} or
 *       {@code jdk.jsobject}) recorded the expected version when the module was compiled.</li>
 * </ul>
 * A missing descriptor or an absent version is a failure too: every location handed to this
 * program is supposed to be a named JavaFX module compiled against the other modules of the
 * same build. The program prints one line per mismatch and exits with status 1 if there is any.
 * <p>
 * The root pom runs it for every project that has a {@code src/main/java/module-info.java}.
 * It uses only JDK APIs and the language level of {@code --release 25}, the oldest JDK that
 * may build JavaFX.
 */
public final class CheckModuleVersions {

    private static final String MODULE_INFO = "module-info.class";
    private static final int EXIT_MISMATCH = 1;
    private static final int EXIT_USAGE = 2;

    private CheckModuleVersions() {
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: java CheckModuleVersions.java <expected-version> <classes-dir-or-jar>...");
            System.exit(EXIT_USAGE);
        }
        String expectedVersion = args[0];
        List<String> mismatches = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            mismatches.addAll(check(Path.of(args[i]), expectedVersion));
        }
        if (!mismatches.isEmpty()) {
            mismatches.forEach(mismatch -> System.err.println("ERROR: " + mismatch));
            System.err.println("ERROR: " + mismatches.size() + " module version mismatch(es); every JavaFX module"
                    + " of this build must carry the module version " + expectedVersion);
            System.exit(EXIT_MISMATCH);
        }
    }

    private static List<String> check(Path location, String expectedVersion) {
        Optional<ModuleDescriptor> descriptor;
        try {
            descriptor = readDescriptor(location);
        } catch (IOException | InvalidModuleDescriptorException e) {
            return List.of(location + ": cannot read " + MODULE_INFO + ": " + e);
        }
        if (descriptor.isEmpty()) {
            return List.of(location + ": no " + MODULE_INFO + " found, but a named module was expected");
        }
        return checkDescriptor(descriptor.get(), location, expectedVersion);
    }

    private static Optional<ModuleDescriptor> readDescriptor(Path location) throws IOException {
        if (Files.isDirectory(location)) {
            return readFromDirectory(location);
        }
        if (Files.isRegularFile(location)) {
            return readFromJar(location);
        }
        throw new IOException("no such directory or jar file");
    }

    private static Optional<ModuleDescriptor> readFromDirectory(Path directory) throws IOException {
        Path moduleInfo = directory.resolve(MODULE_INFO);
        if (!Files.isRegularFile(moduleInfo)) {
            return Optional.empty();
        }
        try (InputStream in = Files.newInputStream(moduleInfo)) {
            return Optional.of(ModuleDescriptor.read(in));
        }
    }

    private static Optional<ModuleDescriptor> readFromJar(Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry moduleInfo = zip.getEntry(MODULE_INFO);
            if (moduleInfo == null) {
                return Optional.empty();
            }
            try (InputStream in = zip.getInputStream(moduleInfo)) {
                return Optional.of(ModuleDescriptor.read(in));
            }
        }
    }

    private static List<String> checkDescriptor(ModuleDescriptor descriptor, Path location, String expectedVersion) {
        String module = descriptor.name();
        List<String> mismatches = new ArrayList<>();
        String version = descriptor.rawVersion().orElse(null);
        compare("module " + module + ": module version", version, expectedVersion, location)
                .ifPresent(mismatches::add);
        int checkedRequires = 0;
        for (ModuleDescriptor.Requires requires : descriptor.requires()) {
            if (isJavaFxModule(requires.name())) {
                checkedRequires++;
                String field = "module " + module + ": compiled version of 'requires " + requires.name() + "'";
                String compiledVersion = requires.rawCompiledVersion().orElse(null);
                compare(field, compiledVersion, expectedVersion, location)
                        .ifPresent(mismatches::add);
            }
        }
        if (mismatches.isEmpty()) {
            System.out.println("module " + module + "@" + expectedVersion + " and its " + checkedRequires
                    + " JavaFX requires carry the expected version (" + location + ")");
        }
        return mismatches;
    }

    /**
     * Returns the mismatch message for {@code field}, or empty if {@code found} is {@code expected}.
     * A {@code null} {@code found} means that the descriptor does not record the version.
     */
    private static Optional<String> compare(String field, String found, String expected, Path location) {
        if (found == null) {
            return Optional.of(field + " is absent, expected " + expected + " (" + location + ")");
        }
        if (!found.equals(expected)) {
            return Optional.of(field + " is " + found + ", expected " + expected + " (" + location + ")");
        }
        return Optional.empty();
    }

    private static boolean isJavaFxModule(String moduleName) {
        return moduleName.startsWith("javafx.")
                || moduleName.startsWith("jfx.")
                || moduleName.equals("jdk.jsobject");
    }
}
