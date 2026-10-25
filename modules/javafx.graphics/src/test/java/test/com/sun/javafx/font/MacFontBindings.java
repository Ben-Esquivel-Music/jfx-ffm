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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassModel;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;
import static test.com.sun.javafx.font.FontClassFiles.fontClasses;
import static test.com.sun.javafx.font.FontClassFiles.invokeTargets;
import static test.com.sun.javafx.font.FontClassFiles.isSameOrNested;
import static test.com.sun.javafx.font.FontClassFiles.member;
import static test.com.sun.javafx.font.FontClassFiles.name;
import static test.com.sun.javafx.font.FontClassFiles.nativeCallSites;
import static test.com.sun.javafx.font.FontClassFiles.owner;

/**
 * The capture guard and binding witness of the macOS goldens, read from class files: a capture runs only on the JNI
 * build of the C of {@link MacFontGoldens#CAPTURE_COMMIT}, and in both modes the shims must reach the font layer
 * through the entry points production reaches it through, so that the rows they produce pin production.
 */
final class MacFontBindings {

    private static final String COMMIT = MacFontGoldens.CAPTURE_COMMIT;

    static final String OS = "com/sun/javafx/font/coretext/OS";
    static final String MAC_FONT_FINDER = "com/sun/javafx/font/MacFontFinder";
    static final String DFONT_DECODER = "com/sun/javafx/font/DFontDecoder";

    /** Their bodies, and those of their nested classes, implement the entry points the shims call. */
    static final List<String> DELEGATE_OWNERS = List.of(OS, MAC_FONT_FINDER, DFONT_DECODER);

    static final String OS_SHIM = "com/sun/javafx/font/coretext/OSShim";
    static final String MAC_FONT_FINDER_SHIM = "com/sun/javafx/font/MacFontFinderShim";
    static final String DFONT_DECODER_SHIM = "com/sun/javafx/font/DFontDecoderShim";
    static final List<String> OBSERVERS =
            List.of("com/sun/javafx/font/MacFontOracleShim", "com/sun/javafx/font/MacMachineShim");

    // 55 names for 56 natives: CFStringCreateWithCharacters is overloaded.
    static final List<String> OS_NATIVES = List.of(
            "CFArrayGetCount", "CFArrayGetValueAtIndex", "CFAttributedStringCreate", "CFDictionaryAddValue",
            "CFDictionaryCreateMutable", "CFDictionaryGetValue", "CFRelease", "CFRetain",
            "CFStringCreateWithCharacters",
            "CFURLCreateWithFileSystemPath", "CGBitmapContextCreate", "CGBitmapContextGetData",
            "CGColorSpaceCreateDeviceGray", "CGColorSpaceCreateDeviceRGB", "CGContextFillRect", "CGContextRelease",
            "CGContextSetAllowsAntialiasing", "CGContextSetAllowsFontSmoothing",
            "CGContextSetAllowsFontSubpixelPositioning", "CGContextSetAllowsFontSubpixelQuantization",
            "CGContextSetRGBFillColor", "CGContextTranslateCTM", "CGDataProviderCreateWithURL",
            "CGFontCreateWithDataProvider", "CGImageContextGetData", "CGPathApply", "CGPathGetPathBoundingBox",
            "CGPathRelease", "CGRectApplyAffineTransform", "CTFontCopyAttributeDisplayName", "CTFontCopyURLAttribute",
            "CTFontCreateCopyWithAttributes", "CTFontCreatePathForGlyph", "CTFontCreateUIFontForLanguage",
            "CTFontCreateWithGraphicsFont", "CTFontCreateWithName", "CTFontDrawGlyphs", "CTFontGetAdvancesForGlyphs",
            "CTFontGetBoundingRectForGlyphUsingTables", "CTFontGetBoundingRectForGlyphs",
            "CTFontManagerRegisterFontsForURL", "CTLineCreateWithAttributedString", "CTLineGetGlyphCount",
            "CTLineGetGlyphRuns", "CTLineGetTypographicBounds", "CTParagraphStyleCreate", "CTRunGetAttributes",
            "CTRunGetGlyphs", "CTRunGetPositions", "CTRunGetStringIndices",
            "kCFAllocatorDefault", "kCFTypeDictionaryKeyCallBacks", "kCFTypeDictionaryValueCallBacks",
            "kCTFontAttributeName", "kCTParagraphStyleAttributeName");

    static final List<String> MAC_FONT_FINDER_NATIVES = List.of(
            "getCascadeList", "getFont", "getFontData", "getSystemFontSize");

    static final List<String> DFONT_DECODER_NATIVES = List.of(
            "createCTFont", "getCTFontFormat", "getCTFontTable", "getCTFontTags", "releaseCTFont");

    private static final Map<String, List<String>> NATIVES = Map.of(
            OS, OS_NATIVES, MAC_FONT_FINDER, MAC_FONT_FINDER_NATIVES, DFONT_DECODER, DFONT_DECODER_NATIVES);

    // Measured on the JNI build. The peers' entries are checked on every run, so a call site moved to another
    // binding fails at once; those of the delegate owners describe the JNI bodies and are checked at capture.
    static final Map<String, String> NATIVE_CALL_SITES = Map.of(
            "com/sun/javafx/font/coretext/CTFactory", "OS.CTFontCopyURLAttribute",
            "com/sun/javafx/font/coretext/CTFontFile", "MacFontFinder.getCascadeList OS.CFRelease*6"
                    + " OS.CFURLCreateWithFileSystemPath*2 OS.CGDataProviderCreateWithURL"
                    + " OS.CGFontCreateWithDataProvider OS.CGPathApply OS.CGPathGetPathBoundingBox*2 OS.CGPathRelease*3"
                    + " OS.CTFontCreateCopyWithAttributes OS.CTFontCreatePathForGlyph*3"
                    + " OS.CTFontCreateUIFontForLanguage"
                    + " OS.CTFontCreateWithGraphicsFont OS.CTFontCreateWithName OS.CTFontGetAdvancesForGlyphs"
                    + " OS.CTFontGetBoundingRectForGlyphUsingTables OS.CTFontGetBoundingRectForGlyphs"
                    + " OS.CTFontManagerRegisterFontsForURL OS.kCFAllocatorDefault*2",
            "com/sun/javafx/font/coretext/CTFontFile$SelfDisposerRecord", "OS.CFRelease",
            "com/sun/javafx/font/coretext/CTGlyph", "OS.CGBitmapContextCreate OS.CGBitmapContextGetData*2"
                    + " OS.CGColorSpaceCreateDeviceGray OS.CGColorSpaceCreateDeviceRGB OS.CGContextFillRect"
                    + " OS.CGContextRelease*2 OS.CGContextSetAllowsAntialiasing OS.CGContextSetAllowsFontSmoothing"
                    + " OS.CGContextSetAllowsFontSubpixelPositioning OS.CGContextSetAllowsFontSubpixelQuantization"
                    + " OS.CGContextSetRGBFillColor*2 OS.CGContextTranslateCTM*4 OS.CGImageContextGetData"
                    + " OS.CGRectApplyAffineTransform OS.CTFontDrawGlyphs*2 OS.CTFontGetAdvancesForGlyphs",
            "com/sun/javafx/font/coretext/CTGlyphLayout", "OS.CFArrayGetCount OS.CFArrayGetValueAtIndex"
                    + " OS.CFAttributedStringCreate OS.CFDictionaryAddValue*2 OS.CFDictionaryCreateMutable"
                    + " OS.CFDictionaryGetValue OS.CFRelease*5 OS.CFRetain OS.CFStringCreateWithCharacters"
                    + " OS.CTFontCopyAttributeDisplayName OS.CTFontCreateCopyWithAttributes"
                    + " OS.CTLineCreateWithAttributedString OS.CTLineGetGlyphCount OS.CTLineGetGlyphRuns"
                    + " OS.CTLineGetTypographicBounds OS.CTParagraphStyleCreate OS.CTRunGetAttributes"
                    + " OS.CTRunGetGlyphs*2"
                    + " OS.CTRunGetPositions OS.CTRunGetStringIndices OS.kCFAllocatorDefault"
                    + " OS.kCFTypeDictionaryKeyCallBacks OS.kCFTypeDictionaryValueCallBacks OS.kCTFontAttributeName*2"
                    + " OS.kCTParagraphStyleAttributeName",
            "com/sun/javafx/font/coretext/CTStrikeDisposer", "OS.CFRelease",
            "com/sun/javafx/font/coretext/OS", "OS.CFStringCreateWithCharacters OS.kCFAllocatorDefault",
            "com/sun/javafx/font/MacFontFinder", "MacFontFinder.getFont*2 MacFontFinder.getFontData",
            "com/sun/javafx/font/DFontDecoder", "DFontDecoder.createCTFont DFontDecoder.getCTFontFormat"
                    + " DFontDecoder.getCTFontTable DFontDecoder.getCTFontTags DFontDecoder.releaseCTFont*3",
            "com/sun/javafx/font/PrismFontFactory", "MacFontFinder.getSystemFontSize");

    static final String NATIVE_FONT_DIRECTORY = "src/main/native-font";

    static final List<String> NATIVE_FONT_SOURCES = List.of("coretext.c", "MacFontFinder.c", "dfontdecoder.c");

    // JNI symbols the C of the capture commit exports for natives the capture tree no longer declares: the dead
    // natives.
    static final Set<String> UNDECLARED_EXPORTS = Set.of(
            "Java_com_sun_javafx_font_coretext_OS_CGColorSpaceRelease",
            "Java_com_sun_javafx_font_coretext_OS_CTRunGetGlyphCount",
            "Java_com_sun_javafx_font_MacFontFinder_getCascadeListRefs");

    static final List<String> SHIMS = List.of(OS_SHIM, MAC_FONT_FINDER_SHIM, DFONT_DECODER_SHIM);

    // OS itself is a caller: its CFStringCreate helper is the only caller of the three-argument
    // CFStringCreateWithCharacters.
    static final List<String> PRODUCTION_CALLERS = List.of(
            "com/sun/javafx/font/coretext/CTFactory", "com/sun/javafx/font/coretext/CTFontFile",
            "com/sun/javafx/font/coretext/CTFontFile$SelfDisposerRecord", "com/sun/javafx/font/coretext/CTFontStrike",
            "com/sun/javafx/font/coretext/CTGlyph", "com/sun/javafx/font/coretext/CTGlyphLayout",
            "com/sun/javafx/font/coretext/CTStrikeDisposer", OS, MAC_FONT_FINDER, DFONT_DECODER,
            "com/sun/javafx/font/PrismFontFactory");

    // getMonospacedFont, whose only call site is commented out. It must stay uncalled; if production starts calling
    // it, the exemption is stale and the check says so.
    static final Set<String> UNCALLED_ENTRY_POINTS = Set.of(MAC_FONT_FINDER + " getMonospacedFont()Ljava/lang/String;");

    private static boolean nativeCountsReported;

    private MacFontBindings() {
    }

    static void requireJniBuild() {
        Path library = FontGoldenRuntime.findOnLibraryPath(MacFontGoldens.JNI_LIBRARY);
        if (library == null) {
            fail("refusing to capture: " + MacFontGoldens.JNI_LIBRARY + " is not on java.library.path ("
                    + System.getProperty("java.library.path") + "). The macOS goldens record the JNI build of commit "
                    + COMMIT + " and are never captured from anything else.");
        }
        requireNative("com.sun.javafx.font.coretext.OS", OS_NATIVES, 56);
        requireNative("com.sun.javafx.font.MacFontFinder", MAC_FONT_FINDER_NATIVES, 4);
        requireNative("com.sun.javafx.font.DFontDecoder", DFONT_DECODER_NATIVES, 5);
        requireBuiltFromCaptureCommit(library);
        for (String owner : DELEGATE_OWNERS) {
            String actual = nativeCallSites(classModel(owner), NATIVES);
            if (!actual.equals(NATIVE_CALL_SITES.get(owner))) {
                fail("refusing to capture: " + owner + " calls the natives [" + actual + "], but in the JNI build of"
                        + " commit " + COMMIT + " it calls [" + NATIVE_CALL_SITES.get(owner) + "]");
            }
        }
        List<String> shimSites = new ArrayList<>();
        for (String name : OS_NATIVES) {
            shimSites.add(name.equals("CFStringCreateWithCharacters") ? "OS." + name + "*2" : "OS." + name);
        }
        String expectedShim = String.join(" ", shimSites);
        String actualShim = nativeCallSites(classModel(OS_SHIM), NATIVES);
        if (!actualShim.equals(expectedShim)) {
            fail("refusing to capture: OSShim calls the natives [" + actualShim + "] instead of every OS native"
                    + " once [" + expectedShim + "]: it has been re-pointed at another binding");
        }
        requireReflective(MAC_FONT_FINDER_SHIM, MAC_FONT_FINDER, List.of("getFont", "getFontData"));
        requireReflective(DFONT_DECODER_SHIM, DFONT_DECODER, DFONT_DECODER_NATIVES);
    }

    private static void requireNative(String className, List<String> names, int expectedCount) {
        FontClassFiles.requireNative(className, names, COMMIT);
        int nativeCount = FontClassFiles.nativeMethods(className).size();
        if (nativeCount != expectedCount) {
            fail("refusing to capture: " + className + " declares " + nativeCount + " native methods, the JNI build of"
                    + " commit " + COMMIT + " declares " + expectedCount);
        }
    }

    private static void requireReflective(String shim, String owner, List<String> names) {
        ClassModel model = classModel(shim);
        if (!FontClassFiles.stringConstants(model).containsAll(names) || !fontClasses(model).contains(owner)) {
            fail("refusing to capture: " + shim + " no longer names " + names + " of " + owner + ": it has been"
                    + " re-pointed at another binding");
        }
    }

    /**
     * The C must be that of the capture commit, the library must be newer than every source file, and it must
     * export exactly the JNI symbols of the declared natives (plus {@link #UNDECLARED_EXPORTS}).
     */
    private static void requireBuiltFromCaptureCommit(Path library) {
        for (String source : NATIVE_FONT_SOURCES) {
            Path file = nativeFontSource(source);
            String committedPath = "modules/javafx.graphics/" + NATIVE_FONT_DIRECTORY + "/" + source;
            byte[] committed = FontGoldenRuntime.runBytes(List.of("git", "-C",
                    FontGoldenRuntime.repositoryDirectory().toString(), "show", COMMIT + ":" + committedPath), true);
            try {
                if (committed == null || !Arrays.equals(committed, Files.readAllBytes(file))) {
                    fail("refusing to capture: " + file + " is not the " + source + " of commit " + COMMIT
                            + " (or git cannot show it)");
                }
                if (Files.getLastModifiedTime(library).compareTo(Files.getLastModifiedTime(file)) <= 0) {
                    fail("refusing to capture: " + library + " is not newer than " + file
                            + ", so it was not built from it");
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        Set<String> exported = exportedJniSymbols(library);
        Set<String> expected = expectedJniSymbols();
        if (!exported.equals(expected)) {
            Set<String> missing = new TreeSet<>(expected);
            missing.removeAll(exported);
            Set<String> extra = new TreeSet<>(exported);
            extra.removeAll(expected);
            fail("refusing to capture: " + library + " does not export the JNI symbols of the declared natives;"
                    + " missing " + missing + ", extra " + extra);
        }
    }

    static Path nativeFontSource(String source) {
        return FontGoldenRuntime.moduleDirectory().resolve(NATIVE_FONT_DIRECTORY).resolve(source);
    }

    /** The {@code Java_} symbols the library exports, read with {@code nm}. */
    static Set<String> exportedJniSymbols(Path library) {
        String listing = FontGoldenRuntime.run(List.of("nm", "-gUj", library.toString()), true);
        if (listing == null) {
            fail("cannot list the symbols of " + library + " with nm");
        }
        Set<String> symbols = new TreeSet<>();
        for (String symbol : listing.split("\n")) {
            if (symbol.startsWith("_Java_")) {
                symbols.add(symbol.substring(1));
            }
        }
        return symbols;
    }

    static Set<String> expectedJniSymbols() {
        Set<String> symbols = new TreeSet<>(UNDECLARED_EXPORTS);
        for (String owner : DELEGATE_OWNERS) {
            List<Method> natives = FontClassFiles.nativeMethods(owner.replace('/', '.'));
            for (Method method : natives) {
                String symbol = "Java_" + jniMangle(owner) + "_" + jniMangle(method.getName());
                boolean overloaded = natives.stream().filter(m -> m.getName().equals(method.getName())).count() > 1;
                if (overloaded) {
                    String descriptor = MethodType.methodType(method.getReturnType(), method.getParameterTypes())
                                                  .descriptorString();
                    symbol += "__" + jniMangle(descriptor.substring(1, descriptor.indexOf(')')));
                }
                symbols.add(symbol);
            }
        }
        return symbols;
    }

    /** JNI name mangling of an internal name, a method name or a parameter descriptor. */
    static String jniMangle(String text) {
        StringBuilder mangled = new StringBuilder();
        for (char c : text.toCharArray()) {
            switch (c) {
                case '/' -> mangled.append('_');
                case '_' -> mangled.append("_1");
                case ';' -> mangled.append("_2");
                case '[' -> mangled.append("_3");
                default -> {
                    if (c < 0x80 && Character.isLetterOrDigit(c)) {
                        mangled.append(c);
                    } else {
                        mangled.append(String.format(Locale.ROOT, "_0%04x", (int) c));
                    }
                }
            }
        }
        return mangled.toString();
    }

    /**
     * Runs in both modes, before the gate. The shims must call nothing production does not call (bar the
     * uncalled entry points), production must call nothing on the delegate owners that the shims do not, and no
     * binding entry point may also be called on a second class. The peers keep their measured call sites, no
     * other font class calls a delegate owner, and the observers use no font class.
     */
    static void requireSameBinding() {
        Bindings bindings = Bindings.read();
        List<String> problems = new ArrayList<>(bindings.unsharedTargets(UNCALLED_ENTRY_POINTS));
        problems.addAll(bindings.secondBindings(bindingNames()));
        problems.addAll(peerCallSiteProblems());
        problems.addAll(unlistedCallerProblems());
        problems.addAll(observerProblems());
        if (!problems.isEmpty()) {
            fail("the shims " + SHIMS + " do not share one binding with the production callers "
                    + PRODUCTION_CALLERS + ", so the goldens would not pin production:\n  "
                    + String.join("\n  ", problems));
        }
        reportNativeCounts();
    }

    /** What the shims call, the classes those calls go to, and what each production caller calls. */
    record Bindings(Set<String> shimTargets, Set<String> delegateOwners, Map<String, Set<String>> callerTargets) {

        static Bindings read() {
            Set<String> shimTargets = new TreeSet<>();
            Set<String> delegateOwners = new TreeSet<>();
            for (String shim : SHIMS) {
                ClassModel model = classModel(shim);
                for (String target : invokeTargets(model, true)) {
                    shimTargets.add(target);
                    delegateOwners.add(owner(target));
                }
                shimTargets.addAll(FontClassFiles.reflectiveTargets(model));
            }
            Map<String, Set<String>> callerTargets = new TreeMap<>();
            for (String caller : PRODUCTION_CALLERS) {
                callerTargets.put(caller, invokeTargets(classModel(caller), false));
            }
            return new Bindings(shimTargets, delegateOwners, callerTargets);
        }

        List<String> unsharedTargets(Set<String> uncalledEntryPoints) {
            Set<String> productionTargets = new TreeSet<>();
            Set<String> externalTargets = new TreeSet<>();
            callerTargets.forEach((caller, targets) -> {
                productionTargets.addAll(targets);
                targets.stream().filter(t -> !isSameOrNested(owner(t), caller)).forEach(externalTargets::add);
            });
            List<String> problems = new ArrayList<>();
            if (delegateOwners.isEmpty()) {
                problems.add("the shims invoke no method of a font class at all");
            }
            for (String target : shimTargets) {
                boolean uncalled = uncalledEntryPoints.contains(target);
                boolean called = productionTargets.contains(target);
                if (!called && !uncalled) {
                    problems.add("the shims call " + target + ", which no production caller calls");
                }
                if (called && uncalled) {
                    problems.add("production now calls " + target + ", listed as uncalled; remove it from"
                            + " UNCALLED_ENTRY_POINTS");
                }
            }
            for (String uncalled : uncalledEntryPoints) {
                if (!shimTargets.contains(uncalled)) {
                    problems.add("no shim exercises the uncalled entry point " + uncalled);
                }
            }
            for (String target : externalTargets) {
                if (delegateOwners.contains(owner(target)) && !shimTargets.contains(target)) {
                    problems.add("production calls " + target + ", which no shim exercises");
                }
            }
            return problems;
        }

        /**
         * A production caller that calls, on a class that is not itself a production caller, a binding entry
         * point with the name and descriptor the shims reach on a delegate owner. Calls made inside a delegate
         * owner (or its nested classes) are its implementation, not a second binding.
         */
        List<String> secondBindings(Set<String> bindingNames) {
            List<String> problems = new ArrayList<>();
            callerTargets.forEach((caller, targets) -> {
                if (delegateOwners.stream().anyMatch(delegate -> isSameOrNested(caller, delegate))) {
                    return;
                }
                for (String target : targets) {
                    if (callerTargets.containsKey(owner(target))) {
                        continue;
                    }
                    for (String shimTarget : shimTargets) {
                        if (bindingNames.contains(name(shimTarget)) && !owner(target).equals(owner(shimTarget))
                                && member(target).equals(member(shimTarget))) {
                            problems.add(caller + " calls " + target + " while the shims call " + shimTarget
                                    + ": two bindings for one entry point");
                        }
                    }
                }
            });
            return problems;
        }
    }

    private static Set<String> bindingNames() {
        Set<String> names = new TreeSet<>();
        NATIVES.values().forEach(names::addAll);
        names.add("CFStringCreate");
        return names;
    }

    private static boolean isDelegate(String internalName) {
        return DELEGATE_OWNERS.stream().anyMatch(owner -> isSameOrNested(internalName, owner));
    }

    private static List<String> peerCallSiteProblems() {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> expected : new TreeMap<>(NATIVE_CALL_SITES).entrySet()) {
            if (isDelegate(expected.getKey())) {
                continue;
            }
            String actual = nativeCallSites(classModel(expected.getKey()), NATIVES);
            if (!actual.equals(expected.getValue())) {
                problems.add(expected.getKey() + " calls [" + actual + "] where the JNI build of commit " + COMMIT
                        + " calls [" + expected.getValue() + "]: a call site moved to another binding");
            }
        }
        return problems;
    }

    /** Production font classes outside {@link #PRODUCTION_CALLERS} that call a delegate owner. */
    private static List<String> unlistedCallerProblems() {
        Path classes = FontGoldenRuntime.moduleDirectory().resolve("target/classes");
        Map<String, Set<String>> callers = new HashMap<>();
        try (Stream<Path> walk = Files.walk(classes.resolve(FontClassFiles.FONT_PACKAGE))) {
            for (Path file : walk.filter(f -> f.toString().endsWith(".class")).toList()) {
                String internalName = classes.relativize(file).toString().replace('\\', '/').replace(".class", "");
                if (PRODUCTION_CALLERS.contains(internalName) || isDelegate(internalName)) {
                    continue;
                }
                Set<String> calls = new TreeSet<>();
                for (String target : invokeTargets(classModel(internalName), true)) {
                    if (isDelegate(owner(target))) {
                        calls.add(target);
                    }
                }
                if (!calls.isEmpty()) {
                    callers.put(internalName, calls);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<String> problems = new ArrayList<>();
        new TreeMap<>(callers).forEach((caller, calls) -> problems.add(caller + " calls " + calls
                + " but is not in PRODUCTION_CALLERS, so neither the witness nor the call-site pin sees it"));
        return problems;
    }

    private static List<String> observerProblems() {
        List<String> problems = new ArrayList<>();
        for (String observer : OBSERVERS) {
            Set<String> observerClasses = fontClasses(classModel(observer));
            observerClasses.removeIf(name -> OBSERVERS.stream().anyMatch(o -> isSameOrNested(name, o)));
            if (!observerClasses.isEmpty()) {
                problems.add("the observer " + observer + " uses the font classes " + observerClasses
                        + "; it must never delegate to the bindings it observes");
            }
        }
        return problems;
    }

    private static synchronized void reportNativeCounts() {
        if (!nativeCountsReported) {
            nativeCountsReported = true;
            List<String> counts = new ArrayList<>();
            for (String owner : DELEGATE_OWNERS) {
                counts.add(owner.substring(owner.lastIndexOf('/') + 1) + " "
                        + FontClassFiles.nativeMethods(owner.replace('/', '.')).size());
            }
            System.out.println("macOS font goldens: native methods left: " + String.join(", ", counts));
        }
    }

    private static ClassModel classModel(String internalName) {
        return FontClassFiles.classModel(internalName, "the macOS font tests know the classes of commit " + COMMIT
                + " and their replacements under these names");
    }
}
