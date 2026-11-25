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
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.InvokeInstruction;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import test.com.sun.javafx.font.MacFontReleaseVerdict.Outcome;
import test.com.sun.javafx.font.MacFontReleaseVerdict.Verdict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rule of {@link MacFontNullInputTest#contextVerdict}, on any OS, fed the unified-log lines the US-111 runs
 * recorded on the owner's macOS 27.2.1 and invented ones, labelled so; and, from its class file, that the log check
 * decides through that verdict.
 */
public class MacFontNullInputVerdictTest {

    private static final String SMOOTHING = "CGContextSetAllowsFontSmoothing";
    private static final String POSITIONING = "CGContextSetAllowsFontSubpixelPositioning";
    private static final String QUANTIZATION = "CGContextSetAllowsFontSubpixelQuantization";

    /** One line of each control. */
    private static final Map<String, Integer> CONTROLS =
            Map.of(MacFontNullInputTest.CONTROL_SETTER, 1, MacFontNullInputTest.CONTROL_CALL, 1);

    private static final String PACKAGE = "test/com/sun/javafx/font/";
    private static final String LOG_CHECK = "aContextCoreGraphicsDidNotMakeIsZeroAndNothingIsSetOnIt";
    private static final String ASSUMPTIONS = "org/junit/jupiter/api/Assumptions";

    /** Recorded: the final tree of US-111, its confirmation run, pid 20604; the two controls' lines alone. */
    @Test
    void theControlsAloneProveNothingIsSet() {
        Verdict verdict = MacFontNullInputTest.contextVerdict("20604", CONTROLS);
        assertEquals(Outcome.PROVED, verdict.outcome(), verdict.message());
    }

    /** Recorded: US-111's mutant M6, createContext's guard deleted, pid 18432; the subject's four setter lines. */
    @Test
    void theFourSetterLinesOfM6AreTheDefect() {
        Verdict verdict = MacFontNullInputTest.contextVerdict("18432",
                Map.of(MacFontNullInputTest.CONTROL_SETTER, 2, SMOOTHING, 1, POSITIONING, 1, QUANTIZATION, 1,
                       MacFontNullInputTest.CONTROL_CALL, 1));
        assertEquals(Outcome.REFUTED, verdict.outcome(), verdict.message());
        assertTrue(verdict.message().startsWith("nothing is set on a context"), verdict.message());
    }

    /** Invented: no line at all, as an account that cannot read the unified log sees it. */
    @Test
    void aLogThisAccountCannotReadCannotShow() {
        Verdict verdict = MacFontNullInputTest.contextVerdict("1", Map.of());
        assertEquals(Outcome.CANNOT_SHOW, verdict.outcome(), verdict.message());
        assertTrue(verdict.message().contains(MacFontNullInputTest.CONTROL_CALL), verdict.message());
    }

    /** Invented: the fill colour's line alone, as where the setters no longer log a call on NULL. */
    @Test
    void setterLinesThatNoLongerLogCannotShow() {
        Verdict verdict = MacFontNullInputTest.contextVerdict("1", Map.of(MacFontNullInputTest.CONTROL_CALL, 1));
        assertEquals(Outcome.CANNOT_SHOW, verdict.outcome(), verdict.message());
        assertTrue(verdict.message().contains(MacFontNullInputTest.CONTROL_SETTER), verdict.message());
    }

    /** Invented: a setter line of the subject and no fill-colour line. */
    @Test
    void aSetterLineBeyondTheControlIsTheDefectEvenWithoutTheFillColour() {
        Verdict verdict = MacFontNullInputTest.contextVerdict("1", Map.of(SMOOTHING, 1));
        assertEquals(Outcome.REFUTED, verdict.outcome(), verdict.message());
    }

    @Test
    void everyMessageIsOneLine() {
        List<Map<String, Integer>> logs = List.of(Map.of(), Map.of(MacFontNullInputTest.CONTROL_CALL, 1),
                                                  Map.of(SMOOTHING, 1), CONTROLS);
        for (Map<String, Integer> log : logs) {
            String message = MacFontNullInputTest.contextVerdict("1", log).message();
            assertFalse(message.contains("\n") || message.contains("\r"), message);
        }
    }

    /**
     * The log check decides through its verdict, which prints its line and, under {@code -Djfx.parity.require=true},
     * fails where the machine cannot show the defect. An assumption would skip it whatever that property says, so no
     * method of {@code MacFontNullInputTest} makes one.
     */
    @Test
    void theLogCheckDecidesThroughItsVerdictAndAssumesNothing() throws IOException {
        assertDecidesThroughItsVerdictAndAssumesNothing("MacFontNullInputTest", LOG_CHECK, "MacFontNullInputTest",
                                                        "contextVerdict");
    }

    /**
     * Fails unless {@code check} of {@code test}, a test class of this package, calls {@code verdict} of
     * {@code verdictClass}, a class of this package, and after that {@code MacFontReleaseVerdict.decide}, and no
     * method of {@code test} calls {@code Assumptions}. It reads the class file of {@code test} and loads no class
     * from it, so it runs on any OS.
     */
    static void assertDecidesThroughItsVerdictAndAssumesNothing(String test, String check, String verdictClass,
                                                                String verdict) throws IOException {
        ClassModel model;
        try (InputStream in = MacFontNullInputVerdictTest.class.getResourceAsStream(test + ".class")) {
            assertNotNull(in, "no class file of " + test + " beside this test");
            model = ClassFile.of().parse(in.readAllBytes());
        }
        List<String> assumptions = new ArrayList<>();
        List<String> checkCalls = new ArrayList<>();
        for (MethodModel method : model.methods()) {
            String name = method.methodName().stringValue();
            List<CodeElement> code = method.code().map(CodeModel::elementList).orElse(List.of());
            for (CodeElement element : code) {
                if (element instanceof InvokeInstruction invoke) {
                    String call = invoke.owner().asInternalName() + " " + invoke.name().stringValue();
                    if (invoke.owner().asInternalName().equals(ASSUMPTIONS)) {
                        assumptions.add(name + " calls " + call);
                    }
                    if (name.equals(check)) {
                        checkCalls.add(call);
                    }
                }
            }
        }
        assertEquals(List.of(), assumptions, test + " may make no assumption");
        int verdictCall = checkCalls.indexOf(PACKAGE + verdictClass + " " + verdict);
        int decide = checkCalls.indexOf(PACKAGE + "MacFontReleaseVerdict decide");
        assertTrue(verdictCall >= 0, check + " never calls " + verdict + ": " + checkCalls);
        assertTrue(decide > verdictCall, check + " does not decide through MacFontReleaseVerdict.decide after "
                + verdict + ": " + checkCalls);
    }
}
