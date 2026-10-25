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

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Paths, read-only commands and child JVMs of the font goldens. A child or command that outlives its time limit,
 * or whose caller is interrupted, is destroyed.
 */
final class FontGoldenRuntime {

    private static final long COMMAND_TIMEOUT_SECONDS = 60;

    private FontGoldenRuntime() {
    }

    /** Surefire runs with the module directory as its working directory; checked rather than assumed. */
    static Path moduleDirectory() {
        Path directory = Path.of("").toAbsolutePath();
        if (!Files.isRegularFile(directory.resolve("pom.xml"))
                || !Files.isDirectory(directory.resolve("src/test/resources"))) {
            fail("expected the working directory to be modules/javafx.graphics but it is " + directory);
        }
        return directory;
    }

    static Path repositoryDirectory() {
        return moduleDirectory().resolve("../..").normalize();
    }

    static Path workDirectory(String relative) throws IOException {
        Path work = moduleDirectory().resolve(relative);
        Files.createDirectories(work);
        return work;
    }

    static Path findOnLibraryPath(String library) {
        String path = System.getProperty("java.library.path", "");
        for (String entry : path.split(File.pathSeparator)) {
            if (!entry.isEmpty() && Files.isRegularFile(Path.of(entry, library))) {
                return Path.of(entry, library);
            }
        }
        return null;
    }

    static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return -1;
        }
    }

    static String gitCommit() {
        String commit = run(List.of("git", "-C", repositoryDirectory().toString(), "log", "-1", "--format=%H"), true);
        return commit == null ? "unknown" : commit.trim();
    }

    static String run(List<String> command, boolean requireSuccess) {
        byte[] output = runBytes(command, requireSuccess);
        return output == null ? null : new String(output, StandardCharsets.UTF_8);
    }

    /**
     * stdout of a read-only command, or {@code null} if it cannot run, outlives its time limit or (when required)
     * exits non-zero. stdout is read on its own thread, so that a command that never closes it still times out.
     */
    static byte[] runBytes(List<String> command, boolean requireSuccess) {
        Process process;
        try {
            process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException e) {
            return null;
        }
        try {
            FutureTask<byte[]> stdout = new FutureTask<>(process.getInputStream()::readAllBytes);
            Thread.ofVirtual().start(stdout);
            if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return null;
            }
            byte[] output = stdout.get(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return !requireSuccess || process.exitValue() == 0 ? output : null;
        } catch (ExecutionException | TimeoutException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            destroy(process);
        }
    }

    /** The outcome of a child JVM run. */
    record ChildRun(String scenario, int exitCode, Path output, Path stdout, Path stderr, List<Path> errorFiles,
                    List<String> command) {

        String describe() {
            return "child JVM '" + scenario + "' exited with " + exitCode + "; hs_err files: " + errorFiles
                    + "; stdout " + stdout + "; last stderr lines:\n" + tail(stderr, 40) + "\ncommand: " + command;
        }

        /** The child's golden entries; fails unless it exited 0, left no fatal error log and wrote its output. */
        Map<String, String> requireSuccess() throws IOException {
            if (exitCode != 0 || !errorFiles.isEmpty() || !Files.isRegularFile(output)) {
                fail(describe());
            }
            return FontGoldens.parse(Files.readString(output, StandardCharsets.UTF_8));
        }
    }

    /**
     * The files of one child run under the work directory. Fatal error logs go to the scenario's own directory,
     * never to the module directory.
     */
    record ChildJvm(String scenario, Path output, Path stdout, Path stderr, Path errors) {

        /** Deletes the previous output and fatal error logs of {@code scenario}. */
        static ChildJvm prepare(Path work, String scenario) throws IOException {
            ChildJvm child = new ChildJvm(scenario, work.resolve(scenario + ".txt"), work.resolve(scenario + ".out"),
                                          work.resolve(scenario + ".err"), work.resolve("hs_err").resolve(scenario));
            Files.deleteIfExists(child.output());
            deleteTree(child.errors());
            Files.createDirectories(child.errors());
            return child;
        }

        String errorFileOption() {
            return "-XX:ErrorFile=" + errors.resolve("hs_err_pid%p.log");
        }

        /** {@code -cp}, the class path, {@code main}, the scenario and the output file. */
        List<String> mainArguments(Class<?> main) {
            return List.of("-cp", System.getProperty("java.class.path"), main.getName(), scenario, output.toString());
        }

        /** Runs {@code command} in the module directory, its environment adjusted by {@code environment}. */
        ChildRun run(List<String> command, Consumer<Map<String, String>> environment, long timeoutSeconds)
                throws IOException, InterruptedException {
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(moduleDirectory().toFile())
                    .redirectOutput(stdout.toFile())
                    .redirectError(stderr.toFile());
            environment.accept(builder.environment());
            Process process = builder.start();
            try {
                if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                    fail("child JVM '" + scenario + "' did not finish within " + timeoutSeconds + " s; stderr:\n"
                            + tail(stderr, 40));
                }
            } finally {
                destroy(process);
            }
            List<Path> errorFiles;
            try (Stream<Path> files = Files.list(errors)) {
                errorFiles = files.sorted().toList();
            }
            return new ChildRun(scenario, process.exitValue(), output, stdout, stderr, errorFiles, command);
        }
    }

    /** The {@code java} launcher and this JVM's options, minus agents, debugging, error files and {@code drop}. */
    static List<String> javaCommand(Predicate<String> drop) {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        for (String argument : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (argument.startsWith("-agentlib") || argument.startsWith("-javaagent")
                    || argument.startsWith("-Xrunjdwp")
                    || argument.startsWith("-Xdebug") || argument.startsWith("-XX:ErrorFile")
                    || drop.test(argument)) {
                continue;
            }
            command.add(argument);
        }
        return command;
    }

    private static void destroy(Process process) {
        if (!process.isAlive()) {
            return;
        }
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            process.waitFor(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static String tail(Path file, int lines) {
        try {
            List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
            return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
        } catch (IOException | UncheckedIOException e) {
            return "(unreadable: " + e + ")";
        }
    }

    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Collections.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
