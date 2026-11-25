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

import com.sun.javafx.font.FontResource;
import com.sun.javafx.font.FontStrike;
import com.sun.javafx.font.FontStrikeDesc;
import com.sun.javafx.geom.transform.BaseTransform;

/**
 * A grey-scale strike without a transform whose queries run hooks first. {@link CTGlyph} asks it:
 * <ul>
 * <li>whether its glyphs take sub-pixel positions, wherever it sets a context's options: while it creates a bitmap
 * context, between the create and the return, and before every draw into its cached context;</li>
 * <li>for a draw's sub-pixel position, before the draw;</li>
 * <li>for its anti-aliasing mode before a draw, at the read of the image and in {@code getWidth}; the sub-pixel
 * position asks for it as well, during the draw, for an index other than 0 at 12 pt and above;</li>
 * <li>for its font file while it measures a glyph, after the advances; wherever it asks whether a glyph is a colour
 * glyph, before a draw and in {@code getWidth}; and, through the strike, for a glyph's outline and its box.</li>
 * </ul>
 * The strike's quantized position asks for the anti-aliasing mode too. No factory registers the strike, so nothing
 * releases its CTFont before the JVM exits. No native call of its own.
 */
public final class CTFontStrikeShim extends CTFontStrike {

    /** The hooks, each run before its query answers; what a hook throws, its query throws. */
    public interface Calls {

        default void subPixelQuery() {
        }

        default void subPixelPosition() {
        }

        default void aaModeQuery() {
        }

        default void fontResourceQuery() {
        }
    }

    private final Calls calls;
    private final boolean subPixelPositions;

    private CTFontStrikeShim(CTFontFile fontResource, float size, Calls calls, boolean subPixelPositions) {
        this.calls = calls;
        this.subPixelPositions = subPixelPositions;
        super(fontResource, size, BaseTransform.IDENTITY_TRANSFORM, FontResource.AA_GREYSCALE,
              new FontStrikeDesc(size, BaseTransform.IDENTITY_TRANSFORM, FontResource.AA_GREYSCALE));
    }

    /** A strike of {@code resource}, a macOS font file, at {@code size}; {@code onSubPixelQuery} must not throw. */
    public static FontStrike create(FontResource resource, float size, Runnable onSubPixelQuery) {
        return create(resource, size, new Calls() {
            @Override
            public void subPixelQuery() {
                onSubPixelQuery.run();
            }
        });
    }

    /** A strike of {@code resource}, a macOS font file, at {@code size}, whose queries run {@code calls}. */
    public static FontStrike create(FontResource resource, float size, Calls calls) {
        return new CTFontStrikeShim((CTFontFile) resource, size, calls, true);
    }

    /**
     * A strike whose glyphs take no sub-pixel positions, as those of a scaled or rotated strike take none, but
     * without a transform.
     */
    public static FontStrike withoutSubPixelPositions(FontResource resource, float size) {
        return new CTFontStrikeShim((CTFontFile) resource, size, new Calls() { }, false);
    }

    @Override
    boolean isSubPixelGlyph() {
        calls.subPixelQuery();
        return subPixelPositions && super.isSubPixelGlyph();
    }

    @Override
    float getSubPixelPosition(int index) {
        calls.subPixelPosition();
        return super.getSubPixelPosition(index);
    }

    @Override
    public int getAAMode() {
        calls.aaModeQuery();
        return super.getAAMode();
    }

    @Override
    public CTFontFile getFontResource() {
        calls.fontResourceQuery();
        return super.getFontResource();
    }
}
