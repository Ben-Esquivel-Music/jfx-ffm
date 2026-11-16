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

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shipped {@code jfxmedia} carries no test hook. The media native build writes two builds of the library on
 * Windows and Linux: the shipped one into {@code target/native/bin}, which the SDK, the other modules and every
 * other test load, and the hooks build into {@code target/native/test-bin} ({@link MediaTestHooks}).
 * <p>
 * Each test loads one of them in a child JVM whose {@code java.library.path} names only the directories it needs,
 * and asks the library that was loaded for the three {@code jfxm_test_*} names. The shipped library must export
 * none; the hooks build must export all three, which is what shows the lookup would have found them. On macOS,
 * where only the shipped library is built, the second test is skipped and the first still runs.
 */
public class JfxmTestHooksExportTest {

    /** How long a child that only loads the libraries gets. */
    private static final long CHILD_TIMEOUT_SECONDS = 60L;

    @Test
    void theShippedLibraryExportsNoTestHook(@TempDir Path dir) {
        MediaNatives.require();
        Path shipped = MediaTestHooks.nativeBinDir();

        ChildJvm.Run run = ChildJvm.run(JfxmediaLibraryProbe.class, shipped.toString(), dir, "shipped",
                CHILD_TIMEOUT_SECONDS, List.of());

        assertTrue(run.clean(), () -> "the child that loads the shipped jfxmedia failed:" + run.describe());
        System.out.println("shipped: " + summary(run));
        MediaTestHooks.assertLoaded(run, shipped, false);
    }

    @Test
    void theHooksBuildExportsEveryTestHook(@TempDir Path dir) {
        Path hooks = MediaTestHooks.requireHooksBuild();

        ChildJvm.Run run = ChildJvm.run(JfxmediaLibraryProbe.class, MediaTestHooks.hooksLibraryPath(hooks), dir,
                "hooks", CHILD_TIMEOUT_SECONDS, List.of());

        assertTrue(run.clean(), () -> "the child that loads the hooks build of jfxmedia failed:" + run.describe());
        System.out.println("hooks: " + summary(run));
        MediaTestHooks.assertLoaded(run, hooks, true);
    }

    /** The library the child loaded and the reported resolution of each name. */
    private static String summary(ChildJvm.Run run) {
        StringBuilder text = new StringBuilder(run.value("library.loaded"));
        text.append(", jfxm_abi_version=").append(run.value("library.symbol.jfxm_abi_version"));
        for (String name : MediaTestHooks.HOOK_SYMBOLS) {
            text.append(", ").append(name).append('=').append(run.value("library.symbol." + name));
        }
        return text.append(", child ").append(run.millis()).append(" ms").toString();
    }
}
