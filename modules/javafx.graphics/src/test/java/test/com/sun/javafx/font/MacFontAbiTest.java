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

import com.sun.javafx.font.MacFontOracleShim;
import com.sun.javafx.font.coretext.CFNative;
import com.sun.javafx.font.coretext.CFNativeShim;
import com.sun.javafx.font.coretext.CGNativeShim;
import com.sun.javafx.font.coretext.CTNativeShim;
import com.sun.javafx.font.coretext.OSShim;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static java.lang.foreign.ValueLayout.JAVA_CHAR;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * C ABI facts the macOS font binding depends on, checked against the running frameworks with no C of the project.
 * Every assertion holds on any macOS build and on both architectures, so this runs in both macOS CI jobs: arm64,
 * where {@code CGRect} is an HFA in {@code d0-d3} and a 48-byte {@code CGAffineTransform} goes indirect, and x86_64,
 * where both are MEMORY class and the result returns through {@code sret}. The sizes follow from the SDK declarations
 * that {@code sdkHeadersDeclareTheseTypes} checks. The struct crossings are checked three ways: through the oracle's
 * own bindings, through the facades, and through the {@code OS} entry points production calls.
 */
@EnabledOnOs(OS.MAC)
public class MacFontAbiTest {

    static final long CG_POINT_SIZE = 16;
    static final long CG_SIZE_SIZE = 16;
    static final long CG_RECT_SIZE = 32;
    static final long CG_AFFINE_TRANSFORM_SIZE = 48;
    static final long CF_RANGE_SIZE = 16;
    static final long CG_PATH_ELEMENT_SIZE = 16;
    static final long CT_PARAGRAPH_STYLE_SETTING_SIZE = 24;

    @BeforeAll
    static void frameworksLoad() {
        assertTrue(MacFontOracleShim.available(), "CoreFoundation, CoreText and CoreGraphics load by install name");
    }

    @Test
    public void recordLayoutsMatchTheHeaders() {
        assertLayout(MacFontOracleShim.CG_POINT, CG_POINT_SIZE, "x", 0, "y", 8);
        assertLayout(MacFontOracleShim.CG_SIZE, CG_SIZE_SIZE, "width", 0, "height", 8);
        assertLayout(MacFontOracleShim.CG_RECT, CG_RECT_SIZE, "origin", 0, "size", 16);
        assertLayout(MacFontOracleShim.CG_AFFINE_TRANSFORM, CG_AFFINE_TRANSFORM_SIZE,
                     "a", 0, "b", 8, "c", 16, "d", 24, "tx", 32, "ty", 40);
        assertLayout(MacFontOracleShim.CF_RANGE, CF_RANGE_SIZE, "location", 0, "length", 8);
        assertLayout(MacFontOracleShim.CG_PATH_ELEMENT, CG_PATH_ELEMENT_SIZE, "type", 0, "points", 8);
        assertLayout(MacFontOracleShim.CT_PARAGRAPH_STYLE_SETTING, CT_PARAGRAPH_STYLE_SETTING_SIZE,
                     "spec", 0, "valueSize", 8, "value", 16);
        assertEquals(8, MacFontOracleShim.CG_RECT.byteAlignment(), "CGRect alignment");
        assertEquals(8, MacFontOracleShim.CT_PARAGRAPH_STYLE_SETTING.byteAlignment(),
                     "CTParagraphStyleSetting alignment");
    }

    @Test
    public void facadeLayoutsMatchTheHeaders() {
        assertEquals(MacFontOracleShim.CG_POINT, CGNativeShim.layout("CGPoint"));
        assertEquals(MacFontOracleShim.CG_SIZE, CGNativeShim.layout("CGSize"));
        assertEquals(MacFontOracleShim.CG_RECT, CGNativeShim.layout("CGRect"));
        assertEquals(MacFontOracleShim.CG_AFFINE_TRANSFORM, CGNativeShim.layout("CGAffineTransform"));
        assertEquals(MacFontOracleShim.CG_PATH_ELEMENT, CGNativeShim.layout("CGPathElement"));
        assertEquals(MacFontOracleShim.CF_RANGE, CFNativeShim.cfRange());
        assertEquals(MacFontOracleShim.CT_PARAGRAPH_STYLE_SETTING, CTNativeShim.paragraphStyleSetting());
        assertLayout(CGNativeShim.layout("CGRect"), CG_RECT_SIZE, "origin", 0, "size", 16);
        assertLayout(CGNativeShim.layout("CGPathElement"), CG_PATH_ELEMENT_SIZE, "type", 0, "points", 8);
        assertLayout(CTNativeShim.paragraphStyleSetting(), CT_PARAGRAPH_STYLE_SETTING_SIZE,
                     "spec", 0, "valueSize", 8, "value", 16);
    }

    @Test
    public void structsCrossByValueThroughTheFacades() {
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment rect = scratch.allocateFrom(JAVA_DOUBLE, 1, 2, 3, 4);
            MemorySegment transform = scratch.allocateFrom(JAVA_DOUBLE, 2, 0.5, -0.25, 3, 10, 20);
            assertArrayEquals(new double[] {10.5, 26.5, 7.0, 13.5},
                              CGNativeShim.CGRectApplyAffineTransform(scratch, rect, transform).toArray(JAVA_DOUBLE));
            MemorySegment text = CFNative.toCFString("Helvetica Bold");
            MemorySegment bold = CFNative.toCFString("bold");
            MemorySegment found = CFNativeShim.CFStringFind(scratch, text, bold, CFNative.kCFCompareCaseInsensitive);
            assertArrayEquals(new long[] {10, 4}, found.toArray(JAVA_LONG));
            MemorySegment missing = CFNativeShim.CFStringFind(scratch, text, bold, 0);
            assertArrayEquals(new long[] {CFNative.kCFNotFound, 0}, missing.toArray(JAVA_LONG));
            CFNative.CFRelease(bold);
            CFNative.CFRelease(text);
            long path = MacFontOracleShim.createPath(new double[] {0, 1, 2}, new double[] {1, 4, 2},
                                                     new double[] {2, 6, 4, 4, 6}, new double[] {4});
            assertArrayEquals(MacFontOracleShim.pathBoundingBox(path),
                              CGNativeShim.CGPathGetPathBoundingBox(scratch, MemorySegment.ofAddress(path))
                                          .toArray(JAVA_DOUBLE));
            MacFontOracleShim.releasePath(path);
            long font = MacFontOracleShim.createFont("Helvetica", 12);
            MemorySegment glyph = scratch.allocateFrom(JAVA_CHAR, (char) 36);
            MemorySegment glyphRect = scratch.allocate(MacFontOracleShim.CG_RECT);
            MemorySegment box = CTNativeShim.CTFontGetBoundingRectsForGlyphs(scratch, MemorySegment.ofAddress(font),
                                                                             0, glyph, glyphRect, 1);
            assertArrayEquals(MacFontOracleShim.glyphBoundingRect(font, (char) 36), box.toArray(JAVA_DOUBLE));
            // The oracle binds it with the same descriptor, so only the out-array, written through memory, checks the
            // rect returned by value (through sret on x86_64).
            assertArrayEquals(glyphRect.toArray(JAVA_DOUBLE), box.toArray(JAVA_DOUBLE),
                              "the returned rect of one glyph is the rect CoreText writes to the array");
            MacFontOracleShim.release(font);
        }
    }

    @Test
    public void glyphsAbove0x7FFFReachCoreTextUnsignedThroughTheFacade() {
        assumeTrue(Files.isRegularFile(Path.of(MacFontGoldens.SYSTEM_FONTS, "STHeiti Light.ttc")),
                   "STHeiti Light.ttc is not installed");
        long font = MacFontOracleShim.createFont("STHeitiSC-Light", 12);
        for (char glyph : new char[] {0x8000, 0x8001}) {
            MemorySegment path = CTNativeShim.CTFontCreatePathForGlyph(MemorySegment.ofAddress(font), glyph,
                                                                       MemorySegment.NULL);
            assertNotEquals(0L, path.address(), "glyph " + Integer.toHexString(glyph));
            CGNativeShim.CGPathRelease(path);
        }
        MacFontOracleShim.release(font);
    }

    @Test
    public void structsCrossThroughTheProductionEntryPoints() {
        double[] rect = {1, 2, 3, 4};
        OSShim.CGRectApplyAffineTransform(rect, new double[] {2, 0.5, -0.25, 3, 10, 20});
        assertArrayEquals(new double[] {10.5, 26.5, 7.0, 13.5}, rect);
        long path = MacFontOracleShim.createPath(new double[] {0, 1, 2}, new double[] {1, 4, 2},
                                                 new double[] {1, 4, 6}, new double[] {4});
        assertArrayEquals(new double[] {1, 2, 3, 4}, OSShim.CGPathGetPathBoundingBox(path));
        MacFontOracleShim.releasePath(path);
        long style = OSShim.CTParagraphStyleCreate(1);
        assertEquals(1, MacFontOracleShim.paragraphWritingDirection(style));
        OSShim.CFRelease(style);
        long directory = OSShim.CFStringCreate("/System/Library/Fonts");
        long url = OSShim.CFURLCreateWithFileSystemPath(0, directory, 0, true);
        assertTrue(MacFontOracleShim.urlHasDirectoryPath(url));
        OSShim.CFRelease(url);
        OSShim.CFRelease(directory);
    }

    @Test
    public void glyph0x8000ReachesCoreTextUnsignedThroughTheProductionEntryPoint() {
        assumeTrue(Files.isRegularFile(Path.of(MacFontGoldens.SYSTEM_FONTS, "STHeiti Light.ttc")),
                   "STHeiti Light.ttc is not installed");
        long font = MacFontOracleShim.createFont("STHeitiSC-Light", 12);
        long glyphPath = OSShim.CTFontCreatePathForGlyph(font, (short) 0x8000, null);
        assertNotEquals(0L, glyphPath, "glyph 0x8000 through OS");
        OSShim.CGPathRelease(glyphPath);
        MacFontOracleShim.release(font);
    }

    private static void assertLayout(StructLayout layout, long size, Object... fields) {
        assertEquals(size, layout.byteSize(), "sizeof " + layout);
        for (int i = 0; i < fields.length; i += 2) {
            String name = (String) fields[i];
            assertEquals(((Integer) fields[i + 1]).longValue(), layout.byteOffset(PathElement.groupElement(name)),
                         "offsetof " + name + " in " + layout);
        }
    }

    @Test
    public void rectApplyAffineTransformRoundTrips() {
        assertArrayEquals(new double[] {10.5, 26.5, 7.0, 13.5},
                          MacFontOracleShim.rectApplyAffineTransform(new double[] {1, 2, 3, 4},
                                                                     new double[] {2, 0.5, -0.25, 3, 10, 20}));
        assertArrayEquals(new double[] {-4.0, 6.0, 3.0, 2.0},
                          MacFontOracleShim.rectApplyAffineTransform(new double[] {1, 2, 3, 2},
                                                                     new double[] {-1, 0, 0, 1, 0, 4}),
                          "a mirror normalises the rectangle");
    }

    @Test
    public void rectContainsPointReturnsBool() {
        assertTrue(MacFontOracleShim.rectContainsPoint(new double[] {0, 0, 10, 10}, new double[] {5, 5}));
        assertFalse(MacFontOracleShim.rectContainsPoint(new double[] {0, 0, 10, 10}, new double[] {10, 5}));
        assertFalse(MacFontOracleShim.rectContainsPoint(new double[] {0, 0, 10, 10}, new double[] {-0.5, 5}));
    }

    @Test
    public void cfRangeCrossesByValue() {
        assertArrayEquals(new long[] {4, 4}, MacFontOracleShim.stringFind("Helvetica Bold", "etic", 0));
        assertArrayEquals(new long[] {10, 4}, MacFontOracleShim.stringFind("Helvetica Bold", "bold", 1),
                          "kCFCompareCaseInsensitive");
        assertArrayEquals(new long[] {-1, 0}, MacFontOracleShim.stringFind("Helvetica", "bold", 1),
                          "kCFNotFound");
        assertEquals("lve", MacFontOracleShim.stringCharacters("Helvetica", 2, 3));
    }

    @Test
    public void pathElementsReadThroughTheLayout() {
        long path = MacFontOracleShim.createPath(
                new double[] {0, 1, 2}, new double[] {1, 3, 4}, new double[] {2, 5, 6, 7, 8},
                new double[] {3, 9, 10, 11, 12, 13, 14}, new double[] {4});
        try {
            assertEquals(List.of("0:1.0,2.0", "1:3.0,4.0", "2:5.0,6.0:7.0,8.0", "3:9.0,10.0:11.0,12.0:13.0,14.0", "4"),
                         MacFontOracleShim.applyPath(path));
        } finally {
            MacFontOracleShim.releasePath(path);
        }
    }

    @Test
    public void writingDirectionIsOneByte() {
        assertEquals(1, MacFontOracleShim.paragraphStyleRoundTrip((byte) 1), "kCTWritingDirectionRightToLeft");
        assertEquals(0, MacFontOracleShim.paragraphStyleRoundTrip((byte) 0), "kCTWritingDirectionLeftToRight");
        assertEquals(-1, MacFontOracleShim.paragraphStyleRoundTrip((byte) -1), "kCTWritingDirectionNatural");
    }

    @Test
    public void cfBooleanCrossesAsOneByte() {
        assertTrue(MacFontOracleShim.urlIsDirectory("/System/Library/Fonts", true));
        assertFalse(MacFontOracleShim.urlIsDirectory("/System/Library/Fonts/Helvetica.ttc", false));
    }

    /** Apple arm64 leaves sub-int extension to the caller: a signed 16-bit glyph arrives as 0xffff8000. */
    @Test
    public void glyphAbove0x8000HasAPath() {
        assumeTrue(Files.isRegularFile(Path.of(MacFontGoldens.SYSTEM_FONTS, "STHeiti Light.ttc")),
                   "STHeiti Light.ttc is not installed");
        long font = MacFontOracleShim.createFont("STHeitiSC-Light", 12);
        try {
            assertNotEquals(0L, font, "STHeitiSC-Light");
            assertTrue(MacFontOracleShim.fontGlyphCount(font) > 0x8000, "STHeiti SC has glyphs past 0x8000");
            assertTrue(MacFontOracleShim.glyphPathExists(font, (char) 0x8000), "glyph 0x8000");
            assertTrue(MacFontOracleShim.glyphPathExists(font, (char) 0x8001), "glyph 0x8001");
            assertTrue(MacFontOracleShim.glyphPathExists(font, (char) 0x0100), "glyph 0x0100");
        } finally {
            MacFontOracleShim.release(font);
        }
    }

    @Test
    public void dataSymbolsAreValuesOrAddresses() {
        assertEquals(0L, MacFontOracleShim.dataSymbol("kCFAllocatorDefault"), "kCFAllocatorDefault is NULL");
        assertNotEquals(0L, MacFontOracleShim.dataSymbol("kCFTypeDictionaryKeyCallBacks"));
        assertEquals(0L, MacFontOracleShim.keyCallBacksVersion(), "CFDictionaryKeyCallBacks.version");
        long fontKey = MacFontOracleShim.dataSymbol("kCTFontAttributeName");
        assertEquals("CFString", MacFontOracleShim.cfTypeName(fontKey), "kCTFontAttributeName is a CFStringRef");
    }

    @Test
    public void sdkHeadersDeclareTheseTypes() throws IOException {
        String sdk = FontGoldenRuntime.run(List.of("xcrun", "--sdk", "macosx", "--show-sdk-path"), true);
        assumeTrue(sdk != null && Files.isDirectory(Path.of(sdk.trim())), "no macOS SDK installed");
        Path frameworks = Path.of(sdk.trim(), "System/Library/Frameworks");
        String cfBase = header(frameworks, "CoreFoundation", "CFBase.h");
        assertDeclares(cfBase, "typedef unsigned char Boolean;");
        assertDeclares(cfBase, "typedef signed long CFIndex;");
        assertDeclares(cfBase, "typedef struct { CFIndex location; CFIndex length; } CFRange;");
        String cgBase = header(frameworks, "CoreGraphics", "CGBase.h");
        assertDeclares(cgBase, "# define CGFLOAT_TYPE double");
        String geometry = header(frameworks, "CoreGraphics", "CGGeometry.h");
        assertDeclares(geometry, "struct CGPoint { CGFloat x; CGFloat y; };");
        assertDeclares(geometry, "struct CGSize { CGFloat width; CGFloat height; };");
        assertDeclares(geometry, "struct CGRect { CGPoint origin; CGSize size; };");
        String transform = header(frameworks, "CoreGraphics", "CGAffineTransform.h");
        assertDeclares(transform, "struct CGAffineTransform { CGFloat a, b, c, d; CGFloat tx, ty; };");
        String path = header(frameworks, "CoreGraphics", "CGPath.h");
        assertDeclares(path, "typedef CF_ENUM(int32_t, CGPathElementType)");
        assertDeclares(path, "struct CGPathElement { CGPathElementType type; CGPoint * points; };");
        String font = header(frameworks, "CoreGraphics", "CGFont.h");
        assertDeclares(font, "typedef unsigned short CGFontIndex;");
        assertDeclares(font, "typedef CGFontIndex CGGlyph;");
        String paragraph = header(frameworks, "CoreText", "CTParagraphStyle.h");
        assertDeclares(paragraph, "typedef CF_ENUM(int8_t, CTWritingDirection)");
        assertDeclares(paragraph, "typedef CF_ENUM(uint32_t, CTParagraphStyleSpecifier)");
        assertDeclares(paragraph, "CTParagraphStyleSpecifier spec; size_t valueSize; const void * value;");
    }

    /** Without white space and nullability annotations, which differ between SDK versions. */
    private static String header(Path frameworks, String framework, String file) throws IOException {
        Path header = frameworks.resolve(framework + ".framework/Headers").resolve(file);
        assumeTrue(Files.isRegularFile(header), header + " is not in the SDK");
        return compact(Files.readString(header, StandardCharsets.UTF_8));
    }

    private static String compact(String text) {
        return text.replaceAll("_Nullable|_Nonnull|_Null_unspecified|__nullable|__nonnull|CG_NULLABLE|cg_nullable", "")
                   .replaceAll("\\s+", "");
    }

    private static void assertDeclares(String header, String declaration) {
        assertTrue(header.contains(compact(declaration)), "the SDK header declares: " + declaration);
    }
}
