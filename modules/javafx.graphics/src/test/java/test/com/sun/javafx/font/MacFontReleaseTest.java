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

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import test.com.sun.javafx.font.FontGoldenRuntime.ChildJvm;
import test.com.sun.javafx.font.MacFontReleaseVerdict.HeapProbe;
import test.com.sun.javafx.font.MacFontReleaseVerdict.Verdict;
import test.com.sun.javafx.test.ParityGate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The CF objects the C of commit {@value MacFontGoldens#CAPTURE_COMMIT} created or copied and never released, and
 * those a Java exception could leave unreleased (US-067): each is now released exactly once. Each observation runs
 * in a child JVM ({@link MacFontReleaseChild}), because an over-release ends the JVM. A leak shows as a retain
 * count that rises with the calls, as in {@link MacFontStressTest}, or as a heap that grows with them.
 * <p>
 * Three checks need a machine that can show their defect: the two heap checks of the autorelease pool need a region
 * without a pool that leaves more than {@link #AUTORELEASED_BLOCKS_PER_CALL} blocks per call, and the fallback-face
 * check needs a fallback face its font file lacks. {@link MacFontReleaseVerdict} decides them, and each prints its
 * verdict with the measured figures on one line of the test JVM's standard error. Where the machine cannot show the
 * defect the check is skipped, or fails under {@code -Djfx.parity.require=true}; each check's comment names what
 * still pins its code on every machine, and what that pins.
 */
@EnabledOnOs(OS.MAC)
public class MacFontReleaseTest {

    private static final String RETAIN_CALLS = Integer.toString(MacFontStressChild.RETAIN_CALLS);

    /**
     * Measured in the child on macOS 27.2.1, smallest of three rounds of 300 calls: without a pool a cascade list call
     * of Helvetica leaves about 230 heap blocks, with its pool 0.3 at most, on either kind of thread. The bound is
     * more than twice the second and less than half the first. Elsewhere the region without a pool may not reach the
     * bound: on the macOS 15 runners of CI run 45022158121 it left about 6.0, so the heap there cannot show the
     * defect.
     */
    static final double AUTORELEASED_BLOCKS_PER_CALL = 10.0;

    /*
     * Surefire copies what the test JVM writes to its file descriptor 2 to the Maven console, while the reason of a
     * skip reaches only the XML report, which the macOS CI jobs do not keep.
     */
    private static final PrintStream FORK_STDERR =
            new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);

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
     * fails. The threads call the cache directly and draw nothing: two threads drawing into the one bitmap is
     * another defect.
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
     * A fallback face that is not in its font file is looked up there once a process: later layouts of the same
     * text neither copy its font again nor parse its file. Its slot stays -1, so every layout gives the glyphs of the
     * first. The child runs with {@code prism.debugfonts}: the factory prints a line per lookup in a font file and
     * layout one per fallback font it resolves. Where no fallback face of the text is missing from its font file, as
     * on the macOS 15 runners of CI run 45022158121, the path is not reached: the check is skipped with what the child
     * printed and laid out, or fails under {@code -Djfx.parity.require=true}. On every machine
     * {@link #aFaceItsFontFileLacksIsRememberedByItsName} pins the factory's memory, and
     * {@code MacFontBindingStructureTest.layoutAsksForAFallbackFaceBeforeCopyingTheRunFont} pins in the class file
     * that layout copies the run's font and adds the copy only where the factory answers that it may find the face.
     */
    @Test
    @Tag("crash-prone")
    @Timeout(2 * MacFontGoldens.CHILD_TIMEOUT_SECONDS)
    void aFallbackFaceItsFontFileLacksIsLookedUpOnce(TestInfo test) throws Exception {
        Map<String, String> values = MacFontGoldens.runChild(MacFontReleaseChild.class,
                MacFontReleaseChild.FALLBACK_FACE, Map.of("prism.debugfonts", "true"));
        assertEquals("true", values.get("debugFonts"),
                     "the child ran without prism.debugfonts, so it printed no lookup to count");
        int layouts = Integer.parseInt(values.get("layouts"));
        List<String> glyphs = new ArrayList<>();
        for (int i = 0; i < layouts; i++) {
            glyphs.add(values.get("glyphs." + i));
        }
        Path stderr = MacFontGoldens.workDirectory().resolve(MacFontReleaseChild.FALLBACK_FACE + ".err");
        List<String> lines = new String(Files.readAllBytes(stderr), StandardCharsets.UTF_8).lines().toList();
        decide(test, MacFontReleaseVerdict.lookedUpOnce(glyphs, lines));
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
     * during the call before it returns, instead of leaving it to wait for the thread's exit. The control, the same
     * region without a pool, must leave more than {@link #AUTORELEASED_BLOCKS_PER_CALL} blocks per call, or the heap
     * cannot show the difference: the check is then skipped with the figures, or fails under
     * {@code -Djfx.parity.require=true}. On every machine {@code MacFontBindingStructureTest} pins in the class file
     * that {@code getCascadeList} calls {@code pooledCascadeList} outside the branch where {@code Thread.isVirtual}
     * answers true, and that {@code pooledCascadeList} pushes its pool before it copies the language codes, and pops
     * it before each return after it reads the list and in a handler that catches everything the read throws, javac's
     * copies of a finally; and {@code MacObjCNativeTest.aPoppedPoolReleasesWhatWasAutoreleasedIntoIt} pins that a
     * pop releases what was autoreleased into the pool.
     */
    @Test
    @Tag("crash-prone")
    @Timeout(2 * MacFontGoldens.CHILD_TIMEOUT_SECONDS)
    void aCascadeListCallOnAPlatformThreadDrainsWhatItAutoreleased(TestInfo test) throws Exception {
        Map<String, String> values = run(MacFontReleaseChild.CASCADE_LIST_POOL);
        requireProbes(values, false);
        decide(test, MacFontReleaseVerdict.drainedOnAPlatformThread(HeapProbe.of(values, "subject"),
                HeapProbe.of(values, "control"), AUTORELEASED_BLOCKS_PER_CALL));
    }

    /**
     * A cascade list call on a virtual thread leaves no block that CoreText autoreleased during it waiting on the
     * caller's carrier, where the same region run on the carrier without a pool leaves more than
     * {@link #AUTORELEASED_BLOCKS_PER_CALL} per call. Every call answers the platform thread's list. Starting and
     * waiting for a thread that runs nothing must stay below the bound, so none is subtracted. A machine where the
     * region without a pool does not exceed the bound, or the hand-off does not stay below it, cannot show the drain:
     * the check is skipped with the figures, or fails under {@code -Djfx.parity.require=true}. On every machine
     * {@code MacFontBindingStructureTest} pins in the class file that {@code getCascadeList} calls
     * {@code cascadeListOnAPlatformThread} only where {@code Thread.isVirtual} answers true, and returns what it
     * answers, and that the hand-off calls {@code Thread.ofPlatform} before it starts a thread, starts one before it
     * waits for the task, and never runs the task itself.
     */
    @Test
    @Tag("crash-prone")
    @Timeout(2 * MacFontGoldens.CHILD_TIMEOUT_SECONDS)
    void aCascadeListCallOnAVirtualThreadDrainsWhatItAutoreleased(TestInfo test) throws Exception {
        Map<String, String> values = run(MacFontReleaseChild.CASCADE_LIST_VIRTUAL);
        assertEquals(FontGoldens.NULL, values.get("handOff.failure"), "the hand-off probe's calls");
        requireProbes(values, true);
        decide(test, MacFontReleaseVerdict.drainedOnAVirtualThread(HeapProbe.of(values, "subject"),
                HeapProbe.of(values, "control"), HeapProbe.of(values, "handOff"), AUTORELEASED_BLOCKS_PER_CALL));
    }

    /**
     * An interrupt does not end a virtual caller's wait for its cascade list, since the caller releases the font as
     * soon as the call returns: an interrupted caller gets the whole list, and its interrupt status is still set.
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
     * The same at the wait, without depending on how fast the task is: a virtual caller that interrupted itself
     * waits for a task that answers only once the caller is seen parked in that wait. It parks only once its
     * interrupt status is clear, which the wait's first {@code get()} does by throwing, so the wait has seen the
     * interrupt, goes on, and sets it again once the task answers. In the call above, a task that is done before
     * the first {@code get()} is never asked about the interrupt.
     */
    @Test
    @Tag("crash-prone")
    @Timeout(2 * MacFontGoldens.CHILD_TIMEOUT_SECONDS)
    void anInterruptTheWaitClearsDoesNotEndItAndIsSetAgainOnceTheTaskAnswers() throws Exception {
        Map<String, String> values = run(MacFontReleaseChild.CASCADE_LIST_WAIT_INTERRUPTED);
        assertEquals("true", values.get("virtual"), "the caller is not a virtual thread");
        assertEquals("true", values.get("interruptedBefore"), "the caller did not interrupt itself");
        assertEquals("true", values.get("parkedInTheWait"), "the caller was not seen parked in its wait for the"
                + " running task (state " + values.get("callerState") + ", failure " + values.get("failure")
                + "), so the interrupt ended the wait or the wait never saw it");
        assertEquals("false", values.get("doneWhenParked"),
                     "the task was done when the caller parked, so the wait had nothing left to outlast");
        assertEquals(FontGoldens.NULL, values.get("failure"), "the interrupted caller's wait for the task");
        assertEquals("true", values.get("theTasksArray"),
                     "the interrupted caller gets the task's array, not " + values.get("list"));
        assertEquals("true", values.get("interruptedAfter"), "the interrupt the wait cleared is set again");
    }

    /**
     * Nor does an error of the wait itself, such as an {@code OutOfMemoryError} of its own allocation: the caller
     * leaves only once the platform thread's task is done, and then throws that error. The child hands the wait a task
     * whose first {@code get()} throws while the task runs, and lets the task finish only after the caller has had a
     * second to leave.
     */
    @Test
    @Tag("crash-prone")
    @Timeout(2 * MacFontGoldens.CHILD_TIMEOUT_SECONDS)
    void anErrorOfTheWaitItselfIsThrownOnlyOnceTheTaskIsDone() throws Exception {
        Map<String, String> values = run(MacFontReleaseChild.CASCADE_LIST_WAIT_FAILS);
        assertEquals("true", values.get("virtual"), "the caller is not a virtual thread");
        assertEquals("true", values.get("waitFailed"), "the caller's wait never called get()");
        assertEquals("false", values.get("doneWhenTheWaitFailed"),
                     "the task was done when get() threw, so the wait had nothing left to outlast");
        assertEquals("true", values.get("doneWhenTheCallerLeft"), "the caller left its wait while the task still ran"
                + " (left within the hold: " + values.get("leftWhileTheTaskRan") + "), where a real task still reads"
                + " the font the caller releases next");
        assertEquals("true", values.get("thrownIsTheWaitsError"),
                     "the caller throws the error of its wait, not " + values.get("thrown"));
        assertEquals(FontGoldens.NULL, values.get("list"), "a caller whose wait failed gets no list");
    }

    /** The probes' heap figures mean something only if each ran on its kind of thread and answered the list. */
    private static void requireProbes(Map<String, String> values, boolean virtual) {
        assertNotEquals("0", values.get("entries"), "Helvetica has no cascade list, so no call autoreleases one");
        List<String> problems = new ArrayList<>();
        for (String probe : List.of("subject", "control")) {
            expect(problems, values, probe + ".virtual", Boolean.toString(virtual), "the thread the probe ran on");
            expect(problems, values, probe + ".failure", FontGoldens.NULL, "the probe's calls");
            expect(problems, values, probe + ".sameList", "true", "every call answers the cascade list of the font");
        }
        assertEquals(List.of(), problems);
    }

    private static void decide(TestInfo test, Verdict verdict) {
        FORK_STDERR.println("MacFontReleaseTest." + test.getTestMethod().orElseThrow().getName() + " "
                + verdict.outcome() + ": " + verdict.message());
        MacFontReleaseVerdict.require(MacFontReleaseTest.class, verdict, ParityGate.required());
    }

    private static void expect(List<String> problems, Map<String, String> values, String key, String expected,
                               String reason) {
        if (!expected.equals(values.get(key))) {
            problems.add(key + " = " + values.get(key) + ", expected " + expected + ": " + reason);
        }
    }

    private static Map<String, String> run(String scenario) throws Exception {
        return MacFontGoldens.runChild(MacFontReleaseChild.class, scenario, Map.of());
    }
}
