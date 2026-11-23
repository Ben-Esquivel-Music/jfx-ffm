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

import com.sun.javafx.font.CharToGlyphMapper;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import test.com.sun.javafx.test.ParityGate;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * The {@link Verdict} type and the print-and-decide gate, {@link #decide}, that {@link MacFontReleaseTest},
 * {@link MacFontNullInputTest} and {@link MacGlyphImageTest} share for their checks whose instrument depends on the
 * machine, and the verdicts of the {@code MacFontReleaseTest} checks, decided from the figures and lines the child
 * wrote and nothing else, so that the rules are tested on every OS; the other two decide by
 * {@code MacFontNullInputTest.contextVerdict} and {@link MacGlyphImageVerdict}. A check proves the fix, refutes it, or
 * finds that the machine cannot show the defect; the last is a skip, or a failure under
 * {@code -Djfx.parity.require=true}.
 */
final class MacFontReleaseVerdict {

    /*
     * Surefire copies what the test JVM writes to its file descriptor 2 to the Maven console, while the reason of a
     * skip reaches only the XML report, which the macOS CI jobs do not keep.
     */
    private static final PrintStream FORK_STDERR =
            new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);

    /** How {@code CTFactory.createFontFile} reports, with {@code prism.debugfonts}, each lookup in a font file. */
    static final String FONT_FILE_LOOKUP = "createFontFile by ref name=";
    static final String NO_FACE = " index=-1";

    /** How layout reports, with {@code prism.debugfonts}, each fallback font it resolves, and to which slot. */
    static final String FALLBACK_FONT = "\tFallback font= ";

    /** How the System font reports, with {@code prism.debugfonts}, a fallback name it has no slot for. */
    static final String UNSUPPORTED_NAME = "\t Font name not supported ";

    /** CoreText's glyph ID of a glyph it removed, which layout keeps whatever slot the run takes. */
    static final int INVISIBLE_GLYPH_ID = CharToGlyphMapper.INVISIBLE_GLYPH_ID;

    private static final String TOKENS_MOVED = "a cascade list call leaves objects autoreleased on the thread that"
            + " made its list: it moved the next pool token, which a region that autoreleases nothing does not";

    enum Outcome {
        PROVED, REFUTED, CANNOT_SHOW
    }

    record Verdict(Outcome outcome, String message) {
    }

    /** A run of a CoreText line: its font's display name, whether that font is the line's by address, its glyph IDs. */
    record LineRun(String font, boolean lineFont, List<Integer> glyphs) {

        String describe() {
            return font + (lineFont ? " (the line's font) " : " ") + hex(glyphs);
        }
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

    /** The heap probes of the virtual check: the call, the region without a pool, a no-op hand-off, a platform call. */
    record VirtualHeap(HeapProbe subject, HeapProbe control, HeapProbe handOff, HeapProbe platform) {

        static VirtualHeap of(Map<String, String> values) {
            return new VirtualHeap(HeapProbe.of(values, "subject"), HeapProbe.of(values, "control"),
                                   HeapProbe.of(values, "handOff"), HeapProbe.of(values, "platform"));
        }

        String figures() {
            return "subject: " + subjectLeaves(subject, control) + "; control: " + controlLeaves(control)
                    + "; handOff: " + handOffLeaves(handOff) + "; platform: a cascade list call on a platform thread"
                    + " leaves " + platform.blocks();
        }
    }

    /**
     * How far each cycle moved the next autorelease-pool token of the thread that made the list, in bytes: around a
     * region that autoreleases nothing, around the region without a pool, and around the call. A pop puts the next
     * push back where the popped pool was, and an object left autoreleased moves it, so a call that drains what it
     * autoreleased moves it by 0.
     */
    record PoolTokens(List<Long> empty, List<Long> control, List<Long> subject) {

        static PoolTokens of(Map<String, String> values) {
            return new PoolTokens(moves(values.get("tokens.empty.moves")),
                                  moves(values.get("tokens.control.moves")),
                                  moves(values.get("tokens.subject.moves")));
        }

        private static List<Long> moves(String written) {
            if (written == null || written.isEmpty()) {
                return List.of();
            }
            return Arrays.stream(written.split(";")).map(Long::valueOf).toList();
        }

        int cycles() {
            return subject.size();
        }

        /**
         * Whether the tokens can show what a call leaves autoreleased: each cycle's region that autoreleases nothing
         * moved them by 0, and its region without a pool did not.
         */
        boolean show() {
            return !empty.isEmpty() && empty.size() == control.size() && empty.size() == subject.size()
                    && moved(empty) == 0 && moved(control) == control.size();
        }

        boolean refute() {
            return show() && moved(subject) > 0;
        }

        String whyNot() {
            if (empty.isEmpty()) {
                return "no pool token was read";
            }
            if (moved(empty) > 0) {
                return "the pool tokens cannot show them either: a region that autoreleases nothing moved them in "
                        + moved(empty) + " of " + empty.size() + " cycles";
            }
            return "the pool tokens cannot show them either: the region without a pool moved them in only "
                    + moved(control) + " of " + control.size() + " cycles";
        }

        String figures() {
            return "tokens.empty: " + listed(empty) + "; tokens.control: " + listed(control) + "; tokens.subject: "
                    + listed(subject);
        }

        private static long moved(List<Long> moves) {
            return moves.stream().filter(move -> move != 0).count();
        }

        private static String listed(List<Long> moves) {
            return moves.stream().map(String::valueOf).collect(Collectors.joining(";", "[", "]")) + " moved in "
                    + moved(moves) + " of " + moves.size() + " cycles";
        }
    }

    private MacFontReleaseVerdict() {
    }

    /**
     * Prints the verdict of {@code method} of {@code test} with its figures on one line of the test JVM's standard
     * error, then {@linkplain #require requires} it as {@code -Djfx.parity.require} says.
     */
    static void decide(Class<?> test, String method, Verdict verdict) {
        FORK_STDERR.println(test.getSimpleName() + "." + method + " " + verdict.outcome() + ": " + verdict.message());
        require(test, verdict, ParityGate.required());
    }

    /** Passes a proof, fails a refutation, and skips where the machine cannot show the defect unless required. */
    static void require(Class<?> test, Verdict verdict, boolean required) {
        ParityGate.requireOracle(test, verdict.outcome() != Outcome.CANNOT_SHOW, required, verdict::message);
        if (verdict.outcome() == Outcome.REFUTED) {
            fail(verdict.message());
        }
    }

    /**
     * Whether a cascade list call on a platform thread drains what it autoreleased, by either instrument. The heap:
     * its blocks per call must stay below {@code bound}, which the region without a pool must exceed for the heap to
     * show the defect. The tokens: the call must move none, where the region without a pool moves one on every call.
     */
    static Verdict drainedOnAPlatformThread(HeapProbe subject, HeapProbe control, PoolTokens tokens, double bound) {
        String figures = "; subject: " + subjectLeaves(subject, control) + "; control: " + controlLeaves(control)
                + "; " + tokens.figures();
        boolean heapShows = control.perCall() > bound;
        if (heapShows && !(subject.perCall() < bound)) {
            return new Verdict(Outcome.REFUTED, atTheBound(subject, bound) + figures);
        }
        if (tokens.refute()) {
            return new Verdict(Outcome.REFUTED, TOKENS_MOVED + figures);
        }
        if (!heapShows && !tokens.show()) {
            return new Verdict(Outcome.CANNOT_SHOW, controlCannotShow(control, bound) + ", and " + tokens.whyNot()
                    + figures);
        }
        return new Verdict(Outcome.PROVED, proof(heapShows, tokens.show(), bound) + figures);
    }

    /**
     * The same on a virtual thread, whose call hands the region to the cascade-list thread: by the heap, where a
     * no-op task handed to that thread per call must also stay below {@code bound}, or the heap cannot tell the
     * hand-off from blocks a call leaves autoreleased (it is never subtracted), and by the tokens of that thread. On
     * every host a call at or above {@code bound} is refuted, and so is one whose list the platform thread did not
     * make: each of the token cycles' callers, {@code handedOff} of them, must have been seen waiting for it.
     */
    static Verdict drainedOnAVirtualThread(VirtualHeap heap, PoolTokens tokens, int handedOff, double bound) {
        String figures = "; " + heap.figures() + "; " + tokens.figures() + "; handedOff: " + handedOff + " of "
                + tokens.cycles() + " callers waited for the platform thread";
        if (!(heap.subject().perCall() < bound)) {
            return new Verdict(Outcome.REFUTED, atOrAboveTheFixedBound(heap.subject(), bound) + figures);
        }
        if (handedOff < tokens.cycles()) {
            return new Verdict(Outcome.REFUTED, "a virtual caller did not wait for the platform thread to make its"
                    + " list" + figures);
        }
        if (tokens.refute()) {
            return new Verdict(Outcome.REFUTED, TOKENS_MOVED + figures);
        }
        boolean controlShows = heap.control().perCall() > bound;
        boolean heapShows = controlShows && heap.handOff().perCall() < bound;
        if (!heapShows && !tokens.show()) {
            String heapCannot = controlShows ? handOffLeaves(heap.handOff()) + ", not below " + bound
                    + ": the heap cannot tell the hand-off from blocks a call leaves autoreleased"
                    : controlCannotShow(heap.control(), bound);
            return new Verdict(Outcome.CANNOT_SHOW, heapCannot + ", and " + tokens.whyNot() + figures);
        }
        return new Verdict(Outcome.PROVED, proof(heapShows, tokens.show(), bound) + figures);
    }

    private static String atTheBound(HeapProbe subject, double bound) {
        return "a cascade list call leaves " + subject.perCall() + " autoreleased blocks per call (rounds "
                + subject.rounds() + ") waiting for its thread to exit, bound " + bound;
    }

    private static String atOrAboveTheFixedBound(HeapProbe subject, double bound) {
        return "a cascade list call leaves " + subject.perCall() + " heap blocks per call (rounds " + subject.rounds()
                + "), at or above the bound of " + bound + " fixed for every host";
    }

    private static String controlCannotShow(HeapProbe control, double bound) {
        return controlLeaves(control) + ", not above " + bound + ": the heap cannot show blocks a call leaves"
                + " autoreleased";
    }

    private static String proof(boolean heapShows, boolean tokensShow, double bound) {
        List<String> instruments = new ArrayList<>();
        if (heapShows) {
            instruments.add("the heap, where the call leaves less than " + bound + " blocks per call and the region"
                    + " without a pool more");
        }
        if (tokensShow) {
            instruments.add("the pool tokens, which no call moved and the region without a pool moved in every cycle");
        }
        return "a cascade list call drains what it autoreleased, shown by " + String.join(" and by ", instruments);
    }

    private static String subjectLeaves(HeapProbe subject, HeapProbe control) {
        String more = subject.perCall() > control.perCall() ? ", more than the region without a pool leaves" : "";
        return "a cascade list call leaves " + subject.blocks() + more;
    }

    private static String controlLeaves(HeapProbe control) {
        return "the region without a pool leaves " + control.blocks();
    }

    private static String handOffLeaves(HeapProbe handOff) {
        return "a no-op task handed to the platform thread per call leaves " + handOff.blocks();
    }

    /**
     * Whether a fallback face its font file lacks was looked up there once over the layouts, whose slots are
     * {@code answers}: each layout answers no slot, the child printed once per layout that it resolves {@code face} to
     * no slot, and it printed one lookup of {@code face} in {@code file} that found no face. A lookup in another file,
     * or down the branch of a font that reports none, is not counted. {@code unmet} are the preconditions on the
     * machine, measured apart from the lookups, that do not hold; {@code stderr} is what the child printed with
     * {@code prism.debugfonts}.
     */
    static Verdict lookedUpOnce(String face, String file, List<String> unmet, List<String> answers,
                                List<String> stderr) {
        Map<String, Integer> census = new LinkedHashMap<>();
        for (String line : stderr) {
            if (line.startsWith(FONT_FILE_LOOKUP) || line.startsWith(FALLBACK_FONT)
                    || line.startsWith(UNSUPPORTED_NAME)) {
                census.merge(line.strip(), 1, Integer::sum);
            }
        }
        String printed = printed(answers, census);
        if (!unmet.isEmpty()) {
            return new Verdict(Outcome.CANNOT_SHOW, "this host cannot show the memory: " + String.join("; ", unmet)
                    + printed);
        }
        int layouts = answers.size();
        for (int i = 0; i < layouts; i++) {
            if (!"-1".equals(answers.get(i))) {
                return new Verdict(Outcome.REFUTED, "layout " + i + " resolves " + face + " to slot " + answers.get(i)
                        + ", where its lookup with filename=" + file + " finds no face" + printed);
            }
        }
        long unresolved = stderr.stream().filter((FALLBACK_FONT + face + " slot=-1")::equals).count();
        if (unresolved != layouts) {
            return new Verdict(Outcome.REFUTED, "each of the " + layouts + " layouts resolves " + face
                    + " to no slot, but the child printed that " + unresolved + " times" + printed);
        }
        String lookup = FONT_FILE_LOOKUP + face + " filename=" + file + NO_FACE;
        long lookups = stderr.stream().filter(lookup::equals).count();
        if (lookups != 1) {
            return new Verdict(Outcome.REFUTED, face + " was looked up with filename=" + file + " " + lookups
                    + " times over " + layouts + " layouts, where it must be looked up once" + printed);
        }
        return new Verdict(Outcome.PROVED, face + " was looked up once with filename=" + file + " over " + layouts
                + " layouts, each of which resolves it to no slot" + printed);
    }

    private static String printed(List<String> answers, Map<String, Integer> census) {
        String lines = census.isEmpty() ? "no lookup, fallback or unsupported-name line"
                : census.entrySet().stream().map(line -> line.getValue() + "x " + line.getKey())
                        .collect(Collectors.joining(" | ", "[", "]"));
        return "; over " + answers.size() + " layouts the child answered the slots " + answers + " and printed "
                + lines;
    }

    /**
     * Whether a plain strike's layout gives each run of its line the glyphs layout's slot for the run's font allows:
     * its own glyph IDs where layout takes the font for the strike's, by address or by name, and otherwise the missing
     * glyph, 0, for each glyph but those CoreText removed, {@value #INVISIBLE_GLYPH_ID}, which stay. {@code runs} are
     * the runs of an oracle line made as layout makes its own, {@code laidOut} what layout gave. A host whose line
     * has no run in a font of the strike's name, or gives such a run another font object than the line's, or has no
     * run of another font with a glyph to lose, cannot show it.
     */
    static Verdict missingGlyphsWhereNoSlot(String strikeFont, List<LineRun> runs, List<Integer> laidOut) {
        List<Integer> expected = new ArrayList<>();
        List<String> owners = new ArrayList<>();
        boolean glyphsToLose = false;
        for (LineRun run : runs) {
            boolean strikes = run.lineFont() || strikeFont.equalsIgnoreCase(run.font());
            for (int glyph : run.glyphs()) {
                boolean kept = strikes || glyph == INVISIBLE_GLYPH_ID;
                expected.add(kept ? glyph : 0);
                owners.add(run.font());
                glyphsToLose |= !kept && glyph != 0;
            }
        }
        String figures = "; the strike's font is " + strikeFont + ", the line's runs are "
                + runs.stream().map(LineRun::describe).collect(Collectors.joining(" | ", "[", "]"))
                + " and layout gave " + hex(laidOut);
        List<LineRun> own = runs.stream().filter(run -> strikeFont.equalsIgnoreCase(run.font())).toList();
        if (own.isEmpty()) {
            return new Verdict(Outcome.CANNOT_SHOW, "no run of the line is in a font named " + strikeFont + figures);
        }
        if (!own.stream().allMatch(LineRun::lineFont)) {
            return new Verdict(Outcome.CANNOT_SHOW, "CoreText gave a run in " + strikeFont + " another font object"
                    + " than the line's, so this host does not show that layout keeps the strike's own run by address"
                    + figures);
        }
        if (!glyphsToLose) {
            return new Verdict(Outcome.CANNOT_SHOW, "no run of another font has a glyph other than 0 or "
                    + Integer.toHexString(INVISIBLE_GLYPH_ID) + ", so none can be drawn with the wrong font" + figures);
        }
        if (laidOut.size() != expected.size()) {
            return new Verdict(Outcome.REFUTED, "layout gave " + laidOut.size() + " glyphs where the line has "
                    + expected.size() + figures);
        }
        for (int i = 0; i < expected.size(); i++) {
            if (!expected.get(i).equals(laidOut.get(i))) {
                return new Verdict(Outcome.REFUTED, "glyph " + i + ", of the run in " + owners.get(i) + ", is "
                        + Integer.toHexString(laidOut.get(i)) + " where it must be "
                        + Integer.toHexString(expected.get(i)) + figures);
            }
        }
        return new Verdict(Outcome.PROVED, "each glyph of a run of another font is the missing glyph or stays"
                + " removed, and each run of the strike's font keeps its glyph IDs" + figures);
    }

    private static String hex(List<Integer> glyphs) {
        return glyphs.stream().map(Integer::toHexString).collect(Collectors.joining(";"));
    }

    /**
     * The verdict of a check whose preconditions on the machine, measured apart from what it checks, are
     * {@code unmet} where they do not hold, and whose findings against its fix are {@code problems}: an unmet
     * precondition cannot show the fix, a problem refutes it, and otherwise {@code proof} holds. {@code figures} are
     * what the child measured.
     */
    static Verdict checked(String proof, String figures, List<String> unmet, List<String> problems) {
        if (!unmet.isEmpty()) {
            return new Verdict(Outcome.CANNOT_SHOW, "this host cannot show it: " + String.join("; ", unmet) + "; "
                    + figures);
        }
        if (!problems.isEmpty()) {
            return new Verdict(Outcome.REFUTED, String.join("; ", problems) + "; " + figures);
        }
        return new Verdict(Outcome.PROVED, proof + "; " + figures);
    }

    /**
     * Whether the task of a virtual caller releases its own reference to the font the caller lent once it has run.
     * {@code baseline} and {@code baselineAgain} are how far the pooled region alone moved the font's retain count over
     * two lists in a row on the cascade-list thread, CoreText's own references; {@code afterRun} how far it moved over
     * the caller's call of the cascade list: from before the caller started, and so before its task took its
     * reference, to after the caller's thread had ended, and so after the task had run, its own release of that
     * reference included. Where the two baselines differ, the count CoreText's references leave has not settled on
     * this host, so a reference the task keeps cannot be told from it.
     */
    static Verdict lentFontReleased(long baseline, long baselineAgain, long afterRun) {
        String figures = "; query.delta: " + baseline + "; query.again.delta: " + baselineAgain + "; retain.afterRun: "
                + afterRun;
        if (baseline != baselineAgain) {
            return new Verdict(Outcome.CANNOT_SHOW, "the count CoreText's own references leave does not settle on this"
                    + " host: two lists in a row on the cascade-list thread moved the lent font's retain count by "
                    + baseline + " and by " + baselineAgain + ", so it cannot show a reference the task keeps"
                    + figures);
        }
        long kept = afterRun - baseline;
        if (kept != 0) {
            String what = kept > 0 ? "kept " + references(kept) + " of its own to the lent font once it had run"
                    : "released " + references(-kept) + " to the lent font beyond the one it took";
            return new Verdict(Outcome.REFUTED, "the task " + what + ": the caller's call, from before its task took"
                    + " its reference to after the task had run, moved the font's retain count by " + afterRun
                    + ", where each of the two lists before it moved it by " + baseline + figures);
        }
        return new Verdict(Outcome.PROVED, "the task releases its reference to the lent font once it has run: the"
                + " caller's call, from before its task took its reference to after the task had run, moved the font's"
                + " retain count by " + afterRun + ", as each of the two lists before it did" + figures);
    }

    private static String references(long count) {
        return count + (count == 1 ? " reference" : " references");
    }
}
