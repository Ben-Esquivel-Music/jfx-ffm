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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.abort;

/**
 * Where the hooks build of {@code jfxmedia} is, whether a test may run without it, and the numbers of its C ABI
 * ({@code src/main/native/jfxmedia/ffi/jfxmedia_test_hooks.h}).
 * <p>
 * The native-win and native-linux profiles of {@code modules/javafx.media/pom.xml} configure the media CMake build
 * with {@code -DJFXM_TEST_HOOKS=ON}, which adds a second {@code jfxmedia}: the same sources plus
 * {@code ffi/jfxmedia_test_hooks.cpp}, compiled with {@code JFXM_TEST_HOOKS=1}, linked under the shipped name into
 * {@code target/native/test-bin} ({@value #TEST_BIN_DIR_PROPERTY}). It needs the {@code gstreamer-lite} of
 * {@code target/native/bin}, and on Windows its {@code glib-lite} too (Linux uses the system GLib), so a child JVM
 * gets {@link #hooksLibraryPath} - the hooks directory first, then the shipped one - and nothing else.
 * <p>
 * The rule is {@link MediaNatives}' rule carried one step further. A build that produced no media natives at all
 * skips, as there. A build that did, on Windows or Linux, has also produced the hooks build, so a missing one is a
 * broken build and fails. macOS is the one platform where the hooks build does not exist by design, and there the
 * tests that need it are skipped by an assumption that says so.
 */
final class MediaTestHooks {

    /** The directory the shipped media natives are in; see {@link MediaNatives}. */
    static final String BIN_DIR_PROPERTY = "jfx.media.nativeBinDir";

    /** The directory the hooks build of {@code jfxmedia} is in, passed by the surefire {@code argLine}. */
    static final String TEST_BIN_DIR_PROPERTY = "jfx.media.nativeTestBinDir";

    /** {@code jfxmedia.dll}, {@code libjfxmedia.so} or {@code libjfxmedia.dylib}. */
    static final String LIBRARY_FILE = System.mapLibraryName("jfxmedia");

    /** The three functions only the hooks build exports. */
    static final List<String> HOOK_SYMBOLS =
            List.of("jfxm_test_reset", "jfxm_test_arm_source_fault", "jfxm_test_counter");

    /** {@code JFXM_TEST_FAULT_*}: the call in {@code CGstPipelineFactory::CreateSourceElement} an armed fault fails. */
    static final int FAULT_NONE = 0;
    static final int FAULT_BIN_NEW = 1;
    static final int FAULT_BUFFER_ELEMENT = 2;
    static final int FAULT_LINK = 3;

    /** {@code JFXM_TEST_COUNTER_*}: the ids {@code jfxm_test_counter} takes. */
    static final int SOURCE_WATCHED = 0;
    static final int SOURCE_FINALIZED = 1;
    static final int SOURCE_FINALIZED_FLOATING = 2;
    static final int BIN_WATCHED = 3;
    static final int BIN_FINALIZED = 4;
    static final int BIN_FINALIZED_FLOATING = 5;
    static final int ADAPTERS_CONSTRUCTED = 6;
    static final int ADAPTERS_DESTROYED = 7;
    static final int FAULTS_FIRED = 8;
    static final int SPECTRUM_STALE_DROPPED = 9;
    static final int SPECTRUM_EVENTS_SENT = 10;
    static final int SPECTRUM_BANDS_WRITTEN = 11;
    static final int COUNTER_COUNT = 12;

    private MediaTestHooks() {
    }

    /** The directory of the shipped media natives. Not being told it is a build-configuration error and fails. */
    static Path nativeBinDir() {
        return directory(BIN_DIR_PROPERTY);
    }

    /**
     * The directory of the hooks build, after {@link MediaNatives#require()}: skips where this build has no media
     * natives at all and on macOS, and fails where the shipped natives are there and the hooks build is not.
     *
     * @return the directory that holds the hooks build of {@code jfxmedia}
     */
    static Path requireHooksBuild() {
        MediaNatives.require();
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("mac")) {
            abort("there is no hooks build of jfxmedia on macOS: native/CMakeLists.txt refuses JFXM_TEST_HOOKS there"
                    + " and the native-mac profile does not ask for it. NOT COVERED ON macOS: the jfxm_test_* fault"
                    + " points and counters, and every assertion made through them. Windows and Linux run them.");
        }
        Path directory = directory(TEST_BIN_DIR_PROPERTY);
        Path library = directory.resolve(LIBRARY_FILE);
        assertTrue(Files.isRegularFile(library), () -> "no hooks build of jfxmedia at " + library + ", although"
                + " a jfxmedia loads in this JVM. The native-win and native-linux profiles build both, with"
                + " -DJFXM_TEST_HOOKS=ON, so this is a broken build, not a machine without the library: a tree"
                + " whose natives were built before that option existed has to be rebuilt without"
                + " -DskipNative=true.");
        return directory;
    }

    /** The {@code java.library.path} of a child that loads the hooks build: its directory, then the shipped one. */
    static String hooksLibraryPath(Path hooksDirectory) {
        return hooksDirectory + File.pathSeparator + nativeBinDir();
    }

    /**
     * Requires the child to have loaded the {@code jfxmedia} in {@code directory}, and requires that library to
     * be the hooks build or not, by what it exports: {@code jfxm_abi_version} always - which shows the lookup sees
     * the library's exports at all - and the three {@link #HOOK_SYMBOLS} all or none.
     *
     * @param run a child that ran {@link JfxmediaLibraryProbe#reportLibrary()}
     * @param directory the directory the child was meant to load {@code jfxmedia} from
     * @param hooksBuild whether that is the hooks build
     */
    static void assertLoaded(ChildJvm.Run run, Path directory, boolean hooksBuild) {
        String loaded = run.value("library.loaded");
        assertNotNull(loaded, () -> "the child loaded no jfxmedia:" + run.describe());
        Path expected = directory.resolve(LIBRARY_FILE);
        assertTrue(sameFile(Path.of(loaded), expected), () -> "the child loaded " + loaded + ", not " + expected
                + ":" + run.describe());
        assertEquals("true", run.value("library.symbol.jfxm_abi_version"), () -> "jfxm_abi_version did not"
                + " resolve in " + loaded + ", so the lookup sees none of its exports:" + run.describe());
        List<String> resolving = HOOK_SYMBOLS.stream()
                .filter(name -> "true".equals(run.value("library.symbol." + name)))
                .toList();
        assertEquals(hooksBuild ? HOOK_SYMBOLS : List.of(), resolving, () -> (hooksBuild
                ? "the hooks build " + loaded + " does not export every test hook"
                : "the shipped " + loaded + " exports test hooks, which only the hooks build may") + ":"
                + run.describe());
    }

    /** Counter {@code id} as the child reported it under {@code counter.<id>}. */
    static int counter(ChildJvm.Run run, int id) {
        String value = run.value("counter." + id);
        assertNotNull(value, () -> "the child reported no counter " + id + ":" + run.describe());
        return Integer.parseInt(value);
    }

    private static Path directory(String property) {
        String value = System.getProperty(property);
        assertTrue(value != null && !value.isBlank(), () -> "-D" + property + " is not set. The surefire argLine in"
                + " modules/javafx.media/pom.xml passes it, and without it a test cannot tell which jfxmedia it is"
                + " about to load.");
        return Path.of(value);
    }

    private static boolean sameFile(Path a, Path b) {
        try {
            return Files.isSameFile(a, b);
        } catch (IOException e) {
            return false;
        }
    }
}
