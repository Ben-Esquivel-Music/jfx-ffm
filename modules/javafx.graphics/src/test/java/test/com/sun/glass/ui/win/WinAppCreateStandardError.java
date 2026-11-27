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

package test.com.sun.glass.ui.win;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * What {@code gwin_app_create} writes to the standard error of a {@link WinGlassChild} JVM: the scenario writes
 * {@link #BEFORE_CREATE} and {@link #AFTER_CREATE} around the call ({@link #mark}), and the parent compares the bytes
 * between them ({@link #assertWrittenByAppCreate}).
 * <p>
 * The expectation depends on the build of the {@code glass.dll} the child loaded, told apart by the import table of
 * that very file: a Release build imports {@code VCRUNTIME140.dll}, a Debug build ({@code -DCONF=Debug})
 * {@code VCRUNTIME140D.dll}. A Release build must write exactly what the preferences code writes, nothing before and
 * nothing after it. A Debug build compiles {@code STRACE} of {@code OleUtils.h} to a write to its own C runtime's
 * standard error, in text mode, and the {@code WM_CREATE} arm of {@code GlassApplication::WindowProc} traces
 * {@code GlassApplication: created.} during the call; so a Debug build must write the same bytes immediately followed
 * by exactly {@value #DEBUG_TRACE} and a carriage return and line feed. Anything else fails on either build - a line
 * break after the preferences' text, a missing or extra trace, other text - and a file that imports neither runtime
 * fails too, since its expectation is unknown. A Release build can never be held to the Debug expectation: the choice
 * is read from the file the child recorded loading ({@link WinGlassChild#LOADED_GLASS_PATH}).
 */
final class WinAppCreateStandardError {

    /** Written to standard error right before and right after {@code gwin_app_create}, each with a line feed. */
    static final String BEFORE_CREATE = "[[before gwin_app_create]]";
    static final String AFTER_CREATE = "[[after gwin_app_create]]";

    /** The text {@code STRACE(_T("GlassApplication: created."))} traces in a Debug build, before its line break. */
    static final String DEBUG_TRACE = "GlassApplication: created.";

    private static final String RELEASE_RUNTIME = "vcruntime140.dll";
    private static final String DEBUG_RUNTIME = "vcruntime140d.dll";

    private WinAppCreateStandardError() {
    }

    /** Writes {@code marker} and a line feed to {@code System.err} and flushes it: the bytes reach the handle now. */
    static void mark(String marker) {
        System.err.print(marker + "\n");
        System.err.flush();
    }

    /**
     * The bytes between the two markers are {@code written}, for the build of the {@code glass.dll} the child loaded:
     * exactly {@code written} for a Release build, {@code written} and the {@code WM_CREATE} trace for a Debug build.
     */
    static void assertWrittenByAppCreate(WinGlassChildJvm.Run run, byte[] written, String what) {
        boolean debug = isDebugBuild(Path.of(run.value(WinGlassChild.LOADED_GLASS_PATH)));
        byte[] expected = debug
                ? concat(written, (DEBUG_TRACE + "\r\n").getBytes(StandardCharsets.US_ASCII))
                : written;
        assertEquals(escape(expected), escape(between(run)), what + ", between the two markers, on a "
                + (debug ? "Debug" : "Release") + " build of glass.dll: " + run.describe());
    }

    static byte[] concat(byte[] first, byte[] second) {
        byte[] both = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, both, first.length, second.length);
        return both;
    }

    /** Printable ASCII as it is, every other byte as {@code \xNN}, so that a line break or a stray byte shows. */
    static String escape(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            int unsigned = b & 0xFF;
            if (unsigned >= 0x20 && unsigned < 0x7F && unsigned != '\\') {
                sb.append((char) unsigned);
            } else {
                sb.append(String.format("\\x%02x", unsigned));
            }
        }
        return sb.toString();
    }

    /** The bytes the child's standard error holds between the two markers. */
    private static byte[] between(WinGlassChildJvm.Run run) {
        byte[] stderr;
        try {
            stderr = Files.readAllBytes(run.stderr());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        byte[] before = (BEFORE_CREATE + "\n").getBytes(StandardCharsets.US_ASCII);
        byte[] after = (AFTER_CREATE + "\n").getBytes(StandardCharsets.US_ASCII);
        int start = indexOf(stderr, before, 0);
        int end = start < 0 ? -1 : indexOf(stderr, after, start + before.length);
        if (start < 0 || end < 0) {
            fail("the child's standard error does not hold both markers: " + run.describe());
        }
        return Arrays.copyOfRange(stderr, start + before.length, end);
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        for (int i = from; i <= haystack.length - needle.length; i++) {
            if (Arrays.equals(haystack, i, i + needle.length, needle, 0, needle.length)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Whether {@code dll} is a Debug build: its import table names {@code VCRUNTIME140D.dll}. A Release build names
     * {@code VCRUNTIME140.dll}; a file that names neither fails the calling test.
     */
    static boolean isDebugBuild(Path dll) {
        List<String> imports = importedLibraries(dll);
        if (imports.contains(DEBUG_RUNTIME)) {
            return true;
        }
        if (imports.contains(RELEASE_RUNTIME)) {
            return false;
        }
        return fail(dll + " imports neither " + RELEASE_RUNTIME + " nor " + DEBUG_RUNTIME + ", so its build kind,"
                + " and with it what gwin_app_create writes to standard error, is unknown: " + imports);
    }

    /**
     * The names, in lower case, of the libraries the import directory of the PE32+ file {@code dll} names (the
     * ordinary imports, not the delay-loaded ones).
     */
    static List<String> importedLibraries(Path dll) {
        ByteBuffer pe;
        try {
            pe = ByteBuffer.wrap(Files.readAllBytes(dll)).order(ByteOrder.LITTLE_ENDIAN);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        int header = pe.getInt(0x3C);
        if (pe.getInt(header) != 0x0000_4550 || pe.getShort(header + 24) != 0x020B) {
            fail(dll + " is not a PE32+ image");
        }
        int sections = pe.getShort(header + 6) & 0xFFFF;
        int optionalHeader = header + 24;
        int sectionTable = optionalHeader + (pe.getShort(header + 20) & 0xFFFF);
        int importDirectory = pe.getInt(optionalHeader + 120);
        List<String> names = new ArrayList<>();
        for (int descriptor = offsetOf(pe, sections, sectionTable, importDirectory); ; descriptor += 20) {
            int nameRva = pe.getInt(descriptor + 12);
            if (nameRva == 0) {
                return names;
            }
            int name = offsetOf(pe, sections, sectionTable, nameRva);
            int end = name;
            while (pe.get(end) != 0) {
                end++;
            }
            names.add(new String(pe.array(), name, end - name, StandardCharsets.US_ASCII).toLowerCase(Locale.ROOT));
        }
    }

    /** The file offset of the relative virtual address {@code rva}, through the section table. */
    private static int offsetOf(ByteBuffer pe, int sections, int sectionTable, int rva) {
        for (int i = 0; i < sections; i++) {
            int section = sectionTable + 40 * i;
            int virtualSize = pe.getInt(section + 8);
            int virtualAddress = pe.getInt(section + 12);
            int rawSize = pe.getInt(section + 16);
            int rawPointer = pe.getInt(section + 20);
            if (rva >= virtualAddress && rva < virtualAddress + Math.max(virtualSize, rawSize)) {
                return rawPointer + (rva - virtualAddress);
            }
        }
        return fail("no section holds the relative virtual address 0x" + Integer.toHexString(rva));
    }
}
