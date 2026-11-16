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

import com.sun.glass.utils.NativeLibLoader;
import com.sun.media.jfxmediaimpl.JfxMediaNative;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The child JVM of {@link JfxmTestHooksExportTest}, and the report every probe here starts with: which
 * {@code jfxmedia} the facade loaded, and which of the names that tell the shipped library from the hooks build it
 * exports. The parent checks both with {@link MediaTestHooks#assertLoaded}, so no test can pass against the wrong
 * library.
 */
public final class JfxmediaLibraryProbe {

    private JfxmediaLibraryProbe() {
    }

    /**
     * Loads the media natives the way the facade does and reports them.
     *
     * @param args ignored
     * @throws Exception if the libraries cannot be loaded, which ends the child with the loader's message
     */
    public static void main(String[] args) throws Exception {
        reportLibrary();
        ChildJvm.report("done", true);
        System.exit(0);
    }

    /**
     * Loads {@code glib-lite}, {@code gstreamer-lite} and {@code jfxmedia} through {@link JfxMediaNative}, from
     * this JVM's {@code java.library.path}, and reports the file {@code jfxmedia} was opened from and whether
     * {@code jfxm_abi_version} and each of {@link MediaTestHooks#HOOK_SYMBOLS} resolve. Names resolve against the
     * libraries {@code NativeLibLoader} loaded, through each library's own export table.
     *
     * @throws ReflectiveOperationException if {@code NativeLibLoader} no longer records the path
     */
    static void reportLibrary() throws ReflectiveOperationException {
        JfxMediaNative.loadLibraries();
        ChildJvm.report("library.loaded", loadedPath());
        ChildJvm.report("library.symbol.jfxm_abi_version", JfxMediaNative.resolvesSymbol("jfxm_abi_version"));
        for (String name : MediaTestHooks.HOOK_SYMBOLS) {
            ChildJvm.report("library.symbol." + name, JfxMediaNative.resolvesSymbol(name));
        }
    }

    /**
     * {@code NativeLibLoader.libraryPath}, which is package private and reached through a shim elsewhere.
     * javafx.media has no shims, and in this child javafx.graphics is on the class path, so reflection reaches it.
     */
    private static Path loadedPath() throws ReflectiveOperationException {
        Method libraryPath = NativeLibLoader.class.getDeclaredMethod("libraryPath", String.class);
        libraryPath.setAccessible(true);
        Optional<?> path = (Optional<?>) libraryPath.invoke(null, "jfxmedia");
        return (Path) path.orElseThrow(() -> new IllegalStateException("NativeLibLoader has loaded no jfxmedia"));
    }
}
