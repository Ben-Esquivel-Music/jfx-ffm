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

import com.sun.glass.ui.win.WinGlassNativeShim;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * The child JVM of the Windows Glass tests that need a process of their own: one Glass toolkit lifetime, or a
 * call that takes the process down when the code under test is wrong. It runs one scenario method and writes the
 * values it recorded to a file. It is not a test class and has no test of its own; {@link WinGlassChildJvm} is
 * the parent side.
 * <p>
 * Usage: {@code WinGlassChild <class>#<method> <output file>}. The method is a {@code static} method of a test
 * class taking a {@code Map<String, String>}, run on the main thread of this JVM; the map is synchronized, so
 * other threads may write into it too. The scenario starts whatever it needs itself, a toolkit through
 * {@link WinNativeToolkit} for instance, and bounds every wait, so that a hang in the code under test is recorded
 * as a value instead of a stuck child. The map is written as {@code key=value} lines even when the scenario
 * throws, and the JVM then exits with status 2. Every exception that reaches the default uncaught-exception
 * handler fails the scenario too: one that killed a thread - a worker of {@link WinNativeToolkit#startThread}, the
 * toolkit thread - or one Glass reports through {@code Application.reportException} on a thread with no handler of
 * its own. The handler prints the stack trace, as the JDK does without one, and keeps the thread's name and the
 * throwable, and when any was kept by the time the scenario returns the first {@value #UNCAUGHT_SHOWN} are written
 * as {@code child.uncaught} and the JVM exits with status 2. One that arrives after that, from a thread the
 * scenario did not join, is only printed; a thread with a handler of its own, such as Quantum's render thread, is
 * not seen. The exit is {@link Runtime#halt}: a toolkit thread still blocked in native code must not keep the JVM
 * alive. With {@code -Dwin.glass.child.naturalExit=true} ({@link #NATURAL_EXIT_PROPERTY}) a scenario that succeeded
 * returns from {@code main} instead, and the JVM exits only once its last non-daemon thread has ended: that is for a
 * scenario whose subject is the exit itself.
 * <p>
 * The {@code glass.dll} this JVM loaded, wherever the scenario loaded it from, is recorded as
 * {@link #LOADED_GLASS_PATH} and {@link #LOADED_GLASS_SHA256}: a daemon thread asks {@code GetModuleHandleW} every
 * {@value #LOADED_GLASS_POLL_MILLIS} ms, records the module's file and its sha256 once it is loaded and writes the
 * values file then, so that a child its parent kills later still names the build it ran; {@code main} records it
 * after the scenario if that thread had not. A JVM that never loaded it records neither key.
 */
public final class WinGlassChild {

    /** {@code true}: after a scenario that succeeded, return from {@code main} instead of halting. */
    static final String NATURAL_EXIT_PROPERTY = "win.glass.child.naturalExit";

    /** The full path {@code GetModuleFileNameW} answers for the {@code glass.dll} this JVM loaded. */
    static final String LOADED_GLASS_PATH = "glass.loaded.path";

    /** The sha256 of the file at {@link #LOADED_GLASS_PATH}, as lower-case hexadecimal. */
    static final String LOADED_GLASS_SHA256 = "glass.loaded.sha256";

    /** How often the watching thread asks whether {@code glass.dll} has been loaded. */
    private static final long LOADED_GLASS_POLL_MILLIS = 10;

    /** How many of the exceptions that reached the default uncaught-exception handler {@code child.uncaught} names. */
    private static final int UNCAUGHT_SHOWN = 3;

    /** {@code <thread name>: <throwable>} of every exception that reached the default uncaught-exception handler. */
    private static final Queue<String> UNCAUGHT = new ConcurrentLinkedQueue<>();

    /** Serializes the writes of the values file; once {@link #finalWritten}, the watching thread writes no more. */
    private static final Object WRITE_LOCK = new Object();

    private static boolean finalWritten;

    private static volatile boolean stopWatching;

    private WinGlassChild() {
    }

    public static void main(String[] args) {
        Thread.setDefaultUncaughtExceptionHandler(WinGlassChild::uncaught);
        int status = 0;
        Map<String, String> values = Collections.synchronizedMap(new TreeMap<>());
        Path output = Path.of(args[1]);
        Thread watcher = watchLoadedGlass(values, output);
        try {
            String target = args[0];
            int hash = target.indexOf('#');
            Method scenario = Class.forName(target.substring(0, hash))
                    .getDeclaredMethod(target.substring(hash + 1), Map.class);
            scenario.setAccessible(true);
            try {
                scenario.invoke(null, values);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        } catch (Throwable t) {
            t.printStackTrace();
            values.put("child.failure", String.valueOf(t));
            status = 2;
        } finally {
            stopWatching = true;
            WinNativeToolkit.join(watcher, 5_000);
            recordLoadedGlass(values);
            if (!UNCAUGHT.isEmpty()) {
                List<String> kept = List.copyOf(UNCAUGHT);
                values.put("child.uncaught", kept.size() + " uncaught: "
                        + String.join("; ", kept.subList(0, Math.min(kept.size(), UNCAUGHT_SHOWN))));
                status = 2;
            }
            try {
                synchronized (WRITE_LOCK) {
                    finalWritten = true;
                    write(output, values);
                }
            } catch (IOException e) {
                e.printStackTrace();
                status = 3;
            }
        }
        System.out.flush();
        System.err.flush();
        if (status == 0 && Boolean.getBoolean(NATURAL_EXIT_PROPERTY)) {
            return;
        }
        Runtime.getRuntime().halt(status);
    }

    /** Keeps the thread's name and {@code throwable}, then prints the trace as the JDK does with no handler. */
    private static void uncaught(Thread thread, Throwable throwable) {
        UNCAUGHT.add(thread.getName() + ": " + throwable);
        System.err.print("Exception in thread \"" + thread.getName() + "\" ");
        throwable.printStackTrace();
    }

    /**
     * Starts the daemon thread that records the loaded {@code glass.dll} into {@code values} as soon as it is loaded
     * and then writes {@code values} to {@code output}, unless {@code main} has written them already.
     */
    private static Thread watchLoadedGlass(Map<String, String> values, Path output) {
        Thread watcher = new Thread(() -> {
            while (!stopWatching) {
                if (recordLoadedGlass(values)) {
                    synchronized (WRITE_LOCK) {
                        if (!finalWritten) {
                            try {
                                write(output, values);
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                    return;
                }
                try {
                    Thread.sleep(LOADED_GLASS_POLL_MILLIS);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "loaded-glass-watcher");
        watcher.setDaemon(true);
        watcher.start();
        return watcher;
    }

    /**
     * Records {@link #LOADED_GLASS_PATH} and {@link #LOADED_GLASS_SHA256} once this JVM has loaded {@code glass.dll},
     * unless they are recorded already; a failure to find or hash the file is recorded in their place. Whether they
     * are recorded now.
     */
    private static boolean recordLoadedGlass(Map<String, String> values) {
        if (values.containsKey(LOADED_GLASS_PATH)) {
            return true;
        }
        String path;
        String sha256;
        try {
            path = WinGlassNativeShim.loadedModulePath(System.mapLibraryName("glass"));
            if (path == null) {
                return false;
            }
            sha256 = sha256(Path.of(path));
        } catch (Throwable t) {
            path = "(not found: " + t + ")";
            sha256 = "(not computed)";
        }
        synchronized (values) {
            if (!values.containsKey(LOADED_GLASS_PATH)) {
                values.put(LOADED_GLASS_PATH, path);
                values.put(LOADED_GLASS_SHA256, sha256);
            }
        }
        return true;
    }

    /** The sha256 of {@code file} as lower-case hexadecimal, or a note saying why it could not be read. */
    static String sha256(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1 << 16];
            for (int n = in.read(buffer); n > 0; n = in.read(buffer)) {
                digest.update(buffer, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException | RuntimeException e) {
            return "(unreadable: " + e + ")";
        }
    }

    /** Polls {@code condition} every millisecond for at most {@code timeoutMillis}; answers its last value. */
    static boolean waitFor(long timeoutMillis, BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                return condition.getAsBoolean();
            }
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return condition.getAsBoolean();
            }
        }
        return true;
    }

    static void write(Path output, Map<String, String> values) throws IOException {
        StringWriter text = new StringWriter();
        Map<String, String> copy;
        synchronized (values) {
            copy = new TreeMap<>(values);
        }
        for (Map.Entry<String, String> entry : copy.entrySet()) {
            text.append(entry.getKey()).append('=').append(escape(entry.getValue())).append('\n');
        }
        Files.writeString(output, text.toString(), StandardCharsets.UTF_8);
    }

    /** Doubles a backslash, writes a non-printable or non-ASCII char as a hex escape, {@code null} as a lone escape. */
    static String escape(String value) {
        if (value == null) {
            return "\\0";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\') {
                sb.append("\\\\");
            } else if (c >= 0x20 && c < 0x7F) {
                sb.append(c);
            } else {
                sb.append(String.format("\\u%04x", (int) c));
            }
        }
        return sb.toString();
    }

    /** The inverse of {@link #escape}. */
    static String unescape(String text) {
        if (text.equals("\\0")) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\\') {
                sb.append(c);
            } else if (text.charAt(i + 1) == '\\') {
                sb.append('\\');
                i++;
            } else {
                sb.append((char) Integer.parseInt(text.substring(i + 2, i + 6), 16));
                i += 5;
            }
        }
        return sb.toString();
    }
}
