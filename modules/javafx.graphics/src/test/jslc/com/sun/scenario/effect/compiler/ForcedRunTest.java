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

package com.sun.scenario.effect.compiler;

import com.sun.scenario.effect.compiler.JSLC.JSLCInfo;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import org.antlr.v4.runtime.misc.ParseCancellationException;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether a run compiles and writes an output, with and without {@code force}. The build forces both generators,
 * because the file times say nothing about a changed compiler or generator.
 */
public class ForcedRunTest extends ProgramBase {

    private static final String PROGRAM = """
            param sampler baseImg;
            param float2 offset;
            void main() {
                float val = sample(baseImg, pos0 - offset).a;
                color = float4(1.0 - val);
            }
            """;

    // A renamed sampler changes the bytes of every output type, also of the Prism peer, which holds the names of the
    // parameters but not the code of main. The new name is as long as the old one, so that a comparison of lengths
    // would miss the change. Both are in MSLBackend.VAR_MAP: the Metal shader reads any other name without its
    // "uniforms." prefix, which would change its length.
    private static final String PROGRAM_WITH_ITS_SAMPLER_RENAMED = PROGRAM.replace("baseImg", "origImg");

    // Only the two peers copy a glue block's text into their files. Its accented letter takes two bytes in UTF-8 and
    // one in a single-byte charset, so a comparison that encodes otherwise than the write sees a change.
    private static final String PROGRAM_WITH_A_NON_ASCII_GLUE_BLOCK = "<<\n// caf\u00e9\n>>\n" + PROGRAM;

    private static final String PROGRAM_WITH_A_SYNTAX_ERROR = PROGRAM.replace("1.0 - val);", "1.0 - val)");

    private static final FileTime AGED = FileTime.fromMillis(1_000_000_000_000L);

    private static final long UP_TO_DATE = AGED.toMillis() - 1;

    @TempDir
    Path tmp;

    enum Output {
        D3D(JSLC.OUT_D3D),
        ES2(JSLC.OUT_ES2),
        METAL(JSLC.OUT_MTL),
        JAVA_PEER(JSLC.OUT_JAVA),
        PRISM_PEER(JSLC.OUT_PRISM);

        private final int outType;

        Output(int outType) {
            this.outType = outType;
        }
    }

    @ParameterizedTest
    @EnumSource(Output.class)
    public void aForcedRunRewritesAnOutputThatIsUpToDateByTimeWhenItsProgramChanged(Output output) throws Exception {
        byte[] changedBytes = bytesOfACleanRun(output, PROGRAM_WITH_ITS_SAMPLER_RENAMED);
        Path file = agedOutput(output, PROGRAM);
        assertTheRenameChangesTheBytesButNotTheLength(Files.readAllBytes(file), changedBytes);

        compile(output, PROGRAM_WITH_ITS_SAMPLER_RENAMED, UP_TO_DATE, true);

        assertArrayEquals(changedBytes, Files.readAllBytes(file));
    }

    @ParameterizedTest
    @EnumSource(Output.class)
    public void aForcedRunKeepsTheFileTimeOfAnOutputWhoseBytesAreUnchangedThoughItsProgramIsNewer(Output output)
            throws Exception {
        Path file = agedOutput(output, PROGRAM);
        byte[] bytes = Files.readAllBytes(file);

        compile(output, PROGRAM, OUT_OF_DATE, true);

        assertEquals(AGED, Files.getLastModifiedTime(file));
        assertArrayEquals(bytes, Files.readAllBytes(file));
    }

    @ParameterizedTest
    @EnumSource(value = Output.class, names = {"JAVA_PEER", "PRISM_PEER"})
    public void aForcedRunComparesInTheCharsetItWritesWithSoAnUnchangedNonAsciiOutputKeepsItsFileTime(Output output)
            throws Exception {
        Path file = agedOutput(output, PROGRAM_WITH_A_NON_ASCII_GLUE_BLOCK);
        byte[] bytes = Files.readAllBytes(file);
        assertTrue(new String(bytes, Charset.defaultCharset()).length() < bytes.length,
                "the glue block's accented letter did not reach the output as more than one byte");

        compile(output, PROGRAM_WITH_A_NON_ASCII_GLUE_BLOCK, OUT_OF_DATE, true);

        assertEquals(AGED, Files.getLastModifiedTime(file));
        assertArrayEquals(bytes, Files.readAllBytes(file));
    }

    @ParameterizedTest
    @EnumSource(Output.class)
    public void aForcedRunWritesAMissingOutput(Output output) throws Exception {
        Path file = agedOutput(output, PROGRAM);
        byte[] bytes = Files.readAllBytes(file);
        Files.delete(file);

        compile(output, PROGRAM, UP_TO_DATE, true);

        assertArrayEquals(bytes, Files.readAllBytes(file));
    }

    @ParameterizedTest
    @EnumSource(Output.class)
    public void aForcedRunFailsAsAnUnforcedOneOnAnErrorInAProgramWhoseOutputIsUpToDate(Output output)
            throws Exception {
        JSLCInfo unforced = run(tmp.resolve("unforced"), output, false);
        String unforcedError = assertThrowsExactly(ParseCancellationException.class,
                () -> JSLC.compile(unforced, PROGRAM_WITH_A_SYNTAX_ERROR, OUT_OF_DATE)).getMessage();
        Path file = agedOutput(output, PROGRAM);
        byte[] bytes = Files.readAllBytes(file);

        ParseCancellationException e = assertThrowsExactly(ParseCancellationException.class,
                () -> compile(output, PROGRAM_WITH_A_SYNTAX_ERROR, UP_TO_DATE, true));

        assertEquals(unforcedError, e.getMessage());
        assertEquals(AGED, Files.getLastModifiedTime(file));
        assertArrayEquals(bytes, Files.readAllBytes(file));
    }

    @ParameterizedTest
    @EnumSource(Output.class)
    public void withoutForceAnOutputThatIsUpToDateByTimeKeepsItsBytesThoughItsProgramChanged(Output output)
            throws Exception {
        byte[] changedBytes = bytesOfACleanRun(output, PROGRAM_WITH_ITS_SAMPLER_RENAMED);
        Path file = agedOutput(output, PROGRAM);
        byte[] bytes = Files.readAllBytes(file);
        assertTheRenameChangesTheBytesButNotTheLength(bytes, changedBytes);

        compile(output, PROGRAM_WITH_ITS_SAMPLER_RENAMED, UP_TO_DATE, false);

        assertEquals(AGED, Files.getLastModifiedTime(file));
        assertArrayEquals(bytes, Files.readAllBytes(file));
    }

    @ParameterizedTest
    @EnumSource(Output.class)
    public void withoutForceAnOutOfDateOutputIsWrittenAgainThoughItsBytesAreUnchanged(Output output)
            throws Exception {
        Path file = agedOutput(output, PROGRAM);
        byte[] bytes = Files.readAllBytes(file);

        compile(output, PROGRAM, OUT_OF_DATE, false);

        assertNotEquals(AGED, Files.getLastModifiedTime(file));
        assertArrayEquals(bytes, Files.readAllBytes(file));
    }

    private Path agedOutput(Output output, String program) throws Exception {
        Path file = compile(output, program, OUT_OF_DATE, false);
        Files.setLastModifiedTime(file, AGED);
        return file;
    }

    private Path compile(Output output, String program, long sourceTime, boolean force) throws Exception {
        JSLCInfo run = run(tmp.resolve("run"), output, force);
        JSLC.compile(run, program, sourceTime);
        return run.getOutputFile(output.outType).toPath();
    }

    private byte[] bytesOfACleanRun(Output output, String program) throws Exception {
        JSLCInfo clean = run(tmp.resolve("clean"), output, false);
        JSLC.compile(clean, program, OUT_OF_DATE);
        return Files.readAllBytes(clean.getOutputFile(output.outType).toPath());
    }

    private static void assertTheRenameChangesTheBytesButNotTheLength(byte[] bytes, byte[] renamedBytes) {
        assertEquals(bytes.length, renamedBytes.length, "the renamed sampler changed the length of the output");
        assertFalse(Arrays.equals(bytes, renamedBytes), "the renamed sampler changed no byte");
    }

    private static JSLCInfo run(Path dir, Output output, boolean force) {
        JSLCInfo run = probeRun(dir, output.outType);
        run.force = force;
        return run;
    }
}
