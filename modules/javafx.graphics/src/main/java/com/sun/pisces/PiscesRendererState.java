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

package com.sun.pisces;

import java.util.Objects;

/**
 * The renderer of the Pisces compositor: the C {@code Renderer} struct of
 * {@code native-prism-sw/PiscesRenderer.h}, the {@code renderer_*} functions and the state validation of
 * {@code PiscesRenderer.inl}, and the {@code psw_renderer_*} functions of {@code prism_sw_api.c} (with
 * {@code psw_fill_rect} and {@code psw_fill_alpha_mask}), at commit {@code 42a5072596}.
 * <p>
 * The port is literal. Fields keep the C names; the C function pointers are nullable fields of the functional
 * interfaces below, assigned by the same {@code update*} functions under the same {@link #rendererState} flags,
 * so that every call selects the routine the C selects. A NULL C pointer stays {@code null}, and calling it
 * throws {@link NullPointerException} where the C would crash the process. C pointers into arrays are an array
 * and an index. Allocation follows the C, including the paint buffer, which is reused while it is large enough
 * and keeps its stale content ({@code ALLOC3}).
 */
final class PiscesRendererState {

    /** A blit or a paint generator: {@code void (*)(Renderer*, jint height)}. */
    @FunctionalInterface
    interface Blit {
        void blit(PiscesRendererState rdr, int height);
    }

    /** An emit-line routine: {@code void (*)(Renderer*, jint height, jint frac)}. */
    @FunctionalInterface
    interface EmitLine {
        void emitLine(PiscesRendererState rdr, int height, int frac);
    }

    /** The clear-rectangle routine: {@code void (*)(Renderer*, jint x, jint y, jint w, jint h)}. */
    @FunctionalInterface
    interface ClearRect {
        void clearRect(PiscesRendererState rdr, int x, int y, int w, int h);
    }

    // PiscesRenderer.h
    static final int PAINT_FLAT_COLOR = 0;
    static final int PAINT_LINEAR_GRADIENT = 1;
    static final int PAINT_RADIAL_GRADIENT = 2;
    static final int PAINT_TEXTURE8888 = 4;
    static final int PAINT_TEXTURE8888_MULTIPLY = 5;

    static final int LG_GRADIENT_MAP_SIZE = 8;
    static final int GRADIENT_MAP_SIZE = 1 << LG_GRADIENT_MAP_SIZE;

    static final int NUM_ALPHA_ROWS = 8;

    static final int INVALID_RENDERER_SURFACE = 16;

    static final int CYCLE_NONE = 0;
    static final int CYCLE_REPEAT = 1;
    static final int CYCLE_REFLECT = 2;

    static final int NO_MASK = 0;
    static final int ALPHA_MASK = 1;
    static final int LCD_ALPHA_MASK = 2;

    static final int TEXTURE_TRANSFORM_IDENTITY = 1;
    static final int TEXTURE_TRANSFORM_TRANSLATE = 2;
    static final int TEXTURE_TRANSFORM_SCALE_TRANSLATE = 3;
    static final int TEXTURE_TRANSFORM_GENERIC = 4;

    // PiscesRenderer.inl
    static final int INVALID_COLOR_ALPHA_MAP = 1;
    static final int INVALID_PAINT_ALPHA_MAP = 2;
    static final int INVALID_INTERNAL_COLOR = 8;
    static final int INVALID_COMPOSITE_DEPENDED_ROUTINES = 32;
    static final int INVALID_PAINT_DEPENDED_ROUTINES = 64;
    static final int INVALID_MASK_DEPENDED_ROUTINES = 128;

    static final int INVALID_BLITTING_MASK = INVALID_INTERNAL_COLOR
            | INVALID_RENDERER_SURFACE
            | INVALID_COMPOSITE_DEPENDED_ROUTINES
            | INVALID_PAINT_DEPENDED_ROUTINES
            | INVALID_MASK_DEPENDED_ROUTINES;

    static final int INVALID_ALL = INVALID_COLOR_ALPHA_MAP
            | INVALID_PAINT_ALPHA_MAP
            | INVALID_BLITTING_MASK;

    // Flat color or (Java2D) linear gradient
    int paintMode;
    int prevPaintMode;

    // Current (user set) color
    int ured;
    int ugreen;
    int ublue;
    int ualpha;
    // Current (internal) color
    int cred;
    int cgreen;
    int cblue;
    int calpha;

    int compositeRule;

    final PiscesSurfaceState surface;

    // Image layout
    int[] data;
    int width;
    int height;
    int imageOffset;
    int imageScanlineStride;
    int imagePixelStride;
    int imageType;

    Blit blSourceOverMask;
    Blit blPTSourceOverMask;
    Blit blSourceMask;
    Blit blPTSourceMask;

    Blit blSourceOverLCDMask;
    Blit blPTSourceOverLCDMask;
    Blit blSourceLCDMask;
    Blit blPTSourceLCDMask;

    Blit blSourceOverNoMask;
    Blit blPTSourceOverNoMask;
    Blit blSourceNoMask;
    Blit blPTSourceNoMask;

    Blit blSourceOver;
    Blit blPTSourceOver;
    Blit blSource;
    Blit blPTSource;

    EmitLine elSource;
    EmitLine elSourceOver;
    EmitLine elPTSource;
    EmitLine elPTSourceOver;

    Blit blClear;
    Blit blPTClear;

    Blit bl;
    Blit blPT;

    EmitLine el;
    EmitLine elPT;

    ClearRect clearRect;
    Blit emitRows;
    EmitLine emitLine;
    Blit genPaint;

    int rowNum;
    int alphaWidth;
    int minTouched;
    int maxTouched;
    int currX;
    int currY;
    int currImageOffset;

    byte[] alphaMap;
    /** {@code _rowAAInt}: the delta row, read from {@link #rowAAIntOffset}. */
    int[] rowAAInt;
    int rowAAIntOffset;

    // used for fillRect call - contains original rectangle's X,Y values
    int rectX;
    int rectY;

    // Mask data
    int maskType;
    byte[] maskByteData;
    int maskOffset;
    int maskWidth;
    int maskHeight;

    // Paint buffer
    int[] paint;
    int paintLength;

    /** Scratch row of the split full-coverage paint blend in {@link PiscesBlit}; not part of the C struct. */
    private int[] blendScratch = new int[0];

    // Paint transform
    final int[] paintTransform = new int[PiscesTransform.INTS];

    // Gradient transform
    final int[] gradientTransform = new int[PiscesTransform.INTS];
    final int[] gradientInverseTransform = new int[PiscesTransform.INTS];

    // New-style linear gradient geometry: g(x, y) = x*mx + y*my + b
    float lgMx;
    float lgMy;
    float lgB;

    // Radial gradient geometry
    float rgA00;
    float rgA01;
    float rgA02;
    float rgA10;
    float rgA11;
    float rgA12;
    float rgCx;
    float rgCy;
    float rgFx;
    float rgFy;
    float rgR;
    float rgRsq;
    float rgA00a00;
    float rgA10a10;
    float rgA00a10;

    // Gradient color map
    final int[] gradientColors = new int[GRADIENT_MAP_SIZE];
    int gradientCycleMethod;

    // Texture paint: _texture_intData is textureIntData + textureIntOffset
    int[] textureIntData;
    int textureIntOffset;

    //hint to image rendering
    boolean textureHasAlpha;

    int textureImageWidth;
    int textureImageHeight;
    int textureStride;
    int textureTxMin;
    int textureTyMin;
    int textureTxMax;
    int textureTyMax;
    // TEXTURE_WRAP_*: REPEAT tiles the texture; see the edge rule in PiscesPaint
    int textureWrapMode;
    long textureM00;
    long textureM01;
    long textureM02;
    long textureM10;
    long textureM11;
    long textureM12;
    // if true, then we use linear interpolation for result pixel value calc.
    // otherwise nearest neighbour value is used
    boolean textureInterpolate;
    int textureTransformType;

    // Current bounding box for all primitives
    int clipBbMinX;
    int clipBbMinY;
    int clipBbMaxX;
    int clipBbMaxY;

    int elLfrac;
    int elRfrac;

    int rendererState;

    /** {@code renderer_create} / {@code createCommon}; every other field starts at 0 as the C calloc leaves it. */
    PiscesRendererState(PiscesSurfaceState surface) {
        // initialize image type to an invalid value (will be corrected later)
        this.imageType = -1;

        // initialize composite mode
        this.compositeRule = RendererBase.COMPOSITE_SRC_OVER;

        this.maskType = NO_MASK;

        // initialize paint mode
        this.paintMode = PAINT_FLAT_COLOR;

        // initialize surface reference
        this.surface = surface;

        // initialize the clip region
        this.clipBbMinX = 0;
        this.clipBbMinY = 0;
        this.clipBbMaxX = surface.width - 1;
        this.clipBbMaxY = surface.height - 1;

        // initialize renderer state
        this.rendererState = INVALID_ALL;
    }

    /* ---------------------------------------------------------------------------------------------
     * The psw_renderer_* entry points and the exceptions their FFM facade threw for each status
     * ------------------------------------------------------------------------------------------- */

    void setClip(int minX, int minY, int width, int height) {
        rendererSetClip(minX, minY, width, height);
    }

    void setColor(int red, int green, int blue, int alpha) {
        rendererSetColor(red, green, blue, alpha);
    }

    void setCompositeRule(int compositeRule) {
        rendererSetCompositeRule(compositeRule);
    }

    void setLinearGradient(int x0, int y0, int x1, int y1, int[] colors, int cycleMethod,
            Transform6 gradientTransform) {
        PiscesStatus.check(pswSetLinearGradient(x0, y0, x1, y1, colors, colors.length, cycleMethod,
                PiscesTransform.of(gradientTransform)), PiscesStatus.ARG_MESSAGE);
    }

    void setRadialGradient(int cx, int cy, int fx, int fy, int radius, int[] colors, int cycleMethod,
            Transform6 gradientTransform) {
        PiscesStatus.check(pswSetRadialGradient(cx, cy, fx, fy, radius, colors, colors.length, cycleMethod,
                PiscesTransform.of(gradientTransform)), PiscesStatus.ARG_MESSAGE);
    }

    void setTexture(int imageType, int[] data, int width, int height, int stride,
            Transform6 textureTransform, int wrapMode, boolean linearFiltering, boolean hasAlpha) {
        PiscesStatus.check(pswSetTexture(imageType, data, data.length, width, height, stride,
                PiscesTransform.of(textureTransform), wrapMode, linearFiltering, hasAlpha),
                PiscesStatus.ARG_MESSAGE);
    }

    void clearRect(int[] pixels, int x, int y, int w, int h) {
        PiscesStatus.check(pswClearRect(pixels, x, y, w, h), PiscesStatus.ARG_MESSAGE);
    }

    void fillRect(int[] pixels, int x, int y, int w, int h) {
        PiscesStatus.check(pswFillRect(pixels, x, y, w, h), PiscesStatus.ARG_MESSAGE);
    }

    void emitAndClearAlphaRow(int[] pixels, byte[] alphaMap, int[] alphaDeltas, int y, int xFrom,
            int xTo, int xOff, int rowNum) {
        // the FFM facade wrapped the arrays in MemorySegment.ofArray before the downcall
        // (PiscesNative.java:501-503 at 42a5072596), which throws NullPointerException for null;
        // keep that rather than return PSW_ERR_ARG
        Objects.requireNonNull(pixels);
        Objects.requireNonNull(alphaMap);
        Objects.requireNonNull(alphaDeltas);
        PiscesStatus.check(pswEmitAndClearAlphaRow(pixels, alphaMap, alphaDeltas, y, xFrom, xTo, xOff, rowNum),
                PiscesStatus.ARG_MESSAGE);
    }

    void fillAlphaMask(int[] pixels, byte[] mask, int x, int y, int width, int height, int offset,
            int stride) {
        PiscesStatus.check(pswFillAlphaMask(pixels, mask, x, y, width, height, offset, stride),
                PiscesStatus.ARG_MESSAGE);
    }

    void fillLCDAlphaMask(int[] pixels, byte[] mask, int x, int y, int width, int height, int offset,
            int stride) {
        PiscesStatus.check(pswFillLCDAlphaMask(pixels, mask, x, y, width, height, offset, stride),
                PiscesStatus.ARG_MESSAGE);
    }

    void drawImage(int[] pixels, int imageType, int imageMode, int[] data, int width, int height,
            int offset, int stride, Transform6 textureTransform, int wrapMode, boolean linearFiltering,
            int bboxX, int bboxY, int bboxW, int bboxH, int lEdge, int rEdge, int tEdge, int bEdge,
            int txMin, int tyMin, int txMax, int tyMax, boolean hasAlpha) {
        PiscesStatus.check(pswDrawImage(pixels, imageType, imageMode, data, width, height, offset, stride,
                PiscesTransform.of(textureTransform), wrapMode, linearFiltering,
                bboxX, bboxY, bboxW, bboxH, lEdge, rEdge, tEdge, bEdge, txMin, tyMin, txMax, tyMax, hasAlpha),
                PiscesStatus.ARG_MESSAGE);
    }

    void lcdGammaSet(float gamma) {
        // psw_lcd_gamma_set
        PiscesBlit.initGammaArrays(gamma);
    }

    /* ---------------------------------------------------------------------------------------------
     * prism_sw_api.c
     * ------------------------------------------------------------------------------------------- */

    /** The wrap modes {@code psw_renderer_set_texture} and {@code psw_renderer_draw_image} accept. */
    private static boolean pswWrapModeValid(int wrapMode) {
        return wrapMode == RendererBase.WRAP_CLAMP_TO_EDGE || wrapMode == RendererBase.WRAP_REPEAT
            || wrapMode == RendererBase.WRAP_CLAMP_TO_ZERO;
    }

    /** {@code psw_renderer_unbind}: unbinds the surface and drops the pixels the last validation took. */
    private void pswRendererUnbind() {
        surface.unbind();
        data = null;
    }

    /** {@code psw_renderer_set_linear_gradient}. */
    int pswSetLinearGradient(int x0, int y0, int x1, int y1, int[] colors, int colorsLen, int cycleMethod,
            int[] tx) {
        if (colors == null || tx == null || colorsLen < GRADIENT_MAP_SIZE) {
            return PiscesStatus.ERR_ARG;
        }
        int[] gradientTransform = tx.clone();
        gradientCycleMethod = cycleMethod;
        rendererSetLinearGradient(x0, y0, x1, y1, colors, gradientTransform);
        return PiscesStatus.OK;
    }

    /** {@code psw_renderer_set_radial_gradient}. */
    int pswSetRadialGradient(int cx, int cy, int fx, int fy, int radius, int[] colors, int colorsLen,
            int cycleMethod, int[] tx) {
        if (colors == null || tx == null || colorsLen < GRADIENT_MAP_SIZE) {
            return PiscesStatus.ERR_ARG;
        }
        int[] gradientTransform = tx.clone();
        gradientCycleMethod = cycleMethod;
        rendererSetRadialGradient(cx, cy, fx, fy, radius, colors, gradientTransform);
        return PiscesStatus.OK;
    }

    /**
     * {@code psw_renderer_set_texture}. The texture is copied. The JNI-era dimension guard is kept unchanged,
     * and when it fails this returns {@code PSW_ERR_OOM}, not {@code PSW_ERR_ARG}, because the JNI
     * {@code setTextureImpl} fell through to {@code setMemErrorFlag()} there; so does a failed allocation.
     */
    int pswSetTexture(int imageType, int[] data, int dataLen, int w, int h, int stride, int[] tx,
            int wrapMode, boolean linearFiltering, boolean hasAlpha) {
        int[] textureTransform = null;
        int[] src = null;

        if (data == null || tx == null || !pswWrapModeValid(wrapMode)) {
            return PiscesStatus.ERR_ARG;
        }

        // Same guard as the JNI-era setTextureImpl (in plain int arithmetic, which
        // is equivalent because w and h have been checked positive first). A failed guard leaves `src`
        // null and ends in the OOM status, exactly as the JNI code fell through to setMemErrorFlag().
        if (w > 0 && h > 0 && w < (Integer.MAX_VALUE / h) / 4
            && stride > 0 && (h - 1) <= (dataLen - w) / stride) {
            textureTransform = tx.clone();
            src = data;
        }
        if (src == null) {
            return PiscesStatus.ERR_OOM;
        }
        int[] allocData;
        try {
            allocData = new int[w * h];
        } catch (OutOfMemoryError e) {
            // my_malloc returned NULL: setMemErrorFlag()
            return PiscesStatus.ERR_OOM;
        }
        if (stride == w) {
            System.arraycopy(src, 0, allocData, 0, w * h);
        } else {
            for (int i = 0; i < h; i++) {
                System.arraycopy(src, i * stride, allocData, i * w, w);
            }
        }
        rendererSetTexture(RendererBase.IMAGE_MODE_NORMAL,
                           allocData, 0, w, h, w,
                           wrapMode, linearFiltering,
                           textureTransform, hasAlpha,
                           0, 0, w - 1, h - 1);
        return PiscesStatus.OK;
    }

    /** {@code psw_renderer_clear_rect}. */
    int pswClearRect(int[] pixels, int x, int y, int w, int h) {
        PiscesSurfaceState surface = this.surface;
        int status = surface.bind(pixels);
        if (status != PiscesStatus.OK) {
            return status;
        }
        rendererState |= INVALID_RENDERER_SURFACE;

        imagePixelStride = 1;
        imageScanlineStride = surface.width;
        rendererClearRect(x, y, w, h);

        pswRendererUnbind();
        return PiscesStatus.OK;
    }

    /** {@code psw_renderer_fill_rect}: S15.16 coordinates, every edge {@code IMAGE_FRAC_EDGE_KEEP}. */
    int pswFillRect(int[] pixels, int x, int y, int w, int h) {
        PiscesSurfaceState surface = this.surface;
        int status = surface.bind(pixels);
        if (status != PiscesStatus.OK) {
            return status;
        }
        fillRect(surface, x, y, w, h,
                 RendererBase.IMAGE_FRAC_EDGE_KEEP, RendererBase.IMAGE_FRAC_EDGE_KEEP,
                 RendererBase.IMAGE_FRAC_EDGE_KEEP, RendererBase.IMAGE_FRAC_EDGE_KEEP);
        pswRendererUnbind();
        return PiscesStatus.OK;
    }

    /**
     * {@code psw_renderer_emit_and_clear_alpha_row}: composites one Marlin coverage row; {@code alphaDeltas}
     * (read from {@code xOff}) is consumed and zeroed in place.
     */
    int pswEmitAndClearAlphaRow(int[] pixels, byte[] alphaMap, int[] alphaDeltas, int y, int xFrom, int xTo,
            int xOff, int rowNum) {
        if (alphaMap == null || alphaDeltas == null) {
            return PiscesStatus.ERR_ARG;
        }
        PiscesSurfaceState surface = this.surface;
        int status = surface.bind(pixels);
        if (status != PiscesStatus.OK) {
            return status;
        }
        rendererState |= INVALID_RENDERER_SURFACE;
        validateBlitting();

        xFrom = Math.max(xFrom, clipBbMinX);
        xTo = Math.min(xTo, clipBbMaxX);

        if (xTo >= xFrom &&
            y >= clipBbMinY &&
            y <= clipBbMaxY)
        {
            minTouched = xFrom;
            maxTouched = xTo;
            currX = xFrom;
            currY = y;

            this.rowNum = rowNum;

            this.alphaMap = alphaMap;
            rowAAInt = alphaDeltas;
            rowAAIntOffset = xOff; /* add offset in alpha buffer */
            alphaWidth = xTo - xFrom + 1;

            currImageOffset = y * surface.width;
            imageScanlineStride = surface.width;
            imagePixelStride = 1;

            if (genPaint != null) {
                int l = (xTo - xFrom + 1);
                allocPaint(l);
                genPaint.blit(this, 1);
            }
            // the no-mask blits consume rowAAInt and zero it in place (PiscesBlit)
            emitRows.blit(this, 1);
            rowAAInt = null;
        }
        // only the no-mask blits read alphaMap, and only in this call: do not keep the array
        this.alphaMap = null;

        pswRendererUnbind();
        return PiscesStatus.OK;
    }

    /** {@code psw_renderer_fill_alpha_mask}. */
    int pswFillAlphaMask(int[] pixels, byte[] mask, int x, int y, int maskW, int maskH, int offset, int stride) {
        int minX;
        int minY;
        int maxX;
        int maxY;
        int maskOffset;

        if (mask == null) {
            return PiscesStatus.ERR_ARG;
        }

        if (x <= -(Integer.MAX_VALUE - maskW) || y <= -(Integer.MAX_VALUE - maskH)) {
            return PiscesStatus.OK;
        }

        if (x >= Integer.MAX_VALUE - maskW || y >= Integer.MAX_VALUE - maskH) {
            return PiscesStatus.OK;
        }

        minX = Math.max(x, clipBbMinX);
        minY = Math.max(y, clipBbMinY);
        maxX = Math.min(x + maskW - 1, clipBbMaxX);
        maxY = Math.min(y + maskH - 1, clipBbMaxY);

        // offset, width, height and stride cannot be negative - checked in Java code.
        // below checks might be a bit excessive (probably won't happen because fillAlphaMask()
        // min/max check will make maskOffset not be used at all) but better to be safe than sorry
        if (maskW > 0 && (minY - y) >= (Integer.MAX_VALUE / maskW)) {
            return PiscesStatus.OK;
        }

        if ((minX - x) >= Integer.MAX_VALUE - ((minY - y) * maskW)) {
            return PiscesStatus.OK;
        }

        if (offset >= Integer.MAX_VALUE - ((minY - y) * maskW + minX - x)) {
            return PiscesStatus.OK;
        }

        maskOffset = offset + (minY - y) * maskW + minX - x;

        if (maxX >= minX && maxY >= minY) {
            PiscesSurfaceState surface = this.surface;
            int status = surface.bind(pixels);
            if (status != PiscesStatus.OK) {
                return status;
            }
            fillAlphaMask(surface, minX, minY, maxX, maxY, ALPHA_MASK, mask, x, maskW, maskH, maskOffset);
            pswRendererUnbind();
        }
        return PiscesStatus.OK;
    }

    /** {@code psw_renderer_fill_lcd_alpha_mask}: three subpixel bytes per output pixel. */
    int pswFillLCDAlphaMask(int[] pixels, byte[] mask, int x, int y, int maskW, int maskH, int offset,
            int stride) {
        int minX;
        int minY;
        int maxX;
        int maxY;
        int maskOffset;

        if (mask == null) {
            return PiscesStatus.ERR_ARG;
        }

        if (x < -(Integer.MAX_VALUE - maskW / 3) || y < -(Integer.MAX_VALUE - maskH)) {
            return PiscesStatus.OK;
        }

        if (x >= Integer.MAX_VALUE - (maskW / 3) || y >= Integer.MAX_VALUE - maskH) {
            return PiscesStatus.OK;
        }

        minX = Math.max(x, clipBbMinX);
        minY = Math.max(y, clipBbMinY);
        maxX = Math.min(x + (maskW / 3) - 1, clipBbMaxX);
        maxY = Math.min(y + maskH - 1, clipBbMaxY);

        if (maskW > 0 && (minY - y) >= (Integer.MAX_VALUE / maskW)) {
            return PiscesStatus.OK;
        }

        if ((minX - x) >= Integer.MAX_VALUE / 3) {
            return PiscesStatus.OK;
        }

        if (((minX - x) * 3) >= Integer.MAX_VALUE - ((minY - y) * maskW)) {
            return PiscesStatus.OK;
        }

        if (offset >= Integer.MAX_VALUE - ((minY - y) * maskW + (minX - x) * 3)) {
            return PiscesStatus.OK;
        }

        maskOffset = offset + (minY - y) * maskW + (minX - x) * 3;

        if (maxX >= minX && maxY >= minY) {
            PiscesSurfaceState surface = this.surface;
            int status = surface.bind(pixels);
            if (status != PiscesStatus.OK) {
                return status;
            }
            fillAlphaMask(surface, minX, minY, maxX, maxY, LCD_ALPHA_MASK, mask, x, maskW, maskH, maskOffset);
            pswRendererUnbind();
        }
        return PiscesStatus.OK;
    }

    /**
     * {@code psw_renderer_draw_image}: the texture is sampled in place from {@code data[offset]} and detached
     * before return.
     */
    int pswDrawImage(int[] pixels, int imageType, int imageMode, int[] data, int w, int h, int offset,
            int stride, int[] tx, int wrapMode, boolean linearFiltering,
            int bboxX, int bboxY, int bboxW, int bboxH, int lEdge, int rEdge, int tEdge, int bEdge,
            int txMin, int tyMin, int txMax, int tyMax, boolean hasAlpha) {
        if (data == null || tx == null || !pswWrapModeValid(wrapMode)) {
            return PiscesStatus.ERR_ARG;
        }
        // the texture is sampled in place, so it must not be empty (it has no texel to read) and the
        // sampling bounds have to lie inside it; txMax < txMin (tyMax < tyMin) is a source of zero
        // width (height), which SWGraphics.drawTexture produces
        if (w <= 0 || h <= 0 || txMin < 0 || tyMin < 0 || txMax >= w || tyMax >= h) {
            return PiscesStatus.ERR_ARG;
        }
        PiscesSurfaceState surface = this.surface;
        int status = surface.bind(pixels);
        if (status != PiscesStatus.OK) {
            return status;
        }

        int[] textureTransform = tx.clone();
        // the texture is sampled in place and detached again below
        rendererSetTexture(imageMode, data, offset, w, h, stride,
                           wrapMode, linearFiltering,
                           textureTransform, hasAlpha,
                           txMin, tyMin, txMax, tyMax);

        fillRect(surface,
                 bboxX, bboxY, bboxW, bboxH,
                 lEdge, rEdge, tEdge, bEdge);

        textureIntData = null;
        textureIntOffset = 0;

        pswRendererUnbind();
        return PiscesStatus.OK;
    }

    /** A scratch row of at least {@code len} ints for {@link PiscesBlit}; its content is not kept between calls. */
    int[] blendScratch(int len) {
        if (blendScratch.length < len) {
            blendScratch = new int[len];
        }
        return blendScratch;
    }

    /** {@code ALLOC3(rdr->_paint, jint, len)}: a new zeroed buffer of exactly {@code len} only when too small. */
    void allocPaint(int len) {
        if (paint == null || paintLength < len) {
            paint = new int[len];
            paintLength = len;
        }
    }

    /**
     * {@code psw_fill_rect}: x, y, w, h are S15.16 surface coordinates of an axis-aligned rectangle; the
     * caller has bound the pixels.
     */
    private void fillRect(PiscesSurfaceState surface, int x, int y, int w, int h,
            int lEdge, int rEdge, int tEdge, int bEdge) {
        int xFrom;
        int xTo;
        int yFrom;
        int yTo;
        int lfrac;
        int rfrac;
        int tfrac;
        int bfrac;
        int rowsToRenderByLoop;
        int rowsBeingRendered;

        lfrac = (0x10000 - (x & 0xFFFF)) & 0xFFFF;
        rfrac = (x + w) & 0xFFFF;
        tfrac = (0x10000 - (y & 0xFFFF)) & 0xFFFF;
        bfrac = (y + h) & 0xFFFF;

        xFrom = x >> 16;
        xTo = x + w;
        xTo = (rfrac != 0) ? xTo >> 16 : (xTo >> 16) - 1;
        yFrom = y >> 16;
        yTo = y + h;
        yTo = (bfrac != 0) ? yTo >> 16 : (yTo >> 16) - 1;

        rectX = xFrom;
        rectY = yFrom;

        switch (lEdge) {
            case RendererBase.IMAGE_FRAC_EDGE_PAD:
                lfrac = 0;
                break;
            case RendererBase.IMAGE_FRAC_EDGE_TRIM:
                if (lfrac != 0) {
                    xFrom++;
                }
                lfrac = 0;
                break;
            default:
                break;
        }

        switch (rEdge) {
            case RendererBase.IMAGE_FRAC_EDGE_PAD:
                rfrac = 0;
                break;
            case RendererBase.IMAGE_FRAC_EDGE_TRIM:
                if (rfrac != 0) {
                    xTo--;
                }
                rfrac = 0;
                break;
            default:
                break;
        }

        switch (tEdge) {
            case RendererBase.IMAGE_FRAC_EDGE_PAD:
                tfrac = 0;
                break;
            case RendererBase.IMAGE_FRAC_EDGE_TRIM:
                if (tfrac != 0) {
                    yFrom++;
                }
                tfrac = 0;
                break;
            default:
                break;
        }

        switch (bEdge) {
            case RendererBase.IMAGE_FRAC_EDGE_PAD:
                bfrac = 0;
                break;
            case RendererBase.IMAGE_FRAC_EDGE_TRIM:
                if (bfrac != 0) {
                    yTo--;
                }
                bfrac = 0;
                break;
            default:
                break;
        }

        // apply clip
        if (xFrom < clipBbMinX) {
            xFrom = clipBbMinX;
            lfrac = 0;
        }
        if (yFrom < clipBbMinY) {
            yFrom = clipBbMinY;
            tfrac = 0;
        }
        if (xTo > clipBbMaxX) {
            xTo = clipBbMaxX;
            rfrac = 0;
        }
        if (yTo > clipBbMaxY) {
            yTo = clipBbMaxY;
            bfrac = 0;
        }

        if ((xFrom <= xTo) && (yFrom <= yTo)) {
            rowsToRenderByLoop = yTo - yFrom + 1;

            rendererState |= INVALID_RENDERER_SURFACE;
            validateBlitting();

            minTouched = xFrom;
            maxTouched = xTo;
            currX = xFrom;
            currY = yFrom;

            alphaWidth = xTo - xFrom + 1;

            currImageOffset = yFrom * surface.width;
            imageScanlineStride = surface.width;
            imagePixelStride = 1;
            rowNum = 0;

            if (yFrom == yTo && (tfrac | bfrac) != 0) {
                // rendering single horizontal fractional line bfrac > (y & 0xFFFF)
                tfrac = (bfrac - 0x10000 + tfrac) & 0xFFFF;
                bfrac = 0;
            }
            if (xFrom == xTo && (lfrac | rfrac) != 0) {
                // rendering single vertival fractional line rfrac > (x & 0xFFFF)
                lfrac = (rfrac - 0x10000 + lfrac) & 0xFFFF;
                rfrac = 0;
            }

            elLfrac = lfrac;
            elRfrac = rfrac;

            if (bfrac != 0) {
                // one "full" line less -> will be rendered at the end
                rowsToRenderByLoop--;
            }

            // emit fractional top line
            if (tfrac != 0) {
                if (genPaint != null) {
                    int l = (xTo - xFrom + 1);
                    allocPaint(l);
                    genPaint.blit(this, 1);
                }
                emitLine.emitLine(this, 1, tfrac);
                rowsToRenderByLoop--;
                currX = xFrom;
                currY++;
                currImageOffset = currY * surface.width;
                rowNum++;
            }

            // emit "full" lines that are in the middle
            while (rowsToRenderByLoop > 0) {
                rowsBeingRendered = Math.min(rowsToRenderByLoop, NUM_ALPHA_ROWS);

                if (genPaint != null) {
                    int l = (xTo - xFrom + 1) * rowsBeingRendered;
                    allocPaint(l);
                    genPaint.blit(this, rowsBeingRendered);
                }
                emitLine.emitLine(this, rowsBeingRendered, 0x10000);

                rowsToRenderByLoop -= rowsBeingRendered;
                currX = xFrom;
                currY += rowsBeingRendered;
                currImageOffset = currY * surface.width;
                rowNum += rowsBeingRendered;
            }

            // emit fractional bottom line
            if (bfrac != 0) {
                if (genPaint != null) {
                    int l = (xTo - xFrom + 1);
                    allocPaint(l);
                    genPaint.blit(this, 1);
                }
                emitLine.emitLine(this, 1, bfrac);
            }
        }
    }

    /**
     * {@code psw_fill_alpha_mask}: [minX, maxX] x [minY, maxY] is the already-clipped destination rectangle;
     * {@code offset} is the index of the first mask byte for (minX, minY).
     */
    private void fillAlphaMask(PiscesSurfaceState surface, int minX, int minY, int maxX, int maxY,
            int maskType, byte[] mask, int x, int maskWidth, int maskHeight, int offset) {
        int rowsToBeRendered;
        int rowsBeingRendered;
        int width = maxX - minX + 1;
        int height = maxY - minY + 1;

        // the mask is read-only for the renderer; renderer_setMask just stores the reference
        rendererSetMask(maskType, mask, maskWidth, maskHeight);

        rendererState |= INVALID_RENDERER_SURFACE;
        validateBlitting();

        minTouched = minX;
        maxTouched = maxX;
        currX = minX;
        currY = minY;

        alphaWidth = width;

        imageScanlineStride = surface.width;
        imagePixelStride = 1;
        rowNum = 0;
        maskOffset = offset;

        rowsToBeRendered = height;

        if (genPaint == null && rowsToBeRendered > 0) {
            // A flat colour has no paint to generate per row, so all rows go to the routine in one call, which
            // composites them as the C's one-row calls below do: a mask blit steps to the next mask row by
            // maskWidth and to the next image row by imageScanlineStride == surface.width, as this loop does.
            // The other routines that can be here (the no-mask blit of COMPOSITE_CLEAR, a NULL LCD routine)
            // fail or restart at the first delta on every row either way. The fields end as the loop leaves them.
            currImageOffset = currY * surface.width;
            emitRows.blit(this, rowsToBeRendered);

            maskOffset += maskWidth * rowsToBeRendered;
            rowNum += rowsToBeRendered;
            currX = x;
            currY += rowsToBeRendered;
            currImageOffset = (currY - 1) * surface.width;
            rowsToBeRendered = 0;
        }

        while (rowsToBeRendered > 0) {
            rowsBeingRendered = 1; //MIN(rowsToBeRendered, NUM_ALPHA_ROWS);

            currImageOffset = currY * surface.width;
            if (genPaint != null) {
                int l = (width * rowsBeingRendered);
                allocPaint(l);
                genPaint.blit(this, rowsBeingRendered);
            }
            emitRows.blit(this, rowsBeingRendered);

            maskOffset += maskWidth;
            rowNum += rowsBeingRendered;
            rowsToBeRendered -= rowsBeingRendered;
            currX = x;
            currY += rowsBeingRendered;
        }

        rendererRemoveMask();
    }

    /* ---------------------------------------------------------------------------------------------
     * PiscesRenderer.inl
     * ------------------------------------------------------------------------------------------- */

    /** {@code VALIDATE_BLITTING}: the update functions run on a snapshot of the flags taken first. */
    void validateBlitting() {
        if ((rendererState & INVALID_BLITTING_MASK) != 0) {
            int state = rendererState;

            if ((state & INVALID_RENDERER_SURFACE) != 0) {
                updateRendererSurface();
            }

            if ((state & INVALID_INTERNAL_COLOR) != 0) {
                updateInternalColor();
            }

            if ((state & INVALID_MASK_DEPENDED_ROUTINES) != 0) {
                /* Optimization: validates also INVALID_PAINT_DEPENDED_ROUTINES */
                updateMaskDependedRoutines();
            } else if ((state & INVALID_COMPOSITE_DEPENDED_ROUTINES) != 0) {
                updateCompositeDependedRoutines();
            } else if ((state & INVALID_PAINT_DEPENDED_ROUTINES) != 0) {
                updatePaintDependedRoutines();
            }
        }
    }

    /** {@code renderer_setClip}: integer (not S15.16) surface coordinates. */
    void rendererSetClip(int minX, int minY, int width, int height) {
        clipBbMinX = minX;
        clipBbMinY = minY;
        clipBbMaxX = minX + width - 1;
        clipBbMaxY = minY + height - 1;
    }

    /** {@code renderer_setColor}. */
    void rendererSetColor(int red, int green, int blue, int alpha) {
        if ((ured != red) ||
            (ugreen != green) ||
            (ublue != blue) ||
            (ualpha != alpha))
        {
            rendererState |= INVALID_INTERNAL_COLOR;
            if (ualpha != alpha) {
                rendererState |= INVALID_COLOR_ALPHA_MAP |
                                 INVALID_PAINT_ALPHA_MAP;
            }

            ured = red;
            ugreen = green;
            ublue = blue;
            ualpha = alpha;
        }

        setPaintMode(PAINT_FLAT_COLOR);
    }

    /** {@code renderer_setCompositeRule}. */
    void rendererSetCompositeRule(int compositeRule) {
        if (this.compositeRule != compositeRule) {
            // composite mode COMPOSITE_CLEAR changes the internal color
            rendererState |= INVALID_INTERNAL_COLOR |
                             INVALID_COMPOSITE_DEPENDED_ROUTINES;

            if ((compositeRule == RendererBase.COMPOSITE_SRC_OVER) ||
                (((compositeRule == RendererBase.COMPOSITE_CLEAR) ||
                (compositeRule == RendererBase.COMPOSITE_SRC)) &&
                ((imageType == RendererBase.TYPE_INT_ARGB_PRE))))
            {
                rendererState |= INVALID_COLOR_ALPHA_MAP |
                                 INVALID_PAINT_ALPHA_MAP;
            }

            this.compositeRule = compositeRule;
        }
    }

    /**
     * {@code renderer_setLinearGradient}: g(x, y) = x*mx + y*my + b in float, in the C's order. The linear part
     * of the inverse is taken as the raw S15.16 ints, so mx and my come out in S15.16 per pixel.
     */
    void rendererSetLinearGradient(int x0, int y0, int x1, int y1, int[] colors, int[] transform) {
        float fx0;
        float fx1;
        float fy0;
        float fy1;
        float fdx;
        float fdy;
        float flensq;
        float t;
        float a00;
        float a01;
        float a02;
        float a10;
        float a11;
        float a12;

        System.arraycopy(transform, 0, gradientTransform, 0, PiscesTransform.INTS);
        System.arraycopy(transform, 0, gradientInverseTransform, 0, PiscesTransform.INTS);
        PiscesTransform.invert(gradientInverseTransform);

        a00 = gradientInverseTransform[PiscesTransform.M00];
        a01 = gradientInverseTransform[PiscesTransform.M01];
        a02 = gradientInverseTransform[PiscesTransform.M02] / 65536.0f;
        a10 = gradientInverseTransform[PiscesTransform.M10];
        a11 = gradientInverseTransform[PiscesTransform.M11];
        a12 = gradientInverseTransform[PiscesTransform.M12] / 65536.0f;

        fx0 = x0 / 65536.0f;
        fx1 = x1 / 65536.0f;
        fy0 = y0 / 65536.0f;
        fy1 = y1 / 65536.0f;
        fdx = fx1 - fx0;
        fdy = fy1 - fy0;
        flensq = fdx * fdx + fdy * fdy;
        t = fdx * fx0 + fdy * fy0;

        lgMx = (a00 * fdx + a10 * fdy) / flensq;
        lgMy = (a01 * fdx + a11 * fdy) / flensq;
        lgB = (65536.0f * (a02 * fdx + a12 * fdy - t) / flensq);

        setPaintMode(PAINT_LINEAR_GRADIENT);
        System.arraycopy(colors, 0, gradientColors, 0, GRADIENT_MAP_SIZE);
    }

    /**
     * {@code renderer_setRadialGradient}: the geometry in float, in the C's order; a focus too close to the
     * circle is pulled in to 0.97 of the radius through a double {@code sqrt} rounded to float, as in the C.
     */
    void rendererSetRadialGradient(int cx, int cy, int fx, int fy, int radius, int[] colors, int[] transform) {
        float fxf;
        float fyf;
        float cxf;
        float cyf;
        float fcx;
        float fcy;
        float dsq;

        System.arraycopy(transform, 0, gradientTransform, 0, PiscesTransform.INTS);
        System.arraycopy(transform, 0, gradientInverseTransform, 0, PiscesTransform.INTS);
        PiscesTransform.invert(gradientInverseTransform);

        rgA00 = gradientInverseTransform[PiscesTransform.M00] / 65536.0f;
        rgA01 = gradientInverseTransform[PiscesTransform.M01] / 65536.0f;
        rgA02 = gradientInverseTransform[PiscesTransform.M02] / 65536.0f;
        rgA10 = gradientInverseTransform[PiscesTransform.M10] / 65536.0f;
        rgA11 = gradientInverseTransform[PiscesTransform.M11] / 65536.0f;
        rgA12 = gradientInverseTransform[PiscesTransform.M12] / 65536.0f;

        rgA00a00 = rgA00 * rgA00;
        rgA10a10 = rgA10 * rgA10;
        rgA00a10 = rgA00 * rgA10;

        cxf = cx / 65536.0f;
        cyf = cy / 65536.0f;
        fxf = fx / 65536.0f;
        fyf = fy / 65536.0f;
        rgR = radius / 65536.0f;
        rgRsq = rgR * rgR;

        fcx = fxf - cxf;
        fcy = fyf - cyf;
        dsq = fcx * fcx + fcy * fcy;
        if (dsq > rgRsq * 0.94f) {
            float f = (rgR * 0.97f) / ((float) Math.sqrt(dsq));
            fxf = cxf + f * fcx;
            fyf = cyf + f * fcy;
        }

        rgCx = cxf;
        rgCy = cyf;
        rgFx = fxf;
        rgFy = fyf;

        setPaintMode(PAINT_RADIAL_GRADIENT);
        System.arraycopy(colors, 0, gradientColors, 0, GRADIENT_MAP_SIZE);
    }

    /**
     * {@code renderer_setTexture}: {@code data[dataOffset]} is the first texel. {@code freeData} of the C only
     * decided who frees the copy; the garbage collector does that here.
     */
    void rendererSetTexture(int renderMode, int[] data, int dataOffset, int width, int height, int stride,
            int wrapMode, boolean smooth, int[] transform, boolean textureHasAlpha,
            int txMin, int tyMin, int txMax, int tyMax) {
        System.arraycopy(transform, 0, paintTransform, 0, PiscesTransform.INTS);

        int[] compoundTransform = transform.clone();
        PiscesTransform.invert(compoundTransform);

        setPaintMode((renderMode == RendererBase.IMAGE_MODE_NORMAL) ?
            PAINT_TEXTURE8888 : PAINT_TEXTURE8888_MULTIPLY);

        this.textureHasAlpha = textureHasAlpha;

        textureIntData = data;
        textureIntOffset = dataOffset;

        textureImageWidth = width;
        textureImageHeight = height;
        textureStride = stride;
        textureWrapMode = wrapMode;
        textureTxMin = txMin;
        textureTyMin = tyMin;
        textureTxMax = txMax;
        textureTyMax = tyMax;

        textureM00 = compoundTransform[PiscesTransform.M00];
        textureM01 = compoundTransform[PiscesTransform.M01];
        textureM10 = compoundTransform[PiscesTransform.M10];
        textureM11 = compoundTransform[PiscesTransform.M11];
        textureM02 = compoundTransform[PiscesTransform.M02];
        textureM12 = compoundTransform[PiscesTransform.M12];

        // Both filters sample the texture at the pixel centre (x + 1/2, y + 1/2), as the GPU pipelines
        // do: the origin moves by half a column and half a row of the inverse transform. Bilinear
        // sampling then subtracts half a texel (32768) to measure the sample from the texel centres:
        // tx and tx + 1 are the texels whose centres bracket it, the fraction is the weight of tx + 1.
        // Nearest sampling reads the texel the pixel centre falls in.
        if (smooth) {
            textureInterpolate = true;
            textureM02 += (textureM00 >> 1) + (textureM01 >> 1) - 32768;   // interpolate == true
            textureM12 += (textureM10 >> 1) + (textureM11 >> 1) - 32768;   // interpolate == true
        } else {
            textureInterpolate = false;
            textureM02 += (textureM00 >> 1) + (textureM01 >> 1);
            textureM12 += (textureM10 >> 1) + (textureM11 >> 1);
        }

        // Classify the sampling transform, shift included. Under a unit scale the bilinear shift is 0,
        // and the nearest one gives an integer translation k the fraction 0x8000: nearest sampling is
        // then TRANSLATE rather than IDENTITY, and floor(x + k + 1/2) = x + k picks the texels it picked
        // without the shift. IDENTITY falls through to TRANSLATE in genTexturePaintTarget anyway.
        if (textureM00 == 65536 &&
            textureM11 == 65536 &&
            textureM01 == 0 &&
            textureM10 == 0)
        {
            if (textureM02 == 0 && textureM12 == 0)
            {
                textureTransformType = TEXTURE_TRANSFORM_IDENTITY;
                // we can disable interpolation since TX and TX has no fraction part
                textureInterpolate = false;
            } else {
                textureTransformType = TEXTURE_TRANSFORM_TRANSLATE;
                if ((textureM02 & 0xFFFF) == 0 &&
                    (textureM12 & 0xFFFF) == 0)
                {
                    // we can disable interpolation since TX and TX has no fraction part
                    textureInterpolate = false;
                }
            }
        } else {
            if (textureM01 == 0 && textureM10 == 0) {
                textureTransformType = TEXTURE_TRANSFORM_SCALE_TRANSLATE;
            } else {
                textureTransformType = TEXTURE_TRANSFORM_GENERIC;
            }
        }
    }

    /** {@code renderer_setMask}; the mask is only referenced, as with the C {@code freeData == XNI_FALSE}. */
    void rendererSetMask(int maskType, byte[] data, int width, int height) {
        this.maskType = maskType;
        maskByteData = data;

        maskWidth = width;
        maskHeight = height;

        rendererState |= INVALID_BLITTING_MASK | INVALID_MASK_DEPENDED_ROUTINES;
    }

    /** {@code renderer_removeMask}. */
    void rendererRemoveMask() {
        maskType = NO_MASK;
        maskByteData = null;
        rendererState |= INVALID_BLITTING_MASK | INVALID_MASK_DEPENDED_ROUTINES;
    }

    /** {@code renderer_clearRect}: integer surface coordinates, clipped to the surface and the clip. */
    void rendererClearRect(int x, int y, int w, int h) {
        int maxX = x + w - 1;
        int maxY = y + h - 1;

        validateBlitting();

        x = Math.max(x, 0);
        x = Math.max(x, clipBbMinX);

        y = Math.max(y, 0);
        y = Math.max(y, clipBbMinY);

        maxX = Math.min(maxX, width - 1);
        maxX = Math.min(maxX, clipBbMaxX);

        maxY = Math.min(maxY, height - 1);
        maxY = Math.min(maxY, clipBbMaxY);

        if ((x <= maxX) && (y <= maxY)) {
            clearRect.clearRect(this, x, y, maxX - x + 1, maxY - y + 1);
        }
    }

    /** {@code updateInternalColor}. */
    private void updateInternalColor() {
        if (compositeRule == RendererBase.COMPOSITE_CLEAR) {
            cred = 0;
            cgreen = 0;
            cblue = 0;
            calpha = 0;
        } else {
            cred = ured;
            cgreen = ugreen;
            cblue = ublue;
            calpha = ualpha;
        }
        rendererState &= ~INVALID_INTERNAL_COLOR;
    }

    /** {@code updateRendererSurface}. */
    private void updateRendererSurface() {
        PiscesSurfaceState surface = this.surface;

        width = surface.width;
        height = surface.height;
        data = surface.data;
        imageOffset = surface.offset;
        imageScanlineStride = surface.scanlineStride;
        imagePixelStride = surface.pixelStride;

        if (imageType != surface.imageType)
        {
            if ((compositeRule != RendererBase.COMPOSITE_SRC_OVER) &&
                (surface.imageType == RendererBase.TYPE_INT_ARGB_PRE))
            {
                rendererState |= INVALID_COLOR_ALPHA_MAP |
                                 INVALID_PAINT_ALPHA_MAP;
            }

            imageType = surface.imageType;
            updateSurfaceDependedRoutines();
        }

        rendererState &= ~INVALID_RENDERER_SURFACE;
    }

    /** {@code updateSurfaceDependedRoutines}. */
    private void updateSurfaceDependedRoutines() {
        switch (imageType) {
            case RendererBase.TYPE_INT_ARGB_PRE:
                blSourceOverNoMask = PiscesBlit::blitSrcOver8888Pre;
                blPTSourceOverNoMask = PiscesBlit::blitPTSrcOver8888Pre;
                blSourceNoMask = PiscesBlit::blitSrc8888Pre;
                blPTSourceNoMask = PiscesBlit::blitPTSrc8888Pre;

                blSourceOverMask = PiscesBlit::blitSrcOverMask8888Pre;
                blPTSourceOverMask = PiscesBlit::blitPTSrcOverMask8888Pre;
                blSourceMask = PiscesBlit::blitSrcMask8888Pre;
                blPTSourceMask = PiscesBlit::blitPTSrcMask8888Pre;

                blSourceOverLCDMask = PiscesBlit::blitSrcOverLCDMask8888Pre;
                blPTSourceOverLCDMask = null;
                blSourceLCDMask = null;
                blPTSourceLCDMask = null;

                blClear = PiscesBlit::blitSrc8888Pre;
                blPTClear = PiscesBlit::blitSrc8888Pre;
                clearRect = PiscesBlit::clearRect8888Any;

                elSource = PiscesBlit::emitLineSource8888Pre;
                elSourceOver = PiscesBlit::emitLineSourceOver8888Pre;
                elPTSource = PiscesBlit::emitLinePTSource8888Pre;
                elPTSourceOver = PiscesBlit::emitLinePTSourceOver8888Pre;
                break;
            default:
                // unsupported!
                break;
        }

        updateMaskDependedRoutines();
    }

    /** {@code updateMaskDependedRoutines}. */
    private void updateMaskDependedRoutines() {
        switch (maskType) {
            case NO_MASK:
                blSourceOver = blSourceOverNoMask;
                blPTSourceOver = blPTSourceOverNoMask;
                blSource = blSourceNoMask;
                blPTSource = blPTSourceNoMask;
                break;
            case ALPHA_MASK:
                blSourceOver = blSourceOverMask;
                blPTSourceOver = blPTSourceOverMask;
                blSource = blSourceMask;
                blPTSource = blPTSourceMask;
                break;
            case LCD_ALPHA_MASK:
                blSourceOver = blSourceOverLCDMask;
                blPTSourceOver = blPTSourceOverLCDMask;
                blSource = blSourceLCDMask;
                blPTSource = blPTSourceLCDMask;
                break;
            default:
                // unsupported!
                break;
        }
        updateCompositeDependedRoutines();
        rendererState &= ~INVALID_MASK_DEPENDED_ROUTINES;
    }

    /** {@code updateCompositeDependedRoutines}. */
    private void updateCompositeDependedRoutines() {
        switch (compositeRule) {
            case RendererBase.COMPOSITE_SRC_OVER:
                bl = blSourceOver;
                blPT = blPTSourceOver;
                el = elSourceOver;
                elPT = elPTSourceOver;
                break;
            case RendererBase.COMPOSITE_SRC:
                bl = blSource;
                blPT = blPTSource;
                el = elSource;
                elPT = elPTSource;
                break;
            case RendererBase.COMPOSITE_CLEAR:
                bl = blClear;
                blPT = blPTClear;
                el = elSource;
                elPT = elSource;
                break;
            default:
                // unsupported!
                break;
        }
        updatePaintDependedRoutines();
        rendererState &= ~INVALID_COMPOSITE_DEPENDED_ROUTINES;
    }

    /** {@code updatePaintDependedRoutines}. */
    private void updatePaintDependedRoutines() {
        switch (paintMode) {
            case PAINT_LINEAR_GRADIENT:
                genPaint = PiscesPaint::genLinearGradientPaint;
                emitRows = blPT;
                emitLine = elPT;
                break;
            case PAINT_RADIAL_GRADIENT:
                genPaint = PiscesPaint::genRadialGradientPaint;
                emitRows = blPT;
                emitLine = elPT;
                break;
            case PAINT_TEXTURE8888:
                genPaint = PiscesPaint::genTexturePaint;
                emitRows = blPT;
                emitLine = elPT;
                break;
            case PAINT_TEXTURE8888_MULTIPLY:
                genPaint = PiscesPaint::genTexturePaintMultiply;
                emitRows = blPT;
                emitLine = elPT;
                break;
            case PAINT_FLAT_COLOR:
                genPaint = null;
                emitRows = bl;
                emitLine = el;
                break;
            default:
                // unsupported!
                break;
        }

        rendererState &= ~INVALID_PAINT_DEPENDED_ROUTINES;
    }

    /** {@code setPaintMode}: leaving a paint mode drops the texture, as the C frees and NULLs it. */
    void setPaintMode(int newPaintMode) {
        if (paintMode != newPaintMode) {
            textureIntData = null;
            textureIntOffset = 0;

            rendererState |= INVALID_PAINT_DEPENDED_ROUTINES;
            prevPaintMode = paintMode;
            paintMode = newPaintMode;
        }
    }
}
