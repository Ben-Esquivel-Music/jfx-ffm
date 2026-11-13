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

import com.sun.javafx.font.CompositeFontResource;
import com.sun.javafx.font.DFontDecoderShim;
import com.sun.javafx.font.FontResource;
import com.sun.javafx.font.FontResourceShim;
import com.sun.javafx.font.MacFontFinderShim;
import com.sun.javafx.font.MacFontOracleShim;
import com.sun.javafx.font.MacFontProbeShim;
import com.sun.javafx.font.PrismFontFactory;
import com.sun.javafx.font.coretext.CoreTextPeerShim;
import com.sun.javafx.font.coretext.OSShim;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import test.com.sun.javafx.font.FontGoldenHarness.GoldenMap;

/**
 * The child JVM of {@link MacFontNullInputTest}: one scenario per input that once handed NULL to CoreFoundation,
 * CoreGraphics or CoreText. A regression ends this JVM, not the test fork.
 */
public final class MacFontNullInputChild {

    static final String FALLBACK_COPY = "null.fallbackCopy";
    static final String UI_FONT = "null.uiFont";
    static final String FAMILY_NAME = "null.familyName";
    static final String CASCADE_FONT = "null.cascadeFont";
    static final String CASCADE_EMBEDDED = "null.cascadeEmbedded";
    static final String CASCADE_NAME = "null.cascadeName";
    static final String FORMAT = "null.format";
    static final String TABLE = "null.table";
    static final String MIRROR = "null.mirror";

    /** {@code kCTFontUIFontNone}. */
    static final int UI_FONT_TYPE_WITHOUT_A_FONT = -1;

    /** {@code 'zzzz'}, a table no font has. */
    private static final int TABLE_TAG_NO_FONT_HAS = 0x7a7a7a7a;

    private MacFontNullInputChild() {
    }

    public static void main(String[] args) throws Exception {
        String scenario = args[0];
        GoldenMap out = new GoldenMap();
        switch (scenario) {
            case FALLBACK_COPY -> fallbackCopy(out);
            case UI_FONT -> uiFont(out);
            case FAMILY_NAME -> out.put("familyName", MacFontFinderShim.familyName(0));
            case CASCADE_FONT -> out.put("entries", Integer.toString(MacFontFinderShim.getCascadeList(0).length));
            case CASCADE_EMBEDDED -> cascadeEmbedded(out);
            case CASCADE_NAME -> cascadeName(out);
            case FORMAT -> out.put("format", Integer.toString(DFontDecoderShim.formatOf(0)));
            case TABLE -> table(out);
            case MIRROR -> mirror(out);
            default -> throw new IllegalArgumentException("unknown scenario " + scenario);
        }
        Files.writeString(Path.of(args[1]), FontGoldens.format(out, List.of("MacFontNullInputChild " + scenario)),
                          StandardCharsets.UTF_8);
        System.exit(0);
    }

    /** CoreText answers no NULL copy for a real font, so the zero goes in where layout hands the copy on. */
    private static void fallbackCopy(GoldenMap out) throws Exception {
        CompositeFontResource composite = FontResourceShim.newLogicalFont("System", false, false);
        String name = "US-067 No Such Fallback";
        out.put("slot", Integer.toString(CoreTextPeerShim.addFallbackFont(composite, name, 0)));
        String withoutFactory;
        try {
            withoutFactory = Integer.toString(CoreTextPeerShim.addFallbackFontWithoutFactory(composite, name, 0));
        } catch (Exception e) {
            withoutFactory = e.getClass().getName();
        }
        out.put("slot.noFactory", withoutFactory);
    }

    private static void uiFont(GoldenMap out) {
        long font = MacFontProbeShim.uiFont(UI_FONT_TYPE_WITHOUT_A_FONT);
        if (font != 0) {
            MacFontOracleShim.release(font);
        }
        out.put("coreText.font", Long.toString(font));
        out.put("getFont", MacFontFinderShim.getFont(UI_FONT_TYPE_WITHOUT_A_FONT));
    }

    /** Ahem with its {@code glyf} table renamed: the Java side opens it, CoreGraphics makes no font of it. */
    private static void cascadeEmbedded(GoldenMap out) throws Exception {
        byte[] font = Files.readAllBytes(MacFontGoldens.pinned("ahem"));
        byte[] unknownTag = "zzzz".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(unknownTag, 0, font, tableRecord(font, "glyf"), unknownTag.length);
        Path file = Path.of(System.getProperty("java.io.tmpdir"), "us067-ahem-without-glyf.ttf");
        Files.write(file, font);
        FontResource resource = FontResourceShim.newUncachedEmbeddedFontFile(file.toString());
        out.put("cgFont", Long.toString(CoreTextPeerShim.cgFontRef(resource)));
        out.put("fallbacks",
                String.join("|", PrismFontFactory.getFontFactory().getFallbacks(resource).getFontNames()));
    }

    /** The offset of the table directory record of {@code tag} in an sfnt file. */
    private static int tableRecord(byte[] font, String tag) {
        int tableCount = (font[4] & 0xff) << 8 | font[5] & 0xff;
        for (int i = 0; i < tableCount; i++) {
            int record = 12 + 16 * i;
            if (new String(font, record, 4, StandardCharsets.US_ASCII).equals(tag)) {
                return record;
            }
        }
        throw new IllegalStateException("the font has no " + tag + " table");
    }

    private static void table(GoldenMap out) {
        long font = DFontDecoderShim.createCTFont("Helvetica");
        out.put("coreText.table", Long.toString(MacFontOracleShim.copyFontTable(font, TABLE_TAG_NO_FONT_HAS)));
        out.put("table", DFontDecoderShim.getCTFontTable(font, TABLE_TAG_NO_FONT_HAS) == null ? null : "bytes");
        DFontDecoderShim.releaseCTFont(font);
    }

    private static void mirror(GoldenMap out) {
        double[] identity = {1, 0, 0, 1, 0, 0};
        out.put("rect.null", thrownAt(() -> OSShim.CGRectApplyAffineTransform(null, identity)));
        out.put("transform.null", thrownAt(() -> OSShim.CGRectApplyAffineTransform(new double[] {1, 2, 3, 4}, null)));
        long gray = OSShim.CGColorSpaceCreateDeviceGray();
        long context = OSShim.CGBitmapContextCreate(0, 8, 8, 8, 8, gray, OSShim.constant("kCGImageAlphaNone"));
        out.put("fill.null", thrownAt(() -> OSShim.CGContextFillRect(context, null)));
        OSShim.CGContextFillRect(context, new double[] {0, 0, 8, 8});
        out.put("fill.after", Integer.toString(OSShim.CGBitmapContextGetData(context, 8, 8, 8).length));
        OSShim.CGContextRelease(context);
        OSShim.CFRelease(gray);
    }

    /** The exception of {@code call} and the frame that threw it, which shows whether a downcall was reached. */
    private static String thrownAt(Runnable call) {
        try {
            call.run();
            return "none";
        } catch (RuntimeException e) {
            StackTraceElement top = e.getStackTrace()[0];
            return e.getClass().getName() + "@" + top.getClassName() + "." + top.getMethodName();
        }
    }

    private static void cascadeName(GoldenMap out) {
        long descriptor = MacFontProbeShim.createFontDescriptor("US067-NoSuchFontAnywhere", 12);
        out.put("coreText.displayName", Boolean.toString(MacFontProbeShim.hasDisplayName(descriptor)));
        String[] entry = MacFontFinderShim.cascadeEntry(descriptor);
        out.put("name", entry[0]);
        out.put("file", entry[1]);
        MacFontOracleShim.release(descriptor);
    }
}
