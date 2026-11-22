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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import test.com.sun.javafx.test.ParityGate;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * The verdicts of the {@link MacFontReleaseTest} checks whose instrument depends on the machine, decided from the
 * figures and lines the child wrote and nothing else, so that the rules are tested on every OS. A check proves the
 * fix, refutes it, or finds that the machine cannot show the defect; the last is a skip, or a failure under
 * {@code -Djfx.parity.require=true}.
 */
final class MacFontReleaseVerdict {

    /** How {@code CTFactory.createFontFile} reports, with {@code prism.debugfonts}, each lookup in a font file. */
    static final String FONT_FILE_LOOKUP = "createFontFile by ref name=";
    static final String NO_FACE = " index=-1";

    /** How layout reports, with {@code prism.debugfonts}, each fallback font it resolves, and to which slot. */
    static final String FALLBACK_FONT = "\tFallback font= ";

    /** How the System font reports, with {@code prism.debugfonts}, a fallback name it has no slot for. */
    static final String UNSUPPORTED_NAME = "\t Font name not supported ";

    enum Outcome {
        PROVED, REFUTED, CANNOT_SHOW
    }

    record Verdict(Outcome outcome, String message) {
    }

    /** The smallest heap blocks per call of a probe's rounds, and every round, as the child wrote them. */
    record HeapProbe(double perCall, String rounds) {

        static HeapProbe of(Map<String, String> values, String probe) {
            return new HeapProbe(Double.parseDouble(values.get(probe + ".perCall")), values.get(probe + ".rounds"));
        }

        String blocks() {
            return perCall + " blocks per call (rounds " + rounds + ")";
        }
    }

    private MacFontReleaseVerdict() {
    }

    /** Passes a proof, fails a refutation, and skips where the machine cannot show the defect unless required. */
    static void require(Class<?> test, Verdict verdict, boolean required) {
        ParityGate.requireOracle(test, verdict.outcome() != Outcome.CANNOT_SHOW, required, verdict::message);
        if (verdict.outcome() == Outcome.REFUTED) {
            fail(verdict.message());
        }
    }

    /**
     * Whether a cascade list call on a platform thread drains what it autoreleased: its heap blocks per call must stay
     * below {@code bound}, which the region without a pool must exceed for the heap to show the defect.
     */
    static Verdict drainedOnAPlatformThread(HeapProbe subject, HeapProbe control, double bound) {
        if (!(control.perCall() > bound)) {
            return controlCannotShow(subject, control, bound, "");
        }
        return subjectDecides(subject, control, bound, "");
    }

    /**
     * The same on a virtual thread, where the hand-off to a platform thread, measured running nothing, must also stay
     * below {@code bound}, or the heap cannot tell it from blocks a call leaves autoreleased. It is never subtracted.
     */
    static Verdict drainedOnAVirtualThread(HeapProbe subject, HeapProbe control, HeapProbe handOff, double bound) {
        String alsoHandOff = "; " + handOffLeaves(handOff);
        if (!(control.perCall() > bound)) {
            return controlCannotShow(subject, control, bound, alsoHandOff);
        }
        if (!(handOff.perCall() < bound)) {
            return new Verdict(Outcome.CANNOT_SHOW, handOffLeaves(handOff) + ", not below " + bound
                    + ": the heap cannot tell the hand-off from blocks a call leaves autoreleased; "
                    + controlLeaves(control) + "; " + subjectLeaves(subject));
        }
        return subjectDecides(subject, control, bound, alsoHandOff);
    }

    private static Verdict controlCannotShow(HeapProbe subject, HeapProbe control, double bound, String alsoHandOff) {
        String more = subject.perCall() > control.perCall() ? ", more than the region without a pool leaves" : "";
        return new Verdict(Outcome.CANNOT_SHOW, controlLeaves(control) + ", not above " + bound
                + ": the heap cannot show blocks a call leaves autoreleased; " + subjectLeaves(subject) + more
                + alsoHandOff);
    }

    private static Verdict subjectDecides(HeapProbe subject, HeapProbe control, double bound, String alsoHandOff) {
        if (!(subject.perCall() < bound)) {
            return new Verdict(Outcome.REFUTED, "a cascade list call leaves " + subject.perCall()
                    + " autoreleased blocks per call (rounds " + subject.rounds() + ") waiting for its thread to exit,"
                    + " bound " + bound + "; " + controlLeaves(control) + alsoHandOff);
        }
        return new Verdict(Outcome.PROVED, subjectLeaves(subject) + ", below " + bound + ", where "
                + controlLeaves(control) + alsoHandOff);
    }

    private static String subjectLeaves(HeapProbe subject) {
        return "a cascade list call leaves " + subject.blocks();
    }

    private static String controlLeaves(HeapProbe control) {
        return "the region without a pool leaves " + control.blocks();
    }

    private static String handOffLeaves(HeapProbe handOff) {
        return "a platform thread started and waited for per call leaves " + handOff.blocks();
    }

    /**
     * Whether each fallback face missing from its font file was looked up there once over the layouts, which are
     * {@code glyphs}, one entry per layout, while every layout resolves it to no slot and gives the glyphs of the
     * first. {@code stderr} is what the child printed with {@code prism.debugfonts}.
     */
    static Verdict lookedUpOnce(List<String> glyphs, List<String> stderr) {
        Map<String, Integer> census = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (String line : stderr) {
            if (line.startsWith(FONT_FILE_LOOKUP) || line.startsWith(FALLBACK_FONT)
                    || line.startsWith(UNSUPPORTED_NAME)) {
                census.merge(line.strip(), 1, Integer::sum);
            }
            if (line.startsWith(FONT_FILE_LOOKUP) && line.endsWith(NO_FACE)) {
                missing.add(line.substring(FONT_FILE_LOOKUP.length(), line.indexOf(" filename=")));
            }
        }
        String printed = printed(glyphs, census);
        if (missing.isEmpty()) {
            return new Verdict(Outcome.CANNOT_SHOW, "no fallback face of the System font's Devanagari text is missing"
                    + " from its font file, so the layouts do not reach the path" + printed);
        }
        int layouts = glyphs.size();
        for (int i = 1; i < layouts; i++) {
            if (!Objects.equals(glyphs.getFirst(), glyphs.get(i))) {
                return new Verdict(Outcome.REFUTED, "layout " + i + " gives other glyphs than the first" + printed);
            }
        }
        Set<String> names = new LinkedHashSet<>(missing);
        for (String name : names) {
            long unresolved = stderr.stream().filter((FALLBACK_FONT + name + " slot=-1")::equals).count();
            if (unresolved != layouts) {
                return new Verdict(Outcome.REFUTED, "each of the " + layouts + " layouts resolves " + name
                        + " to no slot, but the child printed that " + unresolved + " times" + printed);
            }
            int times = Collections.frequency(missing, name);
            if (times != 1) {
                return new Verdict(Outcome.REFUTED, name + " was looked up in its font file " + times + " times over "
                        + layouts + " layouts, where once is enough" + printed);
            }
        }
        return new Verdict(Outcome.PROVED, "each face its font file lacks, " + String.join(", ", names)
                + ", was looked up there once over " + layouts + " layouts, each of which resolves it to no slot"
                + " and gives the glyphs of the first" + printed);
    }

    private static String printed(List<String> glyphs, Map<String, Integer> census) {
        String lines = census.isEmpty() ? "no lookup, fallback or unsupported-name line"
                : census.entrySet().stream().map(line -> line.getValue() + "x " + line.getKey())
                        .collect(Collectors.joining(" | ", "[", "]"));
        return "; over " + glyphs.size() + " layouts the child printed " + lines + " and laid out the glyphs " + glyphs;
    }
}
