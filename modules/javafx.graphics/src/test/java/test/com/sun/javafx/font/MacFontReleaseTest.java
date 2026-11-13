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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import test.com.sun.javafx.font.FontGoldenRuntime.ChildJvm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The CF objects the C of commit {@value MacFontGoldens#CAPTURE_COMMIT} created or copied and never released, and
 * those a Java exception could leave unreleased (US-067): each is now released exactly once. Each observation runs
 * in a child JVM ({@link MacFontReleaseChild}), because an over-release ends the JVM. A leak shows as a retain
 * count that rises with the calls, as in {@link MacFontStressTest}, or as a heap that grows with them.
 */
@EnabledOnOs(OS.MAC)
public class MacFontReleaseTest {

    private static final String RETAIN_CALLS = Integer.toString(MacFontStressChild.RETAIN_CALLS);

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
