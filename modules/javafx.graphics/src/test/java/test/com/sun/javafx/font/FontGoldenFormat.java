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

import com.sun.javafx.font.PGFont;
import com.sun.javafx.geom.BaseBounds;
import com.sun.javafx.geom.Path2D;
import com.sun.javafx.geom.PathIterator;
import com.sun.javafx.geom.RectBounds;
import com.sun.javafx.geom.Shape;
import com.sun.javafx.geom.transform.BaseTransform;
import com.sun.javafx.scene.text.GlyphList;
import com.sun.javafx.scene.text.TextLayout;
import com.sun.javafx.text.PrismTextLayout;
import com.sun.javafx.text.TextRun;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import test.com.sun.javafx.font.FontGoldenHarness.GoldenMap;

/**
 * Value formats shared by the platform font goldens. Floats use {@link Float#toString}, which round-trips exactly,
 * and coordinate streams are hashed over {@link Float#floatToRawIntBits}, so equal behaviour gives byte-identical
 * files.
 */
final class FontGoldenFormat {

    private static final HexFormat HEX = HexFormat.of();

    private FontGoldenFormat() {
    }

    static String pad(int value, int width) {
        String digits = Integer.toString(Math.abs(value));
        StringBuilder text = new StringBuilder(value < 0 ? "m" : "");
        for (int i = digits.length(); i < width; i++) {
            text.append('0');
        }
        return text.append(digits).toString();
    }

    /** UTF-16 code units as upper-case hex joined by {@code .}; {@code #null} for null. */
    static String unitsHex(String text) {
        if (text == null) {
            return FontGoldens.NULL;
        }
        StringBuilder hex = new StringBuilder(text.length() * 5);
        for (int i = 0; i < text.length(); i++) {
            if (i > 0) {
                hex.append('.');
            }
            hex.append(String.format(Locale.ROOT, "%04X", (int) text.charAt(i)));
        }
        return hex.toString();
    }

    static String bytesHex(byte[] bytes) {
        return bytes == null ? FontGoldens.NULL : HEX.formatHex(bytes);
    }

    /** {@code length;sha256}. */
    static String bytesSummary(byte[] bytes) {
        return bytes == null ? FontGoldens.NULL : bytes.length + ";" + FontGoldens.sha256Hex(bytes);
    }

    static String joinFloats(float... values) {
        return values == null ? FontGoldens.NULL : FontGoldens.joinFloats(values, values.length);
    }

    static String joinInts(int[] values) {
        return values == null ? FontGoldens.NULL : FontGoldens.joinInts(values, values.length);
    }

    /**
     * {@code numCommands;commandsArrayLength;coordsIterated;coordsArrayLength;winding;sha256}, the hash over one
     * line per segment, {@code <type>} then {@code  %08x} of {@link Float#floatToRawIntBits} per coordinate.
     */
    static String outlineSummary(Path2D path) {
        if (path == null) {
            return FontGoldens.NULL;
        }
        StringBuilder stream = new StringBuilder();
        int iterated = iterate(path, (type, coords, count) -> {
            stream.append(type);
            for (int i = 0; i < count; i++) {
                stream.append(' ').append(String.format(Locale.ROOT, "%08x", Float.floatToRawIntBits(coords[i])));
            }
            stream.append('\n');
        });
        return path.getNumCommands() + ";" + path.getCommandsNoClone().length + ";" + iterated + ";"
                + path.getFloatCoordsNoClone().length + ";" + path.getWindingRule() + ";"
                + FontGoldens.sha256Hex(stream.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Segment types joined by {@code ;}, then {@code |}, then every iterated coordinate as {@link Float#toString}. */
    static String outlineDump(Path2D path) {
        if (path == null) {
            return FontGoldens.NULL;
        }
        List<String> types = new ArrayList<>();
        List<String> coordinates = new ArrayList<>();
        iterate(path, (type, coords, count) -> {
            types.add(Integer.toString(type));
            for (int i = 0; i < count; i++) {
                coordinates.add(Float.toString(coords[i]));
            }
        });
        return String.join(";", types) + "|" + String.join(";", coordinates);
    }

    /** {@link #outlineSummary} plus {@code ;} and the four bounds, for a glyph shape. */
    static String shapeSummary(Shape shape) {
        if (shape == null) {
            return FontGoldens.NULL;
        }
        if (!(shape instanceof Path2D path)) {
            return "class " + shape.getClass().getName();
        }
        RectBounds bounds = path.getBounds();
        return outlineSummary(path) + ";" + joinFloats(bounds.getMinX(), bounds.getMinY(), bounds.getMaxX(),
                                                        bounds.getMaxY());
    }

    private interface SegmentSink {
        void segment(int type, float[] coords, int count);
    }

    private static int iterate(Path2D path, SegmentSink sink) {
        PathIterator iterator = path.getPathIterator(BaseTransform.IDENTITY_TRANSFORM);
        float[] coords = new float[6];
        int total = 0;
        while (!iterator.isDone()) {
            int type = iterator.currentSegment(coords);
            int count = switch (type) {
                case PathIterator.SEG_MOVETO, PathIterator.SEG_LINETO -> 2;
                case PathIterator.SEG_QUADTO -> 4;
                case PathIterator.SEG_CUBICTO -> 6;
                default -> 0;
            };
            sink.segment(type, coords, count);
            total += count;
            iterator.next();
        }
        return total;
    }

    /**
     * One {@link PrismTextLayout} (no string cache) over {@code text}: {@code prefix} gets
     * {@code bounds|lines|runs}, and {@code prefix.rNN} per run
     * {@code start;length;level;script;slot;complex;ltr;glyphCount;width;height;ascent;descent;leading|codes|
     * charOffsets|posX|posY}, codes as hex.
     */
    static void putLayout(GoldenMap out, String prefix, PGFont font, String text, boolean rtl) {
        PrismTextLayout layout = new PrismTextLayout(0);
        if (rtl) {
            layout.setDirection(TextLayout.DIRECTION_RTL);
        }
        layout.setContent(text, font);
        GlyphList[] runs = layout.getRuns();
        BaseBounds bounds = layout.getBounds();
        out.put(prefix, joinFloats(bounds.getMinX(), bounds.getMinY(), bounds.getMaxX(), bounds.getMaxY())
                + "|" + layout.getLines().length + "|" + runs.length);
        for (int r = 0; r < runs.length; r++) {
            GlyphList run = runs[r];
            int count = run.getGlyphCount();
            StringBuilder value = new StringBuilder();
            value.append(run.getStart());
            if (run instanceof TextRun textRun) {
                value.append(';').append(textRun.getLength()).append(';').append(textRun.getLevel())
                     .append(';').append(textRun.getScript()).append(';').append(textRun.getSlot());
            } else {
                value.append(";#null;#null;#null;#null");
            }
            value.append(';').append(run.isComplex());
            if (run instanceof TextRun textRun) {
                value.append(';').append(textRun.isLeftToRight());
            } else {
                value.append(";#null");
            }
            value.append(';').append(count).append(';').append(Float.toString(run.getWidth()))
                 .append(';').append(Float.toString(run.getHeight()));
            if (run instanceof TextRun textRun) {
                value.append(';').append(Float.toString(textRun.getAscent()))
                     .append(';').append(Float.toString(textRun.getDescent()))
                     .append(';').append(Float.toString(textRun.getLeading()));
            } else {
                value.append(";#null;#null;#null");
            }
            List<String> codes = new ArrayList<>();
            List<String> offsets = new ArrayList<>();
            List<String> posX = new ArrayList<>();
            List<String> posY = new ArrayList<>();
            for (int g = 0; g < count; g++) {
                codes.add(Integer.toHexString(run.getGlyphCode(g)));
                offsets.add(Integer.toString(run.getCharOffset(g)));
            }
            if (count > 0) {
                for (int g = 0; g <= count; g++) {
                    posX.add(Float.toString(run.getPosX(g)));
                    posY.add(Float.toString(run.getPosY(g)));
                }
            }
            value.append('|').append(String.join(";", codes)).append('|').append(String.join(";", offsets))
                 .append('|').append(String.join(";", posX)).append('|').append(String.join(";", posY));
            out.put(prefix + ".r" + pad(r, 2), value.toString());
        }
    }
}
