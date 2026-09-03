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
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What an application sees when {@code jfxmedia} cannot be used: media has to report itself as
 * unavailable, not take the whole {@code NativeMediaManager} class down with it.
 * <p>
 * {@code NativeMediaManager}'s constructor runs inside its singleton's class initializer, so any throw
 * from it - including the {@link UnsatisfiedLinkError} that {@code JfxMediaNative.loadLibraries()} raises
 * for a library that is missing, is missing a {@code jfxm_*} symbol, or reports the wrong ABI version -
 * turns every later {@code getDefaultInstance()} into a {@code NoClassDefFoundError}. Under JNI the first
 * call into the library was lazy, so this surfaced later and further in, inside
 * {@code GSTPlatform.loadPlatform()}, which catches it and posts {@code ERROR_MANAGER_ENGINEINIT_FAIL}.
 * The FFM facade binds and version-checks the library eagerly, which moves the same failure into the
 * constructor; these tests pin the behaviour that has to come out of it either way.
 * <p>
 * Both run in a JVM of their own: the facade's library, its singleton and the platform list are per class
 * loader, and this JVM already has a working {@code jfxmedia}, which cannot be unloaded. The child runs
 * everything from the class path (no module path), so it needs no {@code --add-exports}, and its
 * {@code java.library.path} is a directory this test controls.
 */
public class NativeMediaManagerDegradationTest {

    /** How long the child gets before it is treated as hung. */
    private static final int TIMEOUT_SECONDS = 120;

    @Test
    void mediaDegradesWhenTheLibraryIsMissing(@TempDir Path dir) throws Exception {
        Path libraries = Files.createDirectory(dir.resolve("no-libraries"));

        String output = runProbe(libraries);

        assertDegradedGracefully(output);
        assertTrue(output.contains("UnsatisfiedLinkError"),
                () -> "the load failure should be reported, not swallowed:\n" + output);
    }

    /**
     * The reproduction from the review: not a missing library but a <em>stale</em> one - the JNI-era
     * {@code jfxmedia} that still sits in {@code ../caches/sdk/bin}. It loads and then fails to resolve
     * {@code jfxm_abi_version}, which is the same {@link UnsatisfiedLinkError} arriving through a
     * completely different door. Any library that loads and exports no {@code jfxm_*} symbol reproduces
     * it, so the JDK's own {@code zip} library stands in for one.
     */
    @Test
    void mediaDegradesWhenTheLibraryHasNoJfxmSymbols(@TempDir Path dir) throws Exception {
        Path donor = donorLibrary();
        assumeTrue(Files.isReadable(donor), "no stand-in library at " + donor);

        Path libraries = Files.createDirectory(dir.resolve("stale-libraries"));
        for (String name : List.of("jfxmedia", "glib-lite", "gstreamer-lite", "fxplugins")) {
            Files.copy(donor, libraries.resolve(System.mapLibraryName(name)));
        }

        String output = runProbe(libraries);

        assertDegradedGracefully(output);
        assertTrue(output.contains("missing native symbol: jfxm_abi_version")
                        || output.contains("UnsatisfiedLinkError"),
                () -> "the load failure should be reported, not swallowed:\n" + output);
    }

    /**
     * The whole point: the singleton still answers, the content types and protocols still come back, and
     * the failure arrives as the {@code MediaException} the media stack is written around.
     */
    private static void assertDegradedGracefully(String output) {
        assertFalse(output.contains("NoClassDefFoundError"),
                () -> "the manager's class initializer failed:\n" + output);
        assertFalse(output.contains("ExceptionInInitializerError"),
                () -> "something escaped the manager's constructor:\n" + output);
        assertTrue(output.contains("MANAGER=true"),
                () -> "NativeMediaManager.getDefaultInstance() failed:\n" + output);
        assertTrue(output.contains("MANAGER_AGAIN=true"),
                () -> "the second getDefaultInstance() failed:\n" + output);
        assertTrue(output.contains("audio/x-wav"),
                () -> "the supported content types went missing:\n" + output);
        assertTrue(output.contains("PLAYER_MEDIA_EXCEPTION=ERROR_MANAGER_ENGINEINIT_FAIL"),
                () -> "creating a player should raise ERROR_MANAGER_ENGINEINIT_FAIL:\n" + output);
        assertTrue(output.contains("Unable to load one or more dependent libraries"),
                () -> "the library failure should be logged:\n" + output);
        assertTrue(output.contains("DONE"), () -> "the probe did not run to the end:\n" + output);
    }

    /**
     * Runs {@link MediaWithoutNativesProbe} in a child JVM whose {@code java.library.path} is exactly
     * {@code libraries}, and returns everything it printed on either stream.
     * <p>
     * The child gets {@code --enable-native-access=ALL-UNNAMED} because it loads javafx.media from its
     * class path, where the module is unnamed: that is how the child says "javafx.media has native
     * access", the same grant this build makes with {@code --enable-native-access=javafx.media}, and not
     * a widening of it. Nothing in the test code itself calls a restricted method.
     * <p>
     * Its output goes to a file and the timed {@link Process#waitFor(long, TimeUnit)} happens before
     * that file is read. Reading the child's pipe instead would block until the child closed its end,
     * which a hung child never does, so {@link #TIMEOUT_SECONDS} would never be reached and this
     * regression probe would hang the run rather than fail it; a file also cannot fill up and block the
     * child mid-write, and it keeps whatever the child had printed when it had to be killed, so a
     * timeout is still diagnosable. The file is a sibling of {@code libraries}, never inside it: that
     * directory is the child's whole {@code java.library.path} and has to stay exactly as the caller
     * set it up.
     */
    private static String runProbe(Path libraries) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-classpath");
        command.add(childClassPath());
        command.add("-Djava.library.path=" + libraries);
        command.add("-Djfxmedia.loglevel=error");
        command.add("--enable-native-access=ALL-UNNAMED");
        command.add(MediaWithoutNativesProbe.class.getName());

        Path log = libraries.resolveSibling(libraries.getFileName() + "-output.txt");
        Process child = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        boolean finished = child.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            child.destroyForcibly().waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        // Not Files.readString: a child killed mid-write can leave a truncated multi-byte sequence,
        // which readString rejects and this constructor replaces - the output is a failure message here,
        // never data, so a replacement character is always better than losing it.
        String output = new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
        assertTrue(finished, () -> "the child JVM did not finish:\n" + command + "\n" + output);
        assertEquals(0, child.exitValue(), () -> "the child JVM failed:\n" + output);
        return output;
    }

    /**
     * The class path the child needs: this JVM's own, which carries the tests and the javafx.base and
     * javafx.graphics jars, plus javafx.media itself, which this JVM has on the upgrade module path
     * rather than the class path. The child loads all of it from the class path, where the module
     * system's access rules do not apply, so no {@code --add-exports} has to be repeated.
     */
    private static String childClassPath() {
        List<String> entries = new ArrayList<>();
        entries.add(System.getProperty("java.class.path"));
        mediaClasses().ifPresent(entries::add);
        return String.join(File.pathSeparator, entries);
    }

    private static Optional<String> mediaClasses() {
        Module media = NativeMediaManager.class.getModule();
        if (!media.isNamed()) {
            return Optional.empty();    // already on the class path, so java.class.path has it
        }
        Optional<URI> location = ModuleLayer.boot().configuration()
                .findModule(media.getName())
                .flatMap(module -> module.reference().location());
        return location.filter(uri -> "file".equals(uri.getScheme()))
                .map(uri -> Path.of(uri).toString());
    }

    /**
     * A library that is certain to exist and to load, and equally certain not to export a {@code jfxm_*}
     * symbol: the JDK's own {@code zip}.
     */
    private static Path donorLibrary() {
        Path home = Path.of(System.getProperty("java.home"));
        Path directory = Files.isDirectory(home.resolve("bin")) && isWindows()
                ? home.resolve("bin") : home.resolve("lib");
        return directory.resolve(System.mapLibraryName("zip"));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().startsWith("win");
    }
}
