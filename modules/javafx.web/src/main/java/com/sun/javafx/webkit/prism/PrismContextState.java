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

package com.sun.javafx.webkit.prism;

import com.sun.javafx.geom.Rectangle;
import com.sun.javafx.geom.transform.Affine3D;
import com.sun.javafx.geom.transform.BaseTransform;
import com.sun.javafx.geom.transform.GeneralTransform3D;
import com.sun.prism.Graphics;
import com.sun.prism.paint.Color;
import com.sun.prism.paint.Paint;
import com.sun.scenario.effect.DropShadow;
import com.sun.webkit.graphics.WCGraphicsContext;

final class PrismContextState {
    private final WCStrokeImpl stroke = new WCStrokeImpl();
    private Rectangle clip;
    private Paint paint;
    private float alpha;

    private boolean textFill = true;
    private boolean textStroke = false;
    private boolean textClip = false;
    private boolean restorePoint = false;

    private DropShadow shadow;
    private Affine3D xform;
    private GeneralTransform3D perspectiveTransform;
    private WCGraphicsPrismContext.Layer layer;
    private int compositeOperation;

    PrismContextState() {
        clip = null;
        paint = Color.BLACK;
        stroke.setPaint(Color.BLACK);
        alpha = 1.0f;
        xform = new Affine3D();
        perspectiveTransform = new GeneralTransform3D();
        compositeOperation = WCGraphicsContext.COMPOSITE_SOURCE_OVER;
    }

    private PrismContextState(PrismContextState state) {
        stroke.copyFrom(state.getStrokeNoClone());
        setPaint(state.getPaintNoClone());
        clip = state.getClipNoClone();
        if (clip != null) {
            clip = new Rectangle(clip);
        }
        xform = new Affine3D(state.getTransformNoClone());
        perspectiveTransform = new GeneralTransform3D().set(state.getPerspectiveTransformNoClone());
        setShadow(state.getShadowNoClone());
        setLayer(state.getLayerNoClone());
        setAlpha(state.getAlpha());
        setTextMode(state.isTextFill(), state.isTextStroke(), state.isTextClip());
        setCompositeOperation(state.getCompositeOperation());
    }

    @Override
    protected PrismContextState clone() {
        return new PrismContextState(this);
    }

    void apply(Graphics g) {
        if (perspectiveTransform.isIdentity() || !WCGraphicsPrismContext.appliesPerspective(g)) {
            g.setTransform(getTransformNoClone());
            g.setPerspectiveTransform(getPerspectiveTransformNoClone());
        } else {
            // Prism adds a translate-only transform to the vertices, before the shader applies
            // the perspective; folded into the perspective, the transform follows it everywhere.
            g.setTransform(BaseTransform.IDENTITY_TRANSFORM);
            g.setPerspectiveTransform(new GeneralTransform3D().mul(xform).mul(perspectiveTransform));
        }
        g.setClipRect(getClipNoClone());
        g.setExtraAlpha(getAlpha());
    }

    int getCompositeOperation() {
        return compositeOperation;
    }

    void setCompositeOperation(int compositeOperation) {
        this.compositeOperation = compositeOperation;
    }

    WCStrokeImpl getStrokeNoClone() {
        return stroke;
    }

    Paint getPaintNoClone() {
        return paint;
    }

    void setPaint(Paint paint) {
        this.paint = paint;
    }

    Rectangle getClipNoClone() {
        return clip;
    }

    WCGraphicsPrismContext.Layer getLayerNoClone() {
        return layer;
    }

    void setLayer(WCGraphicsPrismContext.Layer layer) {
        this.layer = layer;
    }

    void setClip(Rectangle area) {
        clip = area;
    }

    void clip(Rectangle area) {
        if (null == clip) {
            clip = area;
        } else {
            clip.intersectWith(area);
        }
    }

    void setAlpha(float alpha) {
        this.alpha = alpha;
    }

    float getAlpha() {
        return alpha;
    }

    void setTextMode(boolean fill, boolean stroke, boolean clip) {
        textFill = fill;
        textStroke = stroke;
        textClip = clip;
    }

    boolean isTextFill() {
        return textFill;
    }

    boolean isTextStroke() {
        return textStroke;
    }

    private boolean isTextClip() {
        return textClip;
    }

    void markAsRestorePoint() {
        restorePoint = true;
    }

    boolean isRestorePoint() {
        return restorePoint;
    }

    void setShadow(DropShadow shadow) {
        this.shadow = shadow;
    }

    DropShadow getShadowNoClone() {
        return shadow;
    }

    Affine3D getTransformNoClone() {
        return xform;
    }

    GeneralTransform3D getPerspectiveTransformNoClone() {
        return perspectiveTransform;
    }

    void setTransform(final Affine3D at) {
        this.xform.setTransform(at);
    }

    void setPerspectiveTransform(final GeneralTransform3D gt) {
        this.perspectiveTransform.set(gt);
    }

    void concatTransform(Affine3D at) {
        xform.concatenate(at);
    }

    void translate(double dx, double dy) {
        xform.translate(dx, dy);
    }

    void scale(double sx, double sy) {
        xform.scale(sx,sy);
    }

    void rotate(double radians) {
        xform.rotate(radians);
    }
}
