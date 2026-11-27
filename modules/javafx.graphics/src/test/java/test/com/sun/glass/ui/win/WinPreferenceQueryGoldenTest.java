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
import com.sun.glass.ui.win.WinGlassNativeShim.QueryResult;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.abort;

/**
 * The bytes {@code gwin_prefs_query_ui_settings} and {@code gwin_prefs_query_network} write on the toolkit thread of
 * a live toolkit, with their statuses, against {@value #GOLDEN}: captured from the C++ of
 * {@code PlatformSupport::collectUISettings} and {@code collectNetworkInfo} at commit {@code 5a709c189d}
 * ({@code GLASS_WIN_ABI_VERSION} 7) on one machine. The values are that machine's: its theme, accent colour,
 * transparency effects, scroll-bar setting and network. The golden therefore verifies only on that machine, while
 * all of these are unchanged; its {@code machine.*} keys name the machine, {@code decoded.*} restates the bytes for a
 * reader. Regenerating it is a behaviour change, not a test fix: it moves what a port of that C is compared against.
 * <p>
 * Opt-in, skipped unless {@value #MODE} is set:
 * <ul>
 * <li>{@code -D}{@value #MODE}{@code =verify} compares the statuses and every byte with the golden, and names each
 *     field that differs. {@code -D}{@value #FILE}{@code =<path>} compares with that file instead.</li>
 * <li>{@code -D}{@value #MODE}{@code =capture -D}{@value #COMMIT}{@code =<commit>} writes
 *     {@code src/test/resources/test/com/sun/glass/ui/win/win-prefs-query-golden-<commit>.txt}, or the file
 *     {@value #FILE} names, recording the commit as given; it refuses to overwrite a file. Take it only from a build
 *     of that commit.</li>
 * </ul>
 * Both modes query twice in one toolkit and fail when the two answers differ: a value that changes between two calls
 * is no golden.
 */
@EnabledOnOs(OS.WINDOWS)
public class WinPreferenceQueryGoldenTest {

    static final String MODE = "win.glass.prefsGolden";
    static final String COMMIT = "win.glass.prefsGolden.commit";
    static final String FILE = "win.glass.prefsGolden.file";

    /** The checked-in golden, a resource of this package. */
    static final String GOLDEN = "win-prefs-query-golden-5a709c189d.txt";

    private static final long WAIT_MILLIS = 10_000;

    /** The four values compared, in the order of the file. */
    private static final List<String> QUERY_KEYS = List.of("query.ui.status", "query.ui.bytes",
            "query.network.status", "query.network.bytes");

    /** The machine keys compared before anything else. */
    private static final List<String> MACHINE_KEYS = List.of("machine.os.name", "machine.os.arch",
            "machine.windows.build");

    /**
     * {@code GwinUiSettings.colors}, in the order of {@code COLOR_TYPES} in the glass-win crate's {@code prefs.rs},
     * which is the order of {@code colorTypes} in {@code PlatformSupport.cpp} at {@code 5a709c189d}.
     */
    private static final List<String> COLOR_NAMES = List.of("Background", "Foreground", "AccentDark3", "AccentDark2",
            "AccentDark1", "Accent", "AccentLight1", "AccentLight2", "AccentLight3");

    /** {@code enum GwinNetworkCost} of {@code glass_win_api.h}. */
    private static final List<String> COST_NAMES = List.of("UNKNOWN", "UNRESTRICTED", "VARIABLE", "FIXED");

    @BeforeAll
    static void requireNatives() {
        WinGlassNatives.require();
    }

    @Test
    @Timeout(180)
    @EnabledIfSystemProperty(named = MODE, matches = ".+")
    public void theQueryStructsOnTheToolkitThreadEqualTheGoldenCapturedFromTheC() throws IOException {
        String mode = System.getProperty(MODE);
        if (!mode.equals("capture") && !mode.equals("verify")) {
            fail(MODE + " is '" + mode + "'; it takes capture or verify");
        }
        WinGlassChildJvm.Run run = WinGlassChildJvm.run(WinPreferenceQueryGoldenTest.class, "queryTwice");
        System.out.println("preference queries: " + run.values() + " (" + run.loadedGlass() + ")");
        Map<String, String> captured = captured(run);
        if (mode.equals("capture")) {
            Path written = capture(captured);
            abort("golden captured to " + written.toAbsolutePath() + "; a capture verifies nothing");
        }
        verify(captured, golden());
    }

    /** Runs in {@link WinGlassChild}: both queries, twice, on the toolkit thread of a live toolkit. */
    static void queryTwice(Map<String, String> out) {
        WinNativeToolkit toolkit = WinNativeToolkit.start();
        out.put("query.invoke", Integer.toString(toolkit.invokeAndWait(() -> {
            for (int round = 1; round <= 2; round++) {
                QueryResult ui = WinGlassNativeShim.queryUiSettingsRaw((byte) 0xA5);
                QueryResult network = WinGlassNativeShim.queryNetworkRaw((byte) 0xA5);
                out.put("round" + round + ".query.ui.status", Integer.toString(ui.status()));
                out.put("round" + round + ".query.ui.bytes", HexFormat.of().formatHex(ui.bytes()));
                out.put("round" + round + ".query.network.status", Integer.toString(network.status()));
                out.put("round" + round + ".query.network.bytes", HexFormat.of().formatHex(network.bytes()));
            }
        })));
        out.put("terminate.post", Integer.toString(toolkit.terminate()));
        out.put("loop.returned", Boolean.toString(toolkit.awaitLoopReturn(WAIT_MILLIS)));
        toolkit.releaseAndAwaitEnd(WAIT_MILLIS);
    }

    /** The golden's keys for what the child recorded; fails when its two rounds differ. */
    private static Map<String, String> captured(WinGlassChildJvm.Run run) {
        List<String> changed = new ArrayList<>();
        Map<String, String> query = new LinkedHashMap<>();
        for (String key : QUERY_KEYS) {
            String first = run.value("round1." + key);
            String second = run.value("round2." + key);
            if (!first.equals(second)) {
                changed.add(key + ": first " + first + ", then " + second);
            }
            query.put(key, first);
        }
        if (!changed.isEmpty()) {
            fail("the queries answered differently in two calls, so their answer is no golden: " + changed + "\n"
                    + run.describe());
        }
        Map<String, String> out = new LinkedHashMap<>();
        out.put("machine.os.name", System.getProperty("os.name"));
        out.put("machine.os.arch", System.getProperty("os.arch"));
        String version = windowsVersion();
        out.put("machine.windows.build", build(version));
        out.put("info.windows.version", version);
        out.put("capture.provenance", System.getProperty("java.vm.name") + " " + System.getProperty("java.vm.version"));
        out.put("capture.glass.sha256", run.values().getOrDefault(WinGlassChild.LOADED_GLASS_SHA256, "unknown"));
        out.putAll(query);
        out.putAll(decoded(query));
        return out;
    }

    private static Path capture(Map<String, String> captured) throws IOException {
        String commit = System.getProperty(COMMIT);
        if (commit == null || !commit.matches("[0-9a-f]{7,40}")) {
            fail("a capture names the commit of the C it was taken from: -D" + COMMIT + "=<commit hash>, not '"
                    + commit + "'");
        }
        String file = System.getProperty(FILE);
        Path target = file != null ? Path.of(file)
                : Path.of("src", "test", "resources", "test", "com", "sun", "glass", "ui", "win",
                        "win-prefs-query-golden-" + commit + ".txt");
        if (Files.exists(target)) {
            fail(target.toAbsolutePath() + " exists; regenerating a golden is a behaviour change: remove it by hand"
                    + " first, with the reason");
        }
        StringBuilder text = new StringBuilder();
        for (String line : header(commit)) {
            text.append("# ").append(line).append('\n');
        }
        Map<String, String> lines = new LinkedHashMap<>();
        captured.forEach((key, value) -> {
            if (!key.startsWith("query.") && !key.startsWith("decoded.")) {
                lines.put(key, value);
            }
        });
        lines.put("capture.commit", commit);
        lines.put("capture.date", LocalDate.now().toString());
        lines.putAll(captured);
        lines.forEach((key, value) -> text.append(key).append('=').append(value).append('\n'));
        Files.createDirectories(target.toAbsolutePath().getParent());
        Files.writeString(target, text.toString(), StandardCharsets.US_ASCII);
        return target;
    }

    private static List<String> header(String commit) {
        return List.of(
                "glass/win preferences query golden: the GwinUiSettings (60 bytes) and GwinNetworkInfo (8 bytes)",
                "that gwin_prefs_query_ui_settings and gwin_prefs_query_network wrote on the toolkit thread of a live",
                "toolkit, and their statuses, as raw bytes in memory order. Captured by",
                "test.com.sun.glass.ui.win.WinPreferenceQueryGoldenTest from the C++ of commit " + commit,
                "(PlatformSupport::collectUISettings and collectNetworkInfo) on one machine. The values are that",
                "machine's theme, accent colour, transparency effects, scroll-bar setting and network: compare only",
                "there, with those unchanged.",
                "Lines beginning with # are ignored. machine.* must match before anything is compared; info.* and",
                "capture.* are recorded, never compared; query.* are compared exactly; decoded.* restate query.* for",
                "a reader. Regenerating this file is a behaviour change, not a test fix.");
    }

    private static Map<String, String> golden() throws IOException {
        String file = System.getProperty(FILE);
        if (file != null) {
            return parse(Files.readString(Path.of(file), StandardCharsets.US_ASCII));
        }
        try (InputStream in = WinPreferenceQueryGoldenTest.class.getResourceAsStream(GOLDEN)) {
            if (in == null) {
                fail("no resource " + GOLDEN + " next to " + WinPreferenceQueryGoldenTest.class.getName());
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.US_ASCII));
        }
    }

    private static Map<String, String> parse(String text) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : text.split("\n", -1)) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0) {
                fail("not a key=value line in the golden: '" + line + "'");
            }
            values.put(line.substring(0, eq), line.substring(eq + 1));
        }
        return values;
    }

    private static void verify(Map<String, String> captured, Map<String, String> golden) {
        List<String> machine = new ArrayList<>();
        for (String key : MACHINE_KEYS) {
            if (!captured.get(key).equals(golden.get(key))) {
                machine.add(key + ": golden " + golden.get(key) + ", here " + captured.get(key));
            }
        }
        if (!machine.isEmpty()) {
            fail("the golden verifies only on the machine it was captured on: " + machine);
        }
        List<String> differences = new ArrayList<>();
        for (String key : QUERY_KEYS) {
            if (!captured.get(key).equals(golden.get(key))) {
                differences.add(key + ": golden " + golden.get(key) + ", now " + captured.get(key));
            }
        }
        if (differences.isEmpty()) {
            return;
        }
        Map<String, String> was = decoded(golden);
        Map<String, String> now = decoded(captured);
        for (Map.Entry<String, String> field : was.entrySet()) {
            String current = now.get(field.getKey());
            if (!field.getValue().equals(current)) {
                differences.add(field.getKey() + ": golden " + field.getValue() + ", now " + current);
            }
        }
        fail(differences.size() + " differences from the golden captured from the C of commit "
                + golden.get("capture.commit") + " on " + golden.get("capture.date") + ":\n"
                + String.join("\n", differences));
    }

    /** The fields of both structs, named, from the {@code query.*} keys of {@code values}. */
    static Map<String, String> decoded(Map<String, String> values) {
        Map<String, String> out = new LinkedHashMap<>();
        ByteBuffer ui = littleEndian(values.get("query.ui.bytes"));
        if (ui.capacity() == 60) {
            out.put("decoded.ui.available", Integer.toString(ui.getInt(0)));
            out.put("decoded.ui.colors_valid", Integer.toString(ui.getInt(4)));
            for (int i = 0; i < COLOR_NAMES.size(); i++) {
                out.put("decoded.ui.colors." + i + "." + COLOR_NAMES.get(i), argb(ui.getInt(8 + 4 * i)));
            }
            out.put("decoded.ui.advanced_effects_valid", Integer.toString(ui.getInt(44)));
            out.put("decoded.ui.advanced_effects_enabled", Integer.toString(ui.getInt(48)));
            out.put("decoded.ui.auto_hide_valid", Integer.toString(ui.getInt(52)));
            out.put("decoded.ui.auto_hide_scroll_bars", Integer.toString(ui.getInt(56)));
        } else {
            out.put("decoded.ui", "not 60 bytes but " + ui.capacity());
        }
        ByteBuffer network = littleEndian(values.get("query.network.bytes"));
        if (network.capacity() == 8) {
            out.put("decoded.network.available", Integer.toString(network.getInt(0)));
            int cost = network.getInt(4);
            out.put("decoded.network.cost_type", cost + (cost >= 0 && cost < COST_NAMES.size()
                    ? " " + COST_NAMES.get(cost) : ""));
        } else {
            out.put("decoded.network", "not 8 bytes but " + network.capacity());
        }
        return out;
    }

    private static ByteBuffer littleEndian(String hex) {
        return ByteBuffer.wrap(HexFormat.of().parseHex(hex == null ? "" : hex)).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static String argb(int value) {
        return String.format("0x%08x", value);
    }

    /** {@code 10.0.19045} of {@code 10.0.19045.6466}: the build, without the monthly update revision. */
    private static String build(String version) {
        String[] parts = version.split("\\.");
        return parts.length >= 3 ? parts[0] + "." + parts[1] + "." + parts[2] : version;
    }

    /** The full Windows version {@code cmd /c ver} prints, {@code 10.0.19045.6466}. */
    private static String windowsVersion() {
        try {
            Process ver = new ProcessBuilder("cmd.exe", "/c", "ver").redirectErrorStream(true).start();
            String output = new String(ver.getInputStream().readAllBytes(), StandardCharsets.US_ASCII).trim();
            ver.waitFor();
            int open = output.indexOf("Version ");
            int close = output.indexOf(']');
            return open >= 0 && close > open ? output.substring(open + "Version ".length(), close) : output;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while cmd /c ver ran", e);
        }
    }
}
