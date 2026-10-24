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

package test.com.sun.javafx.font;

import com.sun.javafx.font.MacMachineShim;
import com.sun.javafx.font.PrismFontFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import test.com.sun.javafx.font.FontGoldenHarness.Corpus;
import test.com.sun.javafx.font.FontGoldenHarness.Coverage;
import test.com.sun.javafx.font.FontGoldenHarness.GoldenMap;
import test.com.sun.javafx.font.FontGoldenRuntime.ChildJvm;

import static org.junit.jupiter.api.Assertions.fail;
import static test.com.sun.javafx.font.FontGoldenRuntime.moduleDirectory;

/**
 * The machine gate, guards and child JVMs of the macOS font goldens: what the JNI build of commit
 * {@value #CAPTURE_COMMIT} returned, captured before any of it was rewritten. File format and capture switches are
 * those of {@link FontGoldens}, the flow is {@link FontGoldenHarness}.
 * <p>
 * The gate is computed without touching any native under test. A macOS update changes the framework UUIDs, so the
 * goldens then skip (or fail under {@code -Djfx.parity.require=true}) until re-captured in a tree with the C of
 * {@value #CAPTURE_COMMIT} and its mac.cmake font target, whose OS, MacFontFinder and DFontDecoder declare the natives
 * {@link MacFontBindings} expects.
 */
final class MacFontGoldens {

    static final String CAPTURE_COMMIT = "28b02ef511";

    static final String WORK_DIRECTORY = "target/mac-font-golden";

    static final String JNI_LIBRARY = "libjavafx_font.dylib";

    static final String SYSTEM_FONTS = "/System/Library/Fonts";

    static final String FONT_SERVICES = "/System/Library/PrivateFrameworks/FontServices.framework/Versions/A/Resources";

    /** None of these is enumerated; only process-scope registration makes them reach {@code DFontDecoder}. */
    static final List<Path> CY_DFONTS = List.of(
            Path.of(FONT_SERVICES, "Fonts/ApplicationSupport/HelveticaCY.dfont"),
            Path.of(FONT_SERVICES, "Fonts/ApplicationSupport/GenevaCY.dfont"),
            Path.of(FONT_SERVICES, "Fonts/ApplicationSupport/CharcoalCY.dfont"));

    static final Path BLAIR_DFONT = Path.of("/System/Library/PrivateFrameworks/Slideshows.framework/Versions/A/"
            + "Resources/Content/Fonts/BlairMdITC TT-Medium.dfont");

    static final String HIRAGINO_SANS = "\u30D2\u30E9\u30AE\u30CE\u89D2\u30B4\u30B7\u30C3\u30AF";

    static final String REPO_FONTS = "../javafx.web/src/main/native/Tools/DumpRenderTree/fonts";

    record Pinned(String key, Path path) {
    }

    static final List<Pinned> PINNED = List.of(
            new Pinned("sfns", Path.of(SYSTEM_FONTS, "SFNS.ttf")),
            new Pinned("helvetica", Path.of(SYSTEM_FONTS, "Helvetica.ttc")),
            new Pinned("helveticaneue", Path.of(SYSTEM_FONTS, "HelveticaNeue.ttc")),
            new Pinned("times", Path.of(SYSTEM_FONTS, "Times.ttc")),
            new Pinned("menlo", Path.of(SYSTEM_FONTS, "Menlo.ttc")),
            new Pinned("courier", Path.of(SYSTEM_FONTS, "Courier.ttc")),
            new Pinned("lucidagrande", Path.of(SYSTEM_FONTS, "LucidaGrande.ttc")),
            new Pinned("avenirnext", Path.of(SYSTEM_FONTS, "Avenir Next.ttc")),
            new Pinned("timesnewroman", Path.of(SYSTEM_FONTS, "Supplemental/Times New Roman.ttf")),
            new Pinned("couriernew", Path.of(SYSTEM_FONTS, "Supplemental/Courier New.ttf")),
            new Pinned("arialunicode", Path.of(SYSTEM_FONTS, "Supplemental/Arial Unicode.ttf")),
            new Pinned("hiraginow3", Path.of(SYSTEM_FONTS, HIRAGINO_SANS + " W3.ttc")),
            new Pinned("hiraginow6", Path.of(SYSTEM_FONTS, HIRAGINO_SANS + " W6.ttc")),
            new Pinned("hiraginosansgb", Path.of(SYSTEM_FONTS, "Hiragino Sans GB.ttc")),
            new Pinned("stheitilight", Path.of(SYSTEM_FONTS, "STHeiti Light.ttc")),
            new Pinned("stheitimedium", Path.of(SYSTEM_FONTS, "STHeiti Medium.ttc")),
            new Pinned("applecoloremoji", Path.of(SYSTEM_FONTS, "Apple Color Emoji.ttc")),
            new Pinned("applesymbols", Path.of(SYSTEM_FONTS, "Apple Symbols.ttf")),
            new Pinned("geezapro", Path.of(SYSTEM_FONTS, "GeezaPro.ttc")),
            new Pinned("applesdgothicneo", Path.of(SYSTEM_FONTS, "AppleSDGothicNeo.ttc")),
            new Pinned("thonburiui", Path.of(SYSTEM_FONTS, "ThonburiUI.ttc")),
            new Pinned("kohinoor", Path.of(SYSTEM_FONTS, "Kohinoor.ttc")),
            new Pinned("sfhebrew", Path.of(SYSTEM_FONTS, "SFHebrew.ttf")),
            new Pinned("sfarabic", Path.of(SYSTEM_FONTS, "SFArabic.ttf")),
            new Pinned("pingfangui", Path.of(FONT_SERVICES, "Reserved/PingFangUI.ttc")),
            new Pinned("dfont.helveticacy", CY_DFONTS.get(0)),
            new Pinned("dfont.genevacy", CY_DFONTS.get(1)),
            new Pinned("dfont.charcoalcy", CY_DFONTS.get(2)),
            new Pinned("dfont.blair", BLAIR_DFONT),
            new Pinned("ahem", Path.of(REPO_FONTS, "AHEM____.TTF")),
            new Pinned("fontwithfeaturesotf", Path.of(REPO_FONTS, "FontWithFeatures.otf")),
            new Pinned("fontwithfeaturesttf", Path.of(REPO_FONTS, "FontWithFeatures.ttf")),
            new Pinned("weightwatcher400", Path.of(REPO_FONTS, "WebKitWeightWatcher400.ttf")),
            new Pinned("weightwatcher700", Path.of(REPO_FONTS, "WebKitWeightWatcher700.ttf")));

    static final List<Pinned> FONT_DIRECTORIES = List.of(
            new Pinned("system", Path.of(SYSTEM_FONTS)),
            new Pinned("library", Path.of("/Library/Fonts")),
            new Pinned("user", Path.of(System.getProperty("user.home"), "Library", "Fonts")),
            new Pinned("fontservices.fonts", Path.of(FONT_SERVICES, "Fonts")),
            new Pinned("fontservices.reserved", Path.of(FONT_SERVICES, "Reserved")));

    static final Map<String, String> FRAMEWORKS = Map.of(
            "CoreText", "/CoreText.framework/Versions/A/CoreText",
            "CoreGraphics", "/CoreGraphics.framework/Versions/A/CoreGraphics",
            "CoreFoundation", "/CoreFoundation.framework/Versions/A/CoreFoundation");

    /** CoreText and CoreGraphics consult these for UI fonts, cascade lists and glyph smoothing. */
    static final List<String> PREFERENCES = List.of(
            "AppleLanguages", "AppleLocale", "AppleFontSmoothing", "CGFontRenderingFontSmoothingDisabled");

    /** Read once by static initializers, so passed to child JVMs explicitly. */
    static final List<String> FONT_PROPERTIES = List.of(
            "com.sun.javafx.fontSize", "prism.cacheLayoutSize", "prism.debugfonts", "prism.embeddedfonts",
            "prism.fontSizeLimit", "prism.lcdtext", "prism.subpixeltext");

    /** Recorded, not gated: neither the font layer nor CoreText reads them, and the JVM's locale is gated. */
    static final List<String> ENVIRONMENT = List.of("LANG", "LC_ALL", "LC_CTYPE", "LC_MESSAGES");

    static final long CHILD_TIMEOUT_SECONDS = 60;

    /** Lone surrogates, NUL and bidi mixtures are deliberate: UTF-16 crosses unchanged in both directions. */
    static final List<String> TEXTS = List.of(
            "",
            "Hello, World! fi ffl 0123 AV To",
            "e\u0301 a\u0308\u0323 A\u030A",
            "\u05E9\u05B8\u05C1\u05DC\u05D5\u05B9\u05DD",
            "\u0645\u0631\u062D\u0628\u0627 \u0644\u0627 \uFEFB",
            "abc \u05D0\u05D1\u05D2 123 \u0645\u0631\u062D def",
            "\u0915\u094D\u0937\u0924\u094D\u0930\u093F\u092F",
            "\u0E2A\u0E27\u0E31\u0E2A\u0E14\u0E35\u0E04\u0E23\u0E31\u0E1A",
            "\u65E5\u672C\u8A9E\u3002\u7B80\u4F53\u4E2D\u6587",
            "\uD55C\uAD6D\uC5B4",
            "\uD83D\uDE00\uD83D\uDC4D\uD83C\uDFFD \uD83C\uDDEF\uD83C\uDDF5"
                    + " \uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67",
            "\uD835\uDC00\uD835\uDD04",
            "ab\uD800",
            "a\uDC00b",
            "\uDC00\uD800",
            "\u05D0\u0000\u05D1",
            "a\tb\u200D\u200Cc\u00AD",
            "\uFEFF\uE000\uFFFE\uFFFF");

    private static Map<String, String> machine;

    private static final FontGoldenHarness HARNESS = new FontGoldenHarness(MacFontBindings::requireSameBinding,
            MacFontGoldens::machine, MacFontBindings::requireJniBuild, MacFontGoldens::requirePristineProcess,
            MacFontGoldens::putCaptureInfo);

    private MacFontGoldens() {
    }

    static void captureOrVerify(Class<?> test, String golden, List<String> header, boolean inProcess,
                                Corpus corpus, Coverage coverage) throws Exception {
        HARNESS.captureOrVerify(test, golden, header, inProcess, corpus, coverage);
    }

    static List<String> header(String title, List<String> legend) {
        List<String> lines = new ArrayList<>();
        lines.add(title);
        lines.add("Captured from the JNI build of commit " + CAPTURE_COMMIT + " (" + JNI_LIBRARY + ": coretext.c,");
        lines.add("MacFontFinder.c, dfontdecoder.c) before any of it was rewritten.");
        lines.add("Machine-specific: the values depend on the macOS build (CoreText, CoreGraphics, CoreFoundation),");
        lines.add("the installed fonts and the user's language and font smoothing preferences. The comparison runs");
        lines.add("only where every machine.* key matches; elsewhere it is skipped, or failed under");
        lines.add("-Djfx.parity.require=true.");
        lines.add("");
        lines.add("Capture, in a tree with the C of " + CAPTURE_COMMIT
                + " and the OS, MacFontFinder, DFontDecoder natives MacFontBindings expects:");
        lines.add("  mvn -B -ntp -pl modules/javafx.graphics -am test -Dtest='Mac*GoldenTest'"
                + " -Dsurefire.failIfNoSpecifiedTests=false -D" + FontGoldens.CAPTURE_PROPERTY + "=true");
        lines.add("Regenerate: add -D" + FontGoldens.REGENERATE_PROPERTY + "=true (reviewed as a behaviour change)");
        lines.add("");
        lines.add("Keys:");
        lines.add("  machine.*    gate: OS build, framework and shared cache UUIDs, font sha256 and directory");
        lines.add("               inventories, CoreText's available fonts, preferences, locale, properties");
        lines.add("  info.*       recorded, never compared (JVM, environment, corpus text)");
        lines.add("  capture.*    recorded, never compared (commit, sha256 of the C and of the JNI library)");
        lines.add("  #null        a null value; u:XXXX.XXXX is text as UTF-16 code units");
        lines.add("  d:           a double as Double.toString; f: a float as Float.toString");
        lines.addAll(legend);
        return lines;
    }

    /**
     * {@code CTFontManagerCopyRegisteredFontDescriptors} does not report process-scope registrations on this macOS,
     * but a registered font joins the available fonts, so the gated inventory is compared instead.
     */
    static void requirePristineProcess() {
        String available = availableFontsFingerprint();
        String gated = machine().get("machine.fonts.coretext");
        int embedded = PrismFontFactory.getFontFactory().test_getNumEmbeddedFonts();
        if (!available.equals(gated) || embedded != 0) {
            fail("the process is not pristine: CoreText now makes " + available + " fonts available where the machine"
                    + " gate saw " + gated + ", and the font factory reports " + embedded + " embedded font load(s)."
                    + " A font registered for the process joins the available fonts. No test in the"
                    + " javafx.graphics fork may register fonts on macOS except in a child JVM; the in-JVM macOS"
                    + " goldens depend on the unmodified set of available fonts.");
        }
    }

    static synchronized Map<String, String> machine() {
        if (machine == null) {
            TreeMap<String, String> keys = new TreeMap<>();
            keys.put("machine.os.name", System.getProperty("os.name"));
            keys.put("machine.os.arch", System.getProperty("os.arch"));
            keys.put("machine.os.version", System.getProperty("os.version"));
            Map<String, String> version = plist(Path.of("/System/Library/CoreServices/SystemVersion.plist"));
            keys.put("machine.os.productVersion", FontGoldens.nullable(version.get("ProductVersion")));
            keys.put("machine.os.build", FontGoldens.nullable(version.get("ProductBuildVersion")));
            Map<String, String> cryptex = plist(Path.of("/System/Volumes/Preboot/Cryptexes/OS/System/Library/"
                    + "CoreServices/SystemVersion.plist"));
            keys.put("machine.os.versionExtra", FontGoldens.nullable(cryptex.get("ProductVersionExtra")));
            keys.put("machine.dyld.sharedCache.uuid", FontGoldens.nullable(MacMachineShim.sharedCacheUuid()));
            for (Map.Entry<String, String> framework : new TreeMap<>(FRAMEWORKS).entrySet()) {
                keys.put("machine.lib." + framework.getKey() + ".uuid",
                         FontGoldens.nullable(MacMachineShim.imageUuid(framework.getValue())));
            }
            for (Pinned pinned : PINNED) {
                Path file = resolve(pinned.path());
                keys.put("machine.font." + pinned.key() + ".sha256",
                         Files.isRegularFile(file) ? FontGoldens.sha256Hex(file) : FontGoldens.NULL);
            }
            for (Pinned directory : FONT_DIRECTORIES) {
                keys.put("machine.fonts." + directory.key(), treeFingerprint(directory.path()));
            }
            keys.put("machine.fonts.coretext", availableFontsFingerprint());
            for (String preference : PREFERENCES) {
                keys.put("machine.prefs." + preference,
                         FontGoldens.nullable(MacMachineShim.preference(preference, false)));
                keys.put("machine.prefs." + preference + ".currentHost",
                         FontGoldens.nullable(MacMachineShim.preference(preference, true)));
            }
            keys.put("machine.locale", FontGoldens.joinList(List.of(Locale.getDefault().toLanguageTag(),
                    FontGoldens.nullable(System.getProperty("file.encoding")),
                    FontGoldens.nullable(System.getProperty("sun.jnu.encoding")))));
            List<String> properties = new ArrayList<>();
            for (String name : FONT_PROPERTIES) {
                properties.add(name + "=" + FontGoldens.nullable(System.getProperty(name)));
            }
            keys.put("machine.props", FontGoldens.joinList(properties));
            keys.put("machine.jdk.fontdir", FontGoldens.directoryFingerprint(
                    Path.of(System.getProperty("java.home"), "lib", "fonts")));
            machine = Collections.unmodifiableMap(keys);
        }
        return machine;
    }

    static String availableFontsFingerprint() {
        List<String> available = MacMachineShim.availableFonts();
        return available.size() + ":" + FontGoldens.sha256Hex(String.join("\n", available)
                .getBytes(StandardCharsets.UTF_8));
    }

    private static final Pattern PLIST_ENTRY =
            Pattern.compile("<key>([^<]+)</key>\\s*<string>([^<]*)</string>");

    static Map<String, String> plist(Path file) {
        Map<String, String> entries = new TreeMap<>();
        try {
            if (Files.isRegularFile(file)) {
                Matcher matcher = PLIST_ENTRY.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    entries.put(matcher.group(1), matcher.group(2));
                }
            }
        } catch (IOException | UncheckedIOException e) {
            entries.put("unreadable", e.toString());
        }
        return entries;
    }

    static String treeFingerprint(Path root) {
        if (!Files.isDirectory(root)) {
            return FontGoldens.NULL;
        }
        TreeSet<String> lines = new TreeSet<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).forEach(file -> {
                try {
                    lines.add(root.relativize(file) + "\t" + Files.size(file) + "\t"
                            + Files.getLastModifiedTime(file).toMillis());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException | RuntimeException e) {
            return "unreadable (" + e.getClass().getSimpleName() + ")";
        }
        return lines.size() + ":" + FontGoldens.sha256Hex(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
    }

    static void putCaptureInfo(GoldenMap out) {
        out.put("capture.provenance", System.getProperty("java.vm.name") + " " + System.getProperty("java.vm.version")
                + " on " + System.getProperty("os.name") + " " + System.getProperty("os.version") + " "
                + System.getProperty("os.arch"));
        out.put("capture.commit", FontGoldenRuntime.gitCommit());
        for (String source : MacFontBindings.NATIVE_FONT_SOURCES) {
            Path file = MacFontBindings.nativeFontSource(source);
            out.put("capture.nativeFont." + source + ".sha256",
                    Files.isRegularFile(file) ? FontGoldens.sha256Hex(file) : FontGoldens.NULL);
        }
        Path library = FontGoldenRuntime.findOnLibraryPath(JNI_LIBRARY);
        out.put("capture.jniLibrary", library == null ? FontGoldens.NULL
                : FontGoldenRuntime.sizeOf(library) + ":" + FontGoldens.sha256Hex(library));
        List<String> exports = library == null ? List.of() : List.copyOf(MacFontBindings.exportedJniSymbols(library));
        out.put("capture.jniLibrary.exports", exports.size() + ":"
                + FontGoldens.sha256Hex(String.join("\n", exports).getBytes(StandardCharsets.UTF_8)));
        out.put("info.java.version", System.getProperty("java.runtime.version"));
        List<String> environment = new ArrayList<>();
        for (String name : ENVIRONMENT) {
            environment.add(name + "=" + FontGoldens.nullable(System.getenv(name)));
        }
        out.put("info.env", FontGoldens.joinList(environment));
        for (int i = 0; i < TEXTS.size(); i++) {
            out.put("info.corpus.s" + FontGoldenFormat.pad(i, 2), FontGoldenFormat.unitsHex(TEXTS.get(i)));
        }
    }

    static Map<String, String> runChild(String scenario, Map<String, String> properties)
            throws IOException, InterruptedException {
        return runChild(MacFontProcessChild.class, scenario, properties);
    }

    /** A child JVM that must succeed within {@link #CHILD_TIMEOUT_SECONDS}; returns its golden entries. */
    static Map<String, String> runChild(Class<?> main, String scenario, Map<String, String> properties)
            throws IOException, InterruptedException {
        ChildJvm child = ChildJvm.prepare(workDirectory(), scenario);
        Path tmp = scenarioDirectory(scenario).resolve("tmp");
        Files.createDirectories(tmp);
        List<String> command = FontGoldenRuntime.javaCommand(argument -> argument.startsWith("-Djava.io.tmpdir")
                || FONT_PROPERTIES.stream().anyMatch(name -> argument.startsWith("-D" + name + "=")));
        for (String name : FONT_PROPERTIES) {
            String value = properties.containsKey(name) ? properties.get(name) : System.getProperty(name);
            if (value != null) {
                command.add("-D" + name + "=" + value);
            }
        }
        command.add("-Djava.io.tmpdir=" + tmp);
        command.add(child.errorFileOption());
        command.addAll(child.mainArguments(main));
        return child.run(command, environment -> { }, CHILD_TIMEOUT_SECONDS).requireSuccess();
    }

    static Path workDirectory() throws IOException {
        return FontGoldenRuntime.workDirectory(WORK_DIRECTORY);
    }

    static Path scenarioDirectory(String scenario) throws IOException {
        Path directory = workDirectory().resolve("work").resolve(scenario);
        Files.createDirectories(directory);
        return directory;
    }

    static Path resolve(Path path) {
        return path.isAbsolute() ? path : moduleDirectory().resolve(path).normalize();
    }

    static Path pinned(String key) {
        for (Pinned pinned : PINNED) {
            if (pinned.key().equals(key)) {
                return resolve(pinned.path());
            }
        }
        throw new IllegalArgumentException("no pinned font " + key);
    }
}
