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
import com.sun.javafx.font.coretext.CGNativeShim;
import com.sun.javafx.font.coretext.CTNative;
import com.sun.javafx.font.coretext.CTNativeShim;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static com.sun.javafx.font.MacFontOracleShim.CF_RANGE;
import static com.sun.javafx.font.MacFontOracleShim.CG_RECT;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BOOLEAN;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_CHAR;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link CTNative} on any macOS: registry, descriptors, keys, runs, and every wrapper once. */
@EnabledOnOs(OS.MAC)
public class CTNativeTest {

    /** From the SDK prototypes; {@code CGGlyph} by value is {@code JAVA_CHAR}, {@code bool} is {@code JAVA_BOOLEAN}. */
    static final Map<String, FunctionDescriptor> SIGNATURES = signatures(
            "CTFontCollectionCreateFromAvailableFonts", FunctionDescriptor.of(ADDRESS, ADDRESS),
            "CTFontCollectionCreateMatchingFontDescriptors", FunctionDescriptor.of(ADDRESS, ADDRESS),
            "CTFontCopyAttribute", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS),
            "CTFontCopyAvailableTables", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT),
            "CTFontCopyDefaultCascadeListForLanguages", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS),
            "CTFontCopyFamilyName", FunctionDescriptor.of(ADDRESS, ADDRESS),
            "CTFontCopyFontDescriptor", FunctionDescriptor.of(ADDRESS, ADDRESS),
            "CTFontCopyTable", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT),
            "CTFontCreateCopyWithAttributes", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_DOUBLE, ADDRESS, ADDRESS),
            "CTFontCreatePathForGlyph", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_CHAR, ADDRESS),
            "CTFontCreateUIFontForLanguage", FunctionDescriptor.of(ADDRESS, JAVA_INT, JAVA_DOUBLE, ADDRESS),
            "CTFontCreateWithFontDescriptor", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_DOUBLE, ADDRESS),
            "CTFontCreateWithGraphicsFont", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_DOUBLE, ADDRESS, ADDRESS),
            "CTFontCreateWithName", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_DOUBLE, ADDRESS),
            "CTFontDescriptorCopyAttribute", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS),
            "CTFontDrawGlyphs", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS),
            "CTFontGetAdvancesForGlyphs", FunctionDescriptor.of(JAVA_DOUBLE, ADDRESS, JAVA_INT, ADDRESS, ADDRESS,
                                                                JAVA_LONG),
            "CTFontGetBoundingRectsForGlyphs", FunctionDescriptor.of(CG_RECT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS,
                                                                     JAVA_LONG),
            "CTFontGetSize", FunctionDescriptor.of(JAVA_DOUBLE, ADDRESS),
            "CTFontManagerRegisterFontsForURL", FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS, JAVA_INT, ADDRESS),
            "CTLineCreateWithAttributedString", FunctionDescriptor.of(ADDRESS, ADDRESS),
            "CTLineGetGlyphCount", FunctionDescriptor.of(JAVA_LONG, ADDRESS),
            "CTLineGetGlyphRuns", FunctionDescriptor.of(ADDRESS, ADDRESS),
            "CTLineGetTypographicBounds", FunctionDescriptor.of(JAVA_DOUBLE, ADDRESS, ADDRESS, ADDRESS, ADDRESS),
            "CTParagraphStyleCreate", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG),
            "CTRunGetAttributes", FunctionDescriptor.of(ADDRESS, ADDRESS),
            "CTRunGetGlyphCount", FunctionDescriptor.of(JAVA_LONG, ADDRESS),
            "CTRunGetGlyphs", FunctionDescriptor.ofVoid(ADDRESS, CF_RANGE, ADDRESS),
            "CTRunGetGlyphsPtr", FunctionDescriptor.of(ADDRESS, ADDRESS),
            "CTRunGetPositions", FunctionDescriptor.ofVoid(ADDRESS, CF_RANGE, ADDRESS),
            "CTRunGetPositionsPtr", FunctionDescriptor.of(ADDRESS, ADDRESS),
            "CTRunGetStringIndices", FunctionDescriptor.ofVoid(ADDRESS, CF_RANGE, ADDRESS),
            "CTRunGetStringIndicesPtr", FunctionDescriptor.of(ADDRESS, ADDRESS));

    static final List<String> DATA = List.of(
            "kCTFontURLAttribute", "kCTFontDisplayNameAttribute", "kCTFontFamilyNameAttribute", "kCTFontNameAttribute",
            "kCTFontFormatAttribute", "kCTFontAttributeName", "kCTParagraphStyleAttributeName");

    static Map<String, FunctionDescriptor> signatures(Object... namesAndDescriptors) {
        Map<String, FunctionDescriptor> signatures = new LinkedHashMap<>();
        for (int i = 0; i < namesAndDescriptors.length; i += 2) {
            signatures.put("CoreText!" + namesAndDescriptors[i], (FunctionDescriptor) namesAndDescriptors[i + 1]);
        }
        return signatures;
    }

    @Test
    void everyFunctionIsBoundOnceWithItsSdkDescriptor() {
        assertEquals(new ArrayList<>(SIGNATURES.entrySet()), new ArrayList<>(CTNativeShim.boundFunctions().entrySet()));
        assertEquals(DATA.stream().map(name -> "CoreText!" + name).toList(), CTNativeShim.boundData());
    }

    /**
     * Reading a registry initialises its facade, so this cannot show which class initialised which: the class files
     * pin that, in {@code MacFontBindingStructureTest}.
     */
    @Test
    void linkReturnsAndEveryRegistryIsComplete() {
        CTNative.link();
        assertEquals(20, CFNativeShim.boundFunctions().size());
        assertEquals(22, CGNativeShim.boundFunctions().size());
        assertEquals(33, CTNativeShim.boundFunctions().size());
    }

    @Test
    void theKeysAreTheCFStringsTheOracleReads() {
        List<MemorySegment> keys = List.of(CTNative.kCTFontURLAttribute, CTNative.kCTFontDisplayNameAttribute,
                CTNative.kCTFontFamilyNameAttribute, CTNative.kCTFontNameAttribute, CTNative.kCTFontFormatAttribute,
                CTNativeShim.kCTFontAttributeName(), CTNativeShim.kCTParagraphStyleAttributeName());
        for (int i = 0; i < keys.size(); i++) {
            assertEquals(MacFontOracleShim.dataSymbol(DATA.get(i)), keys.get(i).address(), DATA.get(i));
            assertEquals("CFString", MacFontOracleShim.cfTypeName(keys.get(i).address()), DATA.get(i));
        }
    }

    @Test
    void theParagraphDirectionIsOneByte() {
        try (Arena scratch = Arena.ofConfined()) {
            for (byte direction : new byte[] {-1, 0, 1}) {
                MemorySegment value = scratch.allocateFrom(JAVA_BYTE, direction);
                MemorySegment setting = scratch.allocate(CTNativeShim.paragraphStyleSetting());
                setting.set(JAVA_INT, 0, CTNativeShim.kCTParagraphStyleSpecifierBaseWritingDirection);
                setting.set(JAVA_LONG, 8, 1L);
                setting.set(ADDRESS, 16, value);
                MemorySegment style = CTNativeShim.CTParagraphStyleCreate(setting, 1);
                assertEquals(direction, MacFontOracleShim.paragraphWritingDirection(style.address()));
                CFNative.CFRelease(style);
            }
        }
    }

    @Test
    void runPointersAndCopiesAgree() {
        MemorySegment font = font("Helvetica", 12);
        MemorySegment line = line(font, "Hello \u05E9\u05DC\u05D5\u05DD \uD83D\uDE00 fi");
        MemorySegment runs = CTNativeShim.CTLineGetGlyphRuns(line);
        long total = 0;
        Map<String, Integer> helveticaPointers = new TreeMap<>(Map.of("glyphs", 0, "positions", 0, "indices", 0));
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment all = scratch.allocate(CFNativeShim.cfRange());
            for (long r = 0; r < CFNative.CFArrayGetCount(runs); r++) {
                MemorySegment run = CFNative.CFArrayGetValueAtIndex(runs, r);
                long count = CTNativeShim.CTRunGetGlyphCount(run);
                total += count;
                MemorySegment glyphs = scratch.allocate(JAVA_CHAR, count);
                MemorySegment positions = scratch.allocate(JAVA_DOUBLE, 2 * count);
                MemorySegment indices = scratch.allocate(JAVA_LONG, count);
                CTNativeShim.CTRunGetGlyphs(run, all, glyphs);
                CTNativeShim.CTRunGetPositions(run, all, positions);
                CTNativeShim.CTRunGetStringIndices(run, all, indices);
                MemorySegment attributes = CTNativeShim.CTRunGetAttributes(run);
                assertNotEquals(0, attributes.address());
                MemorySegment runFont = CFNativeShim.CFDictionaryGetValue(attributes,
                                                                          CTNativeShim.kCTFontAttributeName());
                boolean helvetica = "Helvetica".equals(MacFontOracleShim.fontPostScriptName(runFont.address()));
                Map<String, Integer> counts = helvetica ? helveticaPointers : new TreeMap<>();
                assertPointerEquals(counts, "glyphs", glyphs, CTNativeShim.CTRunGetGlyphsPtr(run, count));
                assertPointerEquals(counts, "positions", positions, CTNativeShim.CTRunGetPositionsPtr(run, count));
                assertPointerEquals(counts, "indices", indices, CTNativeShim.CTRunGetStringIndicesPtr(run, count));
            }
        }
        assertTrue(helveticaPointers.values().stream().allMatch(compared -> compared > 0),
                   "pointers compared in the Helvetica runs: " + helveticaPointers);
        assertEquals(CTNativeShim.CTLineGetGlyphCount(line), total);
        assertTrue(CTNativeShim.CTLineGetTypographicBounds(line, MemorySegment.NULL, MemorySegment.NULL,
                                                           MemorySegment.NULL) > 0);
        CFNative.CFRelease(line);
        CFNative.CFRelease(font);
    }

    /** A run may keep no array behind a pointer: only a non-NULL one is compared, and counted. */
    private static void assertPointerEquals(Map<String, Integer> counts, String accessor, MemorySegment copy,
                                            MemorySegment pointer) {
        if (pointer.address() != 0) {
            assertEquals(copy.byteSize(), pointer.byteSize(), accessor);
            assertArrayEquals(copy.toArray(JAVA_BYTE), pointer.toArray(JAVA_BYTE), accessor);
            counts.merge(accessor, 1, Integer::sum);
        }
    }

    @Test
    void registeringAFileThatIsNoFontFails() throws IOException {
        Path text = Files.createTempFile("ctnative", ".ttf");
        Files.writeString(text, "not a font");
        MemorySegment path = CFNative.toCFString(text.toString());
        MemorySegment url = CFNativeShim.CFURLCreateWithFileSystemPath(CFNativeShim.kCFAllocatorDefault(), path,
                CFNative.kCFURLPOSIXPathStyle, false);
        assertFalse(CTNativeShim.CTFontManagerRegisterFontsForURL(url, 1, MemorySegment.NULL));
        CFNative.CFRelease(url);
        CFNative.CFRelease(path);
        Files.delete(text);
    }

    @Test
    void fontsAreCreatedCopiedAndDescribed() {
        MemorySegment font = font("Helvetica", 12);
        assertEquals(12.0, CTNative.CTFontGetSize(font));
        assertEquals("Helvetica", MacFontOracleShim.fontPostScriptName(font.address()));
        MemorySegment copy = CTNativeShim.CTFontCreateCopyWithAttributes(font, 20, CGNativeShim.identity(),
                                                                         MemorySegment.NULL);
        assertEquals(20.0, CTNative.CTFontGetSize(copy));
        MemorySegment family = CTNative.CTFontCopyFamilyName(font);
        assertEquals("Helvetica", CFNative.cfToString(family));
        MemorySegment displayName = CTNative.CTFontCopyAttribute(font, CTNative.kCTFontDisplayNameAttribute);
        assertEquals("Helvetica", CFNative.cfToString(displayName));
        MemorySegment descriptor = CTNative.CTFontCopyFontDescriptor(font);
        MemorySegment name = CTNative.CTFontDescriptorCopyAttribute(descriptor, CTNative.kCTFontNameAttribute);
        assertEquals("Helvetica", CFNative.cfToString(name));
        MemorySegment fromDescriptor = CTNative.CTFontCreateWithFontDescriptor(descriptor, 0, MemorySegment.NULL);
        assertEquals("Helvetica", MacFontOracleShim.fontPostScriptName(fromDescriptor.address()));
        MemorySegment ui = CTNative.CTFontCreateUIFontForLanguage(CTNative.kCTFontUIFontSystem, 0, MemorySegment.NULL);
        assertTrue(CTNative.CTFontGetSize(ui) > 0);
        for (MemorySegment object : List.of(ui, fromDescriptor, name, descriptor, displayName, family, copy, font)) {
            CFNative.CFRelease(object);
        }
    }

    @Test
    void tablesAndCascadeLists() {
        MemorySegment font = font("Helvetica", 12);
        MemorySegment tags = CTNative.CTFontCopyAvailableTables(font, CTNative.kCTFontTableOptionNoOptions);
        List<Integer> tagValues = new ArrayList<>();
        for (long i = 0; i < CFNative.CFArrayGetCount(tags); i++) {
            tagValues.add((int) CFNative.CFArrayGetValueAtIndex(tags, i).address());
        }
        assertTrue(tagValues.contains(0x68656164), "head in " + tagValues);
        CFNative.CFRelease(tags);
        MemorySegment head = CTNative.CTFontCopyTable(font, 0x68656164, CTNative.kCTFontTableOptionNoOptions);
        assertArrayEquals(MacFontOracleShim.fontTable(font.address(), 0x68656164),
                          CFNative.CFDataGetBytePtr(head, CFNative.CFDataGetLength(head)).toArray(JAVA_BYTE));
        CFNative.CFRelease(head);
        MemorySegment codes = CFNative.CFLocaleCopyISOLanguageCodes();
        MemorySegment cascade = CTNative.CTFontCopyDefaultCascadeListForLanguages(font, codes);
        assertTrue(CFNative.CFArrayGetCount(cascade) > 1);
        CFNative.CFRelease(cascade);
        CFNative.CFRelease(codes);
        MemorySegment collection = CTNative.CTFontCollectionCreateFromAvailableFonts(MemorySegment.NULL);
        MemorySegment descriptors = CTNative.CTFontCollectionCreateMatchingFontDescriptors(collection);
        assertEquals(MacFontOracleShim.arrayCount(descriptors.address()), CFNative.CFArrayGetCount(descriptors));
        CFNative.CFRelease(descriptors);
        CFNative.CFRelease(collection);
        CFNative.CFRelease(font);
    }

    @Test
    void glyphsArePathsBoxesAdvancesAndInk() {
        MemorySegment font = font("Helvetica", 12);
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment glyph = scratch.allocateFrom(JAVA_CHAR, (char) 36);
            MemorySegment advance = scratch.allocate(CGNativeShim.layout("CGSize"));
            double total = CTNativeShim.CTFontGetAdvancesForGlyphs(font, 0, glyph, advance, 1);
            assertEquals(total, advance.get(JAVA_DOUBLE, 0));
            MemorySegment box = CTNativeShim.CTFontGetBoundingRectsForGlyphs(scratch, font, 0, glyph,
                                                                             MemorySegment.NULL, 1);
            MemorySegment path = CTNativeShim.CTFontCreatePathForGlyph(font, (char) 36, MemorySegment.NULL);
            MemorySegment pathBox = CGNativeShim.CGPathGetPathBoundingBox(scratch, path);
            double[] rect = box.toArray(JAVA_DOUBLE);
            double[] tight = pathBox.toArray(JAVA_DOUBLE);
            for (int i = 0; i < 4; i++) {
                assertEquals(tight[i], rect[i], 1.0, "glyph box against its outline");
            }
            CGNativeShim.CGPathRelease(path);
            MemorySegment gray = CGNativeShim.CGColorSpaceCreateDeviceGray();
            MemorySegment context = CGNativeShim.CGBitmapContextCreate(MemorySegment.NULL, 16, 16, 8, 16, gray, 0);
            MemorySegment origin = scratch.allocateFrom(JAVA_DOUBLE, 2, 2);
            CGNativeShim.CGContextSetRGBFillColor(context, 1, 1, 1, 1);
            CTNativeShim.CTFontDrawGlyphs(font, glyph, origin, 1, context);
            byte[] pixels = CGNativeShim.CGBitmapContextGetData(context, 16 * 16).toArray(JAVA_BYTE);
            int ink = 0;
            for (byte pixel : pixels) {
                ink += pixel != 0 ? 1 : 0;
            }
            assertTrue(ink > 10, "ink " + ink);
            CGNativeShim.CGContextRelease(context);
            CFNative.CFRelease(gray);
        }
        CFNative.CFRelease(font);
    }

    @Test
    void aGraphicsFontBecomesAFont() {
        Path ahem = Path.of("../javafx.web/src/main/native/Tools/DumpRenderTree/fonts/AHEM____.TTF")
                .toAbsolutePath().normalize();
        MemorySegment path = CFNative.toCFString(ahem.toString());
        MemorySegment url = CFNativeShim.CFURLCreateWithFileSystemPath(CFNativeShim.kCFAllocatorDefault(), path,
                CFNative.kCFURLPOSIXPathStyle, false);
        MemorySegment provider = CGNativeShim.CGDataProviderCreateWithURL(url);
        MemorySegment cgFont = CGNativeShim.CGFontCreateWithDataProvider(provider);
        MemorySegment font = CTNativeShim.CTFontCreateWithGraphicsFont(cgFont, 10, CGNativeShim.identity(),
                                                                       MemorySegment.NULL);
        assertEquals("Ahem", MacFontOracleShim.fontPostScriptName(font.address()));
        assertEquals(10.0, CTNative.CTFontGetSize(font));
        for (MemorySegment object : List.of(font, cgFont, provider, url, path)) {
            CFNative.CFRelease(object);
        }
    }

    static MemorySegment font(String postScriptName, double size) {
        MemorySegment name = CFNative.toCFString(postScriptName);
        MemorySegment font = CTNativeShim.CTFontCreateWithName(name, size, MemorySegment.NULL);
        CFNative.CFRelease(name);
        return font;
    }

    static MemorySegment line(MemorySegment font, String text) {
        MemorySegment allocator = CFNativeShim.kCFAllocatorDefault();
        MemorySegment attributes = CFNativeShim.CFDictionaryCreateMutable(allocator, 1,
                CFNativeShim.kCFTypeDictionaryKeyCallBacks(), CFNativeShim.kCFTypeDictionaryValueCallBacks());
        CFNativeShim.CFDictionaryAddValue(attributes, CTNativeShim.kCTFontAttributeName(), font);
        MemorySegment string = CFNative.toCFString(text);
        MemorySegment attributed = CFNativeShim.CFAttributedStringCreate(allocator, string, attributes);
        MemorySegment line = CTNativeShim.CTLineCreateWithAttributedString(attributed);
        CFNative.CFRelease(attributed);
        CFNative.CFRelease(string);
        CFNative.CFRelease(attributes);
        return line;
    }
}
