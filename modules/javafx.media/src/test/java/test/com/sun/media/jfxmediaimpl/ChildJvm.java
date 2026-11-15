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

package test.com.sun.media.jfxmediaimpl;

import com.sun.media.jfxmediaimpl.NativeMediaManager;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Both ends of a media test that runs its body in a JVM of its own: {@link #run} starts a probe class there and
 * returns what it did, and {@link #report} is how the probe states a result.
 * <p>
 * A test needs a child when it has to choose which {@code jfxmedia} is loaded - the shipped library or the hooks
 * build - or has to read what the native side prints: the facade loads its library once per class loader, and
 * this JVM already holds the shipped one. As in {@link NativeMediaManagerDegradationTest}, the child loads
 * everything from the class path, so it needs no {@code --add-exports}, and {@code --enable-native-access=ALL-UNNAMED}
 * is how the class path says "javafx.media has native access". There the grant also covers the probes' own
 * downcalls into the hooks build ({@link JfxmTestHooksNative}), which in this JVM would be unnamed code without
 * one.
 * <p>
 * Output goes to a file, and the timed wait comes before the file is read, for the reasons
 * {@code NativeMediaManagerDegradationTest.runProbe} gives: a hung child fails the test instead of hanging the
 * build, and what it printed before it was killed is still in the failure message. A child that crashes writes
 * its {@code hs_err} file beside that output, and {@link Run#describe()} carries both. The results a probe reports
 * go to a file of their own as well as to the output, because the output can be far too large to keep: a child
 * that hits a GLib critical can log one per band of every spectrum message, hundreds of megabytes of it. So
 * {@link Run#output()} holds only its beginning and its end, for failure messages, and {@link Run#scan} reads
 * the whole file.
 */
final class ChildJvm {

    /** The start of every line a probe states a result on, in its report file and in its output. */
    static final String REPORT_PREFIX = "#> ";

    /** The system property that names a child's report file. */
    private static final String REPORT_FILE_PROPERTY = "test.media.childReport";

    /** How much of each end of a child's output is read; the middle of a longer one is left out. */
    private static final int READ_OUTPUT_BYTES = 8 * 1024 * 1024;

    /** How much of each end of the output that was read a failure message carries. */
    private static final int DESCRIBED_OUTPUT_CHARS = 32 * 1024;

    /** How long a killed child gets to end after an interrupt; short, as the interrupt asked this thread to stop. */
    private static final long KILLED_CHILD_END_SECONDS = 5;

    /** The child's report file, opened on its first {@link #report}. */
    private static PrintStream reportFile;

    private ChildJvm() {
    }

    /**
     * What one child did.
     *
     * @param command the command line it was started with
     * @param output what it printed on either stream, for failure messages; its two ends only, when it printed more
     *        than twice {@link #READ_OUTPUT_BYTES}, so a search of the output belongs to {@link #scan}
     * @param log the file that holds all of what it printed
     * @param reports the lines it {@linkplain #report reported}
     * @param finished whether it ended within its timeout
     * @param exitValue its exit value, or -1 when it had to be killed
     * @param directory where its output, its reports and any {@code hs_err} file are
     * @param millis how long it ran, in milliseconds
     */
    record Run(List<String> command, String output, Path log, List<String> reports, boolean finished,
               int exitValue, Path directory, long millis) {

        /** How many matching lines a {@link Matches} keeps. */
        static final int KEPT_MATCHES = 5;

        /**
         * The lines of a {@link #scan}.
         *
         * @param count how many lines of the whole log matched
         * @param first the first of them, at most {@link #KEPT_MATCHES}
         */
        record Matches(long count, List<String> first) {
        }

        /**
         * Reads the whole {@link #log}, not {@link #output}, and counts the lines that match. Decoded with
         * replacement: a child killed mid-write can leave a truncated multi-byte sequence, and a decoding error
         * would end the scan before the lines it is for.
         *
         * @param filter which lines count
         * @return how many lines matched, and the first {@link #KEPT_MATCHES} of them
         * @throws UncheckedIOException when the log cannot be read, so that a missing log never counts as one
         *         without a match
         */
        Matches scan(Predicate<String> filter) {
            long count = 0;
            List<String> first = new ArrayList<>(KEPT_MATCHES);
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(Files.newInputStream(log), StandardCharsets.UTF_8))) {
                for (String line = in.readLine(); line != null; line = in.readLine()) {
                    if (filter.test(line)) {
                        count++;
                        if (first.size() < KEPT_MATCHES) {
                            first.add(line);
                        }
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("the child's log " + log + " cannot be read", e);
            }
            return new Matches(count, List.copyOf(first));
        }

        /** Whether the child ended by itself, within its timeout, with exit value 0. */
        boolean clean() {
            return finished && exitValue == 0;
        }

        /**
         * The last value the child {@linkplain #report reported} for {@code key}, or {@code null} when it reported
         * none.
         */
        String value(String key) {
            String found = null;
            String start = key + "=";
            for (String line : reports) {
                if (line.startsWith(start)) {
                    found = line.substring(start.length());
                }
            }
            return found;
        }

        /** The command, the outcome, the output (shortened in the middle when long) and any {@code hs_err} file. */
        String describe() {
            StringBuilder text = new StringBuilder(2 * DESCRIBED_OUTPUT_CHARS + 4096);
            text.append("\nchild: ").append(String.join(" ", command));
            text.append("\nfinished: ").append(finished).append(", exit value: ").append(exitValue)
                    .append(", ").append(millis).append(" ms");
            text.append("\noutput (").append(output.length()).append(" chars read):\n");
            if (output.length() <= 2 * DESCRIBED_OUTPUT_CHARS) {
                text.append(output);
            } else {
                text.append(output, 0, DESCRIBED_OUTPUT_CHARS)
                        .append("\n[... ").append(output.length() - 2 * DESCRIBED_OUTPUT_CHARS)
                        .append(" chars left out ...]\n")
                        .append(output, output.length() - DESCRIBED_OUTPUT_CHARS, output.length());
            }
            for (Path crash : crashFiles()) {
                text.append("\n").append(crash).append(":\n").append(readAll(crash));
            }
            return text.toString();
        }

        private List<Path> crashFiles() {
            try (Stream<Path> files = Files.list(directory)) {
                return files.filter(file -> file.getFileName().toString().startsWith("hs_err_pid")).toList();
            } catch (IOException e) {
                return List.of();
            }
        }
    }

    /** {@link #run(Class, List, String, Path, String, long, List)} with no JVM options of the caller's. */
    static Run run(Class<?> probe, String libraryPath, Path directory, String name, long timeoutSeconds,
                   List<String> arguments) {
        return run(probe, List.of(), libraryPath, directory, name, timeoutSeconds, arguments);
    }

    /**
     * Runs {@code probe}'s {@code main} in a child JVM and returns what it did. Whether that was a success is for
     * the caller to assert, with {@link Run#describe()} in the message.
     *
     * @param probe the class whose {@code main} the child runs
     * @param jvmOptions options added to the child's command line after this class's own, before the probe class
     * @param libraryPath the child's whole {@code java.library.path}
     * @param directory where the child's output, reports, {@code hs_err} file and temporary files go
     * @param name what the child's files are called, without extension
     * @param timeoutSeconds how long the child gets before it is killed
     * @param arguments the arguments of {@code main}
     * @return what the child did
     */
    static Run run(Class<?> probe, List<String> jvmOptions, String libraryPath, Path directory, String name,
                   long timeoutSeconds, List<String> arguments) {
        Path log = directory.resolve(name + "-output.txt");
        Path reports = directory.resolve(name + "-reports.txt");
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-classpath");
        command.add(childClassPath());
        command.add("-Djava.library.path=" + libraryPath);
        command.add("-Djava.io.tmpdir=" + directory);
        command.add("-D" + REPORT_FILE_PROPERTY + "=" + reports);
        // WARNING, so that what jfxmedia logs at ERROR and WARNING reaches the output; GLib criticals and errors are
        // among it once the platform has installed its GLib log handler (GstMediaManager::Init).
        command.add("-Djfxmedia.loglevel=warning");
        command.add("-Dstdout.encoding=UTF-8");
        command.add("-Dstderr.encoding=UTF-8");
        command.add("-XX:ErrorFile=" + directory.resolve("hs_err_pid%p.log"));
        command.add("--enable-native-access=ALL-UNNAMED");
        command.addAll(jvmOptions);
        command.add(probe.getName());
        command.addAll(arguments);

        long start = System.nanoTime();
        try {
            Process child = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile())
                    .start();
            boolean finished;
            try {
                finished = child.waitFor(timeoutSeconds, TimeUnit.SECONDS);
                if (!finished) {
                    child.destroyForcibly().waitFor(timeoutSeconds, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                child.destroyForcibly();
                // The kill is asynchronous: until the child has ended, its last writes may be missing from the output,
                // and on Windows it still holds the output file open, which fails the cleanup of the directory.
                // The interrupt flag is clear here, which is what lets this wait block, so it is set again only below.
                try {
                    child.waitFor(KILLED_CHILD_END_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException _) {
                    // Interrupted again: the flag is set again below either way, and the report takes what is there.
                }
                String soFar = new Run(List.copyOf(command), readEnds(log), log, List.of(), false, -1,
                        directory, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).describe();
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for the child, which was killed:" + soFar, e);
            }
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            List<String> reported = Files.exists(reports) ? readLines(reports) : List.of();
            return new Run(List.copyOf(command), readEnds(log), log, reported, finished,
                    finished ? child.exitValue() : -1, directory, millis);
        } catch (IOException e) {
            throw new UncheckedIOException("could not run " + command, e);
        }
    }

    /**
     * States one result of a probe, for {@link Run#value} in the parent, and prints it too, so that the output
     * shows it among the log it belongs with. Called in the child only.
     *
     * @param key the name of the result
     * @param value its value; its string form must fit on one line
     */
    static synchronized void report(String key, Object value) {
        String line = key + "=" + value;
        System.out.println(REPORT_PREFIX + line);
        if (reportFile == null) {
            try {
                reportFile = new PrintStream(new FileOutputStream(System.getProperty(REPORT_FILE_PROPERTY), true),
                        true, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("the report file cannot be opened", e);
            }
        }
        reportFile.println(line);
    }

    /**
     * The whole file when it is at most twice {@link #READ_OUTPUT_BYTES}, else its first and last
     * {@link #READ_OUTPUT_BYTES} with a note of what was left out. Decoded with replacement rather than
     * {@code Files.readString}: a child killed mid-write can leave a truncated multi-byte sequence, and the text is
     * a failure message, never data.
     */
    private static String readEnds(Path file) {
        try (RandomAccessFile in = new RandomAccessFile(file.toFile(), "r")) {
            long size = in.length();
            if (size <= 2L * READ_OUTPUT_BYTES) {
                byte[] all = new byte[(int) size];
                in.readFully(all);
                return new String(all, StandardCharsets.UTF_8);
            }
            byte[] head = new byte[READ_OUTPUT_BYTES];
            in.readFully(head);
            byte[] tail = new byte[READ_OUTPUT_BYTES];
            in.seek(size - READ_OUTPUT_BYTES);
            in.readFully(tail);
            return new String(head, StandardCharsets.UTF_8) + "\n[... " + (size - 2L * READ_OUTPUT_BYTES)
                    + " bytes of output left out ...]\n" + new String(tail, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "(" + file + " could not be read: " + e + ")";
        }
    }

    private static String readAll(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "(" + file + " could not be read: " + e + ")";
        }
    }

    /**
     * The lines of a report file, decoded with replacement: a child killed mid-write can leave a truncated
     * multi-byte sequence at the end, and a decoding error would hide why the child failed.
     */
    private static List<String> readLines(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8).lines().toList();
    }

    /**
     * This JVM's class path, which carries the tests and the javafx.base and javafx.graphics classes, plus
     * javafx.media, which this JVM has on the upgrade module path instead.
     */
    static String childClassPath() {
        List<String> entries = new ArrayList<>();
        entries.add(System.getProperty("java.class.path"));
        mediaClasses().ifPresent(entries::add);
        return String.join(File.pathSeparator, entries);
    }

    private static Optional<String> mediaClasses() {
        Module media = NativeMediaManager.class.getModule();
        if (!media.isNamed()) {
            return Optional.empty();
        }
        Optional<URI> location = ModuleLayer.boot().configuration()
                .findModule(media.getName())
                .flatMap(module -> module.reference().location());
        return location.filter(uri -> "file".equals(uri.getScheme()))
                .map(uri -> Path.of(uri).toString());
    }
}
