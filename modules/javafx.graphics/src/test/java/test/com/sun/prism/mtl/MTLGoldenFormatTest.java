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

package test.com.sun.prism.mtl;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.TestAbortedException;
import test.com.sun.javafx.test.ParityGate;
import test.com.sun.prism.mtl.MTLGoldenFormat.FrameRef;
import test.com.sun.prism.mtl.MTLGoldenFormat.GoldenMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Metal golden's file format, comparison, gate and capture refusals, on every platform and without any native:
 * the parts of the harness that decide what a pass or a skip means must themselves be proven.
 */
public class MTLGoldenFormatTest {

    private static final String SHA_A = "a".repeat(64);
    private static final String SHA_B = "b".repeat(64);

    @TempDir
    Path temp;

    @Test
    void theTextFormatRoundTripsEscapesAndNull() {
        GoldenMap values = new GoldenMap();
        values.put("b.key", "back\\slash\nnew\rline");
        values.put("a.key", null);
        values.put("c.key", "plain=with equals");
        String text = MTLGoldenFormat.format(values, List.of("title", ""));
        assertTrue(text.startsWith("# title\n#\na.key=#null\n"), text);
        assertEquals(values, new TreeMap<>(MTLGoldenFormat.parse(text)));
    }

    @Test
    void aKeyWrittenTwiceIsAnError() {
        GoldenMap values = new GoldenMap();
        values.put("k", "1");
        assertThrows(IllegalStateException.class, () -> values.put("k", "1"));
        assertThrows(IllegalArgumentException.class, () -> MTLGoldenFormat.parse("k=1\nk=2\n"));
        assertThrows(IllegalArgumentException.class, () -> MTLGoldenFormat.parse("no separator\n"));
    }

    @Test
    void thePayloadIsAZlibStreamOfBigEndianFrames() {
        byte[] frame = MTLGoldenFormat.frameBytes(new int[] {0x11223344, 0xaabbccdd});
        assertArrayEquals(new byte[] {0x11, 0x22, 0x33, 0x44, (byte) 0xaa, (byte) 0xbb, (byte) 0xcc, (byte) 0xdd},
                frame);
        byte[] compressed = MTLGoldenFormat.deflate(frame);
        assertEquals(0x78, compressed[0] & 0xff, "an RFC 1950 header");
        assertArrayEquals(frame, MTLGoldenFormat.inflate(compressed));
        assertThrows(IllegalArgumentException.class,
                () -> MTLGoldenFormat.inflate(Arrays.copyOf(compressed, compressed.length - 3)));
        FrameRef ref = new FrameRef(2, 1, 0, MTLGoldenFormat.sha256Hex(frame));
        assertArrayEquals(new int[] {0x11223344, 0xaabbccdd}, MTLGoldenFormat.framePixels(frame, ref));
    }

    @Test
    void frameValuesParseAndRebase() {
        FrameRef frame = MTLGoldenFormat.frame("64x32;offset=8192;sha256=" + SHA_A);
        assertEquals(new FrameRef(64, 32, 8192, SHA_A), frame);
        assertEquals(8192, frame.byteLength());
        assertEquals("64x32;offset=0;sha256=" + SHA_A, frame.rebased(8192).value());
        assertNull(MTLGoldenFormat.frame("org.example.Class"));
    }

    @Test
    void framesCompareRelativeToTheirProfileAndInfoKeysAreIgnored() {
        Map<String, String> golden = Map.of(
                "default.s0000.c.a", "2x2;offset=4096;sha256=" + SHA_A,
                "default.s0001.c.b", "2x2;offset=4112;sha256=" + SHA_B,
                "default.pipeline", "com.sun.prism.mtl.MTLPipeline",
                "machine.os.build", "26A434",
                "info.producer.default", "jni(60)",
                "pow2.s0000.c.a", "2x2;offset=0;sha256=" + SHA_A);
        Map<String, String> same = Map.of(
                "default.s0000.c.a", "2x2;offset=0;sha256=" + SHA_A,
                "default.s0001.c.b", "2x2;offset=16;sha256=" + SHA_B,
                "default.pipeline", "com.sun.prism.mtl.MTLPipeline",
                "info.producer.default", "ffm(0)");
        assertEquals(List.of(), MTLGoldenFormat.differences(golden, same, key -> key.startsWith("default.")));
        Map<String, String> changed = Map.of(
                "default.s0000.c.a", "2x2;offset=0;sha256=" + SHA_B,
                "default.pipeline", "com.sun.prism.mtl.MTLPipeline");
        List<String> differences = MTLGoldenFormat.differences(golden, changed, key -> key.startsWith("default."));
        assertEquals(2, differences.size(), differences.toString());
        assertTrue(differences.get(0).startsWith("default.s0000.c.a: golden="), differences.toString());
        assertTrue(differences.get(1).startsWith("only in the golden: default.s0001.c.b"), differences.toString());
        assertTrue(MTLGoldenFormat.report(differences).startsWith("2 difference(s):"));
    }

    @Test
    void aChangedFrameIsDescribedAndWrittenAsPamImages() throws Exception {
        int[] golden = {0xff000000, 0xff102030, 0x80402010, 0};
        int[] actual = {0xff000000, 0xff102031, 0x80402010, 0x01000000};
        String description = MTLGoldenFormat.describe(2, 2, golden, actual);
        assertEquals("2 of 4 pixels differ in [1,0..1,1], max channel delta 1, first: (1,0) golden=ff102030"
                + " now=ff102031 (1,1) golden=00000000 now=01000000", description);
        assertEquals("no pixel differs", MTLGoldenFormat.describe(2, 2, golden, golden.clone()));
        MTLGoldenFormat.writeDiff(temp, "default-s0001", 2, 2, golden, actual);
        byte[] pam = Files.readAllBytes(temp.resolve("default-s0001.actual.pam"));
        String header = "P7\nWIDTH 2\nHEIGHT 2\nDEPTH 4\nMAXVAL 255\nTUPLTYPE RGB_ALPHA\nENDHDR\n";
        assertEquals(header, new String(pam, 0, header.length(), StandardCharsets.US_ASCII));
        assertEquals(header.length() + 16, pam.length);
        assertEquals(0x10, pam[header.length() + 4]);
        assertEquals((byte) 0xff, pam[header.length() + 7]);
        assertTrue(Files.isRegularFile(temp.resolve("default-s0001.diff.pam")));
        assertTrue(MTLGoldenFormat.uniform(new int[] {5, 5, 5}));
        assertFalse(MTLGoldenFormat.uniform(golden));
    }

    @Test
    void aMachineMismatchSkipsAndFailsWhenTheOracleIsRequired() {
        Map<String, String> golden = Map.of("machine.os.build", "26A434", "capture.provenance", "the Mac mini");
        ParityGate.Ledger ledger = ParityGate.ledger(MTLGoldenFormatTest.class);
        MTLGoldens.requireSameMachine(golden, Map.of("machine.os.build", "26A434"), ledger, false);
        TestAbortedException skipped = assertThrows(TestAbortedException.class, () -> MTLGoldens.requireSameMachine(
                golden, Map.of("machine.os.build", "26B12"), ledger, false));
        assertTrue(skipped.getMessage().contains("machine.os.build: golden=26A434 this machine=26B12"),
                skipped.getMessage());
        assertTrue(skipped.getMessage().contains("the Mac mini"), skipped.getMessage());
        AssertionError failed = assertThrows(AssertionError.class, () -> MTLGoldens.requireSameMachine(golden,
                Map.of("machine.os.build", "26A434", "machine.gpu.model", "Apple M7"), ledger, true));
        assertTrue(failed.getMessage().contains("machine.gpu.model: golden=null this machine=Apple M7"),
                failed.getMessage());
    }

    @Test
    void anExistingGoldenIsOverwrittenOnlyWhenRegenerating() throws Exception {
        Path resources = temp.resolve("resources");
        Path classes = temp.resolve("classes");
        MTLGoldens.write(resources, classes, "g.txt", new byte[] {1}, false);
        assertArrayEquals(new byte[] {1}, Files.readAllBytes(classes.resolve("g.txt")));
        AssertionError refused = assertThrows(AssertionError.class,
                () -> MTLGoldens.write(resources, classes, "g.txt", new byte[] {2}, false));
        assertTrue(refused.getMessage().contains(MTLGoldens.REGENERATE_PROPERTY), refused.getMessage());
        assertArrayEquals(new byte[] {1}, Files.readAllBytes(resources.resolve("g.txt")));
        MTLGoldens.write(resources, classes, "g.txt", new byte[] {2}, true);
        assertArrayEquals(new byte[] {2}, Files.readAllBytes(resources.resolve("g.txt")));
    }

    @Test
    void theChildGetsNeitherAgentsNorTheParentsPipelineSettings() {
        for (String dropped : List.of("-agentlib:jdwp=transport=dt_socket", "-javaagent:x.jar", "-Xdebug",
                "-XX:ErrorFile=x", "-Dglass.platform=Headless", "-Dprism.order=sw", "-Djava.awt.headless=true",
                "-Djava.io.tmpdir=/x", "-Djfx.mtl.golden.passes=3")) {
            assertTrue(MTLGoldens.droppedFromChild(dropped), dropped);
        }
        for (String kept : List.of("--upgrade-module-path", "--enable-native-access=javafx.graphics",
                "-Djava.library.path=x", "--add-modules")) {
            assertFalse(MTLGoldens.droppedFromChild(kept), kept);
        }
    }

    @Test
    void aCrashSignatureKeepsTheSignalAndFrameWithoutAddresses() throws Exception {
        Path log = temp.resolve("hs_err_pid1.log");
        Files.writeString(log, String.join("\n",
                "#",
                "# A fatal error has been detected by the Java Runtime Environment:",
                "#",
                "#  SIGSEGV (0xb) at pc=0x000000018c3a1c28, pid=4242, tid=259",
                "#",
                "# Problematic frame:",
                "# C  [libobjc.A.dylib+0x7c28]  objc_release+0x8",
                "#"));
        assertEquals("SIGSEGV;C [libobjc.A.dylib+0x7c28] objc_release+0x8", MTLGoldens.crashSignature(log));
    }

    @Test
    void theJniLinkLogNamesClassAndMethod() throws Exception {
        Path log = temp.resolve("jni-resolve.log");
        Files.writeString(log, "[0.462s][debug][jni,resolve] [Dynamic-linking native method"
                + " com.sun.prism.mtl.MTLPipeline.nSupportsMTL ... JNI]\n[0.497s][debug][jni,resolve]"
                + " [Dynamic-linking native method com.sun.prism.mtl.MTLContext.nSetCompositeMode ... JNI]\n"
                + "[0.5s][debug][jni,resolve] [Dynamic-linking native method java.lang.Object.hashCode ... JNI]\n");
        assertEquals(List.of("MTLContext.nSetCompositeMode", "MTLPipeline.nSupportsMTL"),
                MTLGoldens.linkedNatives(log));
    }
}
