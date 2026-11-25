/*
 * Copyright (c) 2011, 2026, Oracle and/or its affiliates. All rights reserved.
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

import com.sun.javafx.scene.text.GlyphList;
import com.sun.javafx.geom.transform.Affine2D;
import com.sun.javafx.geom.transform.BaseTransform;
import com.sun.javafx.geom.Path2D;
import com.sun.javafx.geom.Point2D;
import com.sun.javafx.geom.Shape;
import java.util.Arrays;

public class CompositeStrike implements FontStrike {

    private CompositeFontResource fontResource;
    private float size;
    private int aaMode;
    BaseTransform transform;
    private volatile FontStrike slot0Strike;
    // Replaced by filled copies, never written in place: render, layout and print threads read it without a lock.
    private volatile FontStrike[] strikeSlots;

    private FontStrikeDesc desc;
    DisposerRecord disposer;

    @Override
    public void clearDesc() {
        fontResource.getStrikeMap().remove(desc);
        // For a composite strike, you also need to remove the strike
        // refs of the raw fonts. At the least this needs to remove
        // the slot 0 strike, but it may be that the fallback strikes
        // should be left alone as they could be shared. This needs
        // to be re-visited.
        FontStrike slot0 = slot0Strike;
        if (slot0 != null) {
            slot0.clearDesc();
        }
        FontStrike[] slots = strikeSlots;
        if (slots != null) {
            for (int i=1; i<slots.length; i++) {
                if (slots[i] != null) {
                    slots[i].clearDesc();
                }
            }
        }
    }

    CompositeStrike(CompositeFontResource fontResource,
                    float size, BaseTransform graphicsTransform, int aaMode,
                    FontStrikeDesc desc) {

        this.fontResource = fontResource;
        this.size = size;
        if (graphicsTransform.isTranslateOrIdentity()) {
            this.transform = BaseTransform.IDENTITY_TRANSFORM;
        } else {
            this.transform = graphicsTransform.copy();
        }
        this.desc = desc;
        this.aaMode = aaMode;
        // CompositeStrikes do not directly hold any native resources
        // but we need to free the desc key from the strike map after
        // we find the strike has been GC'd.
        disposer = new CompositeStrikeDisposer(fontResource, desc);
    }

    @Override
    public int getAAMode() {
        PrismFontFactory factory = PrismFontFactory.getFontFactory();
        if (factory.isLCDTextSupported()) {
            return this.aaMode;
        } else {
            return FontResource.AA_GREYSCALE;
        }
    }

    /**
     * Trusting caller to NOT mutate the returned result, to
     * avoid a clone.
     */
    @Override
    public BaseTransform getTransform() {
        return transform;
    }

    public FontStrike getStrikeSlot(int slot) {
        if (slot == 0) {
            FontStrike strike = slot0Strike;
            if (strike == null) {
                FontResource slot0Resource = fontResource.getSlotResource(0);
                strike = slot0Resource.getStrike(size, transform, getAAMode());
                slot0Strike = strike;
            }
            return strike;
        } else {
            FontStrike[] slots = strikeSlots;
            if (slots == null) {
                slots = new FontStrike[fontResource.getNumSlots()];
            }
            FontStrike strike = slot < slots.length ? slots[slot] : null;
            if (strike == null) {
                FontResource slotResource = fontResource.getSlotResource(slot);
                if (slotResource != null) {
                    strike = slotResource.getStrike(size, transform, getAAMode());
                    FontStrike[] filled = Arrays.copyOf(slots, Math.max(slots.length, slot + 1));
                    filled[slot] = strike;
                    strikeSlots = filled;
                }
            }
            return strike;
        }
    }

    @Override
    public FontResource getFontResource() {
        return fontResource;
    }

    public int getStrikeSlotForGlyph(int glyphCode) {
        return (glyphCode >>> 24);
    }

    @Override
    public float getSize() {
        return size;
    }

    @Override
    public boolean drawAsShapes() {
        return getStrikeSlot(0).drawAsShapes();
    }

    private volatile PrismMetrics metrics;

    @Override
    public Metrics getMetrics() {
        PrismMetrics strikeMetrics = metrics;
        if (strikeMetrics == null) {
            PrismFontFile fr = (PrismFontFile)fontResource.getSlotResource(0);
            strikeMetrics = fr.getFontMetrics(size);
            metrics = strikeMetrics;
        }
        return strikeMetrics;
    }

    @Override
    public Glyph getGlyph(char symbol) {
        int glyphCode = fontResource.getGlyphMapper().charToGlyph(symbol);
        return getGlyph(glyphCode);
    }

    @Override
    public Glyph getGlyph(int glyphCode) {
        int slot = (glyphCode >>> 24);
        int slotglyphCode = glyphCode & CompositeGlyphMapper.GLYPHMASK;
        return getStrikeSlot(slot).getGlyph(slotglyphCode);
    }

     /**
     * Access to individual character advances are frequently needed for layout
     * understand that advance may vary for single glyph if ligatures or kerning
     * are enabled
     * @param ch char
     * @return advance of single char
     */
    @Override
    public float getCharAdvance(char ch) {
        int glyphCode = fontResource.getGlyphMapper().charToGlyph((int)ch);
        return fontResource.getAdvance(glyphCode, size);
    }

    @Override
    public int getQuantizedPosition(Point2D point) {
        return getStrikeSlot(0).getQuantizedPosition(point);
    }

    @Override
    public Shape getOutline(GlyphList gl, BaseTransform transform) {

        Path2D result = new Path2D();
        getOutline(gl, transform, result);
        return result;
    }

    void getOutline(GlyphList gl, BaseTransform transform, Path2D p) {
        p.reset();
        if (gl == null) {
            return;
        }
        if (transform == null) {
            transform = BaseTransform.IDENTITY_TRANSFORM;
        }
        Affine2D t = new Affine2D();
        for (int i = 0; i < gl.getGlyphCount(); i++) {
            int glyphCode = gl.getGlyphCode(i);
            if (glyphCode != CharToGlyphMapper.INVISIBLE_GLYPH_ID) {
                Glyph glyph = getGlyph(glyphCode);
                Shape gp = glyph.getShape();
                if (gp != null) {
                    t.setTransform(transform);
                    t.translate(gl.getPosX(i), gl.getPosY(i));
                    p.append(gp.getPathIterator(t), false);
                }
            }
        }
    }
}
