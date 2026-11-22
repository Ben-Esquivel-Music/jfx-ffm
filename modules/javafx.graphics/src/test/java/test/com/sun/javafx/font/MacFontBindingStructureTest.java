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
    private static final String OBJC = CORETEXT + "ObjCNative";

    /** In dependency order: each may reference only those before it. */
    static final List<String> FACADES = List.of(CF, CG, CT, OBJC);

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

    static final String REACHABILITY_FENCE = "java/lang/ref/Reference reachabilityFence";

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
        assertTrue(references.indexOf(OBJC + " link()V") > references.indexOf(CT + " link()V"),
                   "PrismFontFactory.<clinit> links libobjc after the frameworks: " + references);
    }

    /**
     * An empty font name has no first character, so its length is read before that character is. No run-time test
     * can tell: {@code CFStringGetCharacterAtIndex} of the empty string at index 0 answers 0 on macOS 27.2.1.
     */
    @Test
    void aFontNameIsMeasuredBeforeItsFirstCharacterIsRead() {
        FontCallOrder.assertFirstCallBefore(MacFontBindings.OS, "CTFontCreateWithName", CF + " CFStringGetLength",
                                            CF + " CFStringGetCharacterAtIndex");
    }

    /**
     * The strike's disposer releases the CTFont that layout hands to CoreText, so layout fences the strike it read
     * the font from until the line, which retains the font, is made. That the fence is enough at run time rests on
     * review: losing the strike inside layout needs a compiled frame and a Disposer race, which no test provokes.
     */
    @Test
    void layoutKeepsTheStrikeReachableUntilItsLineHoldsTheFont() {
        String layout = CORETEXT + "CTGlyphLayout";
        FontCallOrder.assertArgumentIsReceiverOf(layout, "layout", REACHABILITY_FENCE,
                                                 CORETEXT + "CTFontStrike getFontRef");
        FontCallOrder.assertFirstCallBefore(layout, "layout", layout + " createCTLine", REACHABILITY_FENCE);
    }

    /**
     * Layout asks the factory whether a fallback face is worth a lookup before it copies the run's font for one, and
     * copies it and adds the copy only when the answer is yes, so a face its font file lacks costs no copy after the
     * first lookup. {@code MacFontReleaseTest} counts the lookups but cannot see a copy that is made and released,
     * nor tell a layout that never looks a face up from a machine where no fallback face is missing.
     */
    @Test
    void layoutAsksForAFallbackFaceBeforeCopyingTheRunFont() {
        String layout = CORETEXT + "CTGlyphLayout";
        String mayFindFace = CORETEXT + "CTFactory mayFindFace";
        String copy = MacFontBindings.OS + " CTFontCreateCopyWithAttributes";
        FontCallOrder.assertFirstCallBefore(layout, "getFontSlot", mayFindFace, copy);
        FontCallOrder.assertGuards(layout, "getFontSlot", mayFindFace, List.of(copy, layout + " addFallbackFont"),
                                   List.of(), "a face its font file lacks costs no copy of the run's font, and no"
                                   + " lookup, after the first");
    }

    /**
     * What CoreText autoreleases while it makes a cascade list waits for a pool, so the list is read inside one of
     * the calling thread's. {@code MacFontReleaseTest} sees a missing pool only where the heap can show it, which the
     * macOS 15 runners' cannot.
     */
    @Test
    void theCascadeListIsReadInsideAnAutoreleasePool() {
        String finder = MacFontBindings.MAC_FONT_FINDER;
        FontCallOrder.assertFirstCallBefore(finder, "pooledCascadeList", OBJC + " objc_autoreleasePoolPush",
                                            CF + " CFLocaleCopyISOLanguageCodes");
        FontCallOrder.assertFirstCallBefore(finder, "pooledCascadeList", finder + " cascadeList",
                                            OBJC + " objc_autoreleasePoolPop");
    }

    /**
     * The pool is popped however the read of the list ends: before each return after the read, and in a handler that
     * catches everything the read throws, before that handler throws, the two copies javac makes of a finally.
     * {@code MacFontReleaseTest} sees a pool left on a return only where the heap can show it, and one left on a throw
     * on no machine, since none of its reads throws.
     */
    @Test
    void thePoolOfTheCascadeListIsPoppedOnBothExits() {
        String finder = MacFontBindings.MAC_FONT_FINDER;
        FontCallOrder.assertReleasedOnEveryExit(finder, "pooledCascadeList", finder + " cascadeList",
                                                OBJC + " objc_autoreleasePoolPop",
                                                "no pool is left pushed, whether the read returns or throws");
    }

    /**
     * A pool must be popped on the thread that pushed it, and a virtual thread can change carriers between the
     * downcalls, so only a virtual caller hands the list to a platform thread, and returns what the hand-off answers,
     * and a platform caller reads it in a pool of its own. {@code MacFontReleaseTest} sees a caller of either kind that
     * reads the list without a pool only where the heap can show it, and a negated condition on no machine, since
     * both kinds of caller drain under it.
     */
    @Test
    void aVirtualCallerHandsTheCascadeListToAPlatformThread() {
        String finder = MacFontBindings.MAC_FONT_FINDER;
        String isVirtual = "java/lang/Thread isVirtual";
        String handOff = finder + " cascadeListOnAPlatformThread";
        String pooled = finder + " pooledCascadeList";
        FontCallOrder.assertFirstCallBefore(finder, "getCascadeList", isVirtual, handOff);
        FontCallOrder.assertFirstCallBefore(finder, "getCascadeList", handOff, pooled);
        FontCallOrder.assertGuards(finder, "getCascadeList", isVirtual, List.of(handOff), List.of(pooled),
                                   "only a virtual caller hands the list to a platform thread, and a platform caller"
                                   + " reads it in a pool of its own");
        FontCallOrder.assertReturnsFirstCallOf(finder, "getCascadeList", handOff,
                                               "a virtual caller pushes no pool on its own thread");
    }

    /**
     * The hand-off reads the list on a thread of its own: it calls {@code Thread.ofPlatform} before it starts a
     * thread, starts one before it waits for the task, and never runs the task, nor calls {@code pooledCascadeList},
     * itself, since javac compiles the task's body, a lambda, into a method of its own. {@code MacFontReleaseTest}
     * measures the heap, which does not show the thread a pool was pushed on.
     */
    @Test
    void theHandOffStartsAPlatformThreadAndNeverRunsTheTaskItself() {
        String finder = MacFontBindings.MAC_FONT_FINDER;
        String handOff = "cascadeListOnAPlatformThread";
        String start = "java/lang/Thread start";
        List<String> runsTheTaskHere = List.of("java/lang/Thread run", "java/util/concurrent/FutureTask run",
                                               "java/lang/Runnable run", finder + " pooledCascadeList");
        FontCallOrder.assertNeverCalls(finder, handOff, runsTheTaskHere,
                                       "the task pushes and pops its pool on the platform thread, not on the virtual"
                                       + " caller");
        FontCallOrder.assertFirstCallBefore(finder, handOff, "java/lang/Thread ofPlatform", start);
        FontCallOrder.assertFirstCallBefore(finder, handOff, start, finder + " resultOf");
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
