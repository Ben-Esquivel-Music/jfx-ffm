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
import java.lang.classfile.Attributes;
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

    static final String CASCADE_LIST_TASK = MacFontBindings.MAC_FONT_FINDER + "$CascadeListTask";

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
     * An empty font name has no first character, so its length is read before that character is, and the character
     * only where the length is above 0. {@code MacFontNullInputTest} sees a character read at length 0 only where
     * CoreFoundation reads an empty string's buffer past its length, as macOS 27.2.1 does for an empty name over a
     * '.'; the constant empty string's first character reads as 0 there.
     */
    @Test
    void aFontNameIsMeasuredBeforeItsFirstCharacterIsRead() {
        FontCallOrder.assertFirstCallBefore(MacFontBindings.OS, "CTFontCreateWithName", CF + " CFStringGetLength",
                                            CF + " CFStringGetCharacterAtIndex");
        FontCallOrder.assertCallsOnlyWhereAnswerIsNotZero(MacFontBindings.OS, "CTFontCreateWithName",
                CF + " CFStringGetLength", CF + " CFStringGetCharacterAtIndex",
                "the empty name, which has no first character, takes the ordinary path");
    }

    /**
     * The bold string is made once, and CoreFoundation answers NULL for it only where it cannot allocate four
     * characters, which no test can provoke; without it no font name is searched. That the code then takes no '.'
     * name as bold rests on review.
     */
    @Test
    void aFontNameIsSearchedOnlyForABoldStringCoreFoundationMade() {
        FontCallOrder.assertCallsOnlyWhereAnswerIsNotZero(MacFontBindings.OS, "CTFontCreateWithName",
                CF + " boldCFString", CF + " CFStringFind",
                "CoreFoundation is never asked to find a string it could not make");
    }

    /**
     * Font 0, which only a shim can pass, since {@code decode} refuses it first, reaches no CoreText call of the
     * decoder. {@code MacFontNullInputTest} pins what each answers for it, but CoreText may answer the same for a NULL
     * font, so only the class file shows a guard that is missing.
     */
    @Test
    void aZeroDfontFontReachesNoCoreTextCall() {
        String decoder = MacFontBindings.DFONT_DECODER;
        String protects = "CoreText is never handed font 0, which only a shim can pass";
        FontCallOrder.assertCallsOnlyWhereParameterIsNotZero(decoder, "getCTFontFormat", 0,
                                                             CT + " CTFontCopyAttribute", protects);
        FontCallOrder.assertCallsOnlyWhereParameterIsNotZero(decoder, "getCTFontTags", 0,
                                                             CT + " CTFontCopyAvailableTables", protects);
        FontCallOrder.assertCallsOnlyWhereParameterIsNotZero(decoder, "getCTFontTable", 0,
                                                             CT + " CTFontCopyTable", protects);
    }

    /**
     * Both copies javac makes of the decoder's {@code finally} release the name string only where CoreFoundation made
     * it. Nothing makes {@code toCFString} answer NULL short of native memory running out, so no run-time test can.
     */
    @Test
    void aDfontNameStringIsReleasedOnlyWhereCoreFoundationMadeIt() {
        FontCallOrder.assertCallsOnlyWhereAnswerIsNotZero(MacFontBindings.DFONT_DECODER, "createCTFont",
                CF + " toCFString", CF + " CFRelease",
                "a name string CoreFoundation did not make is never handed to CFRelease, which ends the JVM on NULL");
    }

    /**
     * A NULL font, which only a shim can pass, since {@code getCascadeList} refuses font 0 first, asks CoreText for no
     * cascade list. CoreText answers no list for a NULL font on macOS 27.2.1, so no run-time test can tell.
     */
    @Test
    void aNullFontAsksCoreTextForNoCascadeList() {
        FontCallOrder.assertCallsOnlyWhereParameterIsNotZero(MacFontBindings.MAC_FONT_FINDER, "cascadeList", 0,
                CT + " CTFontCopyDefaultCascadeListForLanguages", "CoreText is never handed a NULL font");
    }

    /**
     * A strike without a CTFont asks CoreText for no advance. No run-time test can tell: CoreText answers 0.0 for a
     * NULL font on macOS 27.2.1, the advance the guard answers.
     */
    @Test
    void aStrikeWithoutACtFontAsksCoreTextForNoAdvance() {
        FontCallOrder.assertCallsOnlyWhereAnswerIsNotZero(CORETEXT + "CTFontFile", "getAdvanceFromPlatform",
                CORETEXT + "CTFontStrike getFontRef", MacFontBindings.OS + " CTFontGetAdvancesForGlyphs",
                "CoreText is never handed the NULL font of a strike that has none");
    }

    /**
     * A strike's options are set only on a context there is: of the methods of {@code CTGlyph} only
     * {@code setStrikeOptions} calls the four setters, and only {@code createContext} and {@code getImage} call it,
     * {@code createContext} only on a context {@code bitmapContext} made, and the cached path of {@code getImage} only
     * on one the cache holds. {@code MacFontNullInputTest} sees an option set on a NULL context only where the account
     * can read the unified log.
     */
    @Test
    void aContextCoreGraphicsDidNotMakeIsNotConfigured() {
        String glyph = CORETEXT + "CTGlyph";
        String options = glyph + " setStrikeOptions";
        for (String setter : List.of("CGContextSetAllowsFontSmoothing", "CGContextSetAllowsAntialiasing",
                                     "CGContextSetAllowsFontSubpixelPositioning",
                                     "CGContextSetAllowsFontSubpixelQuantization")) {
            assertEquals(Set.of("setStrikeOptions"), callers(glyph, MacFontBindings.OS + " " + setter),
                         "the methods of CTGlyph that call " + setter);
        }
        assertEquals(Set.of("createContext", "getImage"), callers(glyph, options),
                     "the methods of CTGlyph that call setStrikeOptions");
        String protects = "nothing is set on a context CoreGraphics did not make, on every host";
        FontCallOrder.assertCallsOnlyWhereAnswerIsNotZero(glyph, "createContext", glyph + " bitmapContext", options,
                                                          protects);
        FontCallOrder.assertCallsOnlyWhereAnswerIsNotZero(glyph, "getImage", glyph + " getCachedContext", options,
                                                          protects);
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
     * Layout hands the slot lookup of each run, as the line's font, the strike's CTFont it made the line with, so that
     * a run in that font keeps the strike's slot whatever CoreText names it. {@code MacFontReleaseTest} hands
     * {@code getFontSlot} that font itself, and lays out only a line whose run in the strike's font carries the
     * strike's name, so that run keeps its slot by name: no run-time test sees which font layout hands over.
     */
    @Test
    void layoutHandsTheSlotLookupTheFontItsLineWasMadeWith() {
        String layout = CORETEXT + "CTGlyphLayout";
        String fontRef = CORETEXT + "CTFontStrike getFontRef";
        String protects = "a run in the strike's own font keeps the strike's slot whatever CoreText names it";
        FontCallOrder.assertArgumentIsAnswerOf(layout, "layout", layout + " createCTLine", 0, fontRef, protects);
        FontCallOrder.assertArgumentIsAnswerOf(layout, "layout", layout + " getFontSlot", 1, fontRef, protects);
    }

    /**
     * Layout hands the lookup of a fallback face the file the run's own font reports, and that font, and copies no
     * font itself. The lookup asks the factory whether the face is worth looking up in that file before it looks it
     * up, and once more, as the one lookup of the name in flight, before it copies the run's font; only the lookup
     * copies it. So a face its font file lacks costs no copy of the run's font, and no lookup, until the file changes.
     * {@code MacFontFaceLookupTest} counts the lookups and the copies handed to the factory, but cannot see a copy
     * that is made and released.
     */
    @Test
    void layoutCopiesTheRunFontOnlyWhereTheFactoryMayFindItsFace() {
        String layout = CORETEXT + "CTGlyphLayout";
        String mayFindFace = CORETEXT + "CTFactory mayFindFace";
        String copy = MacFontBindings.OS + " CTFontCreateCopyWithAttributes";
        String url = MacFontBindings.OS + " CTFontCopyURLAttribute";
        String runFont = MacFontBindings.OS + " CFDictionaryGetValue";
        String addFallbackFont = layout + " addFallbackFont";
        String lookUp = layout + " lookUpFallbackFont";
        String protects = "a face its font file lacks costs no copy of the run's font, and no lookup, until the file"
                + " changes";
        FontCallOrder.assertArgumentIsAnswerOf(layout, "getFontSlot", url, 0, runFont, protects);
        FontCallOrder.assertArgumentIsAnswerOf(layout, "getFontSlot", addFallbackFont, 3, url, protects);
        FontCallOrder.assertArgumentIsAnswerOf(layout, "getFontSlot", addFallbackFont, 4, runFont, protects);
        FontCallOrder.assertNeverCalls(layout, "getFontSlot", List.of(copy), protects);
        FontCallOrder.assertGuards(layout, "addFallbackFont", mayFindFace, List.of(lookUp), List.of(), protects);
        FontCallOrder.assertGuards(layout, "lookUpFallbackFont", mayFindFace,
                                   List.of(copy, layout + " fontFileOfCopy"), List.of(), protects);
        FontCallOrder.assertCalledOnlyIn(List.of(layout, CORETEXT + "CTFactory"), copy, Set.of(lookUp), protects);
    }

    /**
     * One lookup of a fallback face runs at a time per name and file: a caller that finds one in flight returns what
     * it found and runs none of its own, and the one that runs it ends it on every way out, an error included, removing
     * it before it wakes the callers that wait. A registration that throws, which may have put the lookup in the map
     * already, ends it before the throw, and the ending makes no key of its own. The lookup's outcome is stored only
     * in {@code finish}, straight-line code, before the count-down that lets the waiters read it. The waiters wait
     * with no monitor held, in their own code or in a monitor's {@code wait}, so that a virtual caller unmounts, and
     * layout's code down to the wait takes no monitor. {@code MacFontFaceLookupTest} holds a lookup with callers
     * waiting and fails it with an exception and an error; a registration or removal that throws, a failure at the
     * entry of {@code endLookup} or inside {@code finish}, and a waiter that reads the outcome before it is stored are
     * not provoked.
     */
    @Test
    void aLookupOfAFallbackFaceIsEndedOnEveryWayOutAndSharedByTheCallersThatWait() {
        String layout = CORETEXT + "CTGlyphLayout";
        String factory = CORETEXT + "CTFactory";
        String lookup = factory + "$FaceLookup";
        String remove = "java/util/concurrent/ConcurrentHashMap remove";
        String protects = "callers that need a face at once share one lookup, and none waits for a lookup that ended";
        FontCallOrder.assertFirstCallBefore(layout, "lookUpFallbackFont", factory + " beginLookup", lookup + " await");
        FontCallOrder.assertReturnsFirstCallOf(layout, "lookUpFallbackFont", lookup + " await", protects);
        FontCallOrder.assertReleasedOnEveryExit(layout, "lookUpFallbackFont", factory + " getFontResource",
                                                factory + " endLookup", protects);
        FontCallOrder.assertFirstCallBefore(factory, "endLookup", remove, lookup + " finish");
        FontCallOrder.assertReleasedOnEveryExit(factory, "endLookup", remove, lookup + " finish", protects);
        FontCallOrder.assertHandlerReleasesUnconditionally(factory, "beginLookup",
                "java/util/concurrent/ConcurrentHashMap putIfAbsent", factory + " endLookup", protects);
        FontCallOrder.assertNeverCalls(factory, "endLookup",
                List.of(factory + " faceKey", factory + "$FaceKey", "java/lang/String"), protects);
        String published = "a waiter the latch lets through reads the outcome";
        FontCallOrder.assertWrittenBefore(lookup, "finish", lookup + " font",
                "java/util/concurrent/CountDownLatch countDown", published);
        FontCallOrder.assertFieldStoredOnlyIn(lookup, "font", List.of("finish"), published);
        String unmounts = "a caller that waits for a lookup holds no monitor, and a virtual one unmounts";
        FontCallOrder.assertNeverCalls(lookup, "await", List.of("java/lang/Object wait"), unmounts);
        Map<String, List<String>> methods = new LinkedHashMap<>();
        methods.put(layout, List.of("layout", "getFontSlot", "addFallbackFont", "lookUpFallbackFont"));
        methods.put(factory, List.of("mayFindFace", "beginLookup", "endLookup"));
        methods.put(lookup, List.of("await", "finish"));
        methods.forEach((type, names) -> names.forEach(method -> FontCallOrder.assertTakesNoMonitor(type, method,
                                                                                                   unmounts)));
    }

    /**
     * What CoreText autoreleases while it makes a cascade list waits for a pool, so the list is read inside one of
     * the calling thread's. {@code MacFontReleaseTest} sees a missing pool only where the heap or the pool tokens can
     * show it: the heap of the macOS 15 runners cannot.
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
     * The pool is popped however the read of the list ends: before each return or throw after the read, and in each
     * handler that covers the read, one of them catching everything, before that handler returns or throws, the
     * copies javac makes of a finally. {@code MacFontReleaseTest} sees a pool left on a return only where the heap or
     * the pool tokens can show it, and one left on a throw on no machine, since none of its reads throws.
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
     * reads the list without a pool only where the heap or the pool tokens can show it, and a negated condition where
     * a virtual caller reads the list itself instead of waiting for the platform thread.
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
     * The calls of the hand-off, its queue helper and the wait, and their order: the hand-off starts the cascade-list
     * thread, through {@code Thread.ofPlatform} before {@code Thread.start}, makes the task before it queues it, queues
     * it before it waits for it and returns what the wait answers; the queue helper wakes the thread once each add to
     * the queue returns, with no conditional branch, switch, return, throw or handler on the way, and a task enters
     * the queue only through that add: only the queue helper calls {@code add}, and no method of
     * {@code MacFontFinder} or of its nest calls {@code offer} or {@code addAll} of a {@code ConcurrentLinkedQueue};
     * and neither the hand-off, nor its queue helper, nor the wait calls a method that runs the task or reads the
     * list. The add ends a {@code try} whose handler, for a task that does not enter the queue, lies between the add
     * and the wake-up in code order, so the wake-up follows the add on the path the add's return takes, past that
     * handler. That reads calls, not data flow: that the task runs on that thread, whatever call would run it on the
     * caller, is {@code MacFontReleaseTest.aVirtualCallerWaitsForThePlatformThreadToMakeItsList}.
     */
    @Test
    void theHandOffQueuesATaskForThePlatformThreadAndNeverRunsItItself() {
        String finder = MacFontBindings.MAC_FONT_FINDER;
        String task = CASCADE_LIST_TASK;
        String handOff = "cascadeListOnAPlatformThread";
        String start = finder + " startCascadeListWorker";
        String queue = finder + " queueCascadeListTask";
        String await = task + " awaitList";
        String protects = "the task pushes and pops its pool on the platform thread, not on the virtual caller";
        List<String> runsTheTask = List.of("java/lang/Thread run", "java/util/concurrent/FutureTask run",
                "java/lang/Runnable run", task + " run", finder + " pooledCascadeList",
                finder + " runCascadeListTasks");
        for (String method : List.of(handOff, "queueCascadeListTask")) {
            FontCallOrder.assertNeverCalls(finder, method, runsTheTask, protects);
        }
        FontCallOrder.assertNeverCalls(task, "awaitList", List.of(task + " run", "java/lang/Runnable run",
                finder + " pooledCascadeList", finder + " cascadeList"), protects);
        FontCallOrder.assertFirstCallBefore(finder, handOff, start, task + " <init>");
        FontCallOrder.assertFirstCallBefore(finder, handOff, task + " <init>", queue);
        FontCallOrder.assertFirstCallBefore(finder, handOff, queue, await);
        FontCallOrder.assertReturnsFirstCallOf(finder, handOff, await, "a virtual caller gets the list the platform"
                                               + " thread made");
        String locks = "java/util/concurrent/locks/LockSupport";
        String tasks = "java/util/concurrent/ConcurrentLinkedQueue";
        String add = tasks + " add";
        FontCallOrder.assertFirstCallBefore(finder, "queueCascadeListTask", add, locks + " unpark");
        FontCallOrder.assertCallFollowsUnconditionally(finder, "queueCascadeListTask", add, locks + " unpark",
                "every task queued wakes the thread, which may have parked with the queue empty");
        FontCallOrder.assertFirstCallBefore(finder, "startCascadeListWorker", "java/lang/Thread ofPlatform",
                                            "java/lang/Thread start");
        FontCallOrder.assertFirstCallBefore(finder, "runCascadeListTasks", locks + " park",
                                            "java/lang/Thread interrupted");
        String queuedByAdd = "every task enters the queue through the add that wakes the thread";
        List<String> otherInsertions = List.of(tasks + " offer", tasks + " addAll");
        for (String member : nest(finder)) {
            for (MethodModel method : model(member).methods()) {
                String signature = method.methodName().stringValue() + method.methodType().stringValue();
                FontCallOrder.assertNeverCalls(member, signature, otherInsertions, queuedByAdd);
            }
        }
        FontCallOrder.assertCalledOnlyIn(nest(finder), add, Set.of(finder + " queueCascadeListTask"), queuedByAdd);
    }

    /**
     * The reference a task holds to the font is taken by the queue helper, before the add, and is the task's once the
     * add returns: a handler that catches everything the add throws releases it, with no condition before the
     * release, and rethrows; the helper releases it nowhere else, and no handler covers the wake-up, so a task that
     * entered the queue keeps its reference whatever happens after the add. Nothing else in {@code MacFontFinder}
     * retains a font. The run-time half, a task the queue refuses, is
     * {@code MacFontReleaseTest.aTaskTheQueueRefusesGetsNoReferenceToTheFont}; an add that cannot allocate its node no
     * test can provoke.
     */
    @Test
    void aTaskOwnsItsReferenceToTheFontOnlyOnceItIsQueued() {
        String finder = MacFontBindings.MAC_FONT_FINDER;
        String queue = "queueCascadeListTask";
        String add = "java/util/concurrent/ConcurrentLinkedQueue add";
        String retain = CF + " CFRetain";
        String release = finder + " release";
        String protects = "a task that does not enter the queue leaves no reference to the font behind, and one that"
                + " did is released only by the task";
        FontCallOrder.assertFirstCallBefore(finder, queue, retain, add);
        FontCallOrder.assertCalledOnlyIn(nest(finder), retain, Set.of(finder + " " + queue), protects);
        FontCallOrder.assertHandlerReleasesUnconditionally(finder, queue, add, release, protects);
        FontCallOrder.assertCalledOnlyInHandler(finder, queue, release, "java/lang/Throwable", add, protects);
        FontCallOrder.assertNoHandlerCovers(finder, queue, "java/util/concurrent/locks/LockSupport unpark", protects);
    }

    /**
     * Only the pooled region reads the cascade list: {@code cascadeList} and the copy of the language codes are
     * called only by {@code pooledCascadeList}, {@code pooledCascadeList} only by {@code getCascadeList} and the task,
     * and CoreText's cascade list only by {@code cascadeList}, in every class of the font layer. A read through
     * reflection or a method handle, or through a second facade method over the same binding, is not seen.
     */
    @Test
    void onlyThePooledRegionReadsTheCascadeList() {
        String finder = MacFontBindings.MAC_FONT_FINDER;
        String cascadeList = finder + " cascadeList";
        String pooled = finder + " pooledCascadeList";
        String protects = "a virtual caller reads no cascade list on its own thread";
        FontCallOrder.assertCalledOnlyIn(nest(finder), cascadeList, Set.of(pooled), protects);
        FontCallOrder.assertCalledOnlyIn(nest(finder), CF + " CFLocaleCopyISOLanguageCodes", Set.of(pooled), protects);
        FontCallOrder.assertCalledOnlyIn(nest(finder), pooled,
                                         Set.of(finder + " getCascadeList", CASCADE_LIST_TASK + " run"), protects);
        FontCallOrder.assertCalledOnlyIn(fontPackageClasses(), CT + " CTFontCopyDefaultCascadeListForLanguages",
                                         Set.of(cascadeList), protects);
    }

    /**
     * A virtual caller whose cascade-list thread cannot be started gets that error, which no handler of the hand-off
     * catches, and the thread is recorded only once its start has returned, so the next virtual caller tries to start
     * it again. An empty list in its place would be kept for the life of a composite font as its fallbacks. No test
     * can make the start fail.
     */
    @Test
    void aVirtualCallerGetsWhatStartingTheThreadThrowsAndTheNextOneTriesAgain() {
        String finder = MacFontBindings.MAC_FONT_FINDER;
        FontCallOrder.assertNoHandlerCovers(finder, "cascadeListOnAPlatformThread", finder + " startCascadeListWorker",
                "a failed start reaches the caller, whose composite asks for its fallbacks again");
        FontCallOrder.assertStoredOnlyOnceCallReturns(finder, "startCascadeListWorker", finder + " cascadeListWorker",
                "java/lang/Thread start", "a thread that did not start is never recorded as the cascade-list thread");
    }

    /**
     * The wait reads an interrupt and stores it in the local that its setting again depends on before it clears it,
     * and sets it again on every way out: before its return, before each rethrow of what the task's read threw, and in
     * the handler of every error of the wait. No run-time test can provoke an error inside the clearing, which only
     * that order keeps from losing the interrupt, nor make the read throw.
     */
    @Test
    void theWaitRecordsAnInterruptBeforeClearingItAndSetsItAgainOnEveryExit() {
        String task = CASCADE_LIST_TASK;
        String read = "java/lang/Thread isInterrupted";
        String clear = "java/lang/Thread interrupted";
        String setAgain = "java/lang/Thread interrupt";
        FontCallOrder.assertFirstCallBefore(task, "awaitList", read, clear);
        FontCallOrder.assertReleasedOnEveryExit(task, "awaitList", "java/util/concurrent/locks/LockSupport park",
                                                setAgain,
                                                "an interrupt the wait cleared is set again however the wait ends");
        FontCallOrder.assertGuardStoredBetween(task, "awaitList", setAgain, read, clear,
                                               "an error inside the clearing cannot lose the interrupt");
    }

    /**
     * The task releases its reference to the font on every way out of the read, through each handler around the
     * read, inside a pool of its own, and wakes the caller on every way out of that release, through each handler
     * around it, after it marks itself done. The run-time halves are
     * {@code MacFontReleaseTest.theTaskHoldsAReferenceOfItsOwnToTheLentFontUntilItHasRun} and
     * {@code aTaskWhoseReleaseThrowsStillWakesItsCallerWithTheFailure}; a release that is the last one, which it is
     * only where the caller's wait failed, and what that release autoreleases, no test can provoke.
     */
    @Test
    void aTaskReleasesItsOwnReferenceToTheFontOnEveryPathBeforeItWakesTheCaller() {
        String task = CASCADE_LIST_TASK;
        String releaseFont = task + " releaseFont";
        String release = CF + " CFRelease";
        String unpark = "java/util/concurrent/locks/LockSupport unpark";
        FontCallOrder.assertReleasedOnEveryExit(task, "run", MacFontBindings.MAC_FONT_FINDER + " pooledCascadeList",
                                                releaseFont, "the font a caller lent is released once, after the read,"
                                                + " whether the read returns or throws");
        FontCallOrder.assertFirstCallBefore(task, "releaseFont", OBJC + " objc_autoreleasePoolPush", release);
        FontCallOrder.assertReleasedOnEveryExit(task, "releaseFont", release, OBJC + " objc_autoreleasePoolPop",
                                                "the release leaves nothing autoreleased on the platform thread");
        FontCallOrder.assertReleasedOnEveryExit(task, "run", releaseFont, unpark,
                                                "the caller is woken whether the release returns or throws");
        FontCallOrder.assertWrittenAfter(task, "run", task + " done", releaseFont,
                                         "a caller that still waits holds the font when the task's reference goes");
    }

    /**
     * The task catches everything its read and its release throw, and each of its handlers around them stores what
     * it caught for its caller and goes on to its return; attaching the release's failure to the read's throws
     * nothing either. So the task never throws, whatever fails. The run-time half, a release that throws after a read
     * that answered and after one that threw, is
     * {@code MacFontReleaseTest.aTaskWhoseReleaseThrowsStillWakesItsCallerWithTheFailure}.
     */
    @Test
    void aTaskKeepsWhatItsReadAndItsReleaseThrowAndNeverThrowsIt() {
        String task = CASCADE_LIST_TASK;
        String protects = "a failed read or release reaches its caller, which is woken either way";
        FontCallOrder.assertKeepsWhatItThrows(task, "run", MacFontBindings.MAC_FONT_FINDER + " pooledCascadeList",
                                              "failure", protects);
        FontCallOrder.assertKeepsWhatItThrows(task, "run", task + " releaseFont", "failure", protects);
        FontCallOrder.assertNeverThrowsFrom(task, "run", "java/lang/Throwable addSuppressed", protects);
    }

    /**
     * The loop of the cascade-list thread catches everything a task throws, throws none of it and never returns, so
     * no task ends the one thread every later virtual caller waits for. The run-time half is
     * {@code MacFontReleaseTest.aTaskThatThrowsDoesNotEndTheCascadeListThread}.
     */
    @Test
    void theCascadeListThreadOutlivesWhatATaskThrows() {
        String finder = MacFontBindings.MAC_FONT_FINDER;
        String protects = "every later virtual caller still has a thread to make its list";
        FontCallOrder.assertNeverThrowsFrom(finder, "runCascadeListTasks", "java/lang/Runnable run", protects);
        FontCallOrder.assertNeverReturns(finder, "runCascadeListTasks", protects);
    }

    /**
     * Callers may hold a font-layer monitor across the hand-off, so the methods the cascade-list thread runs take no
     * monitor and call no method of the classes whose monitors layout takes. The facade methods they call are not
     * read.
     */
    @Test
    void theCascadeListThreadTakesNoFontLayerMonitor() {
        String finder = MacFontBindings.MAC_FONT_FINDER;
        List<String> owners = List.of(PRISM_FONT_FACTORY, "com/sun/javafx/font/LogicalFont",
                "com/sun/javafx/font/FallbackResource", "com/sun/javafx/font/PrismCompositeFontResource",
                CORETEXT + "CTFactory");
        String protects = "the thread virtual callers wait on never waits for a monitor one of them holds";
        Map<String, List<String>> methods = new LinkedHashMap<>();
        methods.put(finder, List.of("runCascadeListTasks", "pooledCascadeList", "cascadeList", "addCascadeEntry",
                                    "release"));
        methods.put(CASCADE_LIST_TASK, List.of("run", "releaseFont"));
        methods.forEach((type, names) -> names.forEach(method -> {
            FontCallOrder.assertTakesNoMonitor(type, method, protects);
            FontCallOrder.assertNeverCalls(type, method, owners, protects);
        }));
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

    /**
     * The production classes of the macOS font layer: the coretext package, MacFontFinder with its nested classes,
     * DFontDecoder and PrismFontFactory.
     */
    static List<String> macFontClasses() {
        Path classes = FontGoldenRuntime.moduleDirectory().resolve("target/classes");
        List<String> types = new ArrayList<>(nest("com/sun/javafx/font/MacFontFinder"));
        types.addAll(List.of("com/sun/javafx/font/DFontDecoder", "com/sun/javafx/font/PrismFontFactory"));
        try (Stream<Path> files = Files.list(classes.resolve(CORETEXT))) {
            files.filter(file -> file.toString().endsWith(".class")).map(file -> CORETEXT + file.getFileName()
                    .toString().replace(".class", "")).sorted().forEach(types::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return types;
    }

    /** Every production class under {@code com/sun/javafx/font}, its subpackages and nested classes included. */
    static List<String> fontPackageClasses() {
        Path classes = FontGoldenRuntime.moduleDirectory().resolve("target/classes");
        try (Stream<Path> files = Files.walk(classes.resolve(FontClassFiles.FONT_PACKAGE))) {
            return files.filter(file -> file.toString().endsWith(".class"))
                        .map(file -> classes.relativize(file).toString().replace('\\', '/').replace(".class", ""))
                        .sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code type} and the members of its nest, as its class file lists them. */
    static List<String> nest(String type) {
        List<String> nest = new ArrayList<>(List.of(type));
        model(type).findAttribute(Attributes.nestMembers()).ifPresent(members -> members.nestMembers().stream()
                .map(ClassEntry::asInternalName).forEach(nest::add));
        return nest;
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

    /** The names of the methods of {@code type} that call {@code call}, {@code owner name}. */
    private static Set<String> callers(String type, String call) {
        Set<String> callers = new TreeSet<>();
        for (MethodModel method : model(type).methods()) {
            method.code().ifPresent(code -> code.forEach(element -> {
                if (element instanceof InvokeInstruction invoke
                        && call.equals(invoke.owner().asInternalName() + " " + invoke.name().stringValue())) {
                    callers.add(method.methodName().stringValue());
                }
            }));
        }
        return callers;
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
