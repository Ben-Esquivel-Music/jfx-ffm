/*
 * Copyright (c) 2012, 2026, Oracle and/or its affiliates. All rights reserved.
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

package com.sun.javafx.font;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import com.sun.javafx.font.coretext.CFNative;
import com.sun.javafx.font.coretext.CTNative;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

class DFontDecoder extends FontFileWriter {

    private static MemorySegment ptr(long address) {
        return MemorySegment.ofAddress(address);
    }

    /** The first available font whose display name compares equal with {@code CFStringCompare}, or 0. */
    private static long createCTFont(String fontName) {
        if (fontName == null) {
            return 0;
        }
        MemorySegment nameRef = CFNative.toCFString(fontName);
        MemorySegment descriptor = MemorySegment.NULL;
        MemorySegment collection = CTNative.CTFontCollectionCreateFromAvailableFonts(MemorySegment.NULL);
        MemorySegment fonts = CTNative.CTFontCollectionCreateMatchingFontDescriptors(collection);
        CFNative.CFRelease(collection);
        long count = CFNative.CFArrayGetCount(fonts);
        for (long i = 0; i < count && descriptor.address() == 0; i++) {
            MemorySegment candidate = CFNative.CFArrayGetValueAtIndex(fonts, i);
            if (candidate.address() != 0) {
                MemorySegment name = CTNative.CTFontDescriptorCopyAttribute(candidate,
                                                                            CTNative.kCTFontDisplayNameAttribute);
                if (name.address() != 0) {
                    if (CFNative.CFStringCompare(name, nameRef, 0) == CFNative.kCFCompareEqualTo) {
                        descriptor = candidate;
                    }
                    CFNative.CFRelease(name);
                }
            }
        }
        MemorySegment font = descriptor.address() == 0 ? MemorySegment.NULL
                : CTNative.CTFontCreateWithFontDescriptor(descriptor, 0, MemorySegment.NULL);
        CFNative.CFRelease(fonts);
        CFNative.CFRelease(nameRef);
        return font.address();
    }

    private static void releaseCTFont(long font) {
        CFNative.CFRelease(ptr(font));
    }

    private static int getCTFontFormat(long font) {
        MemorySegment formatRef = CTNative.CTFontCopyAttribute(ptr(font), CTNative.kCTFontFormatAttribute);
        int format;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment value = arena.allocate(JAVA_INT);
            CFNative.CFNumberGetValue(formatRef, CFNative.kCFNumberIntType, value);
            format = value.get(JAVA_INT, 0);
        }
        CFNative.CFRelease(formatRef);
        return switch (format) {
            case CTNative.kCTFontFormatOpenTypePostScript -> 0x4f54544f;
            case CTNative.kCTFontFormatOpenTypeTrueType -> 0x00010000;
            case CTNative.kCTFontFormatTrueType -> 0x74727565;
            default -> 0;
        };
    }

    /** The array holds tags as pointer values, not CF objects, so they are read and never released. */
    private static int[] getCTFontTags(long font) {
        MemorySegment tags = CTNative.CTFontCopyAvailableTables(ptr(font), CTNative.kCTFontTableOptionNoOptions);
        if (tags.address() == 0) {
            return null;
        }
        int[] data = new int[(int) CFNative.CFArrayGetCount(tags)];
        for (int i = 0; i < data.length; i++) {
            data[i] = (int) CFNative.CFArrayGetValueAtIndex(tags, i).address();
        }
        CFNative.CFRelease(tags);
        return data;
    }

    private static byte[] getCTFontTable(long font, int tag) {
        MemorySegment table = CTNative.CTFontCopyTable(ptr(font), tag, CTNative.kCTFontTableOptionNoOptions);
        long length = CFNative.CFDataGetLength(table);
        byte[] bytes = CFNative.CFDataGetBytePtr(table, length).toArray(JAVA_BYTE);
        CFNative.CFRelease(table);
        return bytes;
    }

    public DFontDecoder() {
        super();
    }

    public void decode(String fontName) throws IOException {
        if (fontName == null) {
            throw new IOException("Invalid font name");
        }
        long fontRef = 0;
        try {
            fontRef = DFontDecoder.createCTFont(fontName);
            if (fontRef == 0) {
                throw new IOException("Failure creating CTFont");
            }
            int format = DFontDecoder.getCTFontFormat(fontRef);
            if (format != trueTag && format != v1ttTag && format != ottoTag) {
                throw new IOException("Unsupported Dfont");
            }
            int[] tags = DFontDecoder.getCTFontTags(fontRef);
            if (tags == null) {
                throw new IOException("Could not get tables for Dfont");
            }
            short numTables = (short)tags.length;
            int size = TTCHEADERSIZE + (DIRECTORYENTRYSIZE * numTables);
            byte[][] tableData = new byte[numTables][];
            for (int i = 0; i < tags.length; i++) {
                int tag = tags[i];
                tableData[i] = DFontDecoder.getCTFontTable(fontRef, tag);
                int length = tableData[i].length;
                size += (length + 3) & ~3;
            }
            DFontDecoder.releaseCTFont(fontRef);
            fontRef = 0;

            /* write header */
            setLength(size);
            writeHeader(format, numTables);

            int dataOffset = TTCHEADERSIZE + (DIRECTORYENTRYSIZE * numTables);
            for (int i = 0; i < numTables; i++) {
                int tag = tags[i];
                byte[] data = tableData[i];

                /* write directory entry */
                writeDirectoryEntry(i, tag, 0, dataOffset, data.length);

                /* write table */
                seek(dataOffset);
                writeBytes(data);

                dataOffset += (data.length + 3) & ~3;
            }

        } finally {
            if (fontRef != 0) {
                DFontDecoder.releaseCTFont(fontRef);
            }
        }
    }
}
