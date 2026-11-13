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

package com.sun.javafx.font.coretext;

import com.sun.javafx.font.CompositeFontResource;
import com.sun.javafx.font.FontResource;
import com.sun.javafx.font.FontStrike;
import com.sun.javafx.font.PrismFontFactory;
import java.lang.reflect.Field;

/** Package-private access to the macOS font peers for the font tests. No native call of its own. */
public final class CoreTextPeerShim {

    private CoreTextPeerShim() {
    }

    public static int addFallbackFont(CompositeFontResource fr, String fontName, long newRef) throws Exception {
        return CTGlyphLayout.addFallbackFont((CTFactory) PrismFontFactory.getFontFactory(), fr, fontName, newRef);
    }

    /** With no factory to ask: only a reference refused before the factory is reached returns normally. */
    public static int addFallbackFontWithoutFactory(CompositeFontResource fr, String fontName, long newRef)
            throws Exception {
        return CTGlyphLayout.addFallbackFont(null, fr, fontName, newRef);
    }

    public static FontResource createFontFile(String name, long fontRef) throws Exception {
        return ((CTFactory) PrismFontFactory.getFontFactory()).createFontFile(name, fontRef);
    }

    /** The CTFont a font file made from a reference owns; 0 for any other font. */
    public static long ctFontRef(FontResource resource) throws ReflectiveOperationException {
        if (!(resource instanceof CTFontFile)) {
            return 0;
        }
        Field field = CTFontFile.class.getDeclaredField("ctFontRef");
        field.setAccessible(true);
        return field.getLong(resource);
    }

    /** The CGFont of an embedded font file; 0 for any other font. */
    public static long cgFontRef(FontResource resource) {
        return resource instanceof CTFontFile file ? file.getCGFontRef() : 0;
    }

    public static long fontRef(FontStrike strike) {
        return ((CTFontStrike) strike).getFontRef();
    }
}
