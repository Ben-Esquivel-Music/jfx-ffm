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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import test.com.sun.javafx.font.FontGoldenRuntime.ChildJvm;
import test.com.sun.javafx.font.MacFontReleaseVerdict.HeapProbe;
import test.com.sun.javafx.font.MacFontReleaseVerdict.LineRun;
import test.com.sun.javafx.font.MacFontReleaseVerdict.PoolTokens;
import test.com.sun.javafx.font.MacFontReleaseVerdict.Verdict;
import test.com.sun.javafx.font.MacFontReleaseVerdict.VirtualHeap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The CF objects the C of commit {@value MacFontGoldens#CAPTURE_COMMIT} created or copied and never released, and
 * those a Java exception could leave unreleased (US-067): each is now released exactly once. Each observation runs
 * in a child JVM ({@link MacFontReleaseChild}), because an over-release ends the JVM. A leak shows as a retain
 * count that rises with the calls, as in {@link MacFontStressTest}, or as a heap that grows with them.
 * <p>
 * Some checks need a machine that can show their defect: the two drain checks of the autorelease pool need a region
 * without a pool that leaves more than {@link #AUTORELEASED_BLOCKS_PER_CALL} blocks per call, or that moves the next
 * pool token where a region that autoreleases nothing does not, the check of the lent font needs the count CoreText's
 * own references leave over a list on the cascade-list thread to settle, and the checks of layout's fallback fonts
 * need CoreText to name, file and substitute the fonts they make as their preconditions say, which each measures apart
 * from what it checks. {@link MacFontReleaseVerdict} decides them, and each prints its verdict with the measured
 * figures on one line of the test JVM's standard error. Where the machine cannot show the defect the check is skipped,
 * or fails under {@code -Djfx.parity.require=true}; each check's comment names what still pins its code on every
 * machine, and what that pins. Two checks delete or replace the file of a font that CoreText made from it while the
 * font is in use, keeping its bytes under a second link: a crash there is CoreText reading that file by its path
 * again, not a defect of the font layer.
 */
@EnabledOnOs(OS.MAC)
public class MacFontReleaseTest {

    private static final String RETAIN_CALLS = Integer.toString(MacFontStressChild.RETAIN_CALLS);

    private static final String FACE = MacFontReleaseChild.FACE;

    private static final String AHEM_INSTALLED = "a font named " + FACE + " is installed, so the System font has a"
            + " slot of that name";

    /**
     * Measured in the child on macOS 27.2.1, smallest of three rounds of 300 calls: without a pool a cascade list call
     * of Helvetica leaves about 230 heap blocks, with its pool 0.3 at most, on either kind of thread. The bound is
     * more than twice the second and less than half the first. Elsewhere the region without a pool may not reach the
     * bound: on the macOS 15 runners of CI runs 45022158121 and 45041254324 it left about 6.0, so the heap there
     * cannot show the defect. On every host a virtual caller's call at or above the bound is refuted.
     */
    static final double AUTORELEASED_BLOCKS_PER_CALL = 10.0;

    /**
     * The name copy and the URL of every cascade entry. A URL left behind keeps about two heap blocks, so the bound
     * of half a block per URL and call tells a leak from the noise of a warm heap. On a macOS where every name
     * string is immortal ({@code name.entry} is -1) the name copy cannot be observed and only the heap is checked.
     */
    @Test
    @Tag("crash-prone")
    @Timeout(2 * MacFontGoldens.CHILD_TIMEOUT_SECONDS)
    void theCascadeListReleasesTheNamesAndUrls() throws Exception {
        Map<String, String> values = run(MacFontReleaseChild.CASCADE_LIST);
        List<String> problems = new ArrayList<>();
        int urls = Integer.parseInt(values.get("urls"));
        double blocksPerCall = Double.parseDouble(values.get("blocks.perCall"));
        if (urls < 10) {
            problems.add("the cascade list of Helvetica has " + urls + " files, too few for the heap to show a URL"
                    + " that is not released");
        }
        if (!(blocksPerCall < urls / 2.0)) {
            problems.add("a cascade list call leaves " + blocksPerCall + " heap blocks in use, for " + urls
                    + " URLs: the URLs are not released");
        }
        if (!values.get("name.entry").equals("-1")) {
            expect(problems, values, "retain.delta.control.nameCopyPerCall", RETAIN_CALLS,
                   "a name copy that is not released must raise the retain count of the watched name");
            expect(problems, values, "retain.delta.name", "0", "the cascade list releases the name it copies");
        }
        assertEquals(List.of(), problems);
    }

    /**
     * A fallback font layout takes by reference has one owner, its font file. The layout half reads the fonts this
     * machine's layout added that way: a fresh copy has the count of a font with one owner, so each font file's font
     * must have the same. Where no fallback of the machine took that path ({@code fonts} is 0) that half asserts
     * nothing, and the seam half alone pins the hand-over, the same on every machine.
     */
    @Test
    @Tag("crash-prone")
    @Timeout(2 * MacFontGoldens.CHILD_TIMEOUT_SECONDS)
    void aFallbackFontTakenByReferenceIsOwnedOnce() throws Exception {
        Map<String, String> values = run(MacFontReleaseChild.FALLBACK_FONT);
        List<String> problems = new ArrayList<>();
        int fonts = Integer.parseInt(values.get("fonts"));
        for (int i = 0; i < fonts; i++) {
            String[] font = values.get("font." + FontGoldenFormat.pad(i, 2)).split("\\|");
            if (!font[1].equals(font[2])) {
                problems.add("the fallback font " + font[0] + " has retain count " + font[1] + " where a fresh copy"
                        + " has " + font[2] + ": its font file is not its only owner");
            }
        }
        if (values.get("seam.slot").equals("-1")) {
            problems.add("the Helvetica copy was not added as a fallback, so the seam half proves nothing and the"
                    + " test needs another face");
        }
        if (!values.get("seam.after").equals(values.get("seam.before"))) {
            problems.add("handing a font over as a fallback moved its retain count from " + values.get("seam.before")
                    + " to " + values.get("seam.after") + ": the font file takes the one reference it is handed");
        }
        assertEquals(List.of(), problems);
    }

    /** The factory consumes the reference: a font file's disposer owns it, or it is released when none is made. */
    @Test
    @Tag("crash-prone")
    @Timeout(2 * MacFontGoldens.CHILD_TIMEOUT_SECONDS)
    void aFontReferenceIsReleasedUnlessAFontFileTakesIt() throws Exception {
        Map<String, String> values = run(MacFontReleaseChild.FONT_FILE_OWNERSHIP);
        long unmatchedBefore = Long.parseLong(values.get("unmatched.before"));
        assertEquals(FontGoldens.NULL, values.get("unmatched.made"), "no face has the unmatched name");
        assertEquals(Long.toString(unmatchedBefore - 1), values.get("unmatched.after"),
                     "a reference no font file took is released");
        assertEquals("CTFontFile", values.get("matched.made"), "Helvetica has a face of that name");
        assertEquals(values.get("matched.before"), values.get("matched.after"),
                     "a reference a font file took is neither retained nor released");
    }

    /** The CTFont the cascade lookup makes for a font file is released once the list is read. */
    @Test
    @Tag("crash-prone")
    @Timeout(2 * MacFontGoldens.CHILD_TIMEOUT_SECONDS)
    void theCascadeLookupReleasesItsFont() throws Exception {
        Map<String, String> values = run(MacFontReleaseChild.CASCADE_FONT);
        assertNotEquals("0", values.get("cgFont"), "CoreGraphics made no font of Ahem, so no lookup has a font");
        assertEquals(RETAIN_CALLS, values.get("retain.delta.control.fontPerCall"),
                     "a CTFont that is not released must raise the retain count of its CGFont");
        assertEquals("0", values.get("retain.delta.cascadeFont"), "the cascade lookup releases the CTFont it makes");
    }

    /**
     * A bounds array that cannot take four values still throws what it threw, {@code ArrayIndexOutOfBoundsException}
     * when short and {@code NullPointerException} when null, and the {@code glyf} copy is released first.
     */
    @Test
    @Tag("crash-prone")
    @Timeout(2 * MacFontGoldens.CHILD_TIMEOUT_SECONDS)
    void aBoundsArrayThatThrowsStillReleasesGlyf() throws Exception {
        Map<String, String> values = run(MacFontReleaseChild.GLYF_BOUNDS);
        assertEquals(RETAIN_CALLS, values.get("retain.delta.control.copyTablePerCall"),
                     "a glyf copy that is not released must raise the retain count of the cached table");
        assertEquals(ArrayIndexOutOfBoundsException.class.getName(), values.get("thrown.shortBounds"));
        assertEquals(NullPointerException.class.getName(), values.get("thrown.nullBounds"));
        assertEquals("0", values.get("retain.delta.shortBounds"), "a short bounds array leaves no glyf copy behind");
        assertEquals("0", values.get("retain.delta.nullBounds"), "a null bounds array leaves no glyf copy behind");
    }

    /**
     * A table that does not fit in the heap is released before the {@code OutOfMemoryError} leaves the reader. The
     * child gets a heap smaller than the bitmap table of Apple Color Emoji, the one allocation of the font layer a
     * test can make fail.
     */
    @Test
    @Tag("crash-prone")
    @Timeout(2 * MacFontGoldens.CHILD_TIMEOUT_SECONDS)
    void aTableTooLargeForTheHeapIsStillReleased() throws Exception {
        ChildJvm child = ChildJvm.prepare(MacFontGoldens.workDirectory(), MacFontReleaseChild.TABLE_OUT_OF_MEMORY);
        List<String> command = FontGoldenRuntime.javaCommand(argument -> argument.startsWith("-Xmx"));
        command.add("-Xmx64m");
        command.add(child.errorFileOption());
        command.addAll(child.mainArguments(MacFontReleaseChild.class));
        Map<String, String> values = child.run(command, environment -> { }, MacFontGoldens.CHILD_TIMEOUT_SECONDS)
                                          .requireSuccess();
        long tableLength = Long.parseLong(values.get("table.length"));
        long heapMax = Long.parseLong(values.get("heap.max"));
        assertTrue(tableLength > heapMax, "the sbix table of Apple Color Emoji has " + tableLength
                + " bytes and fits in the child's heap of " + heapMax + ", so the test cannot provoke the path");
        assertEquals(RETAIN_CALLS, values.get("retain.delta.control.copyTablePerCall"),
                     "a table copy that is not released must raise the retain count of the cached table");
        assertEquals(OutOfMemoryError.class.getName(), values.get("thrown"));
        assertEquals("0", values.get("retain.delta.table"),
                     "a table too large for the heap leaves no copy of it behind");
    }

    /**
     * A run that throws when it is handed its glyphs leaves no CTLine behind: the line is released before the
     * exception leaves layout, as it is after a run that takes them.
     */
    @Test
    @Tag("crash-prone")
    @Timeout(2 * MacFontGoldens.CHILD_TIMEOUT_SECONDS)
    void aLayoutWhoseRunThrowsStillReleasesItsLine() throws Exception {
        Map<String, String> values = run(MacFontReleaseChild.LAYOUT_THROWS);
        long linePerCall = Long.parseLong(values.get("retain.delta.control.linePerCall"));
        assertTrue(linePerCall > 0, "a line that is not released must raise the retain count of its font, which"
                + " moved by " + linePerCall);
        assertEquals(IllegalStateException.class.getName(), values.get("thrown"));
        assertEquals("0", values.get("retain.delta.layout"), "a layout releases its line");
        assertEquals("0", values.get("retain.delta.shapeThrows"),
                     "a layout whose run throws leaves no line behind");
    }

    /**
     * The bitmap context the glyph cache keeps has one owner: two threads that find the cache empty create one
     * context between them, and both get the one the cache holds. The first thread is held inside the creation until
     * the second has reached the cache, inside a creation of its own or blocked at the cache; a hold that times out
     * fails. The threads call the cache directly and draw nothing; that two threads do not draw into the one bitmap
     * at once is {@code MacGlyphImageTest.twoGlyphsDrawnAtOnceEachGetOnlyTheirOwnPixels}.
     */
    @Test
    @Tag("crash-prone")
    @Timeout(2 * MacFontGoldens.CHILD_TIMEOUT_SECONDS)
    void twoThreadsThatFindTheContextCacheEmptyCreateOneContext() throws Exception {
        Map<String, String> values = run(MacFontReleaseChild.CACHED_CONTEXT);
        assertEquals("0", values.get("cached.before"),
                     "a context was cached before the two threads asked for one, so the race proves nothing");
        assertEquals("true", values.get("strike.hasFont"), "the strike has no CTFont, so it makes no context");
        assertEquals("true", values.get("firstInside"), "the first thread never created a context");
        assertTrue(Set.of(MacFontReleaseChild.SECOND_CREATING, MacFontReleaseChild.SECOND_BLOCKED)
                      .contains(values.get("hold")),
                   "the first thread stopped waiting for the second to reach the cache: " + values.get("hold"));
        assertEquals("true", values.get("finished"), "a thread did not return from the context cache");
        assertEquals(FontGoldens.NULL, values.get("first.failure"), "the first thread's call of the cache");
        assertEquals(FontGoldens.NULL, values.get("second.failure"), "the second thread's call of the cache");
        assertEquals("1", values.get("creations"), "two threads that find the cache empty create one context"
                + " between them, not one each, which leaves all but the cached one without an owner (hold: "
                + values.get("hold") + ")");
        assertEquals("true", values.get("context.made"), "the cache answers a context");
        assertEquals("true", values.get("context.shared"), "both threads get the same context");
        assertEquals("true", values.get("context.cached"), "the context both threads get is the cached one");
    }

    /**
     * A fallback face that is not in its font file is looked up there once a process: later layouts neither copy its
     * font again nor parse its file, and each resolves it to no slot. The face is Ahem, made from a file of the
     * child's own that is then replaced by Helvetica.ttc, which every macOS has and which has no face named Ahem; the
     * run in Ahem of a line of "A" is laid out in a System font. The child runs with {@code prism.debugfonts}: the
     * factory prints a line per lookup in a font file and layout one per fallback font it resolves; only lookups in
     * that file count. The preconditions are measured apart from the lookups: the file CoreText reports for the font
     * and for a fresh copy of the run's font, the run font's name, that the file lacks the face, and that the System
     * font has no slot of that name, which a font named Ahem installed on the host would give it. Where one does not
     * hold, the check is skipped with its figures, or fails under {@code -Djfx.parity.require=true}. On every machine
     * {@link #aFaceItsFontFileLacksIsRememberedByItsName} pins the factory's memory, and
     * {@code MacFontBindingStructureTest.layoutAsksForAFallbackFaceBeforeCopyingTheRunFont} pins in the class file
     * that layout copies the run's font and adds the copy only where the factory answers that it may find the face.
     */
    @Test
    @Tag("crash-prone")
    @Timeout(2 * MacFontGoldens.CHILD_TIMEOUT_SECONDS)
    void aFallbackFaceItsFontFileLacksIsLookedUpOnce(TestInfo test) throws Exception {
        Map<String, String> values = runWithDebugFonts(MacFontReleaseChild.FALLBACK_FACE);
        decide(test, MacFontReleaseVerdict.lookedUpOnce(FACE, values.get("file"), swappedFacePreconditions(values),
                answers(values), stderr(MacFontReleaseChild.FALLBACK_FACE)));
    }

    /**
     * A fallback face whose font file could not be read is looked up again at a later layout: it is not remembered as
     * one its file lacks. The face is Ahem, made from a file of the child's own, whose run of a line of "A" is laid
     * out in a System font once the file is deleted and again once Ahem is back in its place: the first layout gets
     * no slot and the face is not remembered, the second looks it up, finds it and adds it as a slot. With
     * {@code prism.debugfonts}, the child prints the one lookup that found it and none that found no face. The
     * preconditions are measured apart from the layouts: the file CoreText reports for the font, and for a fresh copy
     * of the run's font once the file is deleted, which is what the lookup reads; the run font's name; and that the
     * System font has no slot of that name. Where one does not hold, the check is skipped with its figures, or fails
     * under {@code -Djfx.parity.require=true}. On every OS {@code PrismFontFactoryTest} pins that
     * {@code findFontIndex} throws what making the font file throws.
     */
    @Test
    @Tag("crash-prone")
    @Timeout(2 * MacFontGoldens.CHILD_TIMEOUT_SECONDS)
    void aFaceLookupThatCannotReadItsFileIsMadeAgainAtTheNextLayout(TestInfo test) throws Exception {
        Map<String, String> values = runWithDebugFonts(MacFontReleaseChild.UNREADABLE_FACE_FILE);
        String file = values.get("file");
        List<String> unmet = new ArrayList<>();
        expect(unmet, values, "file.reported", file, "CoreText reports another file for the font made from it");
        expect(unmet, values, "run.font", FACE, "CoreText names the font of the run another way");
        expect(unmet, values, "file.ofACopy", file, "CoreText reports another file for a copy of the run's font once"
                + " the file is deleted, so the lookup does not read the deleted file");
        expect(unmet, values, "slot.before", "-1", AHEM_INSTALLED);
        List<String> problems = new ArrayList<>();
        expect(problems, values, "answer.unreadable", "-1", "a layout whose lookup cannot read the file has no slot");
        expect(problems, values, "mayFindFace.afterUnreadable", "true",
               "a face whose file could not be read is not remembered as one its file lacks");
        if (!(Integer.parseInt(values.get("answer.readable")) >= 1)) {
            problems.add("answer.readable = " + values.get("answer.readable") + ", expected a slot of 1 or more: the"
                    + " next layout looks the face up again and adds it once the file can be read");
        }
        expect(problems, values, "slot.font", FACE, "the slot the next layout adds holds the face");
        List<String> lines = stderr(MacFontReleaseChild.UNREADABLE_FACE_FILE);
        String lookup = MacFontReleaseVerdict.FONT_FILE_LOOKUP + FACE + " ";
        long found = lines.stream().filter((lookup + "filename=" + file + " index=0")::equals).count();
        long missing = lines.stream().filter(line -> line.startsWith(lookup))
                            .filter(line -> line.endsWith(MacFontReleaseVerdict.NO_FACE)).count();
        if (found != 1) {
            problems.add("the child printed " + found + " lookups of " + FACE + " in " + file + " that found it, where"
                    + " the layout after the file is back makes one");
        }
        if (missing != 0) {
            problems.add("the child printed " + missing + " lookups of " + FACE + " that found no face, where the file"
                    + " either could not be read or had it");
        }
        decide(test, MacFontReleaseVerdict.checked("a face whose file could not be read is looked up again at the next"
                + " layout, which adds it once the file can be read", figures(values, "answer.unreadable",
                "mayFindFace.afterUnreadable", "answer.readable", "slot.font", "file") + ", lookups that found it "
                + found + ", lookups that found no face " + missing, unmet, problems));
    }

    /**
     * A fallback font that reports no file is looked up once a process, as a font whose file lacks its face is: the
     * factory remembers its name without reading any file, and each layout resolves it to no slot. The font is Ahem
     * made from its bytes. With {@code prism.debugfonts}, the child prints the one lookup, with no file. The
     * preconditions are measured apart from the lookups: that a fresh copy of the run's font reports no file, the run
     * font's name, and that the System font has no slot of that name. Where one does not hold, the check is skipped
     * with its figures, or fails under {@code -Djfx.parity.require=true}.
     */
    @Test
    @Tag("crash-prone")
    @Timeout(2 * MacFontGoldens.CHILD_TIMEOUT_SECONDS)
    void aFallbackFontThatReportsNoFileIsLookedUpOnce(TestInfo test) throws Exception {
        Map<String, String> values = runWithDebugFonts(MacFontReleaseChild.FONT_WITHOUT_A_FILE);
        List<String> unmet = new ArrayList<>();
        expect(unmet, values, "file.ofACopy", FontGoldens.NULL, "CoreText reports a file for a font made from bytes");
        expect(unmet, values, "run.font", FACE, "CoreText names the font of the run another way");
        expect(unmet, values, "slot.before", "-1", AHEM_INSTALLED);
        decide(test, MacFontReleaseVerdict.lookedUpOnce(FACE, "null", unmet, answers(values),
                stderr(MacFontReleaseChild.FONT_WITHOUT_A_FILE)));
    }

    /**
     * The glyphs of a run whose font takes no slot are not drawn with another font. In a plain strike of Ahem, as
     * {@code Font.loadFont} gives one, CoreText lays out what Ahem lacks of {@link MacFontReleaseChild#MIXED_TEXTS} in
     * fonts of its own choosing, which take no slot: each of their glyphs is the missing glyph, 0, but those CoreText
     * removed, 0xFFFF, which stay, while the run in Ahem keeps its glyph IDs. The oracle is a line of each text made as
     * layout makes its own. A host whose lines have no run in Ahem, give that run another font object than the
     * line's, or substitute no font with a glyph to lose cannot show it: the check is skipped with its figures, or
     * fails under {@code -Djfx.parity.require=true}. Where CoreText gives no removed glyph, a fill of those too is not
     * seen.
     */
    @Test
    @Tag("crash-prone")
    @Timeout(2 * MacFontGoldens.CHILD_TIMEOUT_SECONDS)
    void aRunWhoseFontTakesNoSlotIsLaidOutWithTheMissingGlyph(TestInfo test) throws Exception {
        Map<String, String> values = run(MacFontReleaseChild.RUN_WITHOUT_A_SLOT);
        List<LineRun> runs = new ArrayList<>();
        for (int i = 0; i < Integer.parseInt(values.get("runs")); i++) {
            String run = "run." + i;
            runs.add(new LineRun(values.get(run + ".font"), Boolean.parseBoolean(values.get(run + ".lineFont")),
                                 glyphs(values.get(run + ".glyphs"))));
        }
        decide(test, MacFontReleaseVerdict.missingGlyphsWhereNoSlot(values.get("strike.font"), runs,
                glyphs(values.get("laidOut"))));
    }

    /**
     * A run in the strike's own font keeps the strike's slot whatever its font's name, where nothing else gives it
     * one, since the line holds the strike's font: asked under a name that is not its font's, the run in Ahem of the
     * line above answers, in a plain strike, the slot it is laid out with, 0 or 1, and -1 as a run of another line;
     * in a composite that holds Ahem, Ahem's slot there, which the strike's does not replace; and in a System font,
     * which has no slot of that name, the strike's slot. A host whose line has no run in a font CoreText names Ahem,
     * has a font named Ahem installed, or leaves the composite no room for Ahem cannot show it: the check is skipped
     * with its figures, or fails under {@code -Djfx.parity.require=true}.
     */
    @Test
    @Tag("crash-prone")
    @Timeout(2 * MacFontGoldens.CHILD_TIMEOUT_SECONDS)
    void aRunInTheStrikesOwnFontKeepsItsSlotWhateverItsName(TestInfo test) throws Exception {
        Map<String, String> values = run(MacFontReleaseChild.RUN_WITHOUT_A_SLOT);
        List<String> unmet = new ArrayList<>();
        expect(unmet, values, "own.run", "true", "the line has no run in a font CoreText names " + FACE);
        if ("true".equals(values.get("own.run"))) {
            expect(unmet, values, "logical.before", "-1", AHEM_INSTALLED);
        }
        if ("-1".equals(values.get("composite.added"))) {
            unmet.add("composite.added = -1: the composite has no room for " + FACE);
        }
        String strikeSlot = Integer.toString(MacFontReleaseChild.STRIKE_SLOT);
        List<String> problems = new ArrayList<>();
        expect(problems, values, "own.plain", "0", "a plain strike keeps its slot for a run in its own font");
        expect(problems, values, "own.plainStrikeSlot", strikeSlot,
               "the slot kept is the one the strike is laid out with");
        expect(problems, values, "own.otherLine", "-1", "a run of another line's font has no slot in a plain strike");
        expect(problems, values, "own.composite", values.get("composite.added"),
               "a composite keeps the slot it holds the run's font in");
        expect(problems, values, "own.logical", strikeSlot,
               "a System font that has no slot of the run font's name keeps the strike's");
        decide(test, MacFontReleaseVerdict.checked("a run in the strike's own font keeps the strike's slot whatever"
                + " its font's name, where nothing else gives it one", figures(values, "own.plain",
                "own.plainStrikeSlot", "own.otherLine", "own.composite", "composite.added", "own.logical",
                "logical.before"), unmet, problems));
    }

    /**
     * The factory remembers a name whose font file has no face of it, in any case, so that layout does not look it up
     * again; a name it found a face for is not remembered. The name is invented and the font is Helvetica's, so this
     * needs no machine whose fallback faces are missing.
     */
    @Test
    @Tag("crash-prone")
    @Timeout(2 * MacFontGoldens.CHILD_TIMEOUT_SECONDS)
    void aFaceItsFontFileLacksIsRememberedByItsName() throws Exception {
        Map<String, String> values = run(MacFontReleaseChild.FACE_MEMORY);
        assertEquals("true", values.get("missing.before"), "the invented name was remembered before any lookup");
        assertEquals(FontGoldens.NULL, values.get("missing.made"), "no face of Helvetica has the invented name");
        assertEquals("false", values.get("missing.after"), "a name its font file has no face of is remembered");
        assertEquals("false", values.get("missing.otherCase"), "the name is remembered whatever its case");
        assertEquals("CTFontFile", values.get("found.made"), "Helvetica has a face of that name");
        assertEquals("true", values.get("found.after"), "a name a face was found for is not remembered");
    }

    /**
     * A cascade list call on a platform thread that has no autorelease pool drains what CoreText autoreleased
     * during the call before it returns, instead of leaving it to wait for the thread's exit. Two instruments can
     * show it. The heap: the control, the same region without a pool, must leave more than
     * {@link #AUTORELEASED_BLOCKS_PER_CALL} blocks per call. The pool tokens: in each cycle a region that autoreleases
     * nothing must leave the thread's next pool token where it was and the control must move it; the call must then
     * move it in no cycle. Where neither can show the difference, the check is skipped with the figures, or fails under
     * {@code -Djfx.parity.require=true}. On every machine {@code MacFontBindingStructureTest} pins in the class file
     * that {@code getCascadeList} calls {@code pooledCascadeList} outside the branch where {@code Thread.isVirtual}
     * answers true, and that {@code pooledCascadeList} pushes its pool before it copies the language codes, and pops
     * it before each return or throw after it reads the list and in each handler that covers the read, one of them
     * catching everything, javac's copies of a finally; and
     * {@code MacObjCNativeTest.aPoppedPoolReleasesWhatWasAutoreleasedIntoIt} pins that a pop releases what was
     * autoreleased into the pool.
     */
    @Test
    @Tag("crash-prone")
    @Timeout(2 * MacFontGoldens.CHILD_TIMEOUT_SECONDS)
    void aCascadeListCallOnAPlatformThreadDrainsWhatItAutoreleased(TestInfo test) throws Exception {
        Map<String, String> values = run(MacFontReleaseChild.CASCADE_LIST_POOL);
        requireProbes(values, List.of(), List.of("subject", "control"));
        decide(test, MacFontReleaseVerdict.drainedOnAPlatformThread(HeapProbe.of(values, "subject"),
                HeapProbe.of(values, "control"), PoolTokens.of(values), AUTORELEASED_BLOCKS_PER_CALL));
    }

    /**
     * A cascade list call on a virtual thread leaves nothing CoreText autoreleased during it waiting on any thread:
     * the cascade-list thread, which makes the list, drains it, where the same region without a pool, run on the
     * carrier, leaves it there. The heap shows it where that region leaves more than
     * {@link #AUTORELEASED_BLOCKS_PER_CALL} blocks per call and a no-op task handed to that thread per call less; none
     * is subtracted. The pool tokens of the cascade-list thread show it as on a platform thread, the call's cycle
     * held open there until its caller is seen waiting. On every host a call at or above the bound is refuted, and so
     * is a caller not seen waiting; where neither instrument can show the drain, the check is skipped with the
     * figures, or fails under {@code -Djfx.parity.require=true}. The platform probe, a call on a platform thread, is
     * printed only. On every machine {@code MacFontBindingStructureTest} pins in the class file that
     * {@code getCascadeList} calls {@code cascadeListOnAPlatformThread} only where {@code Thread.isVirtual} answers
     * true, and returns what it answers.
     */
    @Test
    @Tag("crash-prone")
    @Timeout(2 * MacFontGoldens.CHILD_TIMEOUT_SECONDS)
    void aCascadeListCallOnAVirtualThreadDrainsWhatItAutoreleased(TestInfo test) throws Exception {
        Map<String, String> values = run(MacFontReleaseChild.CASCADE_LIST_VIRTUAL);
        requireProbes(values, List.of("subject", "control", "handOff"), List.of("platform"));
        decide(test, MacFontReleaseVerdict.drainedOnAVirtualThread(VirtualHeap.of(values), PoolTokens.of(values),
                Integer.parseInt(values.get("tokens.handedOff")), AUTORELEASED_BLOCKS_PER_CALL));
    }

    /**
     * Virtual callers that lay out at once share the one cascade-list thread, however many they are: while a task
     * holds that thread, {@value MacFontReleaseChild#HELD_CALLERS} virtual callers are all seen waiting for it, it is
     * the only thread of its name, and it is still the only one once they have their lists.
     */
    @Test
    @Tag("crash-prone")
    @Timeout(2 * MacFontGoldens.CHILD_TIMEOUT_SECONDS)
    void virtualCallersAtOnceShareOnePlatformThread() throws Exception {
        Map<String, String> values = run(MacFontReleaseChild.CASCADE_LIST_WORKER);
        String callers = Integer.toString(MacFontReleaseChild.HELD_CALLERS);
        assertEquals("true", values.get("hold.inside"), "the cascade-list thread did not run the holding task");
        assertEquals(callers, values.get("callers.parked"), "the virtual callers all seen waiting at once");
        assertEquals("1", values.get("workers.whileHeld"), "the threads named "
                + MacFontReleaseChild.CASCADE_LIST_THREAD + " while every caller waits");
        assertEquals("1", values.get("workers.after"), "the threads of that name once every caller has its list");
        assertEquals(callers, values.get("callers.sameLists"), "the callers that got the cascade list of the font");
    }

    /**
     * A virtual caller waits for the cascade-list thread to make its list, whatever call would run its task on the
     * caller instead: while a task holds that thread, every caller is seen waiting and none has ended, and each gets
     * the list once the hold is released. A second thread that makes lists while the first is held may still answer
     * them after the callers were seen waiting; {@link #virtualCallersAtOnceShareOnePlatformThread} counts the threads.
     */
    @Test
    @Tag("crash-prone")
    @Timeout(2 * MacFontGoldens.CHILD_TIMEOUT_SECONDS)
    void aVirtualCallerWaitsForThePlatformThreadToMakeItsList() throws Exception {
        Map<String, String> values = run(MacFontReleaseChild.CASCADE_LIST_WORKER);
        String callers = Integer.toString(MacFontReleaseChild.HELD_CALLERS);
        assertEquals("true", values.get("hold.inside"), "the cascade-list thread did not run the holding task");
        assertEquals("0", values.get("callers.terminatedWhileHeld"),
                     "virtual callers that got a list while the cascade-list thread was held");
        assertEquals(callers, values.get("callers.parked"), "the virtual callers seen waiting for the held thread");
        assertEquals("true", values.get("callers.joined"), "the callers did not get their lists once released");
        assertEquals(FontGoldens.NULL, values.get("callers.failures"), "the callers' calls of the cascade list");
        assertEquals(callers, values.get("callers.sameLists"), "the callers that got the cascade list of the font");
    }

    /**
     * The cascade-list thread is a platform thread that inherits nothing from the virtual caller that started it: no
     * inheritable thread local, and the system class loader as its context class loader, not the caller's. It is a
     * daemon, as every thread a virtual thread starts is.
     */
    @Test
    @Tag("crash-prone")
    @Timeout(2 * MacFontGoldens.CHILD_TIMEOUT_SECONDS)
    void theCascadeListThreadIsAPlatformThreadThatInheritsNothing() throws Exception {
        Map<String, String> values = run(MacFontReleaseChild.CASCADE_LIST_WORKER);
        assertEquals(FontGoldens.NULL, values.get("starter.failure"), "the starting caller's call of the cascade list");
        assertEquals("true", values.get("starter.sameList"), "the starting caller gets the cascade list of the font");
        assertEquals(FontGoldens.NULL, values.get("hold.failure"), "the holding task");
        assertEquals("false", values.get("hold.virtual"), "the cascade-list thread is a virtual thread");
        assertEquals("true", values.get("hold.daemon"), "the cascade-list thread is a daemon");
        assertEquals(MacFontReleaseChild.CASCADE_LIST_THREAD, values.get("hold.name"),
                     "the cascade-list thread's name");
        assertEquals(FontGoldens.NULL, values.get("hold.inherited"),
                     "the cascade-list thread inherited the starting caller's inheritable thread local");
        assertEquals("true", values.get("hold.systemLoader"),
                     "the cascade-list thread's context class loader is not the system class loader");
    }

    /**
     * Virtual callers wait on the cascade-list thread, so an interrupt of it neither ends it nor keeps it from
     * parking: it clears the interrupt, parks again, and makes the next caller's list.
     */
    @Test
    @Tag("crash-prone")
    @Timeout(2 * MacFontGoldens.CHILD_TIMEOUT_SECONDS)
    void anInterruptOfTheCascadeListThreadNeitherEndsItNorKeepsItFromParking() throws Exception {
        Map<String, String> values = run(MacFontReleaseChild.CASCADE_LIST_WORKER);
        assertEquals("true", values.get("worker.parkedAfterInterrupt"),
                     "the interrupted cascade-list thread was not seen parked with its interrupt cleared");
        assertEquals("true", values.get("afterInterrupt.answered"),
                     "a virtual caller got no list from the interrupted cascade-list thread");
        assertEquals(FontGoldens.NULL, values.get("afterInterrupt.failure"), "that caller's call of the cascade list");
        assertEquals("true", values.get("afterInterrupt.sameList"), "that caller gets the cascade list of the font");
    }

    /**
     * The task of a virtual caller holds a reference of its own to the font the caller lent while it waits to run,
     * so the caller may release the font however its wait ends, and it releases that reference once it has run. The
     * first is exact on every host: the task's retain is the only change to the count before the caller is seen
     * waiting. The second is measured against the count CoreText's own references leave over a list on the
     * cascade-list thread, which the pooled region alone gives when that thread runs it after its first list, twice
     * in a row. {@link MacFontReleaseVerdict} decides it, and the check prints its verdict with the three figures on
     * one line of the test JVM's standard error. Where the two baselines differ, that count does not settle on this
     * host, and the check is skipped with its figures, or fails under {@code -Djfx.parity.require=true}. On every
     * machine
     * {@code MacFontBindingStructureTest.aTaskReleasesItsOwnReferenceToTheFontOnEveryPathBeforeItWakesTheCaller}
     * pins in the class file that the task releases its reference on every path before it wakes the caller, and
     * {@code MacFontReleaseVerdictTest} that this check decides through its verdict.
     */
    @Test
    @Tag("crash-prone")
    @Timeout(2 * MacFontGoldens.CHILD_TIMEOUT_SECONDS)
    void theTaskHoldsAReferenceOfItsOwnToTheLentFontUntilItHasRun(TestInfo test) throws Exception {
        Map<String, String> values = run(MacFontReleaseChild.CASCADE_LIST_LENT_FONT);
        assertEquals("true", values.get("warm.sameList"), "the first virtual call gets the cascade list of the font");
        assertEquals("true", values.get("query.sameList"), "the baselines' regions get the cascade list of the font");
        assertEquals("true", values.get("hold.inside"), "the cascade-list thread did not run the holding task");
        assertEquals("true", values.get("parked"), "the caller was not seen waiting behind the holding task");
        assertEquals("1", values.get("retain.whileQueued"), "while the task waits it holds a reference of its own, so"
                + " the caller may release the font it lent however its wait ends");
        assertEquals("true", values.get("joined"), "the caller did not get its list once the hold was released");
        assertEquals(FontGoldens.NULL, values.get("failure"), "the caller's call of the cascade list");
        assertEquals("true", values.get("sameList"), "the caller gets the cascade list of the font");
        Verdict verdict = MacFontReleaseVerdict.lentFontReleased(Long.parseLong(values.get("query.delta")),
                Long.parseLong(values.get("query.again.delta")), Long.parseLong(values.get("retain.afterRun")));
        MacFontReleaseVerdict.decide(MacFontReleaseTest.class, test.getTestMethod().orElseThrow().getName(), verdict);
    }

    /**
     * An interrupt does not end a virtual caller's wait for its cascade list: an interrupted caller gets the whole
     * list, and its interrupt status is still set.
     */
    @Test
    @Tag("crash-prone")
    @Timeout(2 * MacFontGoldens.CHILD_TIMEOUT_SECONDS)
    void anInterruptedVirtualCallerGetsTheWholeCascadeListAndKeepsItsInterrupt() throws Exception {
        Map<String, String> values = run(MacFontReleaseChild.CASCADE_LIST_INTERRUPTED);
        assertNotEquals("0", values.get("entries"), "Helvetica has no cascade list, so no call can be cut short");
        assertEquals("true", values.get("virtual"), "the caller is not a virtual thread");
        assertEquals("true", values.get("interruptedBefore"), "the caller did not interrupt itself");
        assertEquals(FontGoldens.NULL, values.get("failure"), "the interrupted caller's call of the cascade list");
        assertEquals("true", values.get("sameList"), "the interrupted caller gets the whole cascade list");
        assertEquals("true", values.get("interruptedAfter"), "the caller's interrupt status is still set");
    }

    /**
     * The same at the wait, without depending on how fast the task is: a virtual caller that interrupted itself asks
     * for the list while a task holds the cascade-list thread, so its own task cannot be done before the caller is
     * seen parked in its wait. It parks only once its interrupt status is clear, so the wait has seen the interrupt
     * and cleared it, goes on, and sets it again once the task answers. In the call above, a task that is done
     * before the wait parks is never asked about the interrupt.
     */
    @Test
    @Tag("crash-prone")
    @Timeout(2 * MacFontGoldens.CHILD_TIMEOUT_SECONDS)
    void anInterruptTheWaitClearsDoesNotEndItAndIsSetAgainOnceTheTaskAnswers() throws Exception {
        Map<String, String> values = run(MacFontReleaseChild.CASCADE_LIST_WAIT_INTERRUPTED);
        assertEquals("true", values.get("hold.inside"), "the cascade-list thread did not run the holding task");
        assertEquals("true", values.get("virtual"), "the caller is not a virtual thread");
        assertEquals("true", values.get("interruptedBefore"), "the caller did not interrupt itself");
        assertEquals("true", values.get("parkedInTheWait"), "the caller was not seen parked in its wait for the"
                + " held task (state " + values.get("callerState") + ", failure " + values.get("failure")
                + "), so the interrupt ended the wait or the wait never saw it");
        assertEquals("false", values.get("interruptedWhileParked"), "the caller parked with its interrupt set");
        assertEquals("true", values.get("joined"), "the caller did not get its list once the hold was released");
        assertEquals(FontGoldens.NULL, values.get("failure"), "the interrupted caller's call of the cascade list");
        assertEquals("true", values.get("sameList"), "the interrupted caller gets the whole cascade list");
        assertEquals("true", values.get("interruptedAfter"), "the interrupt the wait cleared is set again");
    }

    /**
     * The probes' heap figures mean something only if each ran on its kind of thread and answered the list, and the
     * token cycles only if all of them ran and answered it.
     */
    private static void requireProbes(Map<String, String> values, List<String> virtualProbes,
                                      List<String> platformProbes) {
        assertNotEquals("0", values.get("entries"), "Helvetica has no cascade list, so no call autoreleases one");
        List<String> problems = new ArrayList<>();
        for (String probe : virtualProbes) {
            expect(problems, values, probe + ".virtual", "true", "the thread the probe ran on");
        }
        for (String probe : platformProbes) {
            expect(problems, values, probe + ".virtual", "false", "the thread the probe ran on");
        }
        for (String probe : Stream.concat(virtualProbes.stream(), platformProbes.stream()).toList()) {
            expect(problems, values, probe + ".failure", FontGoldens.NULL, "the probe's calls");
            expect(problems, values, probe + ".sameList", "true", "every call answers the cascade list of the font");
        }
        expect(problems, values, "tokens.failure", FontGoldens.NULL, "the token cycles");
        expect(problems, values, "tokens.cycles", Integer.toString(MacFontReleaseChild.TOKEN_CYCLES),
               "the token cycles that ran");
        expect(problems, values, "tokens.sameList", "true", "every call of the token cycles answers the cascade list");
        assertEquals(List.of(), problems);
    }

    /** The preconditions of a face whose file is replaced, measured apart from layout, that do not hold. */
    private static List<String> swappedFacePreconditions(Map<String, String> values) {
        String file = values.get("file");
        List<String> unmet = new ArrayList<>();
        expect(unmet, values, "file.reported", file, "CoreText reports another file for the font made from it");
        expect(unmet, values, "run.font", FACE, "CoreText names the font of the run another way once its file is"
                + " replaced");
        expect(unmet, values, "file.ofACopy", file, "CoreText reports another file for a copy of the run's font once"
                + " its file is replaced, so the lookup does not read the replaced file");
        expect(unmet, values, "face.missing", "true", "the factory does not read the replaced file as one without "
                + FACE);
        expect(unmet, values, "slot.before", "-1", AHEM_INSTALLED);
        return unmet;
    }

    private static List<String> answers(Map<String, String> values) {
        List<String> answers = new ArrayList<>();
        for (int i = 0; i < Integer.parseInt(values.get("layouts")); i++) {
            answers.add(values.get("answer." + i));
        }
        return answers;
    }

    /** Glyph codes as the child writes them, in hexadecimal, separated by ";". */
    private static List<Integer> glyphs(String written) {
        if (written == null || written.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(written.split(";")).map(code -> Integer.parseUnsignedInt(code, 16)).toList();
    }

    private static String figures(Map<String, String> values, String... keys) {
        return Arrays.stream(keys).map(key -> key + "=" + values.get(key)).collect(Collectors.joining(", "));
    }

    /** The scenario's values; the child runs with {@code prism.debugfonts}, so that it prints what is counted. */
    private static Map<String, String> runWithDebugFonts(String scenario) throws Exception {
        Map<String, String> values = MacFontGoldens.runChild(MacFontReleaseChild.class, scenario,
                                                             Map.of("prism.debugfonts", "true"));
        assertEquals("true", values.get("debugFonts"),
                     "the child ran without prism.debugfonts, so it printed no lookup to count");
        return values;
    }

    private static List<String> stderr(String scenario) throws Exception {
        Path stderr = MacFontGoldens.workDirectory().resolve(scenario + ".err");
        return new String(Files.readAllBytes(stderr), StandardCharsets.UTF_8).lines().toList();
    }

    private static void decide(TestInfo test, Verdict verdict) {
        MacFontReleaseVerdict.decide(MacFontReleaseTest.class, test.getTestMethod().orElseThrow().getName(), verdict);
    }

    private static void expect(List<String> problems, Map<String, String> values, String key, String expected,
                               String reason) {
        if (!Objects.equals(expected, values.get(key))) {
            problems.add(key + " = " + values.get(key) + ", expected " + expected + ": " + reason);
        }
    }

    private static Map<String, String> run(String scenario) throws Exception {
        return MacFontGoldens.runChild(MacFontReleaseChild.class, scenario, Map.of());
    }
}
