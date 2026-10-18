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

/**
 * The surface of the Pisces compositor: the C {@code Surface} struct of
 * {@code native-prism-sw/PiscesSurface.h}, the {@code surface_setRGB} of {@code PiscesSurface.inl} and the
 * {@code psw_surface_*} functions of {@code prism_sw_api.c}, at commit {@code 42a5072596}.
 * <p>
 * Like the C descriptor it owns no pixels: {@link #data} is the caller's array, bound for one call
 * ({@code psw_surface_bind}) and cleared again before the call returns.
 */
final class PiscesSurfaceState {

    final int width;
    final int height;
    final int offset;
    final int scanlineStride;
    final int pixelStride;
    final int imageType;

    /** The pixels of the call in progress, {@code null} between calls. */
    int[] data;

    private PiscesSurfaceState(int imageType, int width, int height) {
        // same geometry psw_surface_create (and the JNI JavaSurface_initialize before it) fixed
        this.width = width;
        this.height = height;
        this.offset = 0;
        this.scanlineStride = width;
        this.pixelStride = 1;
        this.imageType = imageType;
    }

    /**
     * {@code psw_surface_create}. The C returns NULL for any type but {@code TYPE_INT_ARGB_PRE}, which its FFM
     * facade rejected before the downcall with this exception and text ({@code PiscesNative.java:309-311} at
     * {@code 42a5072596}).
     */
    static PiscesSurfaceState create(int imageType, int width, int height) {
        if (imageType != RendererBase.TYPE_INT_ARGB_PRE) {
            throw new IllegalArgumentException("Unsupported image type: " + imageType);
        }
        return new PiscesSurfaceState(imageType, width, height);
    }

    /** Reads a block of pixels, with the {@code IllegalArgumentException} text of {@code getRGB}. */
    void getRGB(int[] pixels, int[] argb, int offset, int scanLength, int x, int y, int width, int height) {
        PiscesStatus.check(pswGetRGB(pixels, argb, argb.length, offset, scanLength, x, y, width, height),
                PiscesStatus.GET_RGB_MESSAGE);
    }

    /** Writes a block of pixels, with the {@code IllegalArgumentException} text of {@code setRGB}. */
    void setRGB(int[] pixels, int[] argb, int offset, int scanLength, int x, int y, int width, int height) {
        PiscesStatus.check(pswSetRGB(pixels, argb, argb.length, offset, scanLength, x, y, width, height),
                PiscesStatus.SET_RGB_MESSAGE);
    }

    /**
     * {@code psw_surface_bind}: binds the caller's pixel array for one call. This is the JNI surface_acquire
     * without the array-length test, which the JavaSurface constructor performs; the width/height test is
     * kept as it was.
     */
    int bind(int[] pixels) {
        if (pixels == null || width < 0 || height < 0) {
            return PiscesStatus.ERR_ARG;
        }
        data = pixels;
        return PiscesStatus.OK;
    }

    /** {@code psw_surface_unbind}. */
    void unbind() {
        data = null;
    }

    /** {@code psw_surface_get_rgb}. */
    int pswGetRGB(int[] pixels, int[] argb, int argbLen, int offset, int scanLength, int x, int y, int w, int h) {
        final int dstX = 0;
        final int dstY = 0;
        int surfaceWidth = this.width;
        int surfaceHeight = this.height;
        if (x < 0 || x >= surfaceWidth ||
            y < 0 || y >= surfaceHeight ||
            w < 0 || w > (surfaceWidth - x) ||
            h < 0 || h > (surfaceHeight - y) ||
            scanLength < w || offset < 0) {
            return PiscesStatus.ERR_ARG;                        // "Illegal arguments"
        }

        if ((w > 0) && (h > 0)) {
            if (dstY > ((Integer.MAX_VALUE - offset - dstX) / scanLength)) {
                return PiscesStatus.ERR_ARG;                    // "Out of bounds offset or scan length"
            }
            int dstStart = offset + dstY * scanLength + dstX;
            if (scanLength > ((Integer.MAX_VALUE - dstStart) / h)) {
                return PiscesStatus.ERR_ARG;                    // "Out of bounds offset or scan length"
            }
            int dstEnd = dstStart + h * scanLength - 1;
            if ((dstStart < 0) || (dstStart >= argbLen) || (dstEnd < 0) || (dstEnd >= argbLen)) {
                return PiscesStatus.ERR_ARG;                    // "Out of range access of buffer"
            }
            if (pixels == null || argb == null) {
                return PiscesStatus.ERR_ARG;
            }

            // the surface is read directly; nothing is bound, nothing to release
            int src = y * this.width + x;
            int dst = dstStart;
            int srcScanRest = this.width - w;
            int dstScanRest = scanLength - w;
            for (; h > 0; --h) {
                for (int w2 = w; w2 > 0; --w2) {
                    argb[dst++] = pixels[src++];
                }
                src += srcScanRest;
                dst += dstScanRest;
            }
        }
        return PiscesStatus.OK;
    }

    /** {@code psw_surface_set_rgb}. */
    int pswSetRGB(int[] pixels, int[] argb, int argbLen, int offset, int scanLength, int x, int y, int w, int h) {
        final int srcX = 0;
        final int srcY = 0;
        int surfaceWidth = this.width;
        int surfaceHeight = this.height;
        if (x < 0 || x >= surfaceWidth ||
            y < 0 || y >= surfaceHeight ||
            w < 0 || w > (surfaceWidth - x) ||
            h < 0 || h > (surfaceHeight - y) ||
            scanLength < w || offset < 0) {
            return PiscesStatus.ERR_ARG;                        // "Illegal arguments"
        }

        if ((w > 0) && (h > 0)) {
            if (srcY > ((Integer.MAX_VALUE - offset - srcX) / scanLength)) {
                return PiscesStatus.ERR_ARG;                    // "Out of bounds offset or scan length"
            }
            int srcStart = offset + srcY * scanLength + srcX;
            if (scanLength > ((Integer.MAX_VALUE - srcStart) / h)) {
                return PiscesStatus.ERR_ARG;                    // "Out of bounds offset or scan length"
            }
            int srcEnd = srcStart + h * scanLength - 1;
            if ((srcStart < 0) || (srcStart >= argbLen) || (srcEnd < 0) || (srcEnd >= argbLen)) {
                return PiscesStatus.ERR_ARG;                    // "out of range access of buffer"
            }
            if (argb == null) {
                return PiscesStatus.ERR_ARG;
            }

            int status = bind(pixels);
            if (status != PiscesStatus.OK) {
                return status;
            }
            surfaceSetRGB(x, y, w, h, argb, srcStart, scanLength);
            unbind();
        }
        return PiscesStatus.OK;
    }

    /** {@code surface_setRGB} and {@code setRGB} of {@code PiscesSurface.inl}. */
    private void surfaceSetRGB(int x, int y, int width, int height, int[] src, int srcStart, int scanLength) {
        if (data == null) {
            return;
        }
        int[] dstData = data;
        int dst = y * this.width + x;
        int srcIndex = srcStart;
        int srcScanRest = scanLength - width;
        int dstScanRest = this.width - width;
        for (; height > 0; --height) {
            for (int w2 = width; w2 > 0; --w2) {
                dstData[dst++] = src[srcIndex++];
            }
            srcIndex += srcScanRest;
            dst += dstScanRest;
        }
    }
}
