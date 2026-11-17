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
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.antlr.v4.runtime.misc.ParseCancellationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class SourceFileClosedTest extends ProgramBase {

    /** A garbage collection between a call and its check closes a leaked file, so one call could miss a leak. */
    private static final int CALLS = 20;

    private static final FileTime OLDER_THAN_THE_COPY = FileTime.fromMillis(1_000_000_000_000L);

    private static final FileTime COPY_TIME =
            FileTime.fromMillis(OLDER_THAN_THE_COPY.toMillis() + Duration.ofDays(1).toMillis());

    private static final String PROGRAM_WITH_A_SYNTAX_ERROR = """
            param float x;
            void main() {
                )
                color = float4(x);
            }
            """;

    private static final Path PROC_SELF_FD = Path.of("/proc/self/fd");

    private static final Path DEV_FD = Path.of("/dev/fd");

    @TempDir
    Path tmp;

    private OpenFileCheck check;

    private Path hlsl;

    private Path frag;

    private FileTime hlslWriteTime;

    private FileTime fragWriteTime;

    @BeforeEach
    public void writeTheOutputsAndProveTheCheckSeesAnOpenFile() throws Exception {
        Optional<OpenFileCheck> checkOfThisSystem = OpenFileCheck.ofThisSystem();
        assumeTrue(checkOfThisSystem.isPresent(),
                () -> "no way to tell whether a file is open on " + System.getProperty("os.name"));
        check = checkOfThisSystem.get();

        JSLC.compile(shaderRun(), VALID_PROGRAM, OUT_OF_DATE);
        hlsl = shaderRun().getOutputFile(JSLC.OUT_D3D).toPath();
        frag = shaderRun().getOutputFile(JSLC.OUT_ES2).toPath();
        hlslWriteTime = Files.getLastModifiedTime(hlsl);
        fragWriteTime = Files.getLastModifiedTime(frag);

        Path control = tmp.resolve("control.jsl");
        Files.writeString(control, VALID_PROGRAM);
        Observation controlObservation = check.observe(control);
        try (FileInputStream _ = new FileInputStream(control.toFile())) {
            assertTrue(controlObservation.fileIsOpen(), "the check missed an open control file");
        }
        assertFalse(controlObservation.fileIsOpen(), "the check took the closed control file for an open one");
    }

    @Test
    public void aFileCompiledWithEveryOutputUpToDateIsClosedWhenTheCompileReturns() throws Exception {
        List<Integer> callsLeavingTheCopyOpen = new ArrayList<>();
        for (int call = 0; call < CALLS; call++) {
            Path copy = freshCopy(call, VALID_PROGRAM);

            if (compileLeavesOpen(copy)) {
                callsLeavingTheCopyOpen.add(call);
            }

            assertEquals(hlslWriteTime, Files.getLastModifiedTime(hlsl), "the .hlsl output was written");
            assertEquals(fragWriteTime, Files.getLastModifiedTime(frag), "the .frag output was written");
        }
        assertEquals(List.of(), callsLeavingTheCopyOpen, "calls after which the copy was still open");
    }

    @Test
    public void aFileCompiledWithAnOutputOutOfDateIsClosedWhenTheCompileReturns() throws Exception {
        List<Integer> callsLeavingTheCopyOpen = new ArrayList<>();
        for (int call = 0; call < CALLS; call++) {
            Path copy = freshCopy(call, VALID_PROGRAM);
            Files.setLastModifiedTime(hlsl, OLDER_THAN_THE_COPY);

            if (compileLeavesOpen(copy)) {
                callsLeavingTheCopyOpen.add(call);
            }

            assertTrue(Files.getLastModifiedTime(hlsl).compareTo(COPY_TIME) > 0, "the .hlsl output was not written");
            assertEquals(fragWriteTime, Files.getLastModifiedTime(frag), "the .frag output was written");
        }
        assertEquals(List.of(), callsLeavingTheCopyOpen, "calls after which the copy was still open");
    }

    @Test
    public void aFileWhoseCompileThrowsIsClosedWhenTheCompileThrows() throws Exception {
        List<Integer> callsLeavingTheCopyOpen = new ArrayList<>();
        for (int call = 0; call < CALLS; call++) {
            Path copy = freshCopy(call, PROGRAM_WITH_A_SYNTAX_ERROR);
            Files.setLastModifiedTime(hlsl, OLDER_THAN_THE_COPY);

            if (failingCompileLeavesOpen(copy)) {
                callsLeavingTheCopyOpen.add(call);
            }
        }
        assertEquals(List.of(), callsLeavingTheCopyOpen, "calls after which the copy was still open");
    }

    private JSLCInfo shaderRun() {
        return probeRun(tmp, JSLC.OUT_D3D | JSLC.OUT_ES2);
    }

    private Path freshCopy(int call, String program) throws IOException {
        Path copy = tmp.resolve("copy-" + call + ".jsl");
        Files.writeString(copy, program);
        Files.setLastModifiedTime(copy, COPY_TIME);
        return copy;
    }

    private boolean compileLeavesOpen(Path copy) throws Exception {
        Observation observation = check.observe(copy);
        JSLC.compile(shaderRun(), copy.toFile());
        return observation.fileIsOpen();
    }

    private boolean failingCompileLeavesOpen(Path copy) throws Exception {
        Observation observation = check.observe(copy);
        assertThrowsExactly(ParseCancellationException.class, () -> JSLC.compile(shaderRun(), copy.toFile()));
        return observation.fileIsOpen();
    }

    @FunctionalInterface
    private interface Observation {

        boolean fileIsOpen() throws IOException;
    }

    /** Observes, from before the code under test runs, whether that code leaves a file open in this JVM. */
    private enum OpenFileCheck {

        /** Windows refuses to delete a file that a {@code FileInputStream} holds open. */
        DELETE {
            @Override
            Observation observe(Path file) {
                return () -> {
                    try {
                        Files.delete(file);
                        return false;
                    } catch (FileSystemException e) {
                        return true;
                    }
                };
            }
        },

        /**
         * On Linux each open descriptor is a link to the path of its file. Deleting is no check there: Linux deletes
         * an open file.
         */
        PROC_SELF_FD_LINKS {
            @Override
            Observation observe(Path file) throws IOException {
                Path realPath = file.toRealPath();
                return () -> linksOfOpenDescriptors().contains(realPath);
            }
        },

        /**
         * On macOS an entry of {@code /dev/fd} stats as the open file itself, except for its device, which is that
         * of devfs, so the inode and the size identify the file. If a file with both is open already before the code
         * runs, the check cannot tell, and it fails rather than answer closed.
         */
        DEV_FD_INODES {
            @Override
            Observation observe(Path file) throws IOException {
                OpenRegularFile observed = new OpenRegularFile((Long) Files.getAttribute(file, "unix:ino"),
                        Files.size(file));
                assertFalse(openRegularFiles().contains(observed),
                        () -> "cannot tell whether the code leaves " + file + " open: it is open already");
                return () -> openRegularFiles().contains(observed);
            }
        };

        abstract Observation observe(Path file) throws IOException;

        static Optional<OpenFileCheck> ofThisSystem() {
            if (OS.WINDOWS.isCurrentOs()) {
                return Optional.of(DELETE);
            }
            if (OS.LINUX.isCurrentOs() && Files.isDirectory(PROC_SELF_FD)) {
                return Optional.of(PROC_SELF_FD_LINKS);
            }
            if (OS.MAC.isCurrentOs() && Files.isDirectory(DEV_FD)) {
                return Optional.of(DEV_FD_INODES);
            }
            return Optional.empty();
        }
    }

    private record OpenRegularFile(long inode, long size) {
    }

    private static Set<Path> linksOfOpenDescriptors() throws IOException {
        Set<Path> links = new HashSet<>();
        try (DirectoryStream<Path> descriptors = Files.newDirectoryStream(PROC_SELF_FD)) {
            for (Path descriptor : descriptors) {
                try {
                    links.add(Files.readSymbolicLink(descriptor));
                } catch (NoSuchFileException e) {
                    // closed while the directory was being listed
                }
            }
        }
        return links;
    }

    private static Set<OpenRegularFile> openRegularFiles() throws IOException {
        Set<OpenRegularFile> files = new HashSet<>();
        try (DirectoryStream<Path> descriptors = Files.newDirectoryStream(DEV_FD)) {
            for (Path descriptor : descriptors) {
                try {
                    Map<String, Object> attributes = Files.readAttributes(descriptor, "unix:ino,size,isRegularFile");
                    if ((Boolean) attributes.get("isRegularFile")) {
                        files.add(new OpenRegularFile((Long) attributes.get("ino"), (Long) attributes.get("size")));
                    }
                } catch (FileSystemException e) {
                    // closed while the directory was being listed, or a descriptor that cannot be stat'ed
                }
            }
        }
        return files;
    }
}
