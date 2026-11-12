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

package test.com.sun.glass.utils;

import com.sun.glass.utils.NativeLibLoader;
import com.sun.glass.utils.NativeLibLoaderShim;
import java.io.File;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.management.ManagementFactory;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link NativeLibLoader}'s search, its failures and its lookups, headless, on every platform.
 * <p>
 * The test JVM runs {@code javafx.graphics} from an exploded shim directory, so the loader's first step - the
 * directory beside the JavaFX jar, or that of the runtime image - always fails here ("Invalid URL for class"), and no
 * test class carries a library as a resource: what runs is the search of {@code java.library.path} and the last step,
 * the directories {@code System.loadLibrary} searches. The library loaded is the JDK's {@code jli}, which the
 * {@code java} launcher loaded before the JVM started, from {@code sun.boot.library.path}, and copies of it under
 * names of this test's own, in directories it controls through {@code java.library.path}: it holds no JavaFX state, it
 * depends on no other library of the JDK, so a copy loads from any directory, and it was not loaded through a class
 * loader, so {@code System.load} would have loaded it too. A library the loader loads stays loaded until the JVM
 * exits, so every name is used once, the copies live under {@code target}, where their directories need not be
 * deleted, and the test of the load order runs first, before any other copy exporting the same symbols is loaded.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class NativeLibLoaderTest {

    /** In {@code sun.boot.library.path} on every platform, and no JavaFX library exports its symbols. */
    private static final String JLI = "jli";
    private static final String JLI_SYMBOL = "JLI_Launch";

    /** How long a child JVM may run; two of them, and their kill, stay well inside the default test timeout. */
    private static final long CHILD_TIMEOUT_SECONDS = 40;

    private static Path work;

    private String javaLibraryPath;

    @BeforeAll
    public static void createWorkDirectory() throws IOException {
        Path target = Path.of("target").toAbsolutePath();
        Files.createDirectories(target);
        work = Files.createTempDirectory(target, "native-lib-loader-test");
    }

    @BeforeEach
    public void saveJavaLibraryPath() {
        javaLibraryPath = System.getProperty("java.library.path");
    }

    @AfterEach
    public void restoreJavaLibraryPath() {
        System.setProperty("java.library.path", javaLibraryPath);
    }

    /**
     * With {@code java.library.path} as the JVM started with it, which is the value {@code System.loadLibrary}
     * searched and named, whatever the property says later.
     */
    @Test
    public void aLibraryFoundNowhereFailsWithTheErrorOfSystemLoadLibrary() {
        String name = "us074_found_nowhere";
        UnsatisfiedLinkError error = assertThrows(UnsatisfiedLinkError.class, () -> NativeLibLoader.loadLibrary(name));
        assertEquals("no " + name + " in java.library.path: " + System.getProperty("java.library.path"),
                error.getMessage());
        assertTrue(NativeLibLoader.libraryLookup(name).isEmpty(), "a failed load registers no lookup");

        String other = "us074_found_nowhere_from_system_paths";
        error = assertThrows(UnsatisfiedLinkError.class, () -> NativeLibLoader.loadLibraryFromSystemPaths(other));
        assertEquals("no " + other + " in java.library.path: " + System.getProperty("java.library.path"),
                error.getMessage());
    }

    @Test
    public void javaLibraryPathIsSearchedInDefinitionOrder() throws IOException {
        Path first = directory("order-first");
        Path second = directory("order-second");
        Path inFirst = copy(JLI, first, "us074_order_a");
        copy(JLI, second, "us074_order_a");
        copy(JLI, first, "us074_order_b");
        Path inSecond = copy(JLI, second, "us074_order_b");

        setJavaLibraryPath(first, second);
        NativeLibLoader.loadLibrary("us074_order_a");
        assertLoadedFrom(inFirst, "us074_order_a");

        setJavaLibraryPath(second, first);
        NativeLibLoader.loadLibrary("us074_order_b");
        assertLoadedFrom(inSecond, "us074_order_b");
    }

    @Test
    public void theLastStepSearchesTheJdkLibraryDirectoryBeforeJavaLibraryPath() throws IOException {
        // An unloadable file of jli's name in java.library.path: the java.library.path step skips it, and the last
        // step has to find the JDK's jli first, because it would fail on the unloadable file instead.
        Path refusing = directory("jdk-first");
        notALibrary(refusing, JLI);
        setJavaLibraryPath(refusing);
        NativeLibLoader.loadLibrary(JLI);
        assertLoadedFrom(jdkLibrary(JLI), JLI);
        assertTrue(NativeLibLoader.libraryLookup(JLI).orElseThrow().find(JLI_SYMBOL).isPresent());
    }

    @Test
    public void aFileTheOperatingSystemRefusesEndsTheLastStepWithItsCanonicalPath() throws IOException {
        Path refusing = directory("refused");
        Path file = notALibrary(refusing, "us074_refused");
        setJavaLibraryPath(refusing);
        UnsatisfiedLinkError error = assertThrows(UnsatisfiedLinkError.class,
                () -> NativeLibLoader.loadLibrary("us074_refused"));
        assertTrue(error.getMessage().startsWith(file.toFile().getCanonicalPath() + ": "), error.getMessage());
        assertInstanceOf(IllegalArgumentException.class, error.getCause(), "the java.lang.foreign failure is kept");
        assertTrue(NativeLibLoader.libraryLookup("us074_refused").isEmpty());
    }

    /** First of all: no other library exporting {@value #JLI_SYMBOL} may have been loaded before it. */
    @Test
    @Order(1)
    public void theLoadedLibrariesLookupIsLiveAndSearchesInLoadOrder() throws IOException {
        Path directory = directory("load-order");
        copy(JLI, directory, "us074_load_order_first");
        copy(JLI, directory, "us074_load_order_second");
        setJavaLibraryPath(directory);

        SymbolLookup loadedLibraries = NativeLibLoader.loadedLibrariesLookup();
        assertTrue(loadedLibraries.find(JLI_SYMBOL).isEmpty(), "no library exporting it is loaded yet");

        NativeLibLoader.loadLibrary("us074_load_order_first");
        NativeLibLoader.loadLibrary("us074_load_order_second");
        MemorySegment first = NativeLibLoader.libraryLookup("us074_load_order_first").orElseThrow()
                .find(JLI_SYMBOL).orElseThrow();
        MemorySegment second = NativeLibLoader.libraryLookup("us074_load_order_second").orElseThrow()
                .find(JLI_SYMBOL).orElseThrow();
        Optional<MemorySegment> found = loadedLibraries.find(JLI_SYMBOL);
        assertTrue(found.isPresent(), "the lookup obtained before the load sees the library");
        assertSame(loadedLibraries, NativeLibLoader.loadedLibrariesLookup());
        assertTrue(loadedLibraries.find("us074_no_library_exports_this").isEmpty());
        assertTrue(NativeLibLoader.libraryLookup("us074_never_loaded").isEmpty());
        assertThrows(NullPointerException.class, () -> loadedLibraries.find(null));

        // Which copy answers is observable only where the operating system maps each copy as a library of its own.
        assumeTrue(first.address() != second.address(), "the operating system mapped both copies as one library");
        assertEquals(first.address(), found.get().address(), "the library loaded first answers");
    }

    @Test
    public void loadingANameTwiceOpensItOnce() throws IOException {
        Path directory = directory("twice");
        copy(JLI, directory, "us074_twice");
        setJavaLibraryPath(directory);
        NativeLibLoader.loadLibrary("us074_twice");
        int opened = NativeLibLoaderShim.openedLibraryCount();
        SymbolLookup lookup = NativeLibLoader.libraryLookup("us074_twice").orElseThrow();

        NativeLibLoader.loadLibrary("us074_twice");
        NativeLibLoader.loadLibraryFromSystemPaths("us074_twice");
        assertEquals(opened, NativeLibLoaderShim.openedLibraryCount());
        assertSame(lookup, NativeLibLoader.libraryLookup("us074_twice").orElseThrow());
    }

    @Test
    public void loadLibraryFromSystemPathsSearchesJavaLibraryPathAfterTheJdkDirectory() throws IOException {
        Path directory = directory("system-paths");
        Path copy = copy(JLI, directory, "us074_system_paths");
        setJavaLibraryPath(directory.resolve("absent"), directory);
        NativeLibLoader.loadLibraryFromSystemPaths("us074_system_paths");
        assertLoadedFrom(copy, "us074_system_paths");
        assertTrue(NativeLibLoader.libraryLookup("us074_system_paths").orElseThrow().find(JLI_SYMBOL).isPresent());
    }

    /**
     * {@code System.load} and {@code System.loadLibrary} checked native access before they looked for a file, so
     * under {@code --illegal-native-access=deny} without a grant a library found nowhere ended in an
     * {@link IllegalCallerException} from the {@code java.library.path} step, not in "no ... in java.library.path".
     */
    @Test
    public void underDenyALibraryFoundNowhereFailsWithIllegalCallerExceptionAtTheJavaLibraryPathStep()
            throws IOException, InterruptedException {
        List<String> output = runChild(false, List.of("--illegal-native-access=deny"),
                "loadLibrary", "us074_deny_nowhere");
        assertIllegalNativeAccess(output);
        assertTrue(line(output, "FRAMES ").startsWith("FRAMES requireNativeAccess,load,loadLibraryInternal,"),
                String.join("\n", output));
    }

    @Test
    public void underDenyLoadLibraryFromSystemPathsFailsWithIllegalCallerException()
            throws IOException, InterruptedException {
        List<String> output = runChild(false, List.of("--illegal-native-access=deny"),
                "loadLibraryFromSystemPaths", "us074_deny_nowhere");
        assertIllegalNativeAccess(output);
        assertTrue(line(output, "FRAMES ").startsWith("FRAMES requireNativeAccess,loadFromSystemLibraryPaths,"),
                String.join("\n", output));
    }

    /**
     * The macOS rule, applied on this host through the shim: a path that does not exist is handed to the operating
     * system loader, which {@code -Xlog:library} records when it fails, and a failure there counts as "not found",
     * not as a file the operating system refused. Without the rule, the path is never handed over.
     */
    @Test
    public void withTheMacOsRuleAnAbsentFileGoesToTheOperatingSystemLoaderAndFailingThereIsNotFound()
            throws IOException, InterruptedException {
        Path directory = directory("absent-files");
        for (boolean rule : new boolean[] {true, false}) {
            String name = "us074_absent_" + rule;
            String candidate = directory.resolve(System.mapLibraryName(name)).toFile().getCanonicalPath();
            List<String> output = runChild(true, List.of("-Djava.library.path=" + directory, "-Xlog:library=info"),
                    "openAbsentFiles", String.valueOf(rule), name);
            String report = String.join("\n", output);
            assertEquals("RESULT java.lang.UnsatisfiedLinkError: no " + name + " in java.library.path: " + directory,
                    line(output, "RESULT "), report);
            boolean handedOver = output.stream().anyMatch(l -> l.contains("Failed to load library " + candidate));
            assertEquals(rule, handedOver, (rule ? "never handed " : "handed ") + candidate + " over:\n" + report);
        }
    }

    /** As {@code System.load} did on macOS: dyld resolves {@code /usr/lib/libz.1.dylib} from its shared cache. */
    @Test
    @EnabledOnOs(OS.MAC)
    public void onMacOsAPathDyldResolvesIsLoadedThoughNoFileExists() {
        Path zlib = Path.of("/usr/lib/libz.1.dylib");
        assumeTrue(!Files.exists(zlib), zlib + " exists on disk, so this macOS has no shared-cache-only library here");
        setJavaLibraryPath(zlib.getParent());
        NativeLibLoader.loadLibrary("z.1");
        assertTrue(NativeLibLoader.libraryLookup("z.1").orElseThrow().find("zlibVersion").isPresent());
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /**
     * Runs {@link NativeLibLoaderChild} with {@code args} in a JVM started with this JVM's options - without its
     * {@code --enable-native-access} unless {@code grant} - followed by {@code options}, and returns its output.
     * <p>
     * The child gets {@value #CHILD_TIMEOUT_SECONDS} s, so that the two children of one test and their kill stay
     * well inside the default test timeout of 120 s, and it is killed, with anything it started, on every way out
     * other than its own exit - a timeout, or JUnit interrupting this thread. Its output is read in the encoding
     * it is written in: the child's {@code System.out}, redirected to a file, and the paths HotSpot logs both use
     * the platform's native encoding, so no {@code stdout.encoding} of this JVM is passed on.
     */
    private static List<String> runChild(boolean grant, List<String> options, String... args)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        for (String argument : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (argument.startsWith("-agentlib") || argument.startsWith("-javaagent")
                    || argument.startsWith("-Xrunjdwp") || argument.startsWith("-XX:ErrorFile")
                    || argument.startsWith("-Dstdout.encoding=") || argument.startsWith("-Dstderr.encoding=")
                    || (!grant && argument.startsWith("--enable-native-access"))) {
                continue;
            }
            command.add(argument);
        }
        command.addAll(options);
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(NativeLibLoaderChild.class.getName());
        command.addAll(List.of(args));
        Path log = Files.createTempFile(work, "child", ".log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            if (!process.waitFor(CHILD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError("child JVM did not finish in " + CHILD_TIMEOUT_SECONDS + " s: " + command);
            }
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        }
        Charset encoding = Charset.forName(System.getProperty("native.encoding"), StandardCharsets.UTF_8);
        List<String> output = new String(Files.readAllBytes(log), encoding).lines().toList();
        assertEquals(0, process.exitValue(), "child JVM exit value; output:\n" + String.join("\n", output));
        return output;
    }

    private static String line(List<String> output, String prefix) {
        return output.stream().filter(l -> l.startsWith(prefix)).findFirst()
                .orElseThrow(() -> new AssertionError("no " + prefix.strip() + " line:\n" + String.join("\n", output)));
    }

    private static void assertIllegalNativeAccess(List<String> output) {
        String result = line(output, "RESULT ");
        assertTrue(result.startsWith("RESULT java.lang.IllegalCallerException: ")
                && result.contains("module javafx.graphics"), String.join("\n", output));
    }

    private static Path directory(String name) throws IOException {
        return Files.createDirectories(work.resolve(name));
    }

    private static void setJavaLibraryPath(Path... directories) {
        List<String> entries = new ArrayList<>();
        for (Path directory : directories) {
            entries.add(directory.toString());
        }
        System.setProperty("java.library.path", String.join(File.pathSeparator, entries));
    }

    /** The JDK's {@code library}, from the first {@code sun.boot.library.path} directory that holds it. */
    private static Path jdkLibrary(String library) {
        for (String directory : System.getProperty("sun.boot.library.path", "").split(File.pathSeparator)) {
            Path file = Path.of(directory, System.mapLibraryName(library));
            if (Files.isRegularFile(file)) {
                return file;
            }
        }
        throw new AssertionError("no " + System.mapLibraryName(library) + " in sun.boot.library.path "
                + System.getProperty("sun.boot.library.path"));
    }

    /** A copy of the JDK's {@code library} in {@code directory}, under the file name of {@code name}. */
    private static Path copy(String library, Path directory, String name) throws IOException {
        return Files.copy(jdkLibrary(library), directory.resolve(System.mapLibraryName(name)));
    }

    /** A file of the library name of {@code name} that no operating system loads. */
    private static Path notALibrary(Path directory, String name) throws IOException {
        return Files.writeString(directory.resolve(System.mapLibraryName(name)), "not a shared library\n",
                StandardCharsets.US_ASCII);
    }

    private static void assertLoadedFrom(Path expected, String name) throws IOException {
        Path actual = NativeLibLoaderShim.libraryPath(name).orElseThrow(() -> new AssertionError(name + " not loaded"));
        assertTrue(Files.isSameFile(expected, actual), name + " was loaded from " + actual + ", not " + expected);
        assertFalse(NativeLibLoader.libraryLookup(name).isEmpty());
    }
}
