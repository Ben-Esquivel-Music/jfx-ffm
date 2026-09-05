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
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.abort;

/**
 * The one decision the media tests make about the native layer: are the {@code jfxm_*} libraries
 * expected in this JVM, or is this a build that legitimately has none?
 * <p>
 * This module compiles {@code jfxmedia} from source - {@code modules/javafx.media/native/CMakeLists.txt},
 * driven by the {@code native-win} / {@code native-linux} / {@code native-mac} profiles of
 * {@code modules/javafx.media/pom.xml} - so a library that is reachable and does not work is a broken
 * build, never an environment fact. A missing {@code JFX_EXPORT}, an {@code jfxm_abi_version} that
 * moved, a dependent library that did not build, a module left out of {@code --enable-native-access},
 * or the JNI-era {@code jfxmedia} in {@code ../caches/sdk/bin} shadowing the fresh one are all failures
 * and all used to be skips, which is how twenty binding tests could disappear from a green build.
 * <p>
 * The rule, in one sentence: <em>skip only when this build produced no {@code jfxmedia} and none is on
 * {@code java.library.path} either; otherwise it has to load, resolve every symbol it binds and report the
 * expected ABI version.</em> Those two places are what this class looks in. {@code NativeLibLoader} has two
 * more - beside the {@code javafx.*} jars and, failing that, a copy extracted from one - which a test run
 * out of this repository does not have, so they are left out rather than guessed at.
 * <p>
 * A normal local or CI build has just written a {@code jfxmedia} into {@code target/native/bin}, so it
 * always takes the failing branch; the skip is reachable only through {@code -DskipNative=true} on a tree
 * whose natives were never built. A build that produced this module's other libraries and not this one is
 * not that tree - the native build ran and said it succeeded - so that fails too.
 * <p>
 * This deliberately says nothing about audio hardware, codecs or anything else the machine owns. Those
 * are separate, genuinely environmental decisions and stay where they are made.
 */
public final class MediaNatives {

    /**
     * The directory this module's native build writes into, handed over by the surefire {@code argLine}
     * of {@code modules/javafx.media/pom.xml}. Not being set is a build-configuration error and fails,
     * exactly as {@code jfx.web.dom.abi.spec} does in {@code WebKitAbiDescriptorTest}: a test must not
     * be skipped for want of something the build is supposed to pass it.
     */
    private static final String BIN_DIR_PROPERTY = "jfx.media.nativeBinDir";

    /** {@code jfxmedia.dll}, {@code libjfxmedia.so} or {@code libjfxmedia.dylib}. */
    private static final String LIBRARY_FILE = System.mapLibraryName("jfxmedia");

    /**
     * The other libraries this module's CMake build writes into the same directory: {@code gstreamer-lite}
     * and {@code fxplugins} on every platform, {@code glib-lite} on Windows and macOS. Any of them next to
     * no {@code jfxmedia} means the native build ran and reported success without producing the library
     * these tests exist for.
     */
    private static final List<String> SIBLING_FILES = List.of(System.mapLibraryName("glib-lite"),
            System.mapLibraryName("gstreamer-lite"), System.mapLibraryName("fxplugins"));

    private static boolean decided;
    private static String skipReason;
    private static String failureMessage;
    private static Throwable failureCause;

    private MediaNatives() {
    }

    /**
     * Loads the media natives, or skips the calling test when this build has none anywhere.
     * <p>
     * Decided once per JVM and then replayed: {@code JfxMediaNative}'s library, lookup and failure are
     * per class loader, and the media test classes share a surefire fork, so a second caller has to get
     * the first caller's verdict rather than a second load attempt.
     *
     * @throws AssertionError if a {@code jfxmedia} is reachable and cannot be used, which is a broken
     *         build; the message names every candidate on {@code java.library.path}
     */
    public static synchronized void require() {
        if (!decided) {
            decide();
            decided = true;
        }
        if (failureMessage != null) {
            throw new AssertionError(failureMessage, failureCause);
        }
        if (skipReason != null) {
            abort(skipReason);
        }
    }

    private static void decide() {
        Path binDir = nativeBinDir();
        Path built = binDir.resolve(LIBRARY_FILE);
        List<Path> reachable = reachableLibraries();
        if (!Files.isRegularFile(built)) {
            List<String> siblings = SIBLING_FILES.stream()
                    .filter(name -> Files.isRegularFile(binDir.resolve(name)))
                    .toList();
            if (!siblings.isEmpty()) {
                failureMessage = "this build produced media natives but not the one under test: " + binDir
                        + " holds " + siblings + " and no " + LIBRARY_FILE + ", so the native build ran"
                        + " and reported success without building the library these tests exist for."
                        + " A renamed target, a condition that skipped it or a changed output directory"
                        + " is a broken build, not a tree that has no natives, and skipping here would"
                        + " report zero media tests and a green run.";
                return;
            }
            if (reachable.isEmpty()) {
                skipReason = "this build has no media natives: neither " + built + " nor any other "
                        + LIBRARY_FILE + " on java.library.path exists, so there is nothing here to test."
                        + " Build them with \"mvn -pl modules/javafx.media test\", that is, without"
                        + " -DskipNative=true.";
                return;
            }
        }
        try {
            JfxMediaNative.loadLibraries();
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            failureMessage = failureText(built, reachable, e);
            failureCause = e;
        }
    }

    /**
     * Why a reachable library could not be used, with every candidate the loader could have taken. The
     * usual answer is the last line: a {@code jfxmedia} that loads and exports no {@code jfxm_*} symbol
     * is the JNI-era one, and it only has to be earlier on the path than the fresh one to win.
     */
    private static String failureText(Path built, List<Path> reachable, Throwable cause) {
        StringBuilder text = new StringBuilder(512);
        text.append("the media natives are reachable but unusable, which is a broken build and never")
                .append(" an environment fact: ").append(cause).append('\n');
        text.append(Files.isRegularFile(built)
                ? "this build's own library is " + built + describe(built) + '\n'
                : "this build produced no " + built + ", so nothing below is the library under test\n");
        for (Path library : reachable) {
            text.append("  reachable on java.library.path: ").append(library).append(describe(library))
                    .append('\n');
        }
        text.append("A jfxmedia that loads and does not export jfxm_abi_version is the JNI-era library:")
                .append(" delete it from ../caches/sdk/bin and rebuild with")
                .append(" \"mvn -pl modules/javafx.media test\".");
        return text.toString();
    }

    /** {@code " (N bytes, modified T)"}, or an empty string when the file cannot be inspected. */
    private static String describe(Path library) {
        try {
            return " (" + Files.size(library) + " bytes, modified " + Files.getLastModifiedTime(library)
                    + ")";
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * Every {@code jfxmedia} on {@code java.library.path}, in the order {@code NativeLibLoader} tries
     * the entries - it takes the first one that loads, so the order is the whole point.
     */
    private static List<Path> reachableLibraries() {
        List<Path> found = new ArrayList<>();
        for (String entry : System.getProperty("java.library.path", "").split(File.pathSeparator)) {
            if (entry.isEmpty()) {
                continue;
            }
            try {
                Path library = Path.of(entry).resolve(LIBRARY_FILE);
                if (Files.isRegularFile(library)) {
                    found.add(library);
                }
            } catch (InvalidPathException e) {
                // An entry this platform cannot even parse holds no library; the next one might.
            }
        }
        return found;
    }

    /** The directory this module's CMake build writes its libraries into. */
    private static Path nativeBinDir() {
        String property = System.getProperty(BIN_DIR_PROPERTY);
        assertTrue(property != null && !property.isBlank(), "-D" + BIN_DIR_PROPERTY + " is not set."
                + " The surefire argLine in modules/javafx.media/pom.xml passes it; without it these"
                + " tests cannot tell a build that produced no natives from one whose natives are"
                + " broken, and they must not guess.");
        return Path.of(property);
    }
}
