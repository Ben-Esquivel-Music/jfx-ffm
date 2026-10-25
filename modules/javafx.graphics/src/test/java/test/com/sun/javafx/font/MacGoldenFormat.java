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

import java.util.Locale;

/**
 * The value formats of the macOS goldens that differ from {@link FontGoldenFormat}: glyph ids in hex, doubles and
 * readable text. {@link Double#toString} round-trips exactly.
 */
final class MacGoldenFormat {

    private MacGoldenFormat() {
    }

    static String gid(int glyph) {
        return "g" + String.format(Locale.ROOT, "%04x", glyph & 0xFFFF);
    }

    /** Readable text where it survives UTF-8 and the separators unchanged, else {@code u:} and the code units. */
    static String text(String text) {
        if (text == null) {
            return FontGoldens.NULL;
        }
        boolean plain = !text.startsWith("u:") && !text.startsWith("#") && !text.startsWith("d:")
                && !text.startsWith("f:");
        for (int i = 0; plain && i < text.length(); i++) {
            char c = text.charAt(i);
            plain = c >= 0x20 && c != 0x7F && !(c >= 0x80 && c < 0xA0) && !Character.isSurrogate(c) && c != '|'
                    && c != ';' && c != '\\' && c != '\u2028' && c != '\u2029' && c != '\uFEFF';
        }
        return plain ? text : "u:" + FontGoldenFormat.unitsHex(text);
    }

    static String doubles(double... values) {
        if (values == null) {
            return FontGoldens.NULL;
        }
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                joined.append(';');
            }
            joined.append(Double.toString(values[i]));
        }
        return joined.toString();
    }

    static String fourcc(int tag) {
        StringBuilder text = new StringBuilder(4);
        for (int shift = 24; shift >= 0; shift -= 8) {
            char c = (char) ((tag >>> shift) & 0xFF);
            if (c < 0x20 || c > 0x7E || c == ';' || c == '|' || c == '\\') {
                return String.format(Locale.ROOT, "0x%08x", tag);
            }
            text.append(c);
        }
        return text.toString();
    }
}
