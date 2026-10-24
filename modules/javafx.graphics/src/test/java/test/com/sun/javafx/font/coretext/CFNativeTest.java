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

package test.com.sun.javafx.font.coretext;

import com.sun.javafx.font.MacFontOracleShim;
import com.sun.javafx.font.coretext.CFNative;
import com.sun.javafx.font.coretext.CFNativeShim;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static com.sun.javafx.font.MacFontOracleShim.CF_RANGE;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BOOLEAN;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_CHAR;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link CFNative} on any macOS: registry, descriptors, data symbols, strings and every wrapper once. */
@EnabledOnOs(OS.MAC)
public class CFNativeTest {

    /** From the SDK prototypes; {@code Boolean} is {@code JAVA_BOOLEAN}, {@code UniChar} is {@code JAVA_CHAR}. */
    static final Map<String, FunctionDescriptor> SIGNATURES = signatures(
            "CFArrayGetCount", FunctionDescriptor.of(JAVA_LONG, ADDRESS),
            "CFArrayGetValueAtIndex", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG),
            "CFAttributedStringCreate", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS),
            "CFDataGetBytePtr", FunctionDescriptor.of(ADDRESS, ADDRESS),
            "CFDataGetLength", FunctionDescriptor.of(JAVA_LONG, ADDRESS),
            "CFDictionaryAddValue", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS),
            "CFDictionaryCreateMutable", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS),
            "CFDictionaryGetValue", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS),
            "CFLocaleCopyISOLanguageCodes", FunctionDescriptor.of(ADDRESS),
            "CFNumberGetValue", FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS, JAVA_LONG, ADDRESS),
            "CFRelease", FunctionDescriptor.ofVoid(ADDRESS),
            "CFRetain", FunctionDescriptor.of(ADDRESS, ADDRESS),
            "CFStringCompare", FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG),
            "CFStringCreateWithCharacters", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG),
            "CFStringFind", FunctionDescriptor.of(CF_RANGE, ADDRESS, ADDRESS, JAVA_LONG),
            "CFStringGetCharacterAtIndex", FunctionDescriptor.of(JAVA_CHAR, ADDRESS, JAVA_LONG),
            "CFStringGetCharacters", FunctionDescriptor.ofVoid(ADDRESS, CF_RANGE, ADDRESS),
            "CFStringGetLength", FunctionDescriptor.of(JAVA_LONG, ADDRESS),
            "CFURLCopyFileSystemPath", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG),
            "CFURLCreateWithFileSystemPath", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG,
                                                                   JAVA_BOOLEAN));

    static final List<String> DATA = List.of(
            "kCFAllocatorDefault", "kCFTypeDictionaryKeyCallBacks", "kCFTypeDictionaryValueCallBacks");

    static Map<String, FunctionDescriptor> signatures(Object... namesAndDescriptors) {
        Map<String, FunctionDescriptor> signatures = new LinkedHashMap<>();
        for (int i = 0; i < namesAndDescriptors.length; i += 2) {
            signatures.put("CoreFoundation!" + namesAndDescriptors[i], (FunctionDescriptor) namesAndDescriptors[i + 1]);
        }
        return signatures;
    }

    @Test
    void everyFunctionIsBoundOnceWithItsSdkDescriptor() {
        assertEquals(new ArrayList<>(SIGNATURES.entrySet()), new ArrayList<>(CFNativeShim.boundFunctions().entrySet()));
    }

    @Test
    void dataSymbolsAreReadAsTheCReadsThem() {
        List<String> qualified = DATA.stream().map(name -> "CoreFoundation!" + name).toList();
        assertEquals(qualified, CFNativeShim.boundData());
        assertEquals(MacFontOracleShim.dataSymbol("kCFAllocatorDefault"), CFNativeShim.kCFAllocatorDefault().address());
        assertEquals(MacFontOracleShim.dataSymbol("kCFTypeDictionaryKeyCallBacks"),
                     CFNativeShim.kCFTypeDictionaryKeyCallBacks().address());
        assertEquals(MacFontOracleShim.dataSymbol("kCFTypeDictionaryValueCallBacks"),
                     CFNativeShim.kCFTypeDictionaryValueCallBacks().address());
    }

    @Test
    void aMissingFrameworkOrSymbolIsNamed() {
        UnsatisfiedLinkError framework = assertThrows(UnsatisfiedLinkError.class,
                () -> CFNativeShim.openFramework("NoSuchFramework"));
        assertTrue(framework.getMessage().contains("/System/Library/Frameworks/NoSuchFramework.framework/"
                + "NoSuchFramework (macOS font support)"), framework.getMessage());
        UnsatisfiedLinkError symbol = assertThrows(UnsatisfiedLinkError.class,
                () -> CFNativeShim.bindIn("CoreFoundation", "CFNoSuchFunction"));
        assertEquals("CoreFoundation does not export CFNoSuchFunction", symbol.getMessage());
    }

    @Test
    void unexpectedRethrowsUncheckedAndWrapsChecked() {
        Error error = new StackOverflowError();
        assertEquals(error, CFNativeShim.unexpected(error));
        IllegalStateException runtime = new IllegalStateException();
        assertEquals(runtime, assertThrows(IllegalStateException.class, () -> CFNativeShim.unexpected(runtime)));
        Exception checked = new Exception();
        assertEquals(checked, CFNativeShim.unexpected(checked).getCause());
    }

    @Test
    void stringsCrossUnchangedInBothDirections() {
        StringBuilder large = new StringBuilder();
        Random random = new Random(42);
        for (int i = 0; i < 100_000; i++) {
            large.append((char) random.nextInt(0x10000));
        }
        for (String text : List.of("", "\u0000", "a\u0000b", "a\uD800b", "\uDC00", "\uDC00\uD800",
                                   "\uD83D\uDE00\uD835\uDC00", "\uFFFF\uFEFF", large.toString())) {
            MemorySegment string = CFNative.toCFString(text);
            assertEquals(text, CFNative.cfToString(string));
            assertEquals(text, MacFontOracleShim.cfString(string.address()));
            CFNative.CFRelease(string);
        }
        assertNull(CFNative.cfToString(MemorySegment.NULL));
    }

    @Test
    void boldIsOneStringThatLivesForTheProcess() {
        MemorySegment bold = CFNativeShim.boldCFString();
        assertEquals(bold.address(), CFNativeShim.boldCFString().address());
        assertEquals("bold", CFNative.cfToString(bold));
    }

    @Test
    void retainAndReleaseBalance() {
        MemorySegment string = CFNative.toCFString("a string too long to be a tagged pointer, for this test alone");
        long count = MacFontOracleShim.retainCount(string.address());
        assertEquals(string.address(), CFNativeShim.CFRetain(string).address());
        assertEquals(count + 1, MacFontOracleShim.retainCount(string.address()));
        CFNative.CFRelease(string);
        assertEquals(count, MacFontOracleShim.retainCount(string.address()));
        CFNative.CFRelease(string);
    }

    @Test
    void dataBytesAreBoundedToTheirLength() {
        byte[] bytes = {1, 2, 3, (byte) 0xFE, 0, 7};
        MemorySegment data = MemorySegment.ofAddress(MacFontOracleShim.createData(bytes));
        long length = CFNative.CFDataGetLength(data);
        MemorySegment pointer = CFNative.CFDataGetBytePtr(data, length);
        assertEquals(bytes.length, pointer.byteSize());
        assertArrayEquals(bytes, pointer.toArray(JAVA_BYTE));
        CFNative.CFRelease(data);
    }

    @Test
    void theDirectoryFlagCrossesAsOneByte() {
        MemorySegment path = CFNative.toCFString("/tmp/jfx-cfnative");
        for (boolean isDirectory : new boolean[] {false, true}) {
            MemorySegment url = CFNativeShim.CFURLCreateWithFileSystemPath(CFNativeShim.kCFAllocatorDefault(), path,
                    CFNative.kCFURLPOSIXPathStyle, isDirectory);
            assertEquals(isDirectory, MacFontOracleShim.urlHasDirectoryPath(url.address()));
            MemorySegment copied = CFNative.CFURLCopyFileSystemPath(url, CFNative.kCFURLPOSIXPathStyle);
            assertEquals("/tmp/jfx-cfnative", CFNative.cfToString(copied));
            CFNative.CFRelease(copied);
            CFNative.CFRelease(url);
        }
        CFNative.CFRelease(path);
    }

    @Test
    void arraysAndNumbers() {
        MemorySegment codes = CFNative.CFLocaleCopyISOLanguageCodes();
        long count = CFNative.CFArrayGetCount(codes);
        assertTrue(count > 100, "ISO language codes: " + count);
        assertEquals(2, CFNative.cfToString(CFNative.CFArrayGetValueAtIndex(codes, 0)).length());
        CFNative.CFRelease(codes);
        MemorySegment number = MemorySegment.ofAddress(MacFontOracleShim.createNumber(0x12345678));
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment value = scratch.allocate(JAVA_INT);
            assertTrue(CFNative.CFNumberGetValue(number, CFNative.kCFNumberIntType, value));
            assertEquals(0x12345678, value.get(JAVA_INT, 0));
        }
        CFNative.CFRelease(number);
    }

    @Test
    void dictionariesAndAttributedStrings() {
        MemorySegment allocator = CFNativeShim.kCFAllocatorDefault();
        MemorySegment key = CFNative.toCFString("key");
        MemorySegment value = CFNative.toCFString("value");
        MemorySegment dictionary = CFNativeShim.CFDictionaryCreateMutable(allocator, 2,
                CFNativeShim.kCFTypeDictionaryKeyCallBacks(), CFNativeShim.kCFTypeDictionaryValueCallBacks());
        CFNativeShim.CFDictionaryAddValue(dictionary, key, value);
        assertEquals(value.address(), CFNativeShim.CFDictionaryGetValue(dictionary, key).address());
        assertEquals(1, MacFontOracleShim.dictionaryCount(dictionary.address()));
        MemorySegment text = CFNative.toCFString("attributed");
        MemorySegment attributed = CFNativeShim.CFAttributedStringCreate(allocator, text, dictionary);
        assertEquals("attributed", MacFontOracleShim.attributedText(attributed.address()));
        for (MemorySegment object : List.of(attributed, text, dictionary, value, key)) {
            CFNative.CFRelease(object);
        }
    }

    @Test
    void comparingFindingAndReadingCharacters() {
        MemorySegment abc = CFNative.toCFString("Bold Italic \u8001");
        MemorySegment bold = CFNativeShim.boldCFString();
        assertEquals(CFNative.kCFCompareEqualTo, CFNative.CFStringCompare(abc, abc, 0));
        MemorySegment a = CFNative.toCFString("a");
        MemorySegment b = CFNative.toCFString("b");
        assertEquals(-1, CFNative.CFStringCompare(a, b, 0));
        assertEquals(1, CFNative.CFStringCompare(b, a, 0));
        CFNative.CFRelease(a);
        CFNative.CFRelease(b);
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment found = CFNativeShim.CFStringFind(scratch, abc, bold, CFNative.kCFCompareCaseInsensitive);
            assertEquals(0, found.get(JAVA_LONG, 0));
            assertEquals(4, found.get(JAVA_LONG, 8));
            MemorySegment missing = CFNativeShim.CFStringFind(scratch, abc, bold, 0);
            assertEquals(CFNative.kCFNotFound, missing.get(JAVA_LONG, 0));
            assertEquals(13, CFNativeShim.CFStringGetLength(abc));
            assertEquals('\u8001', CFNativeShim.CFStringGetCharacterAtIndex(abc, 12));
            MemorySegment range = scratch.allocate(CFNativeShim.cfRange());
            range.set(JAVA_LONG, 0, 5L);
            range.set(JAVA_LONG, 8, 6L);
            MemorySegment buffer = scratch.allocate(JAVA_CHAR, 6);
            CFNativeShim.CFStringGetCharacters(abc, range, buffer);
            assertEquals("Italic", new String(buffer.toArray(JAVA_CHAR)));
            MemorySegment chars = scratch.allocateFrom(JAVA_CHAR, "xyz".toCharArray());
            MemorySegment created = CFNativeShim.CFStringCreateWithCharacters(CFNativeShim.kCFAllocatorDefault(),
                                                                              chars, 2);
            assertEquals("xy", CFNative.cfToString(created));
            CFNative.CFRelease(created);
        }
        CFNative.CFRelease(abc);
    }
}
