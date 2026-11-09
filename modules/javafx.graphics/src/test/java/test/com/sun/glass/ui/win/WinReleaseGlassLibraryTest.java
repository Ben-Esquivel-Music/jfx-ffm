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
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The release {@code glass.dll} carries no test hook (US-068). The javafx.graphics native build writes two builds of
 * the library: the release build to {@code target/native/bin}, which every other module, {@code tests/system} and a
 * packaged runtime load, and a test build with every {@code gwin_test_*} hook compiled in (CMake option
 * {@code GLASS_WIN_TEST_HOOKS}) to {@code target/native/test-bin}, which this module's surefire configuration puts
 * first on {@code java.library.path}, so that the other {@code Win*} test classes load it.
 * <p>
 * A child JVM ({@link WinGlassChild}) whose {@code java.library.path} is the release directory alone loads the
 * release build and proves two things: {@code WinGlassNative} initializes against it - the library loads, every
 * eager symbol binds and the ABI check passes, which it could not while the facade bound three of the hooks - and
 * none of the {@code gwin_test_*} names {@code glass_win_api.h} declares resolves in it. The positive control runs in
 * this JVM, which loaded the test build: every one of those names resolves there, so the lookup in the child would
 * have found them.
 * <p>
 * Nothing is skipped: on Windows the release {@code glass.dll} must exist, because this module builds it, and a
 * missing one fails. {@code -Dwin.glass.releaseLibraryDir=<dir>} ({@link #RELEASE_DIR_PROPERTY}) points the child at
 * another directory; pointed at {@code target/native/test-bin} the release test has to fail, which is how it is shown
 * to be able to.
 */
@EnabledOnOs(OS.WINDOWS)
public class WinReleaseGlassLibraryTest {

    /** Where the child takes {@code glass.dll} from instead of {@link #RELEASE_DIR}. */
    static final String RELEASE_DIR_PROPERTY = "win.glass.releaseLibraryDir";

    /** The child's comma-separated list of the hook names it looks up. */
    static final String HOOK_NAMES_PROPERTY = "win.glass.testHookNames";

    /** The release build's directory, relative to the module directory, surefire's working directory. */
    private static final Path RELEASE_DIR = Path.of("target", "native", "bin");

    /** The test build's directory, relative to the module directory. */
    private static final Path TEST_DIR = Path.of("target", "native", "test-bin");

    /** The header that declares every {@code gwin_*} export, relative to the module directory. */
    private static final Path HEADER = Path.of("src", "main", "native-glass", "win", "glass_win_api.h");

    /** One exported declaration of a hook: {@code GLASS_WIN_EXPORT <type> gwin_test_<name>(}. */
    private static final Pattern HOOK_DECLARATION =
            Pattern.compile("GLASS_WIN_EXPORT\\s[^;(]*?\\b(gwin_test_\\w+)\\s*\\(");

    /**
     * The hooks the shim binds besides its seventeen {@link WinGlassNativeShim#TOOLKIT_HOOK_NAMES}, in the header's
     * order: with those, the 26 the header declared when US-068 took them out of the release build. The header may
     * declare more; it may not declare fewer, or the parse below has stopped finding them.
     */
    private static final List<String> TABLE_HOOK_NAMES = List.of("gwin_test_fire_callback",
            "gwin_test_fire_window_callback", "gwin_test_fire_screen_callback", "gwin_test_fire_clipboard_callback",
            "gwin_test_fire_dnd_callback", "gwin_test_string_block", "gwin_test_fire_accessible_callback",
            "gwin_test_fire_text_range_callback", "gwin_test_variant_offsets");

    private static final String GLASS_FILE = System.mapLibraryName("glass");

    /** The one child of this class, launched by whichever test needs it first. */
    private static WinGlassChildJvm.Run releaseRun;

    @Test
    public void theHeaderDeclaresEveryHookTheShimBinds() {
        List<String> declared = declaredHooks();
        List<String> known = new ArrayList<>(TABLE_HOOK_NAMES);
        known.addAll(WinGlassNativeShim.TOOLKIT_HOOK_NAMES);
        assertEquals(26, known.size());
        List<String> undeclared = known.stream().filter(name -> !declared.contains(name)).toList();
        assertEquals(List.of(), undeclared, "hooks the shim binds that " + HEADER + " no longer declares as"
                + " GLASS_WIN_EXPORT (declared: " + declared + ")");
    }

    /**
     * The positive control: this JVM loaded the test build, from {@link #TEST_DIR}, and every hook the header
     * declares resolves in it.
     */
    @Test
    public void theTestBuildExportsEveryHook() throws IOException {
        Path testGlass = TEST_DIR.resolve(GLASS_FILE).toAbsolutePath();
        assertTrue(Files.isRegularFile(testGlass), "no test build of glass.dll at " + testGlass + ": the"
                + " javafx.graphics native build writes it there with -DGLASS_WIN_TEST_HOOKS=ON, and every Win* class"
                + " that calls a gwin_test_* hook needs it");
        WinGlassNativeShim.loadLibrary();
        String loaded = WinGlassNativeShim.loadedModulePath(GLASS_FILE);
        assertTrue(loaded != null && Files.isSameFile(Path.of(loaded), testGlass), "this JVM loaded " + loaded
                + ", not the test build " + testGlass + ": target/native/test-bin must come before target/native/bin"
                + " on java.library.path (" + System.getProperty("java.library.path") + ")");
        List<String> missing = declaredHooks().stream()
                .filter(name -> !WinGlassNativeShim.resolves("glass!" + name)).toList();
        assertEquals(List.of(), missing, "hooks the test build " + loaded + " does not export");
    }

    /** The child loaded the release build and nothing else, and {@code WinGlassNative} initialized against it. */
    @Test
    public void theReleaseBuildInitializesTheFacade() throws IOException {
        Path releaseGlass = releaseDirectory().resolve(GLASS_FILE);
        WinGlassChildJvm.Run run = releaseRun();
        assertEquals(releaseDirectory().toString(), run.value("library.path"),
                "the child's java.library.path is not the release directory alone: " + run.describe());
        String loaded = run.value(WinGlassChild.LOADED_GLASS_PATH);
        assertTrue(Files.isSameFile(Path.of(loaded), releaseGlass),
                "the child loaded " + loaded + ", not " + releaseGlass + ": " + run.describe());
        String expected = Integer.toString(WinGlassNativeShim.expectedAbiVersion());
        assertEquals(expected, run.value("abi.expected"), run.describe());
        assertEquals(expected, run.value("abi.actual"), run.describe());
    }

    /** No {@code gwin_test_*} name resolves in the release build, and the facade bound none. */
    @Test
    public void theReleaseBuildExportsNoTestHook() {
        List<String> declared = declaredHooks();
        WinGlassChildJvm.Run run = releaseRun();
        assertEquals(Integer.toString(declared.size()), run.value("hooks.checked"), run.describe());
        assertEquals("", run.value("hooks.resolving"), "test hooks the release glass.dll exports, which only its"
                + " test build may: " + run.describe());
        assertEquals("", run.value("bound.hooks"), "test hooks WinGlassNative bound: " + run.describe());
    }

    /** Runs in {@link WinGlassChild}, with {@code java.library.path} set to the release directory alone. */
    static void releaseLibrary(Map<String, String> out) {
        out.put("library.path", System.getProperty("java.library.path"));
        String names = System.getProperty(HOOK_NAMES_PROPERTY, "");
        if (names.isEmpty()) {
            throw new IllegalStateException("no " + HOOK_NAMES_PROPERTY + " to look up");
        }
        WinGlassNativeShim.loadLibrary();
        out.put("abi.expected", Integer.toString(WinGlassNativeShim.expectedAbiVersion()));
        out.put("abi.actual", Integer.toString(WinGlassNativeShim.abiVersion()));
        List<String> hooks = List.of(names.split(","));
        out.put("hooks.checked", Integer.toString(hooks.size()));
        out.put("hooks.resolving", hooks.stream()
                .filter(name -> WinGlassNativeShim.resolves("glass!" + name)).collect(Collectors.joining(",")));
        out.put("bound.hooks", WinGlassNativeShim.boundSymbols().stream()
                .filter(symbol -> symbol.startsWith("glass!gwin_test_")).collect(Collectors.joining(",")));
    }

    /**
     * Launches the child once, after checking that the release build exists: a missing one fails here, never skips,
     * because this module's native build writes it.
     */
    private static synchronized WinGlassChildJvm.Run releaseRun() {
        if (releaseRun == null) {
            Path releaseGlass = releaseDirectory().resolve(GLASS_FILE);
            assertTrue(Files.isRegularFile(releaseGlass), "no release glass.dll at " + releaseGlass + ": the"
                    + " javafx.graphics native build writes it there, so a missing one is a broken build (build the"
                    + " natives with \"mvn -pl modules/javafx.graphics -am test\", without -DskipNative=true)");
            List<String> options = List.of("-Djava.library.path=" + releaseDirectory(),
                    "-D" + HOOK_NAMES_PROPERTY + "=" + String.join(",", declaredHooks()));
            WinGlassChildJvm.Run run = WinGlassChildJvm.launch(WinReleaseGlassLibraryTest.class, "releaseLibrary",
                    "releaseLibrary", options, WinGlassChildJvm.CHILD_SECONDS);
            System.out.println("release glass.dll: " + run.describe());
            assertTrue(run.clean(), "the child that loads the release glass.dll failed: " + run.describe());
            releaseRun = run;
        }
        return releaseRun;
    }

    /** {@link #RELEASE_DIR}, or what {@link #RELEASE_DIR_PROPERTY} names, absolute. */
    private static Path releaseDirectory() {
        String override = System.getProperty(RELEASE_DIR_PROPERTY, "");
        return (override.isEmpty() ? RELEASE_DIR : Path.of(override)).toAbsolutePath().normalize();
    }

    /** Every {@code gwin_test_*} name {@link #HEADER} declares as {@code GLASS_WIN_EXPORT}, in its order, once each. */
    private static List<String> declaredHooks() {
        String header;
        try {
            header = Files.readString(HEADER, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + HEADER.toAbsolutePath(), e);
        }
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = HOOK_DECLARATION.matcher(header);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return List.copyOf(names);
    }
}
