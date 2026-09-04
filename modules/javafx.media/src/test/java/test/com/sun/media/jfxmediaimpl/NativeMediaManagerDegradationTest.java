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

import com.sun.media.jfxmediaimpl.JfxMediaNative;
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
 * All three run in a JVM of their own: the facade's library, its singleton and the platform list are per
 * class loader, and this JVM already has a working {@code jfxmedia}, which cannot be unloaded. The child
 * runs everything from the class path (no module path), so it needs no {@code --add-exports}, and its
 * {@code java.library.path} is either a directory this test controls or, for the denied-access case, this
 * JVM's own.
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
     * The third door into the same wreckage, and the one the review found: the library is exactly where
     * it always is and loads, and the only thing missing is the native-access grant.
     * {@code System::load}, {@code Linker::upcallStub} and {@code Linker::downcallHandle} are all
     * restricted methods, so a JVM run with {@code --illegal-native-access=deny} and no grant for this
     * code refuses them with an {@link IllegalCallerException} - a {@code RuntimeException}, which
     * neither {@code JfxMediaNative}'s {@code catch (UnsatisfiedLinkError)} nor anything above it used to
     * catch. Applications that grant only {@code javafx.graphics} are common enough that this is not a
     * hypothetical; this repository's own {@code apps/samples/RichTextAreaDemo} is one.
     * <p>
     * Which restricted call is refused first differs between the child and a modular run: on the class
     * path the loader and the facade are both unnamed, so the library load goes first, while a modular
     * JVM that grants javafx.graphics alone loads the library and then refuses the facade's upcall stubs.
     * The requirement is the same either way - the class initializer records the refusal as an
     * {@link UnsatisfiedLinkError} and does not throw - so either door proves it.
     */
    @Test
    void mediaDegradesWhenNativeAccessIsDenied(@TempDir Path dir) throws Exception {
        assumeTrue(mediaLibraryIsUsable(), "jfxmedia does not load in this JVM");

        String output = runProbe(System.getProperty("java.library.path", ""),
                dir.resolve("denied-output.txt"), "--illegal-native-access=deny");

        assertDegradedGracefully(output);
        assertTrue(output.contains("was not granted native access"),
                () -> "the failure should name the missing grant, not just the symptom:\n" + output);
    }

    /**
     * Whether this JVM's own {@code java.library.path} - the one the child is handed - carries a
     * {@code jfxmedia} that loads. The denied-access case needs the grant to be the only thing missing,
     * so a build without the media natives skips it rather than failing on the wrong cause.
     */
    private static boolean mediaLibraryIsUsable() {
        try {
            JfxMediaNative.loadLibraries();
            return true;
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            return false;
        }
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
     * Runs the probe against a directory this test built, whose whole content is the child's
     * {@code java.library.path}, and logs beside it - never inside it, because that directory has to stay
     * exactly as the caller set it up.
     * <p>
     * The child gets {@code --enable-native-access=ALL-UNNAMED} because it loads javafx.media from its
     * class path, where the module is unnamed: that is how the child says "javafx.media has native
     * access", the same grant this build makes with {@code --enable-native-access=javafx.media}, and not
     * a widening of it. Nothing in the test code itself calls a restricted method.
     */
    private static String runProbe(Path libraries) throws IOException, InterruptedException {
        return runProbe(libraries.toString(),
                libraries.resolveSibling(libraries.getFileName() + "-output.txt"),
                "--enable-native-access=ALL-UNNAMED");
    }

    /**
     * Runs {@link MediaWithoutNativesProbe} in a child JVM whose {@code java.library.path} is exactly
     * {@code libraryPath} and which is started with {@code jvmOptions}, and returns everything it printed
     * on either stream. The native-access grant is one of those options rather than a fixture of this
     * method: leaving it out is itself one of the failures under test.
     * <p>
     * Its output goes to {@code log} and the timed {@link Process#waitFor(long, TimeUnit)} happens before
     * that file is read. Reading the child's pipe instead would block until the child closed its end,
     * which a hung child never does, so {@link #TIMEOUT_SECONDS} would never be reached and this
     * regression probe would hang the run rather than fail it; a file also cannot fill up and block the
     * child mid-write, and it keeps whatever the child had printed when it had to be killed, so a
     * timeout is still diagnosable.
     */
    private static String runProbe(String libraryPath, Path log, String... jvmOptions)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-classpath");
        command.add(childClassPath());
        command.add("-Djava.library.path=" + libraryPath);
        command.add("-Djfxmedia.loglevel=error");
        command.addAll(List.of(jvmOptions));
        command.add(MediaWithoutNativesProbe.class.getName());

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
