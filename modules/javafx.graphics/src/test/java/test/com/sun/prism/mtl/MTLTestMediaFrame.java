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

package test.com.sun.prism.mtl;

import com.sun.prism.MediaFrame;
import com.sun.prism.PixelFormat;
import java.nio.ByteBuffer;

/**
 * A decoded video frame for the two formats only a {@code MediaFrame} reaches: {@code BYTE_APPLE_422} (one UYVY
 * plane, through {@code uyvy422_to_rgba}) and {@code MULTI_YCbCr_420} (three or four 8-bit planes). The planes are
 * heap buffers with row strides wider than their rows.
 */
final class MTLTestMediaFrame implements MediaFrame {

    private final PixelFormat format;
    private final int width;
    private final int height;
    private final ByteBuffer[] planes;
    private final int[] strides;

    private MTLTestMediaFrame(PixelFormat format, int width, int height, ByteBuffer[] planes, int[] strides) {
        this.format = format;
        this.width = width;
        this.height = height;
        this.planes = planes;
        this.strides = strides;
    }

    /** UYVY 4:2:2, {@code width} even, each row padded to {@code stride} bytes. */
    static MTLTestMediaFrame apple422(int width, int height, int stride) {
        byte[] data = new byte[stride * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width / 2; x++) {
                int at = y * stride + x * 4;
                data[at] = (byte) (64 + x * 11);
                data[at + 1] = (byte) (20 + y * 25 + x * 3);
                data[at + 2] = (byte) (200 - y * 13);
                data[at + 3] = (byte) (35 + y * 21 + x * 7);
            }
            for (int pad = width * 2; pad < stride; pad++) {
                data[y * stride + pad] = (byte) 0x5a;
            }
        }
        return new MTLTestMediaFrame(PixelFormat.BYTE_APPLE_422, width, height,
                new ByteBuffer[] {ByteBuffer.wrap(data)}, new int[] {stride});
    }

    /** Y, Cb, Cr and, with {@code alpha}, A planes; chroma at half resolution. */
    static MTLTestMediaFrame ycbcr420(int width, int height, boolean alpha) {
        int planeCount = alpha ? 4 : 3;
        ByteBuffer[] planes = new ByteBuffer[planeCount];
        int[] strides = new int[planeCount];
        for (int plane = 0; plane < planeCount; plane++) {
            boolean chroma = plane == PixelFormat.YCBCR_PLANE_CHROMABLUE || plane == PixelFormat.YCBCR_PLANE_CHROMARED;
            int w = chroma ? width / 2 : width;
            int h = chroma ? height / 2 : height;
            int stride = w + 3 + plane;
            byte[] data = new byte[stride * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < stride; x++) {
                    data[y * stride + x] = (byte) (x < w ? 30 + plane * 40 + x * 9 + y * 17 : 0xa5);
                }
            }
            planes[plane] = ByteBuffer.wrap(data);
            strides[plane] = stride;
        }
        return new MTLTestMediaFrame(PixelFormat.MULTI_YCbCr_420, width, height, planes, strides);
    }

    @Override
    public ByteBuffer getBufferForPlane(int plane) {
        return planes[plane];
    }

    @Override
    public PixelFormat getPixelFormat() {
        return format;
    }

    @Override
    public int getWidth() {
        return width;
    }

    @Override
    public int getHeight() {
        return height;
    }

    @Override
    public int getEncodedWidth() {
        return width;
    }

    @Override
    public int getEncodedHeight() {
        return height;
    }

    @Override
    public int planeCount() {
        return planes.length;
    }

    @Override
    public int[] planeStrides() {
        return strides.clone();
    }

    @Override
    public int strideForPlane(int planeIndex) {
        return strides[planeIndex];
    }

    @Override
    public MediaFrame convertToFormat(PixelFormat fmt) {
        return fmt == format ? this : null;
    }

    @Override
    public void holdFrame() {
    }

    @Override
    public void releaseFrame() {
    }
}
