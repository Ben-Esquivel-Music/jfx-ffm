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

package test.com.sun.glass.ui.win;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Parent side of {@link WinGlassChild}: runs a scenario in a JVM of its own with this test JVM's options and
 * returns what it recorded, its exit status and any fatal error log.
 * <p>
 * A child is needed where one process cannot hold the case: a Glass toolkit is created once per process (as
 * {@code WinApplication} creates it), so every toolkit lifetime is a child; and a call that crashes the code under
 * test must take down a child, not the test JVM. The child gets this JVM's input arguments - the shim build on
 * {@code --upgrade-module-path}, the add-exports argument files, {@code --enable-native-access} and
 * {@code java.library.path}, so the same {@code glass.dll} - but not surefire's system properties, so no
 * {@code StubToolkit} either. A scenario that starts the JavaFX platform names {@code glass.platform} itself in
 * the options of {@link #launch}, which follow those arguments, because {@code -DHEADLESS_TEST=true} puts
 * {@code -Dglass.platform=Headless} on the argLine. Debug agents are left out. A fatal error log goes to a
 * directory of its own ({@code -XX:ErrorFile}), under {@code target/win-glass-child/<scenario>/} with the values,
 * stdout and stderr.
 * <p>
 * {@link Run#describe}, {@link Run#loadedGlass} and the message of a child that took too long name the
 * {@code glass.dll} the child itself recorded loading, by path and sha256 ({@link WinGlassChild#LOADED_GLASS_PATH}),
 * so a report says which build it ran against; with no such record they say so.
 */
final class WinGlassChildJvm {

    /** How long {@link #run} waits for a child that bounds its own waits. */
    static final long CHILD_SECONDS = 90;

    /** What {@link #loadedGlass} answers for a child that recorded no loaded {@code glass.dll}. */
    static final String NO_LOADED_GLASS = "child did not report the loaded glass.dll";

    /** What a child JVM left behind. */
    record Run(String scenario, int exitCode, Map<String, String> values, Path stdout, Path stderr,
               List<Path> errorFiles, List<String> command) {

        /** Whether the child exited with status 0 and wrote no fatal error log. */
        boolean clean() {
            return exitCode == 0 && errorFiles.isEmpty();
        }

        /** The value of {@code key}; fails, with {@link #describe}, when the child recorded none. */
        String value(String key) {
            String value = values.get(key);
            if (value == null) {
                fail("the child recorded no " + key + ": " + describe());
            }
            return value;
        }

        /** The {@code glass.dll} this child recorded loading, as {@link WinGlassChildJvm#loadedGlass} words it. */
        String loadedGlass() {
            return WinGlassChildJvm.loadedGlass(values);
        }

        String describe() {
            return "child JVM '" + scenario + "' exited with " + exitCode + " (" + loadedGlass() + "); hs_err files: "
                    + errorFiles + "\nvalues: " + values + "\nlast stderr lines:\n" + tail(stderr, 40)
                    + "\nlast stdout lines:\n" + tail(stdout, 20);
        }
    }

    private WinGlassChildJvm() {
    }

    /** {@link #launch} that must exit with status 0 and leave no fatal error log. */
    static Run run(Class<?> scenarioClass, String method) {
        Run run = launch(scenarioClass, method, method, List.of(), CHILD_SECONDS);
        if (!run.clean()) {
            fail(run.describe());
        }
        return run;
    }

    /**
     * Runs {@code scenarioClass#method} in {@link WinGlassChild} and waits up to {@code timeoutSeconds} for it,
     * killing it when it takes longer. {@code label} names the work directory, so that repeated runs of one
     * scenario keep their own. Does not judge the exit status.
     */
    static Run launch(Class<?> scenarioClass, String method, String label, List<String> jvmOptions,
                      long timeoutSeconds) {
        String scenario = scenarioClass.getSimpleName() + "." + label;
        Process process = null;
        try {
            Path work = Path.of("target", "win-glass-child", scenario).toAbsolutePath();
            deleteTree(work);
            Path errors = work.resolve("hs_err");
            Files.createDirectories(errors);
            Path output = work.resolve("values.txt");
            Path stdout = work.resolve("stdout.txt");
            Path stderr = work.resolve("stderr.txt");

            List<String> command = new ArrayList<>();
            command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            for (String argument : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
                if (argument.startsWith("-agentlib") || argument.startsWith("-javaagent")
                        || argument.startsWith("-Xrunjdwp") || argument.startsWith("-Xdebug")
                        || argument.startsWith("-XX:ErrorFile")) {
                    continue;
                }
                command.add(argument);
            }
            command.add("-XX:ErrorFile=" + errors.resolve("hs_err_pid%p.log"));
            command.addAll(jvmOptions);
            command.add("-cp");
            command.add(System.getProperty("java.class.path"));
            command.add(WinGlassChild.class.getName());
            command.add(scenarioClass.getName() + "#" + method);
            command.add(output.toString());

            process = new ProcessBuilder(command)
                    .redirectOutput(stdout.toFile())
                    .redirectError(stderr.toFile())
                    .start();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                kill(process);
                fail("child JVM '" + scenario + "' did not finish within " + timeoutSeconds + " s ("
                        + loadedGlassOfKilled(output) + "); stderr:\n" + tail(stderr, 40));
            }
            List<Path> errorFiles;
            try (Stream<Path> files = Files.list(errors)) {
                errorFiles = files.sorted().toList();
            }
            return new Run(scenario, process.exitValue(), readValues(output), stdout, stderr, errorFiles, command);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            if (process != null) {
                kill(process);
            }
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while the child JVM '" + scenario + "' ran; it was killed",
                    e);
        }
    }

    /**
     * The {@code glass.dll} a child recorded loading in {@code values} - {@code loaded glass.dll sha256 <hex> from
     * <path>} - or {@link #NO_LOADED_GLASS} when it recorded none.
     */
    static String loadedGlass(Map<String, String> values) {
        String path = values.get(WinGlassChild.LOADED_GLASS_PATH);
        if (path == null) {
            return NO_LOADED_GLASS;
        }
        return "loaded glass.dll sha256 " + values.get(WinGlassChild.LOADED_GLASS_SHA256) + " from " + path;
    }

    /**
     * {@link #loadedGlass} of several children, each distinct answer once, in order: one answer when they all
     * loaded the same file.
     */
    static String loadedGlass(Collection<Run> runs) {
        return runs.stream().map(Run::loadedGlass).distinct().collect(Collectors.joining("; "));
    }

    /** The values a child wrote to {@code output}, none when it wrote no file. */
    private static Map<String, String> readValues(Path output) throws IOException {
        Map<String, String> values = new TreeMap<>();
        if (Files.isRegularFile(output)) {
            for (String line : Files.readAllLines(output, StandardCharsets.UTF_8)) {
                int eq = line.indexOf('=');
                values.put(line.substring(0, eq), WinGlassChild.unescape(line.substring(eq + 1)));
            }
        }
        return values;
    }

    /**
     * {@link #loadedGlass} of a child that was killed: what its values file held, which is what the child wrote
     * once it had loaded {@code glass.dll}, if it got that far.
     */
    private static String loadedGlassOfKilled(Path output) {
        try {
            return loadedGlass(readValues(output));
        } catch (IOException | RuntimeException e) {
            return NO_LOADED_GLASS + " (its values file is unreadable: " + e + ")";
        }
    }

    /** Kills {@code process} and waits up to 10 s for it to go, keeping the thread's interrupt status as it was. */
    private static void kill(Process process) {
        process.destroyForcibly();
        boolean interrupted = Thread.interrupted();
        try {
            process.waitFor(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            interrupted = true;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    static String tail(Path file, int lines) {
        try {
            List<String> all = Files.readAllLines(file, StandardCharsets.ISO_8859_1);
            return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
        } catch (IOException | UncheckedIOException e) {
            return "(unreadable: " + e + ")";
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
