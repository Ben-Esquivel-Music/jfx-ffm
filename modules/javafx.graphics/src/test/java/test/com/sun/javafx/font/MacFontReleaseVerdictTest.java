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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.opentest4j.AssertionFailedError;
import org.opentest4j.TestAbortedException;
import test.com.sun.javafx.font.MacFontReleaseVerdict.HeapProbe;
import test.com.sun.javafx.font.MacFontReleaseVerdict.LineRun;
import test.com.sun.javafx.font.MacFontReleaseVerdict.Outcome;
import test.com.sun.javafx.font.MacFontReleaseVerdict.PoolTokens;
import test.com.sun.javafx.font.MacFontReleaseVerdict.Verdict;
import test.com.sun.javafx.font.MacFontReleaseVerdict.VirtualHeap;
import test.com.sun.javafx.test.ParityGate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules of {@link MacFontReleaseVerdict}, on any OS. The figures named "macOS 27.2.1" or after a mutant are those
 * the macOS release checks printed on the owner's macOS 27.2.1, and those named after CI run 45041254324 those its
 * macOS 15 runners printed; every other figure is invented to probe a rule, and says so: {@link #hypothetical} heap
 * figures and the token moves called invented, which no macOS 15 host has printed yet. From its class file, it also
 * pins that the lent-font check of {@code MacFontReleaseTest} decides through its verdict.
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

    /** macOS 27.2.1, US-111's thread per virtual call: a cascade list call on a virtual thread. */
    private static final HeapProbe VIRTUAL_SUBJECT_27 =
            new HeapProbe(0.05, "2.0933333333333333;0.8833333333333333;0.05");

    /** macOS 27.2.1: the region without a pool on a virtual thread's carrier. */
    private static final HeapProbe VIRTUAL_CONTROL_27 =
            new HeapProbe(229.49666666666667, "238.07333333333332;229.49666666666667;231.79333333333332");

    /** macOS 27.2.1: a platform thread started and waited for per call, running nothing, US-111's hand-off. */
    private static final HeapProbe HAND_OFF_27 =
            new HeapProbe(-0.2833333333333333, "-0.2;0.43333333333333335;-0.2833333333333333");

    /** US-111 mutant M10a on macOS 27.2.1, push and pop deleted: a cascade list call on a platform thread. */
    private static final HeapProbe M10A_PLATFORM_SUBJECT =
            new HeapProbe(229.72, "236.16666666666666;229.72;231.88333333333333");

    /** US-111 mutant M10a on macOS 27.2.1: the region without a pool on a platform thread. */
    private static final HeapProbe M10A_PLATFORM_CONTROL =
            new HeapProbe(229.51666666666668, "238.05333333333334;229.51666666666668;231.87666666666667");

    /**
     * US-111 mutant M10a on macOS 27.2.1, with US-111's thread per virtual call, whose exit drained the pool the
     * mutant left: a cascade list call on a virtual thread.
     */
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

    /** CI run 45041254324, macos-15 arm64: a cascade list call on a platform thread. */
    private static final HeapProbe ARM64_PLATFORM_SUBJECT_15 =
            new HeapProbe(4.0633333333333335, "4.796666666666667;4.09;4.0633333333333335");

    /** CI run 45041254324, macos-15 arm64: the region without a pool on a platform thread. */
    private static final HeapProbe ARM64_PLATFORM_CONTROL_15 =
            new HeapProbe(5.906666666666666, "6.526666666666666;5.906666666666666;6.036666666666667");

    /** CI run 45041254324, macos-15-intel x64: a cascade list call on a platform thread. */
    private static final HeapProbe X64_PLATFORM_SUBJECT_15 =
            new HeapProbe(4.006666666666667, "4.283333333333333;4.07;4.006666666666667");

    /** CI run 45041254324, macos-15-intel x64: the region without a pool on a platform thread. */
    private static final HeapProbe X64_PLATFORM_CONTROL_15 =
            new HeapProbe(6.01, "6.586666666666667;6.01;6.013333333333334");

    /** CI run 45041254324, macos-15 arm64, US-111's thread per virtual call: a cascade list call. */
    private static final HeapProbe ARM64_VIRTUAL_SUBJECT_15 =
            new HeapProbe(10.726666666666667, "12.93;11.966666666666667;10.726666666666667");

    /** CI run 45041254324, macos-15 arm64: the region without a pool on a virtual thread's carrier. */
    private static final HeapProbe ARM64_VIRTUAL_CONTROL_15 =
            new HeapProbe(5.9366666666666665, "6.556666666666667;5.9366666666666665;5.993333333333333");

    /** CI run 45041254324, macos-15 arm64: a platform thread started and waited for per call, US-111's hand-off. */
    private static final HeapProbe ARM64_HAND_OFF_15 =
            new HeapProbe(1.3966666666666667, "1.7133333333333334;1.3966666666666667;1.49");

    /** CI run 45041254324, macos-15-intel x64, US-111's thread per virtual call: a cascade list call. */
    private static final HeapProbe X64_VIRTUAL_SUBJECT_15 =
            new HeapProbe(10.943333333333333, "12.993333333333334;12.07;10.943333333333333");

    /** CI run 45041254324, macos-15-intel x64: the region without a pool on a virtual thread's carrier. */
    private static final HeapProbe X64_VIRTUAL_CONTROL_15 =
            new HeapProbe(6.006666666666667, "6.49;6.02;6.006666666666667");

    /** CI run 45041254324, macos-15-intel x64: a platform thread started and waited for per call. */
    private static final HeapProbe X64_HAND_OFF_15 = new HeapProbe(1.66, "1.75;1.66;1.6633333333333333");

    /** macOS 27.2.1, the cascade-list thread: a virtual caller's cascade list call. */
    private static final HeapProbe WORKER_SUBJECT_27 = new HeapProbe(-0.03333333333333333,
            "4.203333333333333;1.6266666666666667;-0.03333333333333333");

    /** macOS 27.2.1, the cascade-list thread: the region without a pool on a virtual thread's carrier. */
    private static final HeapProbe WORKER_CONTROL_27 = new HeapProbe(229.57333333333332,
            "233.62333333333333;229.57333333333332;231.65666666666667");

    /** macOS 27.2.1, the cascade-list thread: a no-op task handed to it per call. */
    private static final HeapProbe WORKER_HAND_OFF_27 = new HeapProbe(0.016666666666666666,
            "0.5266666666666666;3.3666666666666667;0.016666666666666666");

    /** macOS 27.2.1, the cascade-list thread's run: a cascade list call on a platform thread. */
    private static final HeapProbe WORKER_PLATFORM_27 = new HeapProbe(0.006666666666666667,
            "2.566666666666667;0.77;0.006666666666666667");

    /** macOS 27.2.1, the cascade-list thread's run of the platform check: the call and the region without a pool. */
    private static final HeapProbe POOLED_SUBJECT_27 = new HeapProbe(0.0033333333333333335,
            "9.0;0.4666666666666667;0.0033333333333333335");
    private static final HeapProbe POOLED_CONTROL_27 = new HeapProbe(229.57666666666665,
            "238.99;229.57666666666665;231.78333333333333");

    /** macOS 27.2.1, both checks: 150 objects left by the region without a pool, none by the call or nothing. */
    private static final PoolTokens TOKENS_27 = new PoolTokens(Collections.nCopies(10, 0L),
            Collections.nCopies(10, 1200L), Collections.nCopies(10, 0L));

    /** Mutant M10a on macOS 27.2.1 with the cascade-list thread, push and pop deleted: the virtual check's heap. */
    private static final VirtualHeap M10A_WORKER_HEAP = new VirtualHeap(
            new HeapProbe(230.89, "233.36333333333334;230.89;233.33"),
            new HeapProbe(229.50333333333333, "232.68666666666667;229.50333333333333;231.60666666666665"),
            new HeapProbe(0.01, "0.5766666666666667;0.056666666666666664;0.01"),
            new HeapProbe(229.54, "232.19;229.54;231.51333333333332"));

    /** Mutant M10a on macOS 27.2.1, both checks: the call leaves as much autoreleased as the region without a pool. */
    private static final PoolTokens M10A_TOKENS = new PoolTokens(Collections.nCopies(10, 0L),
            Collections.nCopies(10, 1200L), Collections.nCopies(10, 1200L));

    /** No token read: the checks of CI run 45041254324 and of US-111 had no token instrument. */
    private static final PoolTokens NO_TOKENS = new PoolTokens(List.of(), List.of(), List.of());

    /** Invented: tokens that show, the region without a pool moving them by 6 objects, and a call that drains. */
    private static final PoolTokens CLEAN_TOKENS =
            new PoolTokens(Collections.nCopies(10, 0L), Collections.nCopies(10, 48L), Collections.nCopies(10, 0L));

    /** Invented: the same, with a call that left one object autoreleased in one cycle. */
    private static final PoolTokens MOVED_TOKENS = new PoolTokens(Collections.nCopies(10, 0L),
            Collections.nCopies(10, 48L), List.of(0L, 0L, 0L, 8L, 0L, 0L, 0L, 0L, 0L, 0L));

    private static final int CYCLES = 10;

    private static final String AHEM = MacFontReleaseChild.FACE;

    /**
     * macOS 27.2.1: the file of the fallback-face check, the child's copy of Ahem that it replaces by Helvetica.ttc,
     * with the module directory written as {@code <module>}.
     */
    private static final String FACE_FILE = "<module>/target/mac-font-golden/work/release.fallbackFace/fonts/face.ttf";

    private static final String AHEM_UNSUPPORTED = "\t Font name not supported \"Ahem\".";
    private static final String AHEM_NO_SLOT = "\tFallback font= Ahem slot=-1";

    /** macOS 27.2.1, {@code release.fallbackFace.err}: five layouts resolve Ahem to no slot, the first after lookup. */
    private static final List<String> LOOKED_UP_ONCE_27 = linesOfLayouts(FACE_FILE, 1, 5);

    /** Mutant M12a on macOS 27.2.1, the name not remembered: each of the five layouts looks the face up. */
    private static final List<String> LOOKED_UP_IN_EACH_LAYOUT_M12A = linesOfLayouts(FACE_FILE, 5, 5);

    /** Mutant M12f on macOS 27.2.1, the factory's answer negated: no layout looks the face up. */
    private static final List<String> NEVER_LOOKED_UP_M12F = linesOfLayouts(FACE_FILE, 0, 5);

    private static final List<String> NO_SLOTS = Collections.nCopies(5, "-1");

    /**
     * Recorded from CI run 45041254324 on both macOS 15 runners: what the fallback-face check of that tree printed,
     * the System font's Devanagari fallback looked up in its font file, which has it, and slot 35 over five layouts.
     * The runners printed each kind of line once with its count, in the order they first appeared; the lines are
     * those, in that order.
     */
    private static final List<String> ZITHER_LINES_15 = List.of(
            "\t Font name not supported \".Zither India Regular\".",
            "createFontFile by ref name=.Zither India Regular filename=/System/Library/Fonts/ZitherIndia.otf index=0",
            "\tFallback font= .Zither India Regular slot=35", "\tFallback font= .Zither India Regular slot=35",
            "\tFallback font= .Zither India Regular slot=35", "\tFallback font= .Zither India Regular slot=35",
            "\tFallback font= .Zither India Regular slot=35");

    /**
     * macOS 27.2.1, {@code release.runWithoutASlot.txt}: the runs of the lines of KA, A, KA and of a lone joiner in
     * Ahem, the run of A in the line's own font.
     */
    private static final List<LineRun> MIXED_RUNS_27 = List.of(
            new LineRun("Kohinoor Devanagari Regular", false, List.of(0x33)),
            new LineRun(AHEM, true, List.of(0x23)),
            new LineRun("Kohinoor Devanagari Regular", false, List.of(0x33)),
            new LineRun("Apple Color Emoji", false, List.of(0xffff)));

    /** macOS 27.2.1: what layout gave for those texts. */
    private static final List<Integer> LAID_OUT_27 = List.of(0, 0x23, 0, 0xffff);

    /** Mutant M8a1 on macOS 27.2.1, the run without a slot laid out with its glyph IDs, as before the fix. */
    private static final List<Integer> LAID_OUT_M8A1 = List.of(0x33, 0x23, 0x33, 0xffff);

    /** Invented: KA, A, KA, CoreText's run of each KA in a font of its choosing, the run of A in Ahem's own font. */
    private static final List<LineRun> MIXED_RUNS = List.of(
            new LineRun("Kohinoor Devanagari Regular", false, List.of(0x3f)),
            new LineRun(AHEM, true, List.of(0x24)),
            new LineRun("Kohinoor Devanagari Regular", false, List.of(0x3f)));

    @Test
    void theHeapOfMacOs27ProvesBothDrainsWithoutTokens() {
        String platform = assertOutcome(Outcome.PROVED, MacFontReleaseVerdict.drainedOnAPlatformThread(
                PLATFORM_SUBJECT_27, PLATFORM_CONTROL_27, NO_TOKENS, BOUND));
        assertEquals("a cascade list call drains what it autoreleased, shown by the heap, where the call leaves less"
                + " than 10.0 blocks per call and the region without a pool more; subject: a cascade list call leaves"
                + " 0.0033333333333333335 blocks per call (rounds 8.826666666666666;0.53;0.0033333333333333335);"
                + " control: the region without a pool leaves 229.57 blocks per call (rounds"
                + " 238.04333333333332;229.57;232.63666666666666); tokens.empty: [] moved in 0 of 0 cycles;"
                + " tokens.control: [] moved in 0 of 0 cycles; tokens.subject: [] moved in 0 of 0 cycles", platform);
        String virtual = assertOutcome(Outcome.PROVED, MacFontReleaseVerdict.drainedOnAVirtualThread(
                virtualHeap(VIRTUAL_SUBJECT_27, VIRTUAL_CONTROL_27, HAND_OFF_27), NO_TOKENS, 0, BOUND));
        assertTrue(virtual.startsWith("a cascade list call drains what it autoreleased, shown by the heap,"), virtual);
        assertTrue(virtual.contains("; handOff: a no-op task handed to the platform thread per call leaves"
                + " -0.2833333333333333 blocks per call (rounds -0.2;0.43333333333333335;-0.2833333333333333);"),
                   virtual);
    }

    @Test
    void theFiguresOfMacOs27ProveBothDrainsByBothInstruments() {
        String platform = assertOutcome(Outcome.PROVED, MacFontReleaseVerdict.drainedOnAPlatformThread(
                POOLED_SUBJECT_27, POOLED_CONTROL_27, TOKENS_27, BOUND));
        assertTrue(platform.startsWith("a cascade list call drains what it autoreleased, shown by the heap, where the"
                + " call leaves less than 10.0 blocks per call and the region without a pool more and by the pool"
                + " tokens, which no call moved and the region without a pool moved in every cycle;"), platform);
        String virtual = assertOutcome(Outcome.PROVED, MacFontReleaseVerdict.drainedOnAVirtualThread(
                new VirtualHeap(WORKER_SUBJECT_27, WORKER_CONTROL_27, WORKER_HAND_OFF_27, WORKER_PLATFORM_27),
                TOKENS_27, CYCLES, BOUND));
        assertTrue(virtual.endsWith("; tokens.control: [1200;1200;1200;1200;1200;1200;1200;1200;1200;1200] moved in 10"
                + " of 10 cycles; tokens.subject: [0;0;0;0;0;0;0;0;0;0] moved in 0 of 10 cycles; handedOff: 10 of 10"
                + " callers waited for the platform thread"), virtual);
    }

    @Test
    void theTokensProveADrainWhereTheHeapOfMacOs15CannotShowIt() {
        for (HeapProbe[] figures : platformFigures15()) {
            String message = assertOutcome(Outcome.PROVED,
                    MacFontReleaseVerdict.drainedOnAPlatformThread(figures[0], figures[1], CLEAN_TOKENS, BOUND));
            assertTrue(message.startsWith("a cascade list call drains what it autoreleased, shown by the pool tokens,"
                    + " which no call moved and the region without a pool moved in every cycle; subject:"), message);
        }
    }

    @Test
    void theHeapAndTheTokensTogetherProveADrain() {
        String message = assertOutcome(Outcome.PROVED, MacFontReleaseVerdict.drainedOnAPlatformThread(
                PLATFORM_SUBJECT_27, PLATFORM_CONTROL_27, CLEAN_TOKENS, BOUND));
        assertTrue(message.startsWith("a cascade list call drains what it autoreleased, shown by the heap, where the"
                + " call leaves less than 10.0 blocks per call and the region without a pool more and by the pool"
                + " tokens,"), message);
    }

    /**
     * CI run 45041254324's platform checks, which read no tokens, walked through the rule: the region without a pool
     * leaves about 6 blocks per call there, below the bound, so neither instrument shows the drain.
     */
    @Test
    void theRecordedPlatformFiguresOfMacOs15CannotShowTheDrainWithoutTokens() {
        for (HeapProbe[] figures : platformFigures15()) {
            String message = assertOutcome(Outcome.CANNOT_SHOW,
                    MacFontReleaseVerdict.drainedOnAPlatformThread(figures[0], figures[1], NO_TOKENS, BOUND));
            assertTrue(message.startsWith("the region without a pool leaves " + figures[1].blocks() + ", not above"
                    + " 10.0: the heap cannot show blocks a call leaves autoreleased, and no pool token was read;"),
                       message);
        }
    }

    @Test
    void aCallThatMovesTheTokensIsTheDefectWhereTheHeapCannotShowIt() {
        String message = assertOutcome(Outcome.REFUTED, MacFontReleaseVerdict.drainedOnAPlatformThread(
                ARM64_PLATFORM_SUBJECT_15, ARM64_PLATFORM_CONTROL_15, MOVED_TOKENS, BOUND));
        assertTrue(message.startsWith("a cascade list call leaves objects autoreleased on the thread that made its"
                + " list: it moved the next pool token, which a region that autoreleases nothing does not;"), message);
        assertTrue(message.endsWith("tokens.subject: [0;0;0;8;0;0;0;0;0;0] moved in 1 of 10 cycles"), message);
        assertOutcome(Outcome.REFUTED, MacFontReleaseVerdict.drainedOnAVirtualThread(
                virtualHeap(hypothetical(4.0), ARM64_VIRTUAL_CONTROL_15, hypothetical(0.0)), MOVED_TOKENS, CYCLES,
                BOUND));
    }

    /** Invented: tokens that also move around a region that autoreleases nothing tell nothing. */
    @Test
    void tokensThatMoveAroundNothingCannotShow() {
        PoolTokens opaque = new PoolTokens(List.of(0L, 0L, 16L, 0L, 0L, 0L, 0L, 0L, 0L, 0L),
                Collections.nCopies(10, 48L), Collections.nCopies(10, 16L));
        String message = assertOutcome(Outcome.CANNOT_SHOW, MacFontReleaseVerdict.drainedOnAPlatformThread(
                X64_PLATFORM_SUBJECT_15, X64_PLATFORM_CONTROL_15, opaque, BOUND));
        assertTrue(message.contains(", and the pool tokens cannot show them either: a region that autoreleases"
                + " nothing moved them in 1 of 10 cycles;"), message);
    }

    /** Invented: a region without a pool that leaves the tokens where they were in one cycle tells nothing either. */
    @Test
    void aControlThatDoesNotMoveTheTokensInEveryCycleCannotShow() {
        PoolTokens partial = new PoolTokens(Collections.nCopies(10, 0L),
                List.of(48L, 48L, 48L, 48L, 0L, 48L, 48L, 48L, 48L, 48L), Collections.nCopies(10, 8L));
        String message = assertOutcome(Outcome.CANNOT_SHOW, MacFontReleaseVerdict.drainedOnAVirtualThread(
                virtualHeap(hypothetical(4.0), X64_VIRTUAL_CONTROL_15, hypothetical(0.0)), partial, CYCLES, BOUND));
        assertTrue(message.contains(", and the pool tokens cannot show them either: the region without a pool moved"
                + " them in only 9 of 10 cycles;"), message);
    }

    @Test
    void aPlatformCallWithoutItsPoolIsTheDefect() {
        String message = assertOutcome(Outcome.REFUTED, MacFontReleaseVerdict.drainedOnAPlatformThread(
                M10A_PLATFORM_SUBJECT, M10A_PLATFORM_CONTROL, NO_TOKENS, BOUND));
        assertTrue(message.startsWith("a cascade list call leaves 229.72 autoreleased blocks per call (rounds"
                + " 236.16666666666666;229.72;231.88333333333333) waiting for its thread to exit, bound 10.0;"),
                   message);
    }

    /**
     * US-111's thread per virtual call drained, when it exited, the pool a worker without one left, so its heap
     * proved M10a; the cascade-list thread never exits, so its heap and its tokens refute it, and its tokens do so
     * with invented heap figures below the bound too.
     */
    @Test
    void aWorkerWithoutItsPoolIsTheDefect() {
        assertOutcome(Outcome.PROVED, MacFontReleaseVerdict.drainedOnAVirtualThread(
                virtualHeap(M10A_VIRTUAL_SUBJECT, M10A_VIRTUAL_CONTROL, M10A_HAND_OFF), NO_TOKENS, 0, BOUND));
        String message = assertOutcome(Outcome.REFUTED,
                MacFontReleaseVerdict.drainedOnAVirtualThread(M10A_WORKER_HEAP, M10A_TOKENS, CYCLES, BOUND));
        assertTrue(message.startsWith("a cascade list call leaves 230.89 heap blocks per call (rounds"
                + " 233.36333333333334;230.89;233.33), at or above the bound of 10.0 fixed for every host;"), message);
        String tokens = assertOutcome(Outcome.REFUTED, MacFontReleaseVerdict.drainedOnAVirtualThread(
                virtualHeap(hypothetical(4.0), hypothetical(6.0), hypothetical(0.0)), M10A_TOKENS, CYCLES, BOUND));
        assertTrue(tokens.startsWith("a cascade list call leaves objects autoreleased on the thread that made its"
                + " list:"), tokens);
        assertOutcome(Outcome.REFUTED, MacFontReleaseVerdict.drainedOnAPlatformThread(hypothetical(4.0),
                hypothetical(6.0), M10A_TOKENS, BOUND));
    }

    @Test
    void aVirtualCallerThatRunsTheRegionInlineIsTheDefect() {
        String message = assertOutcome(Outcome.REFUTED, MacFontReleaseVerdict.drainedOnAVirtualThread(
                virtualHeap(M10B_VIRTUAL_SUBJECT, M10B_VIRTUAL_CONTROL, M10B_HAND_OFF), NO_TOKENS, 0, BOUND));
        assertTrue(message.contains("leaves 229.99333333333334 heap blocks per call"), message);
    }

    /**
     * macOS 27.2.1's recorded heap figures of US-111's thread per virtual call, with the invented clean tokens and an
     * invented 9 of 10 callers seen waiting for the platform thread: refuted, whatever the instruments show.
     */
    @Test
    void aVirtualCallerNotSeenWaitingIsTheDefect() {
        String message = assertOutcome(Outcome.REFUTED, MacFontReleaseVerdict.drainedOnAVirtualThread(
                virtualHeap(VIRTUAL_SUBJECT_27, VIRTUAL_CONTROL_27, HAND_OFF_27), CLEAN_TOKENS, CYCLES - 1, BOUND));
        assertTrue(message.startsWith("a virtual caller did not wait for the platform thread to make its list;"),
                   message);
        assertTrue(message.endsWith("; handedOff: 9 of 10 callers waited for the platform thread"), message);
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0, 9.99, 10.0, 229.72})
    void theControlOfMacOs15CannotShowWhateverAPlatformCallLeaves(double perCall) {
        HeapProbe subject = hypothetical(perCall);
        for (HeapProbe control : List.of(ARM64_PLATFORM_CONTROL_15, X64_PLATFORM_CONTROL_15)) {
            String message = assertOutcome(Outcome.CANNOT_SHOW,
                    MacFontReleaseVerdict.drainedOnAPlatformThread(subject, control, NO_TOKENS, BOUND));
            assertTrue(message.contains("the region without a pool leaves " + control.perCall()
                    + " blocks per call (rounds " + control.rounds() + "), not above 10.0"), message);
            assertTrue(message.contains("a cascade list call leaves " + perCall + " blocks per call"), message);
            assertEquals(perCall > control.perCall(), message.contains("more than the region without a pool"),
                         message);
        }
    }

    /** The words of the check, as CI run 45041254324 printed them on macos-15-intel. */
    @Test
    void aControlThatCannotShowIsReportedInTheWordsOfTheCheck() {
        String message = MacFontReleaseVerdict.drainedOnAPlatformThread(X64_PLATFORM_SUBJECT_15,
                X64_PLATFORM_CONTROL_15, NO_TOKENS, BOUND).message();
        assertTrue(message.startsWith("the region without a pool leaves 6.01 blocks per call (rounds"
                + " 6.586666666666667;6.01;6.013333333333334), not above 10.0: the heap cannot show blocks a call"
                + " leaves autoreleased"), message);
    }

    /**
     * CI run 45041254324's virtual calls, made by US-111's thread per call, left more than the bound on macOS 15:
     * requirement 14's defect, refuted on every host, whatever the hand-off and the tokens.
     */
    @Test
    void aVirtualCallOfMacOs15ThatStartsAThreadPerCallIsTheDefect() {
        assertVirtualRefuted(ARM64_VIRTUAL_SUBJECT_15, ARM64_VIRTUAL_CONTROL_15, ARM64_HAND_OFF_15);
        assertVirtualRefuted(X64_VIRTUAL_SUBJECT_15, X64_VIRTUAL_CONTROL_15, X64_HAND_OFF_15);
    }

    private static void assertVirtualRefuted(HeapProbe subject, HeapProbe control, HeapProbe handOff) {
        for (PoolTokens tokens : List.of(NO_TOKENS, CLEAN_TOKENS)) {
            String message = assertOutcome(Outcome.REFUTED, MacFontReleaseVerdict.drainedOnAVirtualThread(
                    virtualHeap(subject, control, handOff), tokens, tokens.cycles(), BOUND));
            assertTrue(message.startsWith("a cascade list call leaves " + subject.perCall() + " heap blocks per call"
                    + " (rounds " + subject.rounds() + "), at or above the bound of 10.0 fixed for every host;"),
                       message);
        }
    }

    /**
     * The rule on macOS 15 for a virtual call through the cascade-list thread, which no host has measured yet: invented
     * call and hand-off figures below the bound, the call's at the recorded platform call's, with CI run 45041254324's
     * controls. Without tokens that show, the heap cannot decide; with them the drain is proved. Neither refutes.
     */
    @Test
    void aVirtualCallOfMacOs15BelowTheBoundIsProvedByTheTokensAlone() {
        HeapProbe[][] runners = {
            {ARM64_PLATFORM_SUBJECT_15, ARM64_VIRTUAL_CONTROL_15},
            {X64_PLATFORM_SUBJECT_15, X64_VIRTUAL_CONTROL_15}
        };
        for (HeapProbe[] figures : runners) {
            VirtualHeap heap = virtualHeap(hypothetical(figures[0].perCall()), figures[1], hypothetical(0.0));
            String cannot = assertOutcome(Outcome.CANNOT_SHOW,
                    MacFontReleaseVerdict.drainedOnAVirtualThread(heap, NO_TOKENS, 0, BOUND));
            assertTrue(cannot.startsWith("the region without a pool leaves " + figures[1].blocks() + ", not above 10.0:"
                    + " the heap cannot show blocks a call leaves autoreleased, and no pool token was read;"), cannot);
            String proved = assertOutcome(Outcome.PROVED,
                    MacFontReleaseVerdict.drainedOnAVirtualThread(heap, CLEAN_TOKENS, CYCLES, BOUND));
            assertTrue(proved.startsWith("a cascade list call drains what it autoreleased, shown by the pool tokens,"),
                       proved);
        }
    }

    @Test
    void aControlAtTheBoundCannotShow() {
        assertOutcome(Outcome.CANNOT_SHOW, MacFontReleaseVerdict.drainedOnAPlatformThread(hypothetical(0.05),
                hypothetical(10.0), NO_TOKENS, BOUND));
        assertOutcome(Outcome.CANNOT_SHOW, MacFontReleaseVerdict.drainedOnAVirtualThread(
                virtualHeap(hypothetical(0.05), hypothetical(10.0), HAND_OFF_27), NO_TOKENS, 0, BOUND));
    }

    @Test
    void aCallAtTheBoundIsTheDefect() {
        assertOutcome(Outcome.REFUTED, MacFontReleaseVerdict.drainedOnAPlatformThread(hypothetical(10.0),
                PLATFORM_CONTROL_27, NO_TOKENS, BOUND));
        assertOutcome(Outcome.REFUTED, MacFontReleaseVerdict.drainedOnAVirtualThread(
                virtualHeap(hypothetical(10.0), VIRTUAL_CONTROL_27, HAND_OFF_27), NO_TOKENS, 0, BOUND));
    }

    /** A hand-off the heap cannot tell apart hides a call below the bound from the heap. */
    @Test
    void aHandOffAtTheBoundCannotShowWhereTheControlCould() {
        for (double handOff : new double[] {10.0, Double.NaN}) {
            String message = assertOutcome(Outcome.CANNOT_SHOW, MacFontReleaseVerdict.drainedOnAVirtualThread(
                    virtualHeap(hypothetical(0.05), hypothetical(229.5), hypothetical(handOff)), NO_TOKENS, 0, BOUND));
            assertTrue(message.startsWith("a no-op task handed to the platform thread per call leaves " + handOff
                    + " blocks per call (rounds hypothetical), not below 10.0: the heap cannot tell the hand-off from"
                    + " blocks a call leaves autoreleased, and no pool token was read;"), message);
        }
    }

    /** It no longer hides a call at or above the bound, which is refuted on every host. */
    @Test
    void aHandOffAtTheBoundDoesNotHideACallAtTheBound() {
        for (double handOff : new double[] {10.0, Double.NaN}) {
            String message = assertOutcome(Outcome.REFUTED, MacFontReleaseVerdict.drainedOnAVirtualThread(
                    virtualHeap(hypothetical(229.99), hypothetical(229.5), hypothetical(handOff)), NO_TOKENS, 0,
                    BOUND));
            assertTrue(message.startsWith("a cascade list call leaves 229.99 heap blocks per call"), message);
        }
    }

    @Test
    void aHandOffBelowTheBoundIsNotSubtractedFromTheCall() {
        String message = assertOutcome(Outcome.REFUTED, MacFontReleaseVerdict.drainedOnAVirtualThread(
                virtualHeap(hypothetical(10.0), VIRTUAL_CONTROL_27, hypothetical(9.99)), NO_TOKENS, 0, BOUND));
        assertTrue(message.startsWith("a cascade list call leaves 10.0 heap blocks per call"), message);
    }

    @Test
    void aControlThatIsNotANumberCannotShow() {
        assertOutcome(Outcome.CANNOT_SHOW, MacFontReleaseVerdict.drainedOnAPlatformThread(hypothetical(0.05),
                hypothetical(Double.NaN), NO_TOKENS, BOUND));
        assertOutcome(Outcome.CANNOT_SHOW, MacFontReleaseVerdict.drainedOnAVirtualThread(
                virtualHeap(hypothetical(0.05), hypothetical(Double.NaN), HAND_OFF_27), NO_TOKENS, 0, BOUND));
    }

    @Test
    void aCallThatIsNotANumberIsTheDefect() {
        assertOutcome(Outcome.REFUTED, MacFontReleaseVerdict.drainedOnAPlatformThread(hypothetical(Double.NaN),
                PLATFORM_CONTROL_27, NO_TOKENS, BOUND));
        assertOutcome(Outcome.REFUTED, MacFontReleaseVerdict.drainedOnAVirtualThread(
                virtualHeap(hypothetical(Double.NaN), VIRTUAL_CONTROL_27, HAND_OFF_27), NO_TOKENS, 0, BOUND));
    }

    /** The keys of the virtual check's line, each followed by its figure, as the next CI run's console shows them. */
    @Test
    void theVirtualLineNamesEveryFigure() {
        String message = MacFontReleaseVerdict.drainedOnAVirtualThread(
                virtualHeap(hypothetical(1.0), hypothetical(2.0), hypothetical(3.0)), CLEAN_TOKENS, CYCLES, BOUND)
                .message();
        assertTrue(message.endsWith("; subject: a cascade list call leaves 1.0 blocks per call (rounds hypothetical);"
                + " control: the region without a pool leaves 2.0 blocks per call (rounds hypothetical); handOff: a"
                + " no-op task handed to the platform thread per call leaves 3.0 blocks per call (rounds hypothetical);"
                + " platform: a cascade list call on a platform thread leaves 1.0 blocks per call (rounds"
                + " hypothetical); tokens.empty: [0;0;0;0;0;0;0;0;0;0] moved in 0 of 10 cycles; tokens.control:"
                + " [48;48;48;48;48;48;48;48;48;48] moved in 10 of 10 cycles; tokens.subject: [0;0;0;0;0;0;0;0;0;0]"
                + " moved in 0 of 10 cycles; handedOff: 10 of 10 callers waited for the platform thread"), message);
    }

    /** Invented moves, in bytes, separated by semicolons as the child writes them; an empty or missing key is none. */
    @Test
    void theTokenMovesAreReadAsTheChildWritesThem() {
        PoolTokens tokens = PoolTokens.of(Map.of("tokens.empty.moves", "0;0", "tokens.control.moves", "48;-4048",
                                                 "tokens.subject.moves", "0;0"));
        assertEquals(new PoolTokens(List.of(0L, 0L), List.of(48L, -4048L), List.of(0L, 0L)), tokens);
        assertEquals(2, tokens.cycles());
        assertEquals(NO_TOKENS, PoolTokens.of(Map.of("tokens.empty.moves", "", "tokens.control.moves", "")));
    }

    /** macOS 27.2.1, with line 4 of {@code release.fallbackFace.err}, a line of another kind, before them. */
    @Test
    void oneLookupOverFiveLayoutsProvesTheMemoryOnMacOs27() {
        List<String> stderr = new ArrayList<>(List.of("Loading FontFactory com.sun.javafx.font.coretext.CTFactory"));
        stderr.addAll(LOOKED_UP_ONCE_27);
        String message = assertOutcome(Outcome.PROVED,
                MacFontReleaseVerdict.lookedUpOnce(AHEM, FACE_FILE, List.of(), NO_SLOTS, stderr));
        assertEquals("Ahem was looked up once with filename=" + FACE_FILE + " over 5 layouts, each of which"
                + " resolves it to no slot; over 5 layouts the child answered the slots [-1, -1, -1, -1, -1] and"
                + " printed [6x Font name not supported \"Ahem\". | 1x createFontFile by ref name=Ahem filename="
                + FACE_FILE + " index=-1 | 5x Fallback font= Ahem slot=-1]", message);
    }

    /**
     * Predicted, not yet recorded: the macOS 15 runners' lines, which no CI run has printed for this check yet, with
     * the file in the runner's checkout.
     */
    @Test
    void oneLookupOverFiveLayoutsIsPredictedToProveTheMemoryOnMacOs15() {
        String file = "/Users/runner/work/jfx-ffm/jfx-ffm/modules/javafx.graphics/target/mac-font-golden/work/"
                + "release.fallbackFace/fonts/face.ttf";
        assertOutcome(Outcome.PROVED, MacFontReleaseVerdict.lookedUpOnce(AHEM, file, List.of(), NO_SLOTS,
                linesOfLayouts(file, 1, 5)));
    }

    @Test
    void aLookupInEveryLayoutOfMutantM12aIsTheDefect() {
        String message = assertOutcome(Outcome.REFUTED, MacFontReleaseVerdict.lookedUpOnce(AHEM, FACE_FILE,
                List.of(), NO_SLOTS, LOOKED_UP_IN_EACH_LAYOUT_M12A));
        assertTrue(message.startsWith("Ahem was looked up with filename=" + FACE_FILE + " 5 times over 5 layouts,"
                + " where it must be looked up once;"), message);
    }

    @Test
    void noLookupOfMutantM12fIsTheDefect() {
        String message = assertOutcome(Outcome.REFUTED, MacFontReleaseVerdict.lookedUpOnce(AHEM, FACE_FILE,
                List.of(), NO_SLOTS, NEVER_LOOKED_UP_M12F));
        assertTrue(message.startsWith("Ahem was looked up with filename=" + FACE_FILE + " 0 times over 5 layouts,"),
                   message);
    }

    /** Invented: a lookup of the face that read no file, as for a font that reports none, is not one in its file. */
    @Test
    void aLookupWithAnotherFileDoesNotCount() {
        String message = assertOutcome(Outcome.REFUTED, MacFontReleaseVerdict.lookedUpOnce(AHEM, FACE_FILE,
                List.of(), NO_SLOTS, linesOfLayouts("null", 1, 5)));
        assertTrue(message.startsWith("Ahem was looked up with filename=" + FACE_FILE + " 0 times over 5 layouts,"),
                   message);
    }

    /** Invented: the macOS 27.2.1 lines, with layout 2 answering slot 40 where the lookup found no face. */
    @Test
    void aLayoutThatResolvesTheFaceToASlotIsTheDefect() {
        List<String> answers = new ArrayList<>(NO_SLOTS);
        answers.set(2, "40");
        String message = assertOutcome(Outcome.REFUTED,
                MacFontReleaseVerdict.lookedUpOnce(AHEM, FACE_FILE, List.of(), answers, LOOKED_UP_ONCE_27));
        assertTrue(message.startsWith("layout 2 resolves Ahem to slot 40, where its lookup with filename=" + FACE_FILE
                + " finds no face;"), message);
    }

    /** Invented: the macOS 27.2.1 lines without their last, so one of the 5 layouts prints no unresolved fallback. */
    @Test
    void aLayoutThatPrintsNoUnresolvedFallbackIsTheDefect() {
        List<String> stderr = LOOKED_UP_ONCE_27.subList(0, LOOKED_UP_ONCE_27.size() - 1);
        String message = assertOutcome(Outcome.REFUTED,
                MacFontReleaseVerdict.lookedUpOnce(AHEM, FACE_FILE, List.of(), NO_SLOTS, stderr));
        assertTrue(message.startsWith("each of the 5 layouts resolves Ahem to no slot, but the child printed that 4"
                + " times;"), message);
    }

    /** Invented: a host where a font named Ahem is installed, whatever the child printed. */
    @Test
    void anUnmetPreconditionCannotShowTheMemory() {
        String message = assertOutcome(Outcome.CANNOT_SHOW, MacFontReleaseVerdict.lookedUpOnce(AHEM, FACE_FILE,
                List.of("slot.before = 40, expected -1: a font named Ahem is installed"), NO_SLOTS,
                linesOfLayouts(FACE_FILE, 0, 5)));
        assertTrue(message.startsWith("this host cannot show the memory: slot.before = 40, expected -1: a font named"
                + " Ahem is installed; over 5 layouts"), message);
    }

    /**
     * The recorded macOS 15 lines, under the rule as it is now: the face was found in its font file there, which the
     * check measures apart from the lookups as its precondition that the file lacks the face, so the rule cannot show
     * the memory with them; without that precondition the slots they answer refute it, and nothing proves it.
     */
    @Test
    void theRecordedMacOs15LinesOfAFaceItsFileHasCannotShowTheMemory() {
        String face = ".Zither India Regular";
        String file = "/System/Library/Fonts/ZitherIndia.otf";
        List<String> answers = Collections.nCopies(5, "35");
        List<String> unmet = List.of("face.missing = false, expected true: the factory finds " + face + " in " + file);
        assertOutcome(Outcome.CANNOT_SHOW, MacFontReleaseVerdict.lookedUpOnce(face, file, unmet, answers,
                ZITHER_LINES_15));
        String message = assertOutcome(Outcome.REFUTED, MacFontReleaseVerdict.lookedUpOnce(face, file, List.of(),
                answers, ZITHER_LINES_15));
        assertTrue(message.startsWith("layout 0 resolves .Zither India Regular to slot 35, where its lookup with"
                + " filename=" + file + " finds no face;"), message);
    }

    @Test
    void theMissingGlyphInEveryRunOfAnotherFontProvesTheFixOnMacOs27() {
        String message = assertOutcome(Outcome.PROVED, MacFontReleaseVerdict.missingGlyphsWhereNoSlot(AHEM,
                MIXED_RUNS_27, LAID_OUT_27));
        assertEquals("each glyph of a run of another font is the missing glyph or stays removed, and each run of the"
                + " strike's font keeps its glyph IDs; the strike's font is Ahem, the line's runs are"
                + " [Kohinoor Devanagari Regular 33 | Ahem (the line's font) 23 | Kohinoor Devanagari Regular 33 |"
                + " Apple Color Emoji ffff] and layout gave 0;23;0;ffff", message);
    }

    @Test
    void anotherFontsGlyphIdsLaidOutByMutantM8a1AreTheDefect() {
        String message = assertOutcome(Outcome.REFUTED, MacFontReleaseVerdict.missingGlyphsWhereNoSlot(AHEM,
                MIXED_RUNS_27, LAID_OUT_M8A1));
        assertTrue(message.startsWith("glyph 0, of the run in Kohinoor Devanagari Regular, is 33 where it must be 0;"),
                   message);
    }

    /** Invented: a glyph CoreText removed turned into the missing glyph. */
    @Test
    void aRemovedGlyphTurnedIntoTheMissingGlyphIsTheDefect() {
        List<LineRun> runs = List.of(new LineRun("Kohinoor Devanagari Regular", false, List.of(0x3f, 0xffff)),
                                     new LineRun(AHEM, true, List.of(0x24)));
        assertOutcome(Outcome.PROVED, MacFontReleaseVerdict.missingGlyphsWhereNoSlot(AHEM, runs,
                List.of(0, 0xffff, 0x24)));
        String message = assertOutcome(Outcome.REFUTED, MacFontReleaseVerdict.missingGlyphsWhereNoSlot(AHEM, runs,
                List.of(0, 0, 0x24)));
        assertTrue(message.startsWith("glyph 1, of the run in Kohinoor Devanagari Regular, is 0 where it must be"
                + " ffff;"), message);
    }

    /** Invented: the run of the strike's own font laid out with the missing glyph. */
    @Test
    void theStrikesOwnRunWithoutItsGlyphsIsTheDefect() {
        String message = assertOutcome(Outcome.REFUTED, MacFontReleaseVerdict.missingGlyphsWhereNoSlot(AHEM,
                MIXED_RUNS, List.of(0, 0, 0)));
        assertTrue(message.startsWith("glyph 1, of the run in Ahem, is 0 where it must be 24;"), message);
    }

    /** Invented: a run in the line's own font that CoreText names otherwise is the strike's, as layout takes it. */
    @Test
    void aRunInTheLinesFontIsTheStrikesWhateverItsName() {
        List<LineRun> runs = List.of(new LineRun("Kohinoor Devanagari Regular", false, List.of(0x3f)),
                                     new LineRun(AHEM, true, List.of(0x24)),
                                     new LineRun("Ahem Renamed", true, List.of(0x25)));
        assertOutcome(Outcome.PROVED, MacFontReleaseVerdict.missingGlyphsWhereNoSlot(AHEM, runs,
                List.of(0, 0x24, 0x25)));
    }

    /** Invented: a layout that gives two glyphs where the line has three. */
    @Test
    void aLayoutOfAnotherGlyphCountIsTheDefect() {
        String message = assertOutcome(Outcome.REFUTED, MacFontReleaseVerdict.missingGlyphsWhereNoSlot(AHEM,
                MIXED_RUNS, List.of(0, 0x24)));
        assertTrue(message.startsWith("layout gave 2 glyphs where the line has 3;"), message);
    }

    /** Invented: a line CoreText laid out in Ahem alone, or with a substitute whose glyphs are 0 or removed. */
    @Test
    void aLineWithNoGlyphOfAnotherFontToLoseCannotShowTheFix() {
        List<LineRun> ahemAlone = List.of(new LineRun(AHEM, true, List.of(0x24)));
        assertOutcome(Outcome.CANNOT_SHOW, MacFontReleaseVerdict.missingGlyphsWhereNoSlot(AHEM, ahemAlone,
                List.of(0x24)));
        List<LineRun> nothingToLose = List.of(new LineRun("LastResort", false, List.of(0, 0xffff)),
                                              new LineRun(AHEM, true, List.of(0x24)));
        String message = assertOutcome(Outcome.CANNOT_SHOW, MacFontReleaseVerdict.missingGlyphsWhereNoSlot(AHEM,
                nothingToLose, List.of(0, 0xffff, 0x24)));
        assertTrue(message.startsWith("no run of another font has a glyph other than 0 or ffff,"), message);
    }

    /** Invented: a host whose run in Ahem is another font object than the line's, or that has no run in Ahem. */
    @Test
    void aLineWhoseRunInTheStrikesFontIsNotTheLinesFontCannotShowTheFix() {
        List<LineRun> copied = List.of(new LineRun("Kohinoor Devanagari Regular", false, List.of(0x3f)),
                                       new LineRun(AHEM, false, List.of(0x24)));
        String message = assertOutcome(Outcome.CANNOT_SHOW, MacFontReleaseVerdict.missingGlyphsWhereNoSlot(AHEM,
                copied, List.of(0x3f, 0x24)));
        assertTrue(message.startsWith("CoreText gave a run in Ahem another font object than the line's,"), message);
        List<LineRun> noAhem = List.of(new LineRun("Kohinoor Devanagari Regular", false, List.of(0x3f)));
        assertOutcome(Outcome.CANNOT_SHOW, MacFontReleaseVerdict.missingGlyphsWhereNoSlot(AHEM, noAhem, List.of(0)));
    }

    /** Invented: placeholder figures, a precondition the host does not meet and a problem of the fix. */
    @Test
    void anUnmetPreconditionCannotShowWhateverTheProblems() {
        String message = assertOutcome(Outcome.CANNOT_SHOW, MacFontReleaseVerdict.checked("the proof", "a=1",
                List.of("b = 2, expected 1: the host"), List.of("c = 3, expected 1: the fix")));
        assertEquals("this host cannot show it: b = 2, expected 1: the host; a=1", message);
    }

    /** Invented: placeholder figures, two problems of the fix on a host that meets every precondition. */
    @Test
    void aProblemRefutesTheFixWhereThePreconditionsHold() {
        String message = assertOutcome(Outcome.REFUTED, MacFontReleaseVerdict.checked("the proof", "a=1", List.of(),
                List.of("c = 3, expected 1: the fix", "d = 4, expected 1: the fix")));
        assertEquals("c = 3, expected 1: the fix; d = 4, expected 1: the fix; a=1", message);
    }

    /** Invented: a placeholder figure, on a host that meets every precondition and shows no problem. */
    @Test
    void noProblemWhereThePreconditionsHoldProvesTheFix() {
        String message = assertOutcome(Outcome.PROVED,
                MacFontReleaseVerdict.checked("the proof", "a=1", List.of(), List.of()));
        assertEquals("the proof; a=1", message);
    }

    /**
     * macOS 27.2.1, {@code release.cascadeListLentFont.txt}: each of the two baselines and the caller's call, the
     * task's reference and its release included, moved the lent font's retain count by 0.
     */
    @Test
    void aTaskThatReleasesItsReferenceOnMacOs27ProvesTheRelease() {
        String message = assertOutcome(Outcome.PROVED, MacFontReleaseVerdict.lentFontReleased(0, 0, 0));
        assertEquals("the task releases its reference to the lent font once it has run: the caller's call, from before"
                + " its task took its reference to after the task had run, moved the font's retain count by 0, as each"
                + " of the two lists before it did; query.delta: 0; query.again.delta: 0; retain.afterRun: 0", message);
    }

    /**
     * Mutant C-M6 on macOS 27.2.1, the task's release deleted: the baselines moved the count by 0 and the caller's
     * call, over which the task kept its reference, by 1. Invented: a task that released one reference more than it
     * took, the caller's call moving the count by -1.
     */
    @Test
    void aTaskThatKeepsOrOverReleasesItsReferenceIsTheDefect() {
        String kept = assertOutcome(Outcome.REFUTED, MacFontReleaseVerdict.lentFontReleased(0, 0, 1));
        assertEquals("the task kept 1 reference of its own to the lent font once it had run: the caller's call, from"
                + " before its task took its reference to after the task had run, moved the font's retain count by 1,"
                + " where each of the two lists before it moved it by 0; query.delta: 0; query.again.delta: 0;"
                + " retain.afterRun: 1", kept);
        String released = assertOutcome(Outcome.REFUTED, MacFontReleaseVerdict.lentFontReleased(0, 0, -1));
        assertTrue(released.startsWith("the task released 1 reference to the lent font beyond the one it took: the"
                + " caller's call, from before its task took its reference to after the task had run, moved the font's"
                + " retain count by -1,"), released);
    }

    /**
     * Invented: a host where CoreText's own references move the lent font's retain count by 1 over each list on the
     * cascade-list thread, the two baselines and the task's list alike.
     */
    @Test
    void aTaskThatReleasesItsReferenceOverASettledNonZeroBaselineProvesTheRelease() {
        String message = assertOutcome(Outcome.PROVED, MacFontReleaseVerdict.lentFontReleased(1, 1, 1));
        assertEquals("the task releases its reference to the lent font once it has run: the caller's call, from before"
                + " its task took its reference to after the task had run, moved the font's retain count by 1, as each"
                + " of the two lists before it did; query.delta: 1; query.again.delta: 1; retain.afterRun: 1", message);
    }

    /**
     * Invented: each of the two baselines moving the lent font's retain count by 1, and the caller's call moving it by
     * 2 where the task kept its reference, or by 0 where it released one more than it took.
     */
    @Test
    void aTaskThatKeepsOrOverReleasesItsReferenceOverASettledNonZeroBaselineIsTheDefect() {
        String kept = assertOutcome(Outcome.REFUTED, MacFontReleaseVerdict.lentFontReleased(1, 1, 2));
        assertEquals("the task kept 1 reference of its own to the lent font once it had run: the caller's call, from"
                + " before its task took its reference to after the task had run, moved the font's retain count by 2,"
                + " where each of the two lists before it moved it by 1; query.delta: 1; query.again.delta: 1;"
                + " retain.afterRun: 2", kept);
        String released = assertOutcome(Outcome.REFUTED, MacFontReleaseVerdict.lentFontReleased(1, 1, 0));
        assertTrue(released.startsWith("the task released 1 reference to the lent font beyond the one it took: the"
                + " caller's call, from before its task took its reference to after the task had run, moved the font's"
                + " retain count by 0,"), released);
    }

    /**
     * Invented: a CoreText cache that settles one list later on the cascade-list thread, the first baseline moving the
     * count by 1 and the second by 0, or a count that moves again at the second list, the first baseline moving it by
     * 0 and the second by 1, whatever the caller's call moved it by.
     */
    @Test
    void baselinesThatDoNotSettleCannotShowTheRelease() {
        long[][] unsettledBaselines = {{1, 0}, {0, 1}};
        for (long[] baselines : unsettledBaselines) {
            for (long afterRun : new long[] {0, 1}) {
                String message = assertOutcome(Outcome.CANNOT_SHOW,
                        MacFontReleaseVerdict.lentFontReleased(baselines[0], baselines[1], afterRun));
                assertTrue(message.startsWith("the count CoreText's own references leave does not settle on this host:"
                        + " two lists in a row on the cascade-list thread moved the lent font's retain count by "
                        + baselines[0] + " and by " + baselines[1] + ","), message);
            }
        }
    }

    /**
     * The lent-font check decides through its verdict, which prints its line and, under
     * {@code -Djfx.parity.require=true}, fails where the machine cannot show the defect. An assumption would skip it
     * whatever that property says, so no method of {@code MacFontReleaseTest} makes one.
     */
    @Test
    void theLentFontCheckDecidesThroughItsVerdictAndAssumesNothing() throws IOException {
        MacFontNullInputVerdictTest.assertDecidesThroughItsVerdictAndAssumesNothing("MacFontReleaseTest",
                "theTaskHoldsAReferenceOfItsOwnToTheLentFontUntilItHasRun", "MacFontReleaseVerdict",
                "lentFontReleased");
    }

    /**
     * The test prints each verdict on one line of its standard error, which the Maven console shows line by line.
     * Invented: the unmet precondition of the Zither lines, the placeholder figures handed to {@code checked} and the
     * lent font's baselines that do not settle.
     */
    @Test
    void everyMessageIsOneLine() {
        List<Verdict> verdicts = List.of(
                MacFontReleaseVerdict.drainedOnAPlatformThread(PLATFORM_SUBJECT_27, PLATFORM_CONTROL_27, CLEAN_TOKENS,
                        BOUND),
                MacFontReleaseVerdict.drainedOnAPlatformThread(ARM64_PLATFORM_SUBJECT_15, ARM64_PLATFORM_CONTROL_15,
                        NO_TOKENS, BOUND),
                MacFontReleaseVerdict.drainedOnAVirtualThread(virtualHeap(ARM64_VIRTUAL_SUBJECT_15,
                        ARM64_VIRTUAL_CONTROL_15, ARM64_HAND_OFF_15), MOVED_TOKENS, CYCLES, BOUND),
                MacFontReleaseVerdict.lookedUpOnce(AHEM, FACE_FILE, List.of(), NO_SLOTS, LOOKED_UP_ONCE_27),
                MacFontReleaseVerdict.lookedUpOnce(AHEM, FACE_FILE, List.of(), NO_SLOTS,
                        LOOKED_UP_IN_EACH_LAYOUT_M12A),
                MacFontReleaseVerdict.lookedUpOnce(".Zither India Regular", "/System/Library/Fonts/ZitherIndia.otf",
                        List.of("face.missing = false"), Collections.nCopies(5, "35"), ZITHER_LINES_15),
                MacFontReleaseVerdict.missingGlyphsWhereNoSlot(AHEM, MIXED_RUNS_27, LAID_OUT_27),
                MacFontReleaseVerdict.missingGlyphsWhereNoSlot(AHEM, MIXED_RUNS_27, LAID_OUT_M8A1),
                MacFontReleaseVerdict.checked("the proof", "a=1", List.of("b"), List.of("c")),
                MacFontReleaseVerdict.lentFontReleased(0, 0, 0),
                MacFontReleaseVerdict.lentFontReleased(0, 0, 1),
                MacFontReleaseVerdict.lentFontReleased(1, 0, 0));
        for (Verdict verdict : verdicts) {
            assertTrue(verdict.message().chars().noneMatch(c -> c == '\n' || c == '\r' || c == '\t'),
                       verdict.message());
        }
    }

    @Test
    void aProofPassesInEitherMode() {
        Verdict proof = MacFontReleaseVerdict.drainedOnAPlatformThread(PLATFORM_SUBJECT_27, PLATFORM_CONTROL_27,
                                                                       NO_TOKENS, BOUND);
        assertDoesNotThrow(() -> MacFontReleaseVerdict.require(MacFontReleaseVerdictTest.class, proof, false));
        assertDoesNotThrow(() -> MacFontReleaseVerdict.require(MacFontReleaseVerdictTest.class, proof, true));
    }

    @Test
    void aRefutationFailsInEitherMode() {
        Verdict refutation = MacFontReleaseVerdict.drainedOnAPlatformThread(M10A_PLATFORM_SUBJECT,
                                                                            M10A_PLATFORM_CONTROL, NO_TOKENS, BOUND);
        for (boolean required : new boolean[] {false, true}) {
            AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                    () -> MacFontReleaseVerdict.require(MacFontReleaseVerdictTest.class, refutation, required));
            assertEquals(refutation.message(), failure.getMessage());
        }
    }

    @Test
    void aMachineThatCannotShowIsSkippedWithItsFigures() {
        Verdict cannotShow = MacFontReleaseVerdict.drainedOnAPlatformThread(X64_PLATFORM_SUBJECT_15,
                                                                            X64_PLATFORM_CONTROL_15, NO_TOKENS, BOUND);
        TestAbortedException aborted = assertThrows(TestAbortedException.class,
                () -> MacFontReleaseVerdict.require(MacFontReleaseVerdictTest.class, cannotShow, false));
        assertEquals(cannotShow.message(), aborted.getMessage());
    }

    @Test
    void aMachineThatCannotShowFailsWhenRequired() {
        Verdict cannotShow = MacFontReleaseVerdict.drainedOnAPlatformThread(X64_PLATFORM_SUBJECT_15,
                                                                            X64_PLATFORM_CONTROL_15, NO_TOKENS, BOUND);
        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> MacFontReleaseVerdict.require(MacFontReleaseVerdictTest.class, cannotShow, true));
        assertTrue(failure.getMessage().contains("-D" + ParityGate.REQUIRE_PROPERTY + "=true"), failure.getMessage());
        assertTrue(failure.getMessage().contains(cannotShow.message()), failure.getMessage());
    }

    private static String assertOutcome(Outcome expected, Verdict verdict) {
        assertEquals(expected, verdict.outcome(), verdict.message());
        return verdict.message();
    }

    /** CI run 45041254324's platform checks: the call and the region without a pool, of each runner. */
    private static List<HeapProbe[]> platformFigures15() {
        return List.of(new HeapProbe[] {ARM64_PLATFORM_SUBJECT_15, ARM64_PLATFORM_CONTROL_15},
                       new HeapProbe[] {X64_PLATFORM_SUBJECT_15, X64_PLATFORM_CONTROL_15});
    }

    /** The virtual check's heap probes, with the subject's figure as the platform probe's, which no rule reads. */
    private static VirtualHeap virtualHeap(HeapProbe subject, HeapProbe control, HeapProbe handOff) {
        return new VirtualHeap(subject, control, handOff, subject);
    }

    /** A figure no machine recorded, chosen to probe a rule. */
    private static HeapProbe hypothetical(double perCall) {
        return new HeapProbe(perCall, "hypothetical");
    }

    /**
     * What the child prints, with {@code prism.debugfonts}, for the System font's slot for Ahem and then over
     * {@code layouts} layouts of Ahem in it, the first {@code lookups} of which look the face up in {@code file} and
     * find none there: on macOS 27.2.1 it printed these lines and no other of their kinds, with 1 lookup, with 5
     * under mutant M12a and with none under M12f.
     */
    private static List<String> linesOfLayouts(String file, int lookups, int layouts) {
        List<String> lines = new ArrayList<>(List.of(AHEM_UNSUPPORTED));
        for (int i = 0; i < layouts; i++) {
            lines.add(AHEM_UNSUPPORTED);
            if (i < lookups) {
                lines.add(MacFontReleaseVerdict.FONT_FILE_LOOKUP + AHEM + " filename=" + file
                        + MacFontReleaseVerdict.NO_FACE);
            }
            lines.add(AHEM_NO_SLOT);
        }
        return lines;
    }
}
