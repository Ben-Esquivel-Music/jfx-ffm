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

import com.sun.javafx.font.coretext.CFNativeShim;
import com.sun.javafx.font.coretext.CGNativeShim;
import com.sun.javafx.font.coretext.CTNative;
import com.sun.javafx.font.coretext.CTNativeShim;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shape of the macOS font bindings, read from class files on any OS: where restricted calls and the linkage
 * toolkit may appear, which way the facades depend on each other, what they may not reference, the order their
 * initialisers link them in, and the descriptor rule that keeps the goldens' binding witness sound.
 */
public class MacFontBindingStructureTest {

    private static final String CORETEXT = "com/sun/javafx/font/coretext/";
    private static final String CF = CORETEXT + "CFNative";
    private static final String CG = CORETEXT + "CGNative";
    private static final String CT = CORETEXT + "CTNative";

    /** In dependency order: each may reference only those before it. */
    static final List<String> FACADES = List.of(CF, CG, CT);

    static final Set<String> RESTRICTED = Set.of(
            "java/lang/foreign/SymbolLookup libraryLookup", "java/lang/foreign/Linker downcallHandle",
            "java/lang/foreign/Linker upcallStub", "java/lang/foreign/MemorySegment reinterpret",
            "java/lang/foreign/AddressLayout withTargetLayout");

    static final Set<String> TOOLKIT = Set.of(
            CF + " framework", CF + " unexpected", CF + " LINKER", CF + "$Framework bind",
            CF + "$Framework dataValue", CF + "$Framework dataAddress");

    static final List<String> FORBIDDEN = List.of(
            CORETEXT + "OS", CORETEXT + "CTFactory", CORETEXT + "CTFontFile", CORETEXT + "CTFontStrike",
            CORETEXT + "CTGlyph", CORETEXT + "CTGlyphLayout", CORETEXT + "CTStrikeDisposer", CORETEXT + "CGRect",
            CORETEXT + "CGPoint", CORETEXT + "CGSize", CORETEXT + "CGAffineTransform", CORETEXT + "CGPathSink",
            CORETEXT + "CGBitmapReader", CORETEXT + "GlyfTableBounds", "com/sun/javafx/font/MacFontFinder",
            "com/sun/javafx/font/DFontDecoder", "com/sun/javafx/font/PrismFontFactory");

    static final String PRISM_FONT_FACTORY = "com/sun/javafx/font/PrismFontFactory";

    /**
     * The binding names of the goldens' witness, by owner. Only these are entry points: the owners' private helpers
     * may take segments.
     */
    static final Map<String, List<String>> ENTRY_POINTS = entryPoints();

    /** The sinks of {@code CGPathApply}, which carry only doubles and ints. */
    static final List<String> FOREIGN_FREE = List.of(CG + "$PathSink", CORETEXT + "CGPathSink");

    private static final String SEGMENT = "Ljava/lang/foreign/MemorySegment;";

    private static final Pattern DESCRIPTOR_CLASS = Pattern.compile("L(com/sun/javafx/[\\w/$]+);");

    @Test
    void restrictedCallsStayInTheFacades() {
        List<String> problems = new ArrayList<>();
        for (String type : macFontClasses()) {
            if (!isFacadeOrNested(type)) {
                uses(type).stream().filter(RESTRICTED::contains).forEach(use -> problems.add(type + " calls " + use));
            }
        }
        assertEquals(List.of(), problems);
    }

    @Test
    void onlyTheFacadesUseTheLinkageToolkit() {
        List<String> problems = new ArrayList<>();
        for (String type : macFontClasses()) {
            if (!isFacadeOrNested(type)) {
                uses(type).stream().filter(TOOLKIT::contains).forEach(use -> problems.add(type + " uses " + use));
            }
        }
        assertEquals(List.of(), problems);
    }

    @Test
    void facadesDependOnlyOnEarlierFacadesAndNoFontClass() {
        List<String> problems = new ArrayList<>();
        for (int i = 0; i < FACADES.size(); i++) {
            Set<String> referenced = referencedClasses(FACADES.get(i));
            for (String later : FACADES.subList(i + 1, FACADES.size())) {
                referenced.stream().filter(name -> FontClassFiles.isSameOrNested(name, later))
                          .forEach(name -> problems.add("back edge to " + name));
            }
            for (String forbidden : FORBIDDEN) {
                if (referenced.contains(forbidden)) {
                    problems.add(FACADES.get(i) + " references " + forbidden);
                }
            }
        }
        assertEquals(List.of(), problems);
    }

    @Test
    void facadeMethodsNamedLikeEntryPointsTakeOrReturnSegments() {
        Set<String> entryPointNames = new TreeSet<>();
        Set<String> read = new TreeSet<>();
        List<String> problems = new ArrayList<>();
        ENTRY_POINTS.forEach((owner, names) -> {
            entryPointNames.addAll(names);
            for (MethodModel method : model(owner).methods()) {
                String name = method.methodName().stringValue();
                if (names.contains(name)) {
                    read.add(name);
                    if (method.methodType().stringValue().contains(SEGMENT)) {
                        problems.add("entry point " + owner + "." + name + method.methodType());
                    }
                }
            }
        });
        for (String facade : FACADES) {
            for (MethodModel method : model(facade).methods()) {
                String name = method.methodName().stringValue();
                if (entryPointNames.contains(name) && !method.methodType().stringValue().contains(SEGMENT)) {
                    problems.add("facade method " + facade + "." + name + method.methodType());
                }
            }
        }
        assertEquals(List.of(), problems);
        assertTrue(read.contains("CFRelease"), "the entry points were read");
    }

    @Test
    void theSinksOfThePathApplierCarryNoForeignType() {
        Set<String> foreign = new TreeSet<>();
        for (String sink : FOREIGN_FREE) {
            for (PoolEntry entry : model(sink).constantPool()) {
                if (entry instanceof Utf8Entry utf8 && utf8.stringValue().contains("java/lang/foreign")) {
                    foreign.add(sink + ": " + utf8.stringValue());
                }
            }
        }
        assertEquals(Set.of(), foreign);
    }

    @Test
    void theFontFactoryInitialiserLinksTheBindings() {
        List<String> references = initialiserReferences(PRISM_FONT_FACTORY);
        assertTrue(references.contains(CT + " link()V"), "PrismFontFactory.<clinit> references " + references);
    }

    @Test
    void coreTextInitialisesCoreGraphicsBeforeOpeningItsFramework() {
        List<String> earlierFacades = initialiserReferences(CT).stream()
                .filter(reference -> isSameOrNestedIn(owner(reference), CF, CG)).toList();
        int framework = earlierFacades.indexOf(CF + " framework(Ljava/lang/String;)L" + CF + "$Framework;");
        assertTrue(framework > 0, "CTNative.<clinit> opens CoreText after another facade reference: " + earlierFacades);
        assertEquals(CG + " link()V", earlierFacades.get(0), "the first reference to CFNative or CGNative");
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void everyFrameworkSymbolIsBoundOnceInTheFacadeItsPrefixNames() {
        CTNative.link();
        Map<String, List<String>> registries = new LinkedHashMap<>();
        registries.put("CoreFoundation", symbols(CFNativeShim.boundFunctions().keySet(), CFNativeShim.boundData()));
        registries.put("CoreGraphics", symbols(CGNativeShim.boundFunctions().keySet(), CGNativeShim.boundData()));
        registries.put("CoreText", symbols(CTNativeShim.boundFunctions().keySet(), CTNativeShim.boundData()));
        Set<String> seen = new TreeSet<>();
        List<String> problems = new ArrayList<>();
        registries.forEach((framework, symbols) -> {
            for (String qualified : symbols) {
                String symbol = qualified.substring(qualified.indexOf('!') + 1);
                String prefix = symbol.replaceFirst("^k", "").substring(0, 2);
                String expected = switch (prefix) {
                    case "CF" -> "CoreFoundation";
                    case "CG" -> "CoreGraphics";
                    case "CT" -> "CoreText";
                    default -> "?";
                };
                if (!qualified.startsWith(framework + "!") || !framework.equals(expected) || !seen.add(symbol)) {
                    problems.add(framework + ": " + qualified);
                }
            }
        });
        assertEquals(List.of(), problems);
        assertEquals(75, CFNativeShim.boundFunctions().size() + CGNativeShim.boundFunctions().size()
                + CTNativeShim.boundFunctions().size(), "functions");
        assertEquals(11, CFNativeShim.boundData().size() + CGNativeShim.boundData().size()
                + CTNativeShim.boundData().size(), "data symbols");
    }

    private static Map<String, List<String>> entryPoints() {
        Map<String, List<String>> entryPoints = new LinkedHashMap<>();
        entryPoints.put(MacFontBindings.OS, MacFontBindings.OS_NATIVES);
        entryPoints.put(MacFontBindings.MAC_FONT_FINDER, MacFontBindings.MAC_FONT_FINDER_NATIVES);
        entryPoints.put(MacFontBindings.DFONT_DECODER, MacFontBindings.DFONT_DECODER_NATIVES);
        return entryPoints;
    }

    /** {@code owner name descriptor} of each method invoked, {@code owner name} of each field, in code order. */
    static List<String> initialiserReferences(String type) {
        List<String> references = new ArrayList<>();
        for (MethodModel method : model(type).methods()) {
            if (method.methodName().equalsString("<clinit>")) {
                method.code().ifPresent(code -> code.forEach(element -> {
                    if (element instanceof InvokeInstruction invoke) {
                        references.add(invoke.owner().asInternalName() + " " + invoke.name().stringValue()
                                + invoke.typeSymbol().descriptorString());
                    } else if (element instanceof FieldInstruction field) {
                        references.add(field.owner().asInternalName() + " " + field.name().stringValue());
                    }
                }));
            }
        }
        return references;
    }

    private static String owner(String reference) {
        return reference.substring(0, reference.indexOf(' '));
    }

    private static boolean isSameOrNestedIn(String type, String... classes) {
        for (String candidate : classes) {
            if (FontClassFiles.isSameOrNested(type, candidate)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> symbols(Set<String> functions, List<String> data) {
        List<String> symbols = new ArrayList<>(functions);
        symbols.addAll(data);
        return symbols;
    }

    static boolean isFacadeOrNested(String type) {
        return FACADES.stream().anyMatch(facade -> FontClassFiles.isSameOrNested(type, facade));
    }

    static ClassModel model(String internalName) {
        return FontClassFiles.classModel(internalName, "the macOS font structure test reads the production classes");
    }

    /** The production classes of the macOS font layer: the coretext package, MacFontFinder and DFontDecoder. */
    static List<String> macFontClasses() {
        Path classes = FontGoldenRuntime.moduleDirectory().resolve("target/classes");
        List<String> types = new ArrayList<>(List.of("com/sun/javafx/font/MacFontFinder",
                "com/sun/javafx/font/DFontDecoder", "com/sun/javafx/font/PrismFontFactory"));
        try (Stream<Path> files = Files.list(classes.resolve(CORETEXT))) {
            files.filter(file -> file.toString().endsWith(".class")).map(file -> CORETEXT + file.getFileName()
                    .toString().replace(".class", "")).sorted().forEach(types::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return types;
    }

    /** {@code owner member} of every method invoked and field accessed by the class. */
    static Set<String> uses(String type) {
        Set<String> uses = new TreeSet<>();
        for (MethodModel method : model(type).methods()) {
            method.code().ifPresent(code -> code.forEach(element -> {
                if (element instanceof InvokeInstruction invoke) {
                    uses.add(invoke.owner().asInternalName() + " " + invoke.name().stringValue());
                } else if (element instanceof FieldInstruction field) {
                    uses.add(field.owner().asInternalName() + " " + field.name().stringValue());
                }
            }));
        }
        return uses;
    }

    /** Classes named in the constant pool or in a method or field descriptor. */
    static Set<String> referencedClasses(String type) {
        Set<String> classes = new TreeSet<>();
        for (PoolEntry entry : model(type).constantPool()) {
            if (entry instanceof ClassEntry classEntry) {
                classes.add(classEntry.asInternalName().replaceFirst("^\\[+L?", "").replaceFirst(";$", ""));
            } else if (entry instanceof Utf8Entry utf8) {
                Matcher matcher = DESCRIPTOR_CLASS.matcher(utf8.stringValue());
                while (matcher.find()) {
                    classes.add(matcher.group(1));
                }
            }
        }
        return classes;
    }
}
