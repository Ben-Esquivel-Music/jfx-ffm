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

import com.sun.javafx.font.DFontDecoderShim;
import com.sun.javafx.font.MacFontOracleShim;
import com.sun.javafx.font.coretext.OSShim;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import test.com.sun.javafx.font.FontGoldenHarness.GoldenMap;
import test.com.sun.javafx.test.ParityGate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parity oracle for {@code DFontDecoder} on installed fonts, against {@value #GOLDEN}, captured from the JNI build of
 * commit {@value MacFontGoldens#CAPTURE_COMMIT}. {@code createCTFont} finds a font by display name among all available
 * fonts, whatever its file, so no registration is needed; the {@code .dfont} files are in
 * {@link MacFontProcessGoldenTest}.
 * <p>
 * Left out on purpose: a table tag the font lacks, because {@code getCTFontTable} then crashes the JVM in
 * {@code CFDataGetLength(NULL)} (US-067), and the {@code sbix} table of Apple Color Emoji, which is hundreds of
 * megabytes (its length is recorded).
 */
@EnabledOnOs(OS.MAC)
public class MacDFontGoldenTest {

    static final String GOLDEN = "mac-dfont-golden.txt";

    private static final ParityGate.Ledger LEDGER = ParityGate.ledger(MacDFontGoldenTest.class);

    private static final int SBIX = 0x73626978;

    static final List<String[]> NAMES = List.of(
            new String[] {"helvetica", "Helvetica"},
            new String[] {"helveticabold", "Helvetica Bold"},
            new String[] {"timesroman", "Times Roman"},
            new String[] {"timesnewroman", "Times New Roman"},
            new String[] {"menloregular", "Menlo Regular"},
            new String[] {"couriernew", "Courier New"},
            new String[] {"geezapro", "Geeza Pro Regular"},
            new String[] {"hiraginosansw3", "Hiragino Sans W3"},
            new String[] {"applesdgothicneo", "Apple SD Gothic Neo Regular"},
            new String[] {"thonburi", "Thonburi"},
            new String[] {"thonburiui", ".ThonburiUI Regular"},
            new String[] {"applecoloremoji", "Apple Color Emoji"},
            new String[] {"lowercase", "helvetica"},
            new String[] {"trailingspace", "Helvetica "},
            new String[] {"nosuchfont", "No Such Font"},
            new String[] {"empty", ""},
            new String[] {"lonesurrogate", "Helvetica\uD800"},
            new String[] {"null", null});

    static final Set<String> NOT_DECODED = Set.of("applecoloremoji");

    @Test
    public void dfontDecoderMatchesTheJniGolden() throws Exception {
        MacFontGoldens.captureOrVerify(MacDFontGoldenTest.class, GOLDEN, header(), true,
                                       MacDFontGoldenTest::capture, MacDFontGoldenTest::assertCoverage);
    }

    @AfterAll
    static void theOracleRan() {
        LEDGER.assertOracleRan();
    }

    /** Runs on any macOS, where the gated golden would skip. */
    @Test
    public void helveticaDecodes() {
        OSShim.ensureLoaded();
        long font = DFontDecoderShim.createCTFont("Helvetica");
        assertNotEquals(0L, font, "createCTFont(Helvetica)");
        int[] tags = DFontDecoderShim.getCTFontTags(font);
        assertNotNull(tags, "tags");
        assertTrue(tags.length > 5, "Helvetica has " + tags.length + " tables");
        DFontDecoderShim.releaseCTFont(font);
        DFontDecoderShim.Decoded decoded = DFontDecoderShim.decode("Helvetica");
        assertNotNull(decoded.bytes(), "decode(Helvetica): " + decoded.failure());
        assertEquals(tags.length, ByteBuffer.wrap(decoded.bytes()).getShort(4), "numTables of the decoded file");
    }

    static List<String> header() {
        return MacFontGoldens.header("macOS .dfont decoder golden (DFontDecoder, installed fonts)", List.of(
                "  dfn.<name>.input        the display name passed",
                "  dfn.<name>.ref          found|ps name, or 0",
                "  dfn.<name>.format       getCTFontFormat as hex",
                "  dfn.<name>.tags         getCTFontTags as four-character codes",
                "  dfn.<name>.table.<tag>  getCTFontTable: length;sha256",
                "  dfn.<name>.decode       decode(): length;sha256|format|tag:offset:length;... or the exception"));
    }

    static void capture(GoldenMap out) {
        OSShim.ensureLoaded();
        for (String[] entry : NAMES) {
            String key = "dfn." + entry[0];
            String name = entry[1];
            out.put(key + ".input", MacGoldenFormat.text(name));
            long font = DFontDecoderShim.createCTFont(name);
            if (font == 0) {
                out.put(key + ".ref", "0");
            } else {
                out.put(key + ".ref", "found|" + MacGoldenFormat.text(MacFontOracleShim.fontPostScriptName(font)));
                out.put(key + ".format", Integer.toHexString(DFontDecoderShim.getCTFontFormat(font)));
                int[] tags = DFontDecoderShim.getCTFontTags(font);
                List<String> tagNames = new ArrayList<>();
                for (int tag : tags) {
                    tagNames.add(MacGoldenFormat.fourcc(tag));
                }
                out.put(key + ".tags", tags.length + "|" + FontGoldens.joinList(tagNames));
                for (int tag : tags) {
                    String value;
                    if (tag == SBIX && NOT_DECODED.contains(entry[0])) {
                        value = "skipped;" + MacFontOracleShim.fontTableLength(font, tag);
                    } else {
                        value = FontGoldenFormat.bytesSummary(DFontDecoderShim.getCTFontTable(font, tag));
                    }
                    out.put(key + ".table." + MacGoldenFormat.fourcc(tag), value);
                }
                DFontDecoderShim.releaseCTFont(font);
            }
            if (!NOT_DECODED.contains(entry[0])) {
                out.put(key + ".decode", decoded(DFontDecoderShim.decode(name)));
            }
        }
    }

    /** {@code length;sha256|format|tag:offset:length;...} of a decoded file, or its failure. */
    static String decoded(DFontDecoderShim.Decoded decoded) {
        if (decoded.bytes() == null) {
            return "failed|" + MacGoldenFormat.text(decoded.failure());
        }
        ByteBuffer file = ByteBuffer.wrap(decoded.bytes());
        int numTables = file.getShort(4) & 0xFFFF;
        List<String> directory = new ArrayList<>();
        for (int i = 0; i < numTables; i++) {
            int record = 12 + 16 * i;
            directory.add(MacGoldenFormat.fourcc(file.getInt(record)) + ":" + file.getInt(record + 8) + ":"
                    + file.getInt(record + 12));
        }
        return FontGoldenFormat.bytesSummary(decoded.bytes()) + "|" + Integer.toHexString(file.getInt(0)) + "|"
                + FontGoldens.joinList(directory);
    }

    static void assertCoverage(Map<String, String> values) {
        assertEquals("0", values.get("dfn.nosuchfont.ref"), "an unknown name finds nothing");
        assertEquals("0", values.get("dfn.null.ref"), "null finds nothing");
        assertTrue(values.get("dfn.helvetica.ref").startsWith("found|"), "Helvetica is found");
        assertTrue(values.get("dfn.hiraginosansw3.format").equals("4f54544f"), "Hiragino is OpenType CFF ('OTTO')");
        assertTrue(values.get("dfn.nosuchfont.decode").startsWith("failed|"), "decoding an unknown name fails");
        assertTrue(values.get("dfn.null.decode").startsWith("failed|"), "decoding null fails");
        assertTrue(values.get("dfn.helvetica.decode").contains("|10000|")
                   || values.get("dfn.helvetica.decode").contains("|74727565|"), "Helvetica decodes to TrueType");
    }
}
