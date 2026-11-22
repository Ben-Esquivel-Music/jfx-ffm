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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.opentest4j.AssertionFailedError;
import org.opentest4j.TestAbortedException;
import test.com.sun.javafx.font.MacFontReleaseVerdict.HeapProbe;
import test.com.sun.javafx.font.MacFontReleaseVerdict.Outcome;
import test.com.sun.javafx.font.MacFontReleaseVerdict.Verdict;
import test.com.sun.javafx.test.ParityGate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules of {@link MacFontReleaseVerdict}, on any OS, fed the figures and lines the macOS release checks recorded
 * on the owner's macOS 27.2.1, in the US-111 mutant runs there and on the macOS 15 runners of CI run 45022158121.
 */
public class MacFontReleaseVerdictTest {

    private static final double BOUND = MacFontReleaseTest.AUTORELEASED_BLOCKS_PER_CALL;

    /** The US-111 mutant runs recorded only the smallest round of these probes. */
    private static final String ROUNDS_NOT_RECORDED = "not recorded";

    /** macOS 27.2.1: a cascade list call on a platform thread. */
    private static final HeapProbe PLATFORM_SUBJECT_27 =
            new HeapProbe(0.0033333333333333335, "8.826666666666666;0.53;0.0033333333333333335");

    /** macOS 27.2.1: the region without a pool on a platform thread. */
    private static final HeapProbe PLATFORM_CONTROL_27 =
            new HeapProbe(229.57, "238.04333333333332;229.57;232.63666666666666");

    /** macOS 27.2.1: a cascade list call on a virtual thread. */
    private static final HeapProbe VIRTUAL_SUBJECT_27 =
            new HeapProbe(0.05, "2.0933333333333333;0.8833333333333333;0.05");

    /** macOS 27.2.1: the region without a pool on a virtual thread's carrier. */
    private static final HeapProbe VIRTUAL_CONTROL_27 =
            new HeapProbe(229.49666666666667, "238.07333333333332;229.49666666666667;231.79333333333332");

    /** macOS 27.2.1: a platform thread started and waited for per call, running nothing. */
    private static final HeapProbe HAND_OFF_27 =
            new HeapProbe(-0.2833333333333333, "-0.2;0.43333333333333335;-0.2833333333333333");

    /** US-111 mutant M10a on macOS 27.2.1, push and pop deleted: a cascade list call on a platform thread. */
    private static final HeapProbe M10A_PLATFORM_SUBJECT =
            new HeapProbe(229.72, "236.16666666666666;229.72;231.88333333333333");

    /** US-111 mutant M10a on macOS 27.2.1: the region without a pool on a platform thread. */
    private static final HeapProbe M10A_PLATFORM_CONTROL =
            new HeapProbe(229.51666666666668, "238.05333333333334;229.51666666666668;231.87666666666667");

    /** US-111 mutant M10a on macOS 27.2.1: a cascade list call on a virtual thread. */
    private static final HeapProbe M10A_VIRTUAL_SUBJECT = new HeapProbe(0.043333333333333335, ROUNDS_NOT_RECORDED);

    /** US-111 mutant M10a on macOS 27.2.1: the region without a pool on a virtual thread's carrier. */
    private static final HeapProbe M10A_VIRTUAL_CONTROL = new HeapProbe(229.87, ROUNDS_NOT_RECORDED);

    /** US-111 mutant M10a on macOS 27.2.1: the hand-off running nothing. */
    private static final HeapProbe M10A_HAND_OFF = new HeapProbe(-0.15333333333333332, ROUNDS_NOT_RECORDED);

    /** US-111 mutant M10b on macOS 27.2.1, a virtual caller runs the region inline: its cascade list call. */
    private static final HeapProbe M10B_VIRTUAL_SUBJECT =
            new HeapProbe(229.99333333333334, "231.79;229.99333333333334;231.60666666666665");

    /** US-111 mutant M10b on macOS 27.2.1: the region without a pool on a virtual thread's carrier. */
    private static final HeapProbe M10B_VIRTUAL_CONTROL =
            new HeapProbe(230.79333333333332, "233.87;230.90333333333334;230.79333333333332");

    /** US-111 mutant M10b on macOS 27.2.1: the hand-off running nothing. */
    private static final HeapProbe M10B_HAND_OFF = new HeapProbe(-1.28, "1.8566666666666667;2.033333333333333;-1.28");

    /** CI run 45022158121, macos-15 arm64 (macOS 15.8.2): the region without a pool on a platform thread. */
    private static final HeapProbe ARM64_PLATFORM_CONTROL_15 =
            new HeapProbe(6.013333333333334, "6.59;6.013333333333334;6.033333333333333");

    /** CI run 45022158121, macos-15-intel x64 (macOS 15.7.9): the region without a pool on a platform thread. */
    private static final HeapProbe X64_PLATFORM_CONTROL_15 = new HeapProbe(6.01, "6.58;6.02;6.01");

    /** CI run 45022158121, macos-15 arm64 (macOS 15.8.2): a cascade list call on a virtual thread. */
    private static final HeapProbe ARM64_VIRTUAL_SUBJECT_15 =
            new HeapProbe(10.673333333333334, "12.713333333333333;11.62;10.673333333333334");

    /** CI run 45022158121, macos-15 arm64 (macOS 15.8.2): the region without a pool on a virtual thread's carrier. */
    private static final HeapProbe ARM64_VIRTUAL_CONTROL_15 =
            new HeapProbe(6.013333333333334, "6.503333333333333;6.043333333333333;6.013333333333334");

    /** CI run 45022158121, macos-15-intel x64 (macOS 15.7.9): a cascade list call on a virtual thread. */
    private static final HeapProbe X64_VIRTUAL_SUBJECT_15 =
            new HeapProbe(11.26, "13.096666666666666;12.376666666666667;11.26");

    /** CI run 45022158121, macos-15-intel x64 (macOS 15.7.9): the region without a pool on a carrier. */
    private static final HeapProbe X64_VIRTUAL_CONTROL_15 = new HeapProbe(5.99, "6.523333333333333;6.04;5.99");

    private static final String UNSUPPORTED = "\t Font name not supported \".SF Devanagari Regular\".";
    private static final String MISSING_FACE_LOOKUP =
            "createFontFile by ref name=.SF Devanagari Regular filename=/System/Library/Fonts/SFIndia.ttc index=-1";
    private static final String NO_SLOT = "\tFallback font= .SF Devanagari Regular slot=-1";

    /** macOS 27.2.1, {@code release.fallbackFace.err}: the 11 lines of the three kinds the verdict reads. */
    private static final List<String> LINES_27 = List.of(UNSUPPORTED, MISSING_FACE_LOOKUP, NO_SLOT,
            UNSUPPORTED, NO_SLOT, UNSUPPORTED, NO_SLOT, UNSUPPORTED, NO_SLOT, UNSUPPORTED, NO_SLOT);

    /** macOS 27.2.1, {@code release.fallbackFace.txt}: the glyphs of the five layouts. */
    private static final List<String> GLYPHS_27 = Collections.nCopies(5, "7f;40a;f3;d5;40");

    /**
     * Mutant M12a on macOS 27.2.1, the name not remembered, {@code release.fallbackFace.err}: each of the five layouts
     * looks the face up again.
     */
    private static final List<String> LINES_M12A = List.of(UNSUPPORTED, MISSING_FACE_LOOKUP, NO_SLOT,
            UNSUPPORTED, MISSING_FACE_LOOKUP, NO_SLOT, UNSUPPORTED, MISSING_FACE_LOOKUP, NO_SLOT,
            UNSUPPORTED, MISSING_FACE_LOOKUP, NO_SLOT, UNSUPPORTED, MISSING_FACE_LOOKUP, NO_SLOT);

    /** macOS 27.2.1, {@code release.fallbackFace.err} lines 4, 5 and 2819: lines of other kinds. */
    private static final List<String> OTHER_LINES =
            List.of("Loading FontFactory com.sun.javafx.font.coretext.CTFactory", "Subpixel: enabled",
                    "End cascading list");

    /** Hypothetical: a macOS whose SFIndia.ttc has the face, which the first layout adds as slot 19. */
    private static final List<String> FOUND_FACE_LINES = List.of(UNSUPPORTED,
            "createFontFile by ref name=.SF Devanagari Regular filename=/System/Library/Fonts/SFIndia.ttc index=0",
            "\tFallback font= .SF Devanagari Regular slot=19", "\tFallback font= .SF Devanagari Regular slot=19",
            "\tFallback font= .SF Devanagari Regular slot=19", "\tFallback font= .SF Devanagari Regular slot=19",
            "\tFallback font= .SF Devanagari Regular slot=19");

    /** Hypothetical: the glyphs of five layouts in a fallback face of slot 19. */
    private static final List<String> FOUND_FACE_GLYPHS = Collections.nCopies(5, "13000051;1300013a;13000062");

    @Test
    void theFiguresOfMacOs27ProveBothDrains() {
        String platform = assertOutcome(Outcome.PROVED,
                MacFontReleaseVerdict.drainedOnAPlatformThread(PLATFORM_SUBJECT_27, PLATFORM_CONTROL_27, BOUND));
        assertEquals("a cascade list call leaves 0.0033333333333333335 blocks per call (rounds"
                + " 8.826666666666666;0.53;0.0033333333333333335), below 10.0, where the region without a pool leaves"
                + " 229.57 blocks per call (rounds 238.04333333333332;229.57;232.63666666666666)", platform);
        String virtual = assertOutcome(Outcome.PROVED, MacFontReleaseVerdict.drainedOnAVirtualThread(VIRTUAL_SUBJECT_27,
                VIRTUAL_CONTROL_27, HAND_OFF_27, BOUND));
        assertTrue(virtual.endsWith("; a platform thread started and waited for per call leaves -0.2833333333333333"
                + " blocks per call (rounds -0.2;0.43333333333333335;-0.2833333333333333)"), virtual);
    }

    @Test
    void aPlatformCallWithoutItsPoolIsTheDefect() {
        String message = assertOutcome(Outcome.REFUTED,
                MacFontReleaseVerdict.drainedOnAPlatformThread(M10A_PLATFORM_SUBJECT, M10A_PLATFORM_CONTROL, BOUND));
        assertTrue(message.startsWith("a cascade list call leaves 229.72 autoreleased blocks per call (rounds"
                + " 236.16666666666666;229.72;231.88333333333333) waiting for its thread to exit, bound 10.0;"),
                   message);
    }

    /**
     * A worker that pushes no pool is drained when its thread exits, so the virtual heap check passes M10a; the
     * class-file order checks of {@link MacFontBindingStructureTest} catch it.
     */
    @Test
    void aWorkerWithoutItsPoolLeavesTheVirtualCallerNothing() {
        assertOutcome(Outcome.PROVED, MacFontReleaseVerdict.drainedOnAVirtualThread(M10A_VIRTUAL_SUBJECT,
                M10A_VIRTUAL_CONTROL, M10A_HAND_OFF, BOUND));
    }

    @Test
    void aVirtualCallerThatRunsTheRegionInlineIsTheDefect() {
        String message = assertOutcome(Outcome.REFUTED, MacFontReleaseVerdict.drainedOnAVirtualThread(
                M10B_VIRTUAL_SUBJECT, M10B_VIRTUAL_CONTROL, M10B_HAND_OFF, BOUND));
        assertTrue(message.contains("leaves 229.99333333333334 autoreleased blocks per call"), message);
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0, 9.99, 10.0, 229.72})
    void theControlOfMacOs15CannotShowWhateverAPlatformCallLeaves(double perCall) {
        HeapProbe subject = hypothetical(perCall);
        for (HeapProbe control : List.of(ARM64_PLATFORM_CONTROL_15, X64_PLATFORM_CONTROL_15)) {
            String message = assertOutcome(Outcome.CANNOT_SHOW,
                    MacFontReleaseVerdict.drainedOnAPlatformThread(subject, control, BOUND));
            assertTrue(message.contains("the region without a pool leaves " + control.perCall()
                    + " blocks per call (rounds " + control.rounds() + "), not above 10.0"), message);
            assertTrue(message.contains("a cascade list call leaves " + perCall + " blocks per call"), message);
            assertEquals(perCall > control.perCall(), message.contains("more than the region without a pool"),
                         message);
        }
    }

    /** The words of the check this verdict replaced, as CI run 45022158121 printed them on macos-15-intel. */
    @Test
    void aControlThatCannotShowIsReportedInTheWordsOfTheCheck() {
        String message = MacFontReleaseVerdict.drainedOnAPlatformThread(hypothetical(0.0), X64_PLATFORM_CONTROL_15,
                BOUND).message();
        assertTrue(message.startsWith("the region without a pool leaves 6.01 blocks per call (rounds 6.58;6.02;6.01),"
                + " not above 10.0: the heap cannot show blocks a call leaves autoreleased"), message);
    }

    @ParameterizedTest
    @ValueSource(doubles = {-1.28, 0.0, 9.99})
    void aVirtualCallOfMacOs15AboveItsControlCannotShowTheDrain(double perCall) {
        HeapProbe handOff = hypothetical(perCall);
        assertVirtualCannotShow(ARM64_VIRTUAL_SUBJECT_15, ARM64_VIRTUAL_CONTROL_15, handOff);
        assertVirtualCannotShow(X64_VIRTUAL_SUBJECT_15, X64_VIRTUAL_CONTROL_15, handOff);
    }

    private static void assertVirtualCannotShow(HeapProbe subject, HeapProbe control, HeapProbe handOff) {
        String message = assertOutcome(Outcome.CANNOT_SHOW,
                MacFontReleaseVerdict.drainedOnAVirtualThread(subject, control, handOff, BOUND));
        assertTrue(message.contains("the region without a pool leaves " + control.perCall()
                + " blocks per call (rounds " + control.rounds() + "), not above 10.0"), message);
        assertTrue(message.contains("a cascade list call leaves " + subject.perCall() + " blocks per call (rounds "
                + subject.rounds() + "), more than the region without a pool leaves"), message);
        assertTrue(message.contains("a platform thread started and waited for per call leaves " + handOff.perCall()),
                   message);
    }

    @Test
    void aControlAtTheBoundCannotShow() {
        assertOutcome(Outcome.CANNOT_SHOW,
                MacFontReleaseVerdict.drainedOnAPlatformThread(hypothetical(0.05), hypothetical(10.0), BOUND));
        assertOutcome(Outcome.CANNOT_SHOW, MacFontReleaseVerdict.drainedOnAVirtualThread(hypothetical(0.05),
                hypothetical(10.0), HAND_OFF_27, BOUND));
    }

    @Test
    void aCallAtTheBoundIsTheDefect() {
        assertOutcome(Outcome.REFUTED,
                MacFontReleaseVerdict.drainedOnAPlatformThread(hypothetical(10.0), PLATFORM_CONTROL_27, BOUND));
        assertOutcome(Outcome.REFUTED, MacFontReleaseVerdict.drainedOnAVirtualThread(hypothetical(10.0),
                VIRTUAL_CONTROL_27, HAND_OFF_27, BOUND));
    }

    /** The hand-off is judged before the call, so a hand-off the heap cannot tell apart hides any call. */
    @ParameterizedTest
    @ValueSource(doubles = {0.05, 229.99})
    void aHandOffAtTheBoundCannotShowWhereTheControlCould(double perCall) {
        for (double handOff : new double[] {10.0, Double.NaN}) {
            String message = assertOutcome(Outcome.CANNOT_SHOW, MacFontReleaseVerdict.drainedOnAVirtualThread(
                    hypothetical(perCall), hypothetical(229.5), hypothetical(handOff), BOUND));
            assertTrue(message.startsWith("a platform thread started and waited for per call leaves " + handOff
                    + " blocks per call (rounds hypothetical), not below 10.0: the heap cannot tell the hand-off from"
                    + " blocks a call leaves autoreleased;"), message);
        }
    }

    @Test
    void aHandOffBelowTheBoundIsNotSubtractedFromTheCall() {
        String message = assertOutcome(Outcome.REFUTED, MacFontReleaseVerdict.drainedOnAVirtualThread(
                hypothetical(10.0), VIRTUAL_CONTROL_27, hypothetical(9.99), BOUND));
        assertTrue(message.startsWith("a cascade list call leaves 10.0 autoreleased blocks per call"), message);
    }

    @Test
    void aControlThatIsNotANumberCannotShow() {
        assertOutcome(Outcome.CANNOT_SHOW,
                MacFontReleaseVerdict.drainedOnAPlatformThread(hypothetical(0.05), hypothetical(Double.NaN), BOUND));
        assertOutcome(Outcome.CANNOT_SHOW, MacFontReleaseVerdict.drainedOnAVirtualThread(hypothetical(0.05),
                hypothetical(Double.NaN), HAND_OFF_27, BOUND));
    }

    @Test
    void aCallThatIsNotANumberIsTheDefect() {
        assertOutcome(Outcome.REFUTED,
                MacFontReleaseVerdict.drainedOnAPlatformThread(hypothetical(Double.NaN), PLATFORM_CONTROL_27, BOUND));
        assertOutcome(Outcome.REFUTED, MacFontReleaseVerdict.drainedOnAVirtualThread(hypothetical(Double.NaN),
                VIRTUAL_CONTROL_27, HAND_OFF_27, BOUND));
    }

    @Test
    void oneLookupOfTheFaceSfIndiaLacksProvesTheMemoryOnMacOs27() {
        List<String> stderr = new ArrayList<>(OTHER_LINES);
        stderr.addAll(LINES_27);
        String message = assertOutcome(Outcome.PROVED, MacFontReleaseVerdict.lookedUpOnce(GLYPHS_27, stderr));
        assertTrue(message.endsWith("; over 5 layouts the child printed [5x Font name not supported \".SF Devanagari"
                + " Regular\". | 1x createFontFile by ref name=.SF Devanagari Regular"
                + " filename=/System/Library/Fonts/SFIndia.ttc index=-1 | 5x Fallback font= .SF Devanagari Regular"
                + " slot=-1] and laid out the glyphs [7f;40a;f3;d5;40, 7f;40a;f3;d5;40, 7f;40a;f3;d5;40,"
                + " 7f;40a;f3;d5;40, 7f;40a;f3;d5;40]"), message);
    }

    @Test
    void fiveLookupsOverFiveLayoutsAreTheDefect() {
        String message = assertOutcome(Outcome.REFUTED, MacFontReleaseVerdict.lookedUpOnce(GLYPHS_27, LINES_M12A));
        assertTrue(message.startsWith(".SF Devanagari Regular was looked up in its font file 5 times over 5 layouts,"
                + " where once is enough;"), message);
    }

    @Test
    void layoutsThatLookNoFaceUpCannotShowTheMemory() {
        String message = assertOutcome(Outcome.CANNOT_SHOW,
                MacFontReleaseVerdict.lookedUpOnce(FOUND_FACE_GLYPHS, OTHER_LINES));
        assertEquals("no fallback face of the System font's Devanagari text is missing from its font file, so the"
                + " layouts do not reach the path; over 5 layouts the child printed no lookup, fallback or"
                + " unsupported-name line and laid out the glyphs " + FOUND_FACE_GLYPHS, message);
    }

    @Test
    void aFallbackFaceItsFontFileHasCannotShowTheMemory() {
        String message = assertOutcome(Outcome.CANNOT_SHOW,
                MacFontReleaseVerdict.lookedUpOnce(FOUND_FACE_GLYPHS, FOUND_FACE_LINES));
        assertTrue(message.contains("1x createFontFile by ref name=.SF Devanagari Regular"
                + " filename=/System/Library/Fonts/SFIndia.ttc index=0 | 5x Fallback font= .SF Devanagari Regular"
                + " slot=19]"), message);
        assertTrue(message.endsWith(" and laid out the glyphs " + FOUND_FACE_GLYPHS), message);
    }

    @Test
    void aLayoutWithOtherGlyphsIsTheDefect() {
        List<String> glyphs = new ArrayList<>(GLYPHS_27);
        glyphs.set(3, "7f;40a;f3;d5;41");
        String message = assertOutcome(Outcome.REFUTED, MacFontReleaseVerdict.lookedUpOnce(glyphs, LINES_27));
        assertTrue(message.startsWith("layout 3 gives other glyphs than the first;"), message);
    }

    @Test
    void aMissingFaceALayoutResolvesToASlotIsTheDefect() {
        List<String> stderr = LINES_27.subList(0, LINES_27.size() - 1);
        String message = assertOutcome(Outcome.REFUTED, MacFontReleaseVerdict.lookedUpOnce(GLYPHS_27, stderr));
        assertTrue(message.startsWith("each of the 5 layouts resolves .SF Devanagari Regular to no slot, but the child"
                + " printed that 4 times;"), message);
    }

    /** The test prints each verdict on one line of its standard error, which the Maven console shows line by line. */
    @Test
    void everyMessageIsOneLine() {
        List<Verdict> verdicts = List.of(
                MacFontReleaseVerdict.drainedOnAPlatformThread(PLATFORM_SUBJECT_27, PLATFORM_CONTROL_27, BOUND),
                MacFontReleaseVerdict.drainedOnAVirtualThread(ARM64_VIRTUAL_SUBJECT_15, ARM64_VIRTUAL_CONTROL_15,
                        HAND_OFF_27, BOUND),
                MacFontReleaseVerdict.lookedUpOnce(GLYPHS_27, LINES_27),
                MacFontReleaseVerdict.lookedUpOnce(GLYPHS_27, LINES_M12A),
                MacFontReleaseVerdict.lookedUpOnce(FOUND_FACE_GLYPHS, FOUND_FACE_LINES));
        for (Verdict verdict : verdicts) {
            assertTrue(verdict.message().chars().noneMatch(c -> c == '\n' || c == '\r' || c == '\t'),
                       verdict.message());
        }
    }

    @Test
    void aProofPassesInEitherMode() {
        Verdict proof = MacFontReleaseVerdict.drainedOnAPlatformThread(PLATFORM_SUBJECT_27, PLATFORM_CONTROL_27, BOUND);
        assertDoesNotThrow(() -> MacFontReleaseVerdict.require(MacFontReleaseVerdictTest.class, proof, false));
        assertDoesNotThrow(() -> MacFontReleaseVerdict.require(MacFontReleaseVerdictTest.class, proof, true));
    }

    @Test
    void aRefutationFailsInEitherMode() {
        Verdict refutation =
                MacFontReleaseVerdict.drainedOnAPlatformThread(M10A_PLATFORM_SUBJECT, M10A_PLATFORM_CONTROL, BOUND);
        for (boolean required : new boolean[] {false, true}) {
            AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                    () -> MacFontReleaseVerdict.require(MacFontReleaseVerdictTest.class, refutation, required));
            assertEquals(refutation.message(), failure.getMessage());
        }
    }

    @Test
    void aMachineThatCannotShowIsSkippedWithItsFigures() {
        Verdict cannotShow =
                MacFontReleaseVerdict.drainedOnAPlatformThread(hypothetical(0.0), X64_PLATFORM_CONTROL_15, BOUND);
        TestAbortedException aborted = assertThrows(TestAbortedException.class,
                () -> MacFontReleaseVerdict.require(MacFontReleaseVerdictTest.class, cannotShow, false));
        assertEquals(cannotShow.message(), aborted.getMessage());
    }

    @Test
    void aMachineThatCannotShowFailsWhenRequired() {
        Verdict cannotShow =
                MacFontReleaseVerdict.drainedOnAPlatformThread(hypothetical(0.0), X64_PLATFORM_CONTROL_15, BOUND);
        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> MacFontReleaseVerdict.require(MacFontReleaseVerdictTest.class, cannotShow, true));
        assertTrue(failure.getMessage().contains("-D" + ParityGate.REQUIRE_PROPERTY + "=true"), failure.getMessage());
        assertTrue(failure.getMessage().contains(cannotShow.message()), failure.getMessage());
    }

    private static String assertOutcome(Outcome expected, Verdict verdict) {
        assertEquals(expected, verdict.outcome(), verdict.message());
        return verdict.message();
    }

    /** A figure no machine recorded, chosen to probe a rule. */
    private static HeapProbe hypothetical(double perCall) {
        return new HeapProbe(perCall, "hypothetical");
    }
}
