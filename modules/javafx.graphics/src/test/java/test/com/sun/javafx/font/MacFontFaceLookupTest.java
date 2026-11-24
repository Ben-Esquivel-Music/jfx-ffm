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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How layout looks a fallback face up in the file its run font reports (US-111, site 12, and the Copilot review of
 * PR 71). A lookup that finds no face is remembered with the file it read and that file's version, and holds only
 * while a run font of the name reports that file in that version; callers that need a face at once share one lookup.
 * Each observation runs in a child JVM ({@link MacFontFaceLookupChild}) on the child's own copies of Ahem and
 * Helvetica.ttc. The checks that go through CoreText's run fonts and the files it reports for them decide through
 * {@link MacFontReleaseVerdict}, and print their verdict on the test JVM's standard error; the others call the lookup
 * with a factory of the child's own, a file it names itself and a font CoreText made from a copy of that file, and
 * assert.
 */
@EnabledOnOs(OS.MAC)
public class MacFontFaceLookupTest {

    private static final String FACE = MacFontFaceLookupChild.FACE;

    private static final String LOOKUP = MacFontReleaseVerdict.FONT_FILE_LOOKUP + FACE + " filename=";

    /**
     * A lookup that found no face of a name in a run font's file holds only while a run font of the name reports that
     * file in the version the lookup read. The same file, asked under any case, is not looked up again; another path
     * to the same file (a hard link, so the same version), no path, a new modification time, a new size, a new file
     * key (a rename over the file, which keeps its time and size), the file gone and the file back are, and a lookup
     * after each change is remembered again. A lookup during whose read the file changed is not remembered, nor a file
     * that is no font, whose read throws. A font that reports no file is remembered by its name with no file, and
     * another font of that name that reports one is looked up. Each change of the file is first checked to change only
     * what it is meant to: the file key is that of the file system the child's work directory is on.
     */
    @Test
    @Tag("crash-prone")
    @Timeout(2 * MacFontGoldens.CHILD_TIMEOUT_SECONDS)
    void aMissHoldsOnlyWhileTheRunFontReportsTheSameFileInTheSameVersion() throws Exception {
        Map<String, String> values = run(MacFontFaceLookupChild.MISS_VERSION);
        assertEquals("time", values.get("time.changed"), "setting the modification time changes only that");
        assertEquals("size", values.get("size.changed"), "appending and setting the time back changes only the size");
        assertEquals("key", values.get("key.changed"), "a rename over the file changes only its file key");
        assertEquals("none", values.get("otherPath.changed"), "a hard link is the same file in the same version");
        assertEquals("true", values.get("before"), "a name looked up in no file");
        assertEquals(FontGoldens.NULL, values.get("made"), "no face of Helvetica.ttc has the invented name");
        assertEquals("false", values.get("same"), "the same file, unchanged, is not looked up again");
        assertEquals("false", values.get("otherCase"), "the name is remembered whatever its case");
        assertEquals("true", values.get("otherPath"), "a run font that reports another file is looked up");
        assertEquals("true", values.get("noPath"), "a run font that reports no file is looked up");
        for (String change : List.of("time", "size", "key")) {
            assertEquals("true", values.get(change), "the file is looked up again once its " + change + " changed");
            assertEquals(FontGoldens.NULL, values.get(change + ".made"), "the new version has no face of the name");
            assertEquals("false", values.get(change + ".again"), "the lookup of the new version is remembered");
        }
        assertEquals("true", values.get("gone"), "a file that is gone is looked up again");
        assertEquals("true", values.get("back"), "a file copied back in its place is looked up again");
        assertEquals("4", values.get("reads"), "the factory read the file once per lookup");
        assertEquals(FontGoldens.NULL, values.get("readChanged.made"), "no face in the file whose time changed");
        assertEquals("false", values.get("readChanged.remembered"), "a file that changed while it was read");
        assertEquals("true", values.get("readChanged"), "a file that changed while it was read is looked up again");
        assertTrue(values.get("garbage.thrown") != null, "reading a file that is no font throws");
        assertEquals("false", values.get("garbage.remembered"), "a file whose read threw is not remembered");
        assertEquals("true", values.get("garbage"), "a file whose read threw is looked up again");
        assertEquals(FontGoldens.NULL, values.get("noFile.made"), "a font that reports no file has no font file");
        assertEquals("false", values.get("noFile"), "a font that reports no file is remembered by its name");
        assertEquals("false", values.get("noFile.again"), "and stays remembered");
        assertEquals("true", values.get("noFile.withAFile"), "a font of that name that reports a file is looked up");
        assertEquals("0", values.get("noFile.reads"), "a font that reports no file is read from no file");
    }

    /**
     * A face its file lacked is looked up again once the file holds it. Ahem, made from a file of the child's own,
     * which then holds Helvetica.ttc, is laid out {@value MacFontFaceLookupChild#LAYOUTS} times in a System font that
     * has no slot of its name: one lookup, which finds no face. Once Ahem is copied back in the file's place, a new
     * version of the file, the next layout looks it up again, finds it and adds it as a slot. With
     * {@code prism.debugfonts} the child prints each lookup. The preconditions are those of
     * {@code MacFontReleaseTest.aFallbackFaceItsFontFileLacksIsLookedUpOnce}, and that a copy of the run's font reports
     * the file once Ahem is back; where one does not hold, the check is skipped with its figures, or fails under
     * {@code -Djfx.parity.require=true}. On every OS {@code MacFontBindingStructureTest} pins that layout hands the
     * factory the file the run's font reports.
     */
    @Test
    @Tag("crash-prone")
    @Timeout(2 * MacFontGoldens.CHILD_TIMEOUT_SECONDS)
    void aFaceItsFileLackedIsFoundOnceTheFileHoldsIt(TestInfo test) throws Exception {
        Map<String, String> values = runWithDebugFonts(MacFontFaceLookupChild.CHANGED_FILE);
        String file = values.get("file");
        List<String> unmet = MacFontReleaseTest.swappedFacePreconditions(values);
        expect(unmet, values, "file.ofACopyOnceBack", file, "CoreText reports another file for a copy of the run's"
                + " font once Ahem is back, so the lookup does not read it");
        List<String> problems = missingThenFound(MacFontFaceLookupChild.CHANGED_FILE, values, file, file);
        decide(test, MacFontReleaseVerdict.checked("a face its file lacked is looked up again and found once the file"
                + " holds it", figures(values, "missing.0", "missing.1", "found", "found.font", "file"), unmet,
                problems));
    }

    /**
     * A face its file lacked is looked up in another file that a run font of its name reports. Ahem, made from a file
     * of the child's own, which then holds Helvetica.ttc, is laid out {@value MacFontFaceLookupChild#LAYOUTS} times in
     * a System font that has no slot of its name: one lookup, which finds no face. Then a second Ahem, made from a copy
     * of Ahem in another directory, is laid out in the same System font: its file is looked up, the face found and
     * added as a slot. With {@code prism.debugfonts} the child prints each lookup. The preconditions are those of
     * {@code MacFontReleaseTest.aFallbackFaceItsFontFileLacksIsLookedUpOnce}, and that CoreText reports the second
     * file for the second font and for a copy of its run's font, whose name is Ahem; where one does not hold, the
     * check is skipped with its figures, or fails under {@code -Djfx.parity.require=true}.
     */
    @Test
    @Tag("crash-prone")
    @Timeout(2 * MacFontGoldens.CHILD_TIMEOUT_SECONDS)
    void aFaceItsFileLackedIsLookedUpInAnotherFileOfItsName(TestInfo test) throws Exception {
        Map<String, String> values = runWithDebugFonts(MacFontFaceLookupChild.MOVED_FILE);
        String moved = values.get("moved.file");
        List<String> unmet = MacFontReleaseTest.swappedFacePreconditions(values);
        expect(unmet, values, "moved.reported", moved, "CoreText reports another file for the second font");
        expect(unmet, values, "moved.file.ofACopy", moved, "CoreText reports another file for a copy of the second"
                + " font's run font, so the lookup does not read it");
        expect(unmet, values, "moved.run.font", FACE, "CoreText names the second font's run font another way");
        List<String> problems = missingThenFound(MacFontFaceLookupChild.MOVED_FILE, values, values.get("file"), moved);
        decide(test, MacFontReleaseVerdict.checked("a face its file lacked is looked up in another file of its name",
                figures(values, "missing.0", "missing.1", "found", "found.font", "file", "moved.file"), unmet,
                problems));
    }

    /**
     * Callers that need a face its file holds at once share one lookup: one read of the file, one copy of the run's
     * font, and each caller's own System font gets the very font file the lookup made as a slot. The child holds the
     * lookup's read of the file until a platform caller and a virtual one wait for it, then interrupts the platform
     * one, which keeps waiting with its interrupt cleared, and gets the font with its interrupt set again. No lookup
     * is left in flight, and nothing is remembered.
     */
    @Test
    @Tag("crash-prone")
    @Timeout(2 * MacFontGoldens.CHILD_TIMEOUT_SECONDS)
    void callersThatNeedAFaceAtOnceShareOneLookupAndItsFontFile() throws Exception {
        Map<String, String> values = run(MacFontFaceLookupChild.SHARED_FOUND);
        assertSharedOnce(values, "");
        for (String caller : MacFontFaceLookupChild.CALLERS) {
            assertTrue(Integer.parseInt(values.get(caller + ".slot")) >= 1, caller + " has the face as a slot: "
                    + values.get(caller + ".slot"));
            assertEquals("true", values.get(caller + ".leadersFont"), caller + "'s slot holds the lookup's font file");
        }
        assertEquals("false", values.get("remembered"), "a face that was found is not remembered as a miss");
    }

    /**
     * Callers that need a face its file lacks at once share one lookup and its miss: one read of the file and one copy
     * of the run's font, no slot for any of them, and the miss remembered, so that the next caller reads nothing. The
     * child holds the lookup as above.
     */
    @Test
    @Tag("crash-prone")
    @Timeout(2 * MacFontGoldens.CHILD_TIMEOUT_SECONDS)
    void callersThatNeedAFaceItsFileLacksAtOnceShareOneLookupAndItsMiss() throws Exception {
        Map<String, String> values = run(MacFontFaceLookupChild.SHARED_MISSING);
        assertSharedOnce(values, "");
        for (String caller : MacFontFaceLookupChild.CALLERS) {
            assertEquals("-1", values.get(caller + ".slot"), caller + " has no slot for a face its file lacks");
        }
        assertEquals("true", values.get("remembered"), "the miss is remembered");
        assertEquals("false", values.get("mayFindFace"), "the miss holds for the file unchanged");
        assertEquals("true", values.get("next.finished"), "the next caller waits for nothing");
        assertEquals("-1", values.get("next.slot"), "the next caller has no slot either");
        assertEquals(FontGoldens.NULL, values.get("next.failure"), "the next caller's call");
        assertEquals("1", values.get("next.reads"), "the next caller reads nothing");
        assertEquals("1", values.get("next.copies"), "the next caller copies nothing");
    }

    /**
     * A lookup that fails leaves nothing behind: its caller gets what it threw, the callers that wait for it get no
     * slot, nothing is remembered and no lookup is left in flight, so the next caller reads the file again, finds the
     * face and adds it. The child holds the lookup as above, then fails its read: the file is deleted before the read
     * goes on, a {@code FileNotFoundException}; and the read throws an {@code OutOfMemoryError}, for any error.
     */
    @Test
    @Tag("crash-prone")
    @Timeout(2 * MacFontGoldens.CHILD_TIMEOUT_SECONDS)
    void aLookupThatFailsLeavesNothingBehindAndTheNextCallerLooksAgain() throws Exception {
        Map<String, String> values = run(MacFontFaceLookupChild.SHARED_FAILED);
        Map<String, String> thrown = Map.of("unreadable.", "java.io.FileNotFoundException",
                                            "error.", "java.lang.OutOfMemoryError");
        for (Map.Entry<String, String> failure : thrown.entrySet()) {
            String prefix = failure.getKey();
            assertSharedOnce(values, prefix);
            assertEquals(failure.getValue(), values.get(prefix + "leader.failure"), prefix + " what the lookup threw");
            for (String caller : List.of("platform", "virtual")) {
                assertEquals("-1", values.get(prefix + caller + ".slot"), prefix + caller + " waited for a lookup"
                        + " that failed");
            }
            assertEquals("false", values.get(prefix + "remembered"), prefix + " a failed lookup is not remembered");
            assertEquals("true", values.get(prefix + "mayFindFace"), prefix + " the face is looked up again");
            assertTrue(Integer.parseInt(values.get(prefix + "next.slot")) >= 1, prefix + " the next caller finds the"
                    + " face: " + values.get(prefix + "next.slot"));
            assertEquals("true", values.get(prefix + "next.finished"), prefix + " the next caller waits for nothing");
            assertEquals(FontGoldens.NULL, values.get(prefix + "next.failure"), prefix + " the next caller's call");
            assertEquals("2", values.get(prefix + "next.reads"), prefix + " the next caller reads the file again");
            assertEquals("2", values.get(prefix + "next.copies"), prefix + " the next caller copies the run's font");
        }
    }

    /**
     * A caller that takes up a lookup once another lookup of the face has ended uses what that one left, and reads
     * nothing: the child holds the late caller right after the factory first answered that it may find the face,
     * until a second caller's whole call has run, which reads the file once. Then the late caller takes up a lookup of
     * its own, which adds the font file the first one stored where the file holds the face, and, where it lacks the
     * face, finds the miss remembered.
     */
    @Test
    @Tag("crash-prone")
    @Timeout(2 * MacFontGoldens.CHILD_TIMEOUT_SECONDS)
    void aCallerThatTakesUpALookupOnceAnotherHasEndedUsesWhatItLeft() throws Exception {
        Map<String, String> values = run(MacFontFaceLookupChild.LATE_LEADER);
        for (String prefix : List.of("found.", "missing.")) {
            assertEquals("true", values.get(prefix + "late.held"), prefix + " the late caller was held");
            assertEquals("true", values.get(prefix + "late.firstAnswer"), prefix + " it was held after a yes");
            assertEquals("true", values.get(prefix + "leader.finished"), prefix + " the leader's call ended");
            assertEquals("true", values.get(prefix + "late.finished"), prefix + " the late caller's call ended");
            assertEquals("1", values.get(prefix + "reads"), prefix + " the file is read once");
            assertEquals("1", values.get(prefix + "copies"), prefix + " the run's font is copied once");
            assertEquals(FontGoldens.NULL, values.get(prefix + "leader.failure"), prefix + " the leader's call");
            assertEquals(FontGoldens.NULL, values.get(prefix + "late.failure"), prefix + " the late caller's call");
        }
        assertTrue(Integer.parseInt(values.get("found.late.slot")) >= 1, "the late caller adds the face: "
                + values.get("found.late.slot"));
        assertEquals("true", values.get("found.late.leadersFont"), "the late caller adds the leader's font file");
        assertEquals("-1", values.get("missing.leader.slot"), "the file lacks the face");
        assertEquals("-1", values.get("missing.late.slot"), "the late caller finds the miss remembered");
    }

    /**
     * Faces of one name in two files are looked up and remembered apart. With a lookup of Ahem in a file that lacks it
     * held in its read, a caller whose run font reports a file that holds Ahem does not wait for it: it runs a lookup
     * of its own, which reads its file and adds the face while the first is still held. Once released, the first has no
     * slot, and only its file's miss is remembered. Layouts that alternate between two files that lack the face read
     * each once, and nothing more once both misses are remembered.
     */
    @Test
    @Tag("crash-prone")
    @Timeout(2 * MacFontGoldens.CHILD_TIMEOUT_SECONDS)
    void facesOfOneNameInTwoFilesAreLookedUpAndRememberedApart() throws Exception {
        Map<String, String> values = run(MacFontFaceLookupChild.TWO_FILES);
        assertEquals("true", values.get("lacking.held"), "the lookup in the file that lacks the face was held");
        assertEquals("true", values.get("holding.finishedWhileHeld"), "a caller of another file waits for nothing");
        assertEquals("2", values.get("readsWhileHeld"), "it reads its own file while the other lookup is held");
        assertTrue(Integer.parseInt(values.get("holding.slot")) >= 1, "it adds the face its file holds: "
                + values.get("holding.slot"));
        assertEquals(FACE, values.get("holding.font"), "the slot holds the face");
        assertEquals(FontGoldens.NULL, values.get("holding.failure"), "the call of the file that holds the face");
        assertEquals("true", values.get("lacking.finished"), "the held lookup ends once released");
        assertEquals("-1", values.get("lacking.slot"), "the file that lacks the face gives no slot");
        assertEquals(FontGoldens.NULL, values.get("lacking.failure"), "the call of the file that lacks the face");
        assertEquals("true", values.get("lacking.remembered"), "the file that lacks the face is remembered");
        assertEquals("false", values.get("holding.remembered"), "the file that holds the face is not");
        int alternating = 2 * MacFontFaceLookupChild.ALTERNATIONS;
        String noSlots = String.join(";", Collections.nCopies(alternating, "-1"));
        assertEquals(noSlots, values.get("alternating.slots"), "no file of the alternating layouts holds the face");
        assertEquals("2", values.get("alternating.reads"), "each of two files without the face is read once");
    }

    /**
     * What every shared lookup shows: the read held, both callers seen waiting for it, the interrupted one waiting on
     * with its interrupt cleared, one lookup in flight while held and none after, every thread ended, one read and one
     * copy, the waiters' calls returning normally, and the interrupt set again after the call.
     */
    private static void assertSharedOnce(Map<String, String> values, String prefix) {
        assertEquals("true", values.get(prefix + "leader.held"), prefix + "the lookup's read of the file was held");
        assertEquals("true", values.get(prefix + "waiting"), prefix + "a caller that came meanwhile waits for it");
        assertEquals("true", values.get(prefix + "interrupted.waitsOn"), prefix + "an interrupt does not end the wait");
        assertEquals("1", values.get(prefix + "inFlight.during"), prefix + "one lookup in flight");
        assertEquals("true", values.get(prefix + "finished"), prefix + "every caller's call ended");
        assertEquals("1", values.get(prefix + "reads"), prefix + "the callers read the file once");
        assertEquals("1", values.get(prefix + "copies"), prefix + "the callers copy the run's font once");
        for (String caller : List.of("platform", "virtual")) {
            assertEquals(FontGoldens.NULL, values.get(prefix + caller + ".failure"), prefix + caller + "'s call");
        }
        assertEquals("true", values.get(prefix + "platform.interruptedAfter"), prefix + "the interrupt is set again");
        assertEquals("0", values.get(prefix + "inFlight.after"), prefix + "no lookup is left in flight");
    }

    /**
     * The problems of a face missing from {@code missingFile} in each layout, then found in {@code foundFile}: each
     * layout before has no slot, the one after has a slot holding Ahem, and the child printed exactly one lookup of
     * each.
     */
    private static List<String> missingThenFound(String scenario, Map<String, String> values, String missingFile,
                                                 String foundFile) throws Exception {
        List<String> problems = new ArrayList<>();
        for (int i = 0; i < MacFontFaceLookupChild.LAYOUTS; i++) {
            expect(problems, values, "missing." + i, "-1", "a layout of a face its file lacks has no slot");
        }
        if (!(Integer.parseInt(values.get("found")) >= 1)) {
            problems.add("found = " + values.get("found") + ", expected a slot of 1 or more: the face is looked up"
                    + " again and added");
        }
        expect(problems, values, "found.font", FACE, "the slot the lookup adds holds the face");
        List<String> lines = stderr(scenario);
        long missing = lines.stream().filter((LOOKUP + missingFile + MacFontReleaseVerdict.NO_FACE)::equals).count();
        long found = lines.stream().filter((LOOKUP + foundFile + " index=0")::equals).count();
        if (missing != 1) {
            problems.add("the child printed " + missing + " lookups of " + FACE + " in " + missingFile + " that found"
                    + " no face, where the " + MacFontFaceLookupChild.LAYOUTS + " layouts make one");
        }
        if (found != 1) {
            problems.add("the child printed " + found + " lookups of " + FACE + " in " + foundFile + " that found it,"
                    + " where the layout after the change makes one");
        }
        return problems;
    }

    private static List<String> stderr(String scenario) throws Exception {
        Path stderr = MacFontGoldens.workDirectory().resolve(scenario + ".err");
        return new String(Files.readAllBytes(stderr), StandardCharsets.UTF_8).lines().toList();
    }

    private static String figures(Map<String, String> values, String... keys) {
        return Arrays.stream(keys).map(key -> key + "=" + values.get(key)).collect(Collectors.joining(", "));
    }

    private static void expect(List<String> problems, Map<String, String> values, String key, String expected,
                               String reason) {
        if (!Objects.equals(expected, values.get(key))) {
            problems.add(key + " = " + values.get(key) + ", expected " + expected + ": " + reason);
        }
    }

    private static void decide(TestInfo test, MacFontReleaseVerdict.Verdict verdict) {
        MacFontReleaseVerdict.decide(MacFontFaceLookupTest.class, test.getTestMethod().orElseThrow().getName(),
                                     verdict);
    }

    /** The scenario's values; the child runs with {@code prism.debugfonts}, so that it prints what is counted. */
    private static Map<String, String> runWithDebugFonts(String scenario) throws Exception {
        Map<String, String> values = MacFontGoldens.runChild(MacFontFaceLookupChild.class, scenario,
                                                             Map.of("prism.debugfonts", "true"));
        assertEquals("true", values.get("debugFonts"),
                     "the child ran without prism.debugfonts, so it printed no lookup to count");
        return values;
    }

    private static Map<String, String> run(String scenario) throws Exception {
        return MacFontGoldens.runChild(MacFontFaceLookupChild.class, scenario, Map.of());
    }
}
