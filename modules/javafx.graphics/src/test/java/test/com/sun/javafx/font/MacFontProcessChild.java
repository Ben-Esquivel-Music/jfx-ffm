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
import com.sun.javafx.font.FontFallbackInfo;
import com.sun.javafx.font.FontResource;
import com.sun.javafx.font.MacFontFinderShim;
import com.sun.javafx.font.MacFontOracleShim;
import com.sun.javafx.font.MacMachineShim;
import com.sun.javafx.font.PGFont;
import com.sun.javafx.font.PrismFontFactory;
import com.sun.javafx.font.coretext.OSShim;
import com.sun.javafx.geom.transform.BaseTransform;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.UnaryOperator;
import test.com.sun.javafx.font.FontGoldenHarness.GoldenMap;

/**
 * The child JVM of {@link MacGlyphLayoutGoldenTest} and {@link MacFontProcessGoldenTest}. Paths are normalised, and
 * the names of temporary files are never recorded, only their contents.
 */
public final class MacFontProcessChild {

    static final String PING = "ping";
    static final String LAYOUT = "layout";
    static final String LCD = "p1.lcd";
    static final String SUBPIXEL_OFF = "p2.subpixelOff";
    static final String SUBPIXEL_NATIVE = "p3.subpixelNative";
    static final String EMBEDDED = "p4.embedded";
    static final String DFONT = "p5.dfont";

    private static UnaryOperator<String> normalizer = UnaryOperator.identity();

    private MacFontProcessChild() {
    }

    public static void main(String[] args) throws Exception {
        String scenario = args[0];
        Path output = Path.of(args[1]);
        normalizer = normalizer(scenario);
        GoldenMap out = new GoldenMap();
        switch (scenario) {
            case PING -> ping(out);
            case LAYOUT -> layout(out);
            case LCD -> lcd(out);
            case SUBPIXEL_OFF, SUBPIXEL_NATIVE -> subpixel(out);
            case EMBEDDED -> embedded(out, scenario);
            case DFONT -> dfont(out);
            default -> throw new IllegalArgumentException("unknown scenario " + scenario);
        }
        Files.writeString(output, FontGoldens.format(out, List.of("MacFontProcessChild " + scenario)),
                          StandardCharsets.UTF_8);
        System.exit(0);
    }

    private static UnaryOperator<String> normalizer(String scenario) throws Exception {
        Path module = Path.of("").toAbsolutePath();
        List<String[]> replacements = new ArrayList<>();
        Path work = module.resolve(MacFontGoldens.WORK_DIRECTORY).resolve("work").resolve(scenario);
        Path tmp = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath();
        Path repository = module.resolve("../..").normalize();
        Path[][] mostSpecificFirst = {
            {tmp, Path.of("${TMP}")},
            {work, Path.of("${WORK}")},
            {module, Path.of("${MODULE}")},
            {repository, Path.of("${REPO}")},
        };
        for (Path[] pair : mostSpecificFirst) {
            replacements.add(new String[] {pair[0].toString(), pair[1].toString()});
            if (Files.exists(pair[0])) {
                replacements.add(new String[] {pair[0].toRealPath().toString(), pair[1].toString()});
            }
        }
        return text -> {
            if (text == null) {
                return null;
            }
            String result = text;
            for (String[] replacement : replacements) {
                result = result.replace(replacement[0], replacement[1]);
            }
            return result.replaceAll("\\+JXF[0-9]+\\.tmp", "+JXF<n>.tmp");
        };
    }

    static String text(String text) {
        return MacGoldenFormat.text(normalizer.apply(text));
    }

    private static void ping(GoldenMap out) {
        out.put("factory", PrismFontFactory.getFontFactory().getClass().getName());
        out.put("os.name", System.getProperty("os.name"));
        out.put("prism.lcdtext", System.getProperty("prism.lcdtext"));
    }

    static final List<Object[]> LAYOUT_FONTS = List.of(
            new Object[] {"system", "System", false, false},
            new Object[] {"systembold", "System", true, false},
            new Object[] {"systemitalic", "System", false, true},
            new Object[] {"sansserif", "SansSerif", false, false},
            new Object[] {"serif", "Serif", false, false},
            new Object[] {"monospaced", "Monospaced", false, false},
            new Object[] {"helvetica", "Helvetica", false, false},
            new Object[] {"times", "Times", false, false},
            new Object[] {"menlo", "Menlo", false, false},
            new Object[] {"avenirnext", "Avenir Next", false, false},
            new Object[] {"hiraginosans", "Hiragino Sans", false, false},
            new Object[] {"pingfangsc", "PingFang SC", false, false},
            new Object[] {"geezapro", "Geeza Pro", false, false},
            new Object[] {"applecoloremoji", "Apple Color Emoji", false, false},
            new Object[] {"monacobold", "Monaco", true, false});

    static final List<Integer> RTL_TEXTS = List.of(3, 4, 5, 15);

    private static void layout(GoldenMap out) {
        PrismFontFactory factory = PrismFontFactory.getFontFactory();
        List<String> texts = MacFontGoldens.TEXTS;
        List<PGFont> fonts = new ArrayList<>();
        for (Object[] spec : LAYOUT_FONTS) {
            PGFont font = factory.createFont((String) spec[1], (Boolean) spec[2], (Boolean) spec[3], 12f);
            fonts.add(font);
            String prefix = "lay." + spec[0];
            out.put(prefix + ".font", text(font.getFullName()) + "|" + text(font.getFamilyName()) + "|"
                    + font.getFontResource().getClass().getSimpleName());
            for (int i = 0; i < texts.size(); i++) {
                String key = FontGoldenFormat.pad(i, 2);
                FontGoldenFormat.putLayout(out, prefix + ".s12.t" + key, font, texts.get(i), false);
                if (RTL_TEXTS.contains(i)) {
                    FontGoldenFormat.putLayout(out, prefix + ".s12.rtl.t" + key, font, texts.get(i), true);
                }
            }
            for (float size : new float[] {0f, 36f}) {
                PGFont sized = factory.createFont((String) spec[1], (Boolean) spec[2], (Boolean) spec[3], size);
                for (int i : new int[] {1, 5, 8, 10}) {
                    String key = prefix + ".s" + (int) size + ".t" + FontGoldenFormat.pad(i, 2);
                    FontGoldenFormat.putLayout(out, key, sized, texts.get(i), false);
                }
            }
        }
        for (int f = 0; f < fonts.size(); f++) {
            FontResource resource = fonts.get(f).getFontResource();
            String prefix = "lay." + LAYOUT_FONTS.get(f)[0] + ".slots";
            if (resource instanceof CompositeFontResource composite) {
                int slots = composite.getNumSlots();
                List<String> names = new ArrayList<>();
                FontResource primary = composite.getSlotResource(0);
                for (int s = 0; s < slots; s++) {
                    FontResource slot = composite.getSlotResource(s);
                    names.add(s > 0 && slot == primary ? "(primary)"
                            : text(slot == null ? null : slot.getFullName()) + "@" + text(slot == null ? null
                            : slot.getFileName()));
                }
                out.put(prefix, slots + "|" + FontGoldens.joinList(names));
            } else {
                out.put(prefix, resource.getClass().getSimpleName());
            }
        }
        for (String name : new String[] {"Helvetica", "System Regular", "System Bold", "Times Roman"}) {
            PGFont font = factory.createFont(name, 12f);
            FontResource primary = font.getFontResource() instanceof CompositeFontResource composite
                    ? composite.getSlotResource(0) : font.getFontResource();
            FontFallbackInfo info = factory.getFallbacks(primary);
            List<String> entries = new ArrayList<>();
            String[] names = info.getFontNames();
            String[] files = info.getFontFiles();
            for (int i = 0; i < names.length; i++) {
                entries.add(text(names[i]) + "@" + text(files[i]));
            }
            out.put("lay.fallbacks." + name.replace(' ', '_'), names.length + "|" + FontGoldens.joinList(entries));
        }
        for (String name : new String[] {".SFNS-Regular", "System Font", "Helvetica", ".Apple Color Emoji UI"}) {
            out.put("lay.isExcluded." + name.replace(' ', '_'), Boolean.toString(factory.isExcluded(name)));
        }
        putRangeChecks(out);
    }

    private static void putRangeChecks(GoldenMap out) {
        long alloc = OSShim.kCFAllocatorDefault();
        char[] abc = "abc".toCharArray();
        long[][] cases = {{-1, 1}, {0, -1}, {2, 2}, {3, 0}, {0, 3}, {Long.MAX_VALUE, 1}, {1, Long.MAX_VALUE},
            {1, 1}, {0, 0}, {Long.MIN_VALUE, 0}};
        for (long[] c : cases) {
            long string = OSShim.CFStringCreateWithCharacters(alloc, abc, c[0], c[1]);
            out.put("lay.range." + c[0] + "_" + c[1], string == 0 ? "0"
                    : FontGoldenFormat.unitsHex(MacFontOracleShim.cfString(string)));
            if (string != 0) {
                OSShim.CFRelease(string);
            }
        }
        out.put("lay.range.nullArray", Long.toString(OSShim.CFStringCreateWithCharacters(alloc, null, 0, 0)));
    }

    private static void lcd(GoldenMap out) throws Exception {
        PrismFontFactory factory = PrismFontFactory.getFontFactory();
        out.put("factory.lcdTextSupported", Boolean.toString(factory.isLCDTextSupported()));
        for (MacFontCorpus.Face face : MacFontCorpus.FACES) {
            if (!List.of("helvetica", "times", "sfns", "hiragino", "emoji").contains(face.key())) {
                continue;
            }
            FontResource resource = MacFontCorpus.open(face);
            int[] glyphs = MacFontCorpus.glyphs(resource, face.sample());
            glyphs = Arrays.copyOf(glyphs, Math.min(2, glyphs.length));
            for (float size : new float[] {9f, 12f, 17.9f, 24f, 34f}) {
                for (int aa : new int[] {FontResource.AA_GREYSCALE, FontResource.AA_LCD}) {
                    MacFontCorpus.putStrike(out, "lcd." + face.key() + ".aa" + aa + ".s" + MacFontCorpus.sizeKey(size),
                                            resource, size, BaseTransform.IDENTITY_TRANSFORM, aa, glyphs, 4, false);
                }
            }
            for (MacFontCorpus.Transform transform : MacFontCorpus.TRANSFORMS) {
                MacFontCorpus.putStrike(out, "lcd." + face.key() + ".aa1." + transform.key() + ".s12", resource, 12f,
                                        transform.transform(), FontResource.AA_LCD, glyphs, 1, false);
            }
            if (face.key().equals("helvetica")) {
                MacFontCorpus.putLargeMasks(out, "lcd.helvetica.aa1", resource, glyphs, FontResource.AA_LCD);
            }
        }
    }

    private static void subpixel(GoldenMap out) throws Exception {
        PrismFontFactory factory = PrismFontFactory.getFontFactory();
        out.put("factory.subPixelMode", Integer.toString(factory.getSubPixelMode()));
        for (MacFontCorpus.Face face : MacFontCorpus.FACES) {
            if (!List.of("helvetica", "sfns").contains(face.key())) {
                continue;
            }
            FontResource resource = MacFontCorpus.open(face);
            int[] glyphs = MacFontCorpus.glyphs(resource, "Ag");
            for (float size : new float[] {9f, 12f, 17.9f, 24f}) {
                MacFontCorpus.putStrike(out, "sub." + face.key() + ".s" + MacFontCorpus.sizeKey(size), resource, size,
                                        BaseTransform.IDENTITY_TRANSFORM, FontResource.AA_GREYSCALE, glyphs, 4, false);
            }
        }
    }

    static boolean register(Path path, int scope) {
        long string = OSShim.CFStringCreate(path.toString());
        long url = OSShim.CFURLCreateWithFileSystemPath(OSShim.kCFAllocatorDefault(), string,
                OSShim.constant("kCFURLPOSIXPathStyle"), false);
        boolean result = OSShim.CTFontManagerRegisterFontsForURL(url, scope, 0);
        OSShim.CFRelease(url);
        OSShim.CFRelease(string);
        return result;
    }

    private static void embedded(GoldenMap out, String scenario) throws Exception {
        Path work = Path.of("").toAbsolutePath().resolve(MacFontGoldens.WORK_DIRECTORY).resolve("work")
                        .resolve(scenario);
        Path ahem = copy(MacFontGoldens.pinned("ahem"), work.resolve("Ahem.ttf"));
        Path ttf = copy(MacFontGoldens.pinned("fontwithfeaturesttf"), work.resolve("FontWithFeatures.ttf"));
        Path otf = copy(MacFontGoldens.pinned("fontwithfeaturesotf"), work.resolve("FontWithFeatures.otf"));
        Path ttc = work.resolve("WeightWatcher.ttc");
        Files.write(ttc, collection(Files.readAllBytes(MacFontGoldens.pinned("weightwatcher400")),
                                    Files.readAllBytes(MacFontGoldens.pinned("weightwatcher700"))));
        Path notAFont = work.resolve("notafont.ttf");
        Files.write(notAFont, "not a font file\n".getBytes(StandardCharsets.UTF_8));
        out.put("emb.input.ttc", FontGoldenFormat.bytesSummary(Files.readAllBytes(ttc)));

        out.put("emb.register.ahem", Boolean.toString(register(ahem, OSShim.constant("kCTFontManagerScopeProcess"))));
        out.put("emb.register.ahemAgain", Boolean.toString(register(ahem, 1)));
        out.put("emb.register.missing", Boolean.toString(register(work.resolve("missing.ttf"), 1)));
        out.put("emb.register.notAFont", Boolean.toString(register(notAFont, 1)));
        out.put("emb.register.directory", Boolean.toString(register(work, 1)));
        out.put("emb.register.scopeNone", Boolean.toString(register(otf, 0)));
        out.put("emb.available", Integer.toString(MacMachineShim.availableFonts().size()));

        PrismFontFactory factory = PrismFontFactory.getFontFactory();
        List<Object[]> loads = new ArrayList<>();
        loads.add(new Object[] {"ttf", factory.loadEmbeddedFont(null, ttf.toString(), 12f, true, false)});
        loads.add(new Object[] {"otf", factory.loadEmbeddedFont(null, otf.toString(), 12f, true, false)});
        loads.add(new Object[] {"ttc", factory.loadEmbeddedFont(null, ttc.toString(), 12f, true, true)});
        try (InputStream in = Files.newInputStream(MacFontGoldens.pinned("ahem"))) {
            loads.add(new Object[] {"stream", factory.loadEmbeddedFont(null, in, 12f, true, false)});
        }
        loads.add(new Object[] {"notAFont", factory.loadEmbeddedFont(null, notAFont.toString(), 12f, true, false)});
        for (Object[] load : loads) {
            PGFont[] fonts = (PGFont[]) load[1];
            String prefix = "emb.load." + load[0];
            out.put(prefix, fonts == null ? FontGoldens.NULL : Integer.toString(fonts.length));
            for (int i = 0; fonts != null && i < fonts.length; i++) {
                FontResource resource = fonts[i].getFontResource();
                String facePrefix = prefix + ".f" + i;
                out.put(facePrefix + ".file", text(resource.getFileName()));
                int[] glyphs = MacFontCorpus.glyphs(resource, "AgX0");
                MacFontCorpus.putMetrics(out, facePrefix, resource, glyphs);
                for (float size : new float[] {12f, 24f, 96f}) {
                    MacFontCorpus.putStrike(out, facePrefix + ".s" + (int) size, resource, size,
                                            BaseTransform.IDENTITY_TRANSFORM, FontResource.AA_GREYSCALE, glyphs, 1,
                                            size != 96f);
                }
                MacFontCorpus.putStrike(out, facePrefix + ".rotate30.s12", resource, 12f,
                                        MacFontCorpus.TRANSFORMS.get(2).transform(), FontResource.AA_GREYSCALE, glyphs,
                                        1, true);
            }
        }
        PGFont byName = factory.createFont("Ahem", 12f);
        out.put("emb.lookup.Ahem", text(byName.getFullName()) + "|" + text(byName.getFamilyName()));
        putRegisteredTriples(out, "emb", work.toString());
    }

    /** Sorted: one file is a temporary file whose random name may decide its place in CoreText's order. */
    private static void putRegisteredTriples(GoldenMap out, String prefix, String marker) {
        String[] data = MacFontFinderShim.getFontData();
        List<String> triples = new ArrayList<>();
        for (int i = 0; i + 2 < data.length; i += 3) {
            if (data[i + 2] != null && data[i + 2].contains(marker)) {
                triples.add(text(data[i]) + "|" + text(data[i + 1]) + "|" + text(data[i + 2]));
            }
        }
        Collections.sort(triples);
        out.put(prefix + ".getFontData.length", Integer.toString(data.length));
        out.put(prefix + ".getFontData.registered", triples.size() + "|" + FontGoldens.joinList(triples));
    }

    private static void dfont(GoldenMap out) throws Exception {
        List<Path> dfonts = new ArrayList<>(MacFontGoldens.CY_DFONTS);
        dfonts.add(MacFontGoldens.BLAIR_DFONT);
        for (Path dfont : dfonts) {
            out.put("dfont.register." + dfont.getFileName(), Boolean.toString(register(dfont, 1)));
        }
        out.put("dfont.available", Integer.toString(MacMachineShim.availableFonts().size()));
        String[] data = MacFontFinderShim.getFontData();
        List<String> triples = new ArrayList<>();
        for (int i = 0; i + 2 < data.length; i += 3) {
            if (data[i + 2] != null && data[i + 2].endsWith(".dfont")) {
                triples.add(text(data[i]) + "|" + text(data[i + 1]) + "|" + text(data[i + 2]));
            }
        }
        out.put("dfont.getFontData.length", Integer.toString(data.length));
        out.put("dfont.getFontData.dfonts", triples.size() + "|" + FontGoldens.joinList(triples));

        PrismFontFactory factory = PrismFontFactory.getFontFactory();
        String[] names = {"Helvetica CY Plain", "Helvetica CY Bold", "Helvetica CY Oblique", "Helvetica CY BoldOblique",
            "Geneva CY", "Charcoal CY", "BlairMdITC TT Medium"};
        for (String name : names) {
            String prefix = "dfont.font." + name.replace(' ', '_');
            PGFont font = factory.createFont(name, 12f);
            FontResource resource = font.getFontResource() instanceof CompositeFontResource composite
                    ? composite.getSlotResource(0) : font.getFontResource();
            out.put(prefix, text(font.getFullName()) + "|" + text(resource.getFullName()) + "|"
                    + text(resource.getPSName()) + "|" + resource.isEmbeddedFont());
            String file = resource.getFileName();
            boolean decoded = file != null && !file.endsWith(".dfont") && !file.endsWith(".ttc")
                    && !file.endsWith(".ttf") && Files.isRegularFile(Path.of(file));
            out.put(prefix + ".file", decoded ? "decoded" : text(file));
            if (decoded) {
                out.put(prefix + ".decoded", MacDFontGoldenTest.decoded(
                        new DFontDecoderShim.Decoded(Files.readAllBytes(Path.of(file)), null)));
            }
            int[] glyphs = MacFontCorpus.glyphs(resource, "Ag\u0416\u044F");
            MacFontCorpus.putMetrics(out, prefix, resource, glyphs);
            MacFontCorpus.putStrike(out, prefix + ".s12", resource, 12f, BaseTransform.IDENTITY_TRANSFORM,
                                    FontResource.AA_GREYSCALE, glyphs, 2, true);
            MacFontCorpus.putStrike(out, prefix + ".s24", resource, 24f, BaseTransform.IDENTITY_TRANSFORM,
                                    FontResource.AA_GREYSCALE, glyphs, 1, false);
        }
        for (String family : new String[] {"Helvetica CY", "Geneva CY", "Charcoal CY", "BlairMdITC TT"}) {
            for (boolean bold : new boolean[] {false, true}) {
                PGFont font = factory.createFont(family, bold, false, 12f);
                out.put("dfont.family." + family.replace(' ', '_') + (bold ? ".bold" : ".regular"),
                        text(font.getFullName()));
            }
        }
        for (String name : new String[] {"Helvetica CY Plain", "Charcoal CY", "BlairMdITC TT Medium"}) {
            String prefix = "dfont.decoder." + name.replace(' ', '_');
            long font = DFontDecoderShim.createCTFont(name);
            out.put(prefix + ".ref", font == 0 ? "0" : "found|" + text(MacFontOracleShim.fontPostScriptName(font)));
            if (font != 0) {
                out.put(prefix + ".format", Integer.toHexString(DFontDecoderShim.getCTFontFormat(font)));
                int[] tags = DFontDecoderShim.getCTFontTags(font);
                List<String> tables = new ArrayList<>();
                for (int tag : tags) {
                    tables.add(MacGoldenFormat.fourcc(tag) + ":"
                            + FontGoldenFormat.bytesSummary(DFontDecoderShim.getCTFontTable(font, tag)));
                }
                out.put(prefix + ".tables", tags.length + "|" + FontGoldens.joinList(tables));
                DFontDecoderShim.releaseCTFont(font);
            }
            out.put(prefix + ".decode", MacDFontGoldenTest.decoded(DFontDecoderShim.decode(name)));
        }
        out.put("dfont.decoder.null.decode", MacDFontGoldenTest.decoded(DFontDecoderShim.decode(null)));
        out.put("dfont.decoder.noSuchFont.decode", MacDFontGoldenTest.decoded(DFontDecoderShim.decode("No Such Font")));
    }

    private static Path copy(Path from, Path to) throws Exception {
        Files.createDirectories(to.getParent());
        return Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
    }

    static byte[] collection(byte[]... faces) {
        int header = 12 + 4 * faces.length;
        int[] directory = new int[faces.length];
        int position = header;
        for (int f = 0; f < faces.length; f++) {
            directory[f] = position;
            position += 12 + 16 * (ByteBuffer.wrap(faces[f]).getShort(4) & 0xFFFF);
        }
        position = (position + 3) & ~3;
        int dataStart = position;
        int total = dataStart;
        for (byte[] face : faces) {
            ByteBuffer src = ByteBuffer.wrap(face);
            int numTables = src.getShort(4) & 0xFFFF;
            for (int t = 0; t < numTables; t++) {
                total += (src.getInt(12 + 16 * t + 12) + 3) & ~3;
            }
        }
        ByteBuffer file = ByteBuffer.allocate(total);
        file.putInt(0x74746366).putInt(0x00010000).putInt(faces.length);
        for (int offset : directory) {
            file.putInt(offset);
        }
        int data = dataStart;
        for (int f = 0; f < faces.length; f++) {
            ByteBuffer src = ByteBuffer.wrap(faces[f]);
            int numTables = src.getShort(4) & 0xFFFF;
            file.position(directory[f]);
            file.putInt(src.getInt(0)).putShort((short) numTables).putShort(src.getShort(6)).putShort(src.getShort(8))
                .putShort(src.getShort(10));
            for (int t = 0; t < numTables; t++) {
                int record = 12 + 16 * t;
                int length = src.getInt(record + 12);
                file.putInt(src.getInt(record)).putInt(src.getInt(record + 4)).putInt(data).putInt(length);
                file.put(data, faces[f], src.getInt(record + 8), length);
                data += (length + 3) & ~3;
            }
        }
        return file.array();
    }
}
