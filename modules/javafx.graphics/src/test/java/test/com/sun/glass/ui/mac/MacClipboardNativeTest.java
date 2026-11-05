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

package test.com.sun.glass.ui.mac;

import com.sun.glass.ui.mac.MacGlassNativeShim;
import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import test.com.sun.javafx.test.ParityGate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.abort;

/**
 * {@code MacSystemClipboard}'s natives against the golden of their JNI bodies (audit g3 §9, PB2): the CoreServices
 * UTType and CoreFoundation calls that replaced {@code FormatEncoder._convertMIMEtoUTI} and
 * {@code _convertUTItoMIME}, and the strings around {@code gmac_clipboard_file_reference_path}, which replaced
 * {@code _convertFileReferencePath}. The two conversions are also compared, in this JVM, with
 * {@code gmac_test_clipboard_mime_to_uti} and {@code gmac_test_clipboard_uti_to_mime}, which run the JNI bodies'
 * Objective-C.
 * <p>
 * The corpus holds MIME types, the declared UTIs {@code MacPasteboard} names, empty and null strings, an embedded
 * NUL, lone and swapped surrogates, U+00E9, U+1F600, and malformed {@code dyn.} UTIs; each MIME type is also taken
 * through both conversions, to its {@code dyn.} UTI and back. The paths hold file URLs and plain paths whose UTF-8
 * {@code NewStringUTF} mangled (L22), which stop at an embedded NUL, or which UTF-8 cannot hold. None refers to a
 * file by its id, which would ask the file system.
 * <p>
 * The golden was captured, with {@code -D}{@value #CAPTURE_PROPERTY}{@code =true}, from the JNI natives, in this JVM,
 * before {@code MacSystemClipboard} was moved to Java. None of the natives asserted a thread or reached a
 * {@code GLASS_CHECK_EXCEPTION} with an exception pending, so the capture needs no child JVM and no
 * {@code MacApplication}. Nothing here creates an {@code NSApplication}: AppKit's {@code NSApp} is nil before the
 * first call into the library and after the last.
 */
@EnabledOnOs(OS.MAC)
public class MacClipboardNativeTest {

    static final String CAPTURE_COMMIT = "1a6788cc4d";
    static final String GOLDEN = "mac-clipboard-golden-" + CAPTURE_COMMIT + ".txt";
    static final String CAPTURE_PROPERTY = "jfx.mac.clipboard.golden.capture";

    private static final String RESOURCE_DIRECTORY = "src/test/resources/test/com/sun/glass/ui/mac";
    private static final String CLASSES_DIRECTORY = "target/test-classes/test/com/sun/glass/ui/mac";
    private static final String NATIVE_SOURCE = "src/main/native-glass/mac/GlassSystemClipboard.m";

    private static final HexFormat HEX = HexFormat.of();

    /** A named input of the corpus. */
    record Case(String name, String value) {
    }

    /** The three natives, as the JNI or the Java calls them. */
    private record Natives(UnaryOperator<String> mimeToUti, UnaryOperator<String> utiToMime,
                           UnaryOperator<String> fileReferencePath) {
    }

    static List<Case> conversionCorpus() {
        return List.of(
                new Case("null", null),
                new Case("empty", ""),
                new Case("textPlain", "text/plain"),
                new Case("textHtml", "text/html"),
                new Case("imagePng", "image/png"),
                new Case("uriList", "text/uri-list"),
                new Case("serializedObject", "application/x-java-serialized-object"),
                new Case("customMime", "application/x-my-type;class=java.lang.String"),
                new Case("embeddedNul", "text/pl\u0000ain"),
                new Case("loneHigh", "text/\uD800"),
                new Case("loneLow", "text/\uDC00"),
                new Case("highThenAscii", "text/\uD800x"),
                new Case("swappedPair", "text/\uDE00\uD83D"),
                new Case("eAcute", "text/é"),
                new Case("grinningFace", "text/😀"),
                new Case("long", "x/" + "a".repeat(300)),
                new Case("publicPng", "public.png"),
                new Case("adobePdf", "com.adobe.pdf"),
                new Case("publicHtml", "public.html"),
                new Case("publicUtf8PlainText", "public.utf8-plain-text"),
                new Case("publicFileUrl", "public.file-url"),
                new Case("javaRawImage", "application.x-java-rawimage"),
                new Case("dynEmpty", "dyn."),
                new Case("dynShort", "dyn.a"),
                new Case("dynPunctuation", "dyn.!!!!"),
                new Case("dynTruncated", "dyn.ah62d4rv4ge8"),
                new Case("dynUpperCase", "DYN.AH62D4RV4GE81A6XTSBW1E7DMQZ3U"),
                new Case("dynEAcute", "dyn.éé"),
                new Case("dynLoneHigh", "dyn.\uD800abc"),
                new Case("dynEmbeddedNul", "dyn.a\u0000b"));
    }

    static List<Case> pathCorpus() {
        return List.of(
                new Case("null", null),
                new Case("empty", ""),
                new Case("absolutePath", "/tmp/a"),
                new Case("fileUrl", "file:///tmp/a"),
                new Case("fileUrlEscaped", "file:///tmp/a%20b"),
                new Case("fileUrlLocalhost", "file://localhost/tmp/x"),
                new Case("fileUrlSupplementary", "file:///tmp/%F0%9F%98%80"),
                new Case("httpUrl", "http://example.com/a"),
                new Case("supplementaryPath", "/tmp/é😀"),
                new Case("embeddedNul", "/tmp/a\u0000b"),
                new Case("loneSurrogate", "/tmp/\uD800"),
                new Case("notAUrl", "a b c"));
    }

    @BeforeAll
    static void loadGlass() {
        MacGlassNatives.require();
        assertTrue(MacGlassNativeShim.nsAppIsNil(), "an NSApplication exists before the first call into libglass");
        MacGlassNativeShim.loadLibrary();
    }

    @AfterAll
    static void noApplicationExistsAfterTheLastCallAndTheOracleRan() {
        assertTrue(MacGlassNativeShim.nsAppIsNil(), "an NSApplication exists after the last call into libglass");
        ParityGate.ledger(MacClipboardNativeTest.class).assertOracleRan();
    }

    @Test
    public void theConversionsAndTheFileReferencePathAreThoseOfTheJniBodies() throws Exception {
        if (Boolean.getBoolean(CAPTURE_PROPERTY)) {
            capture();
            abort("the clipboard golden was captured to " + GOLDEN + "; a capture verifies nothing");
        }
        Map<String, String> golden = readGolden();
        ParityGate.Ledger ledger = ParityGate.ledger(MacClipboardNativeTest.class);
        List<String> differences = new ArrayList<>();
        machine().forEach((key, value) -> {
            if (!value.equals(golden.get(key))) {
                differences.add(key + "=" + value + " (golden: " + golden.get(key) + ")");
            }
        });
        ledger.requireOracle(differences.isEmpty(), () -> "the clipboard golden describes another machine: "
                + differences + ". The UTType database is the OS release's. A cross-machine oracle is a re-capture"
                + " from the JNI natives of " + CAPTURE_COMMIT + " on that machine.");
        assertFalse(MacGlassNativeShim.clipboardIsNative(), "the corpus would run the JNI natives, not the Java");
        Map<String, String> expected = compared(golden);
        Map<String, String> actual = run(javaNatives());
        assertEquals(3 * conversionCorpus().size() + pathCorpus().size(), expected.size(),
                "the golden holds three lines per conversion case and one per path");
        assertEquals(expected.keySet(), actual.keySet(), "the cases of the corpus and of the golden");
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            assertEquals(entry.getValue(), actual.get(entry.getKey()), entry.getKey());
            ledger.compared();
        }
    }

    @Test
    public void theConversionsAreThoseOfTheirObjectiveCBodies() {
        ParityGate.Ledger ledger = ParityGate.ledger(MacClipboardNativeTest.class);
        ledger.oracleAvailable();
        for (Case conversionCase : conversionCorpus()) {
            String value = conversionCase.value();
            String uti = MacGlassNativeShim.oracleMimeToUti(value);
            assertEquals(quote(uti), quote(MacGlassNativeShim.mimeToUti(value)),
                    "mimeToUti of " + conversionCase.name());
            assertEquals(quote(MacGlassNativeShim.oracleUtiToMime(value)), quote(MacGlassNativeShim.utiToMime(value)),
                    "utiToMime of " + conversionCase.name());
            assertEquals(quote(MacGlassNativeShim.oracleUtiToMime(uti)), quote(MacGlassNativeShim.utiToMime(uti)),
                    "utiToMime of the UTI of " + conversionCase.name());
            ledger.compared(3);
        }
    }

    @Test
    public void noClipboardNativeIsLeft() {
        assertFalse(MacGlassNativeShim.clipboardIsNative());
    }

    /**
     * A MIME type goes to the identifier the Objective-C body made of it, with the same conformance (L19), and that
     * identifier back to the MIME type the body read from it, in whatever case this machine's UTType database keeps.
     */
    @Test
    public void aMimeTypeGoesToTheIdentifierOfTheObjectiveCBodyAndBackToItsMimeType() {
        ParityGate.Ledger ledger = ParityGate.ledger(MacClipboardNativeTest.class);
        ledger.oracleAvailable();
        for (String mime : List.of("text/plain", "Text/Plain", "image/png",
                "application/x-my-type;class=java.lang.String")) {
            String uti = MacGlassNativeShim.mimeToUti(mime);
            assertEquals(quote(MacGlassNativeShim.oracleMimeToUti(mime)), quote(uti), "the identifier of " + mime);
            assertEquals(quote(MacGlassNativeShim.oracleUtiToMime(uti)), quote(MacGlassNativeShim.utiToMime(uti)),
                    "the MIME type of " + uti);
            ledger.compared(2);
        }
    }

    @Test
    public void aDeclaredUtiHasTheMimeTypeOfTheObjectiveCBody() {
        ParityGate.Ledger ledger = ParityGate.ledger(MacClipboardNativeTest.class);
        ledger.oracleAvailable();
        for (String uti : List.of("public.png", "com.adobe.pdf", "public.html")) {
            assertEquals(quote(MacGlassNativeShim.oracleUtiToMime(uti)), quote(MacGlassNativeShim.utiToMime(uti)),
                    "the MIME type of " + uti);
            ledger.compared();
        }
    }

    /**
     * A pool left raised would keep the calling thread's later Glass pools from draining, and {@code FormatEncoder}'s
     * callers run on any thread.
     */
    @Test
    public void aConversionPopsItsPoolHoweverItEnds() {
        MacGlassNativeShim.mimeToUti("text/plain");
        int depth = MacGlassNativeShim.poolDepth();
        List<Long> strings = new ArrayList<>();
        assertNull(MacGlassNativeShim.clipboardConvert(null, string -> {
            strings.add(string.address());
            return MemorySegment.NULL;
        }));
        assertEquals(depth, MacGlassNativeShim.poolDepth(), "after a NULL result");
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> MacGlassNativeShim.clipboardConvert("text/plain", string -> {
                    strings.add(string.address());
                    throw new IllegalStateException("the conversion threw");
                }));
        assertEquals("the conversion threw", thrown.getMessage());
        assertEquals(depth, MacGlassNativeShim.poolDepth(), "after a throw");
        assertEquals(2, strings.size());
        assertFalse(strings.contains(0L), "each conversion was given a string, \"\" for null: " + strings);
    }

    private static Natives javaNatives() {
        return new Natives(MacGlassNativeShim::mimeToUti, MacGlassNativeShim::utiToMime,
                MacGlassNativeShim::fileReferencePath);
    }

    private static Natives jniNatives() {
        return new Natives(mime -> MacGlassNativeShim.clipboardJniNative("_convertMIMEtoUTI", mime),
                uti -> MacGlassNativeShim.clipboardJniNative("_convertUTItoMIME", uti),
                path -> MacGlassNativeShim.clipboardJniNative("_convertFileReferencePath", path));
    }

    /** Every case of the corpus through {@code natives}, by golden key. */
    private static Map<String, String> run(Natives natives) {
        Map<String, String> lines = new TreeMap<>();
        for (Case conversionCase : conversionCorpus()) {
            String uti = natives.mimeToUti().apply(conversionCase.value());
            lines.put("case.mimeToUti." + conversionCase.name(), quote(uti));
            lines.put("case.utiToMime." + conversionCase.name(),
                    quote(natives.utiToMime().apply(conversionCase.value())));
            lines.put("case.roundTrip." + conversionCase.name(), quote(natives.utiToMime().apply(uti)));
        }
        for (Case pathCase : pathCorpus()) {
            lines.put("case.fileReferencePath." + pathCase.name(),
                    quote(natives.fileReferencePath().apply(pathCase.value())));
        }
        return lines;
    }

    /** {@code null}, or the string quoted, every unit outside printable ASCII, a quote or a backslash escaped. */
    static String quote(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder quoted = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c >= 0x20 && c < 0x7F && c != '"' && c != '\\') {
                quoted.append(c);
            } else {
                quoted.append("\\u%04X".formatted((int) c));
            }
        }
        return quoted.append('"').toString();
    }

    private static Map<String, String> compared(Map<String, String> lines) {
        Map<String, String> compared = new TreeMap<>();
        lines.forEach((key, value) -> {
            if (key.startsWith("case.")) {
                compared.put(key, value);
            }
        });
        return compared;
    }

    /** The machine the golden describes: the UTType database and NSURL's parsing are the OS release's. */
    private static Map<String, String> machine() {
        Map<String, String> keys = new TreeMap<>();
        keys.put("machine.os.name", System.getProperty("os.name"));
        keys.put("machine.os.version", System.getProperty("os.version"));
        keys.put("machine.os.arch", System.getProperty("os.arch"));
        return keys;
    }

    private static Map<String, String> readGolden() throws IOException {
        Map<String, String> golden = new LinkedHashMap<>();
        try (InputStream in = MacClipboardNativeTest.class.getResourceAsStream(GOLDEN)) {
            if (in == null) {
                fail("no " + GOLDEN + " on the test class path: the golden records what the JNI natives of "
                        + CAPTURE_COMMIT + " did, and with no capture there is nothing to compare");
            }
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int equals = line.indexOf('=');
                golden.put(line.substring(0, equals), line.substring(equals + 1));
            }
        }
        return golden;
    }

    /** Refuses unless the JNI natives still exist, so that the Java can never regenerate the golden. */
    private static void capture() throws Exception {
        if (!MacGlassNativeShim.clipboardIsNative()) {
            fail("refusing to capture: MacSystemClipboard no longer declares the JNI natives "
                    + MacGlassNativeShim.CLIPBOARD_NATIVES + ", so a capture would record the Java it is meant to"
                    + " verify");
        }
        Path module = Path.of("").toAbsolutePath();
        Path source = module.resolve(RESOURCE_DIRECTORY).resolve(GOLDEN);
        if (Files.exists(source)) {
            fail("a golden already exists at " + source + "; it records what the JNI natives did");
        }
        Map<String, String> cases = run(jniNatives());
        List<String> lines = new ArrayList<>(List.of(
                "# MacSystemClipboard golden: what the JNI natives FormatEncoder._convertMIMEtoUTI,",
                "# FormatEncoder._convertUTItoMIME and _convertFileReferencePath (GlassSystemClipboard.m: the bodies",
                "# of " + CAPTURE_COMMIT + ", their Objective-C factored out unchanged) answered for each case of",
                "# MacClipboardNativeTest.conversionCorpus() and pathCorpus(), in the surefire JVM. Compared exactly.",
                "# Keys: machine.* gate the comparison; capture.* and info.* are recorded, never compared; case.* are",
                "# compared. roundTrip is _convertUTItoMIME of the _convertMIMEtoUTI answer. A value is null, or the",
                "# string quoted, every unit outside printable ASCII, a quote or a backslash as \\uXXXX."));
        machine().forEach((key, value) -> lines.add(key + "=" + value));
        lines.add("capture.commit=" + git(module, "rev-parse", "HEAD"));
        lines.add("capture.jniLibrary.sha256=" + sha256(module.resolve("target/native/bin/libglass.dylib")));
        lines.add("capture.nativeSource.GlassSystemClipboard.m.sha256=" + sha256(module.resolve(NATIVE_SOURCE)));
        lines.add("capture.clipboardNative=" + MacGlassNativeShim.clipboardIsNative());
        lines.add("info.java.vm=" + System.getProperty("java.vm.name") + " " + System.getProperty("java.vm.version"));
        cases.forEach((key, value) -> lines.add(key + "=" + value));
        byte[] content = (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(source.getParent());
        Files.write(source, content);
        Path classes = module.resolve(CLASSES_DIRECTORY);
        Files.createDirectories(classes);
        Files.write(classes.resolve(GOLDEN), content);
    }

    private static String git(Path directory, String... arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git", "-C", directory.toString()));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        return process.waitFor() == 0 ? output : "(git failed: " + output + ")";
    }

    private static String sha256(Path file) throws IOException, NoSuchAlgorithmException {
        return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}
