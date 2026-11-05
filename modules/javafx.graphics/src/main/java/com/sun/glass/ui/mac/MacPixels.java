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
package com.sun.glass.ui.mac;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import com.sun.glass.ui.Pixels;

/**
 * MacOSX platform implementation class for Pixels.
 */
final class MacPixels extends Pixels {

    /** What {@code gmac_image_create} reads: CoreGraphics' 32-bit little-endian, alpha-first premultiplied pixels. */
    private static final int nativeFormat = Pixels.Format.BYTE_BGRA_PRE;

    static int getNativeFormat_impl() {
        return nativeFormat;
    }

    protected MacPixels(int width, int height, ByteBuffer data) {
        super(width, height, data);
    }

    protected MacPixels(int width, int height, ByteBuffer data, float scalex, float scaley) {
        super(width, height, data, scalex, scaley);
    }

    protected MacPixels(int width, int height, IntBuffer data) {
        super(width, height, data);
    }

    protected MacPixels(int width, int height, IntBuffer data, float scalex, float scaley) {
        super(width, height, data, scalex, scaley);
    }

    @Override
    protected void _fillDirectByteBuffer(ByteBuffer bb) {
        if (this.bytes != null) {
            this.bytes.rewind();
            bb.put(this.bytes);
            this.bytes.rewind();
        } else {
            this.ints.rewind();
            for (int i=0; i<this.ints.capacity(); i++) {
                int data = this.ints.get();
                bb.put((byte)((data>>0)&0xff));
                bb.put((byte)((data>>8)&0xff));
                bb.put((byte)((data>>16)&0xff));
                bb.put((byte)((data>>24)&0xff));
            }
            this.ints.rewind();
        }
    }

    @Override
    protected void _attachInt(long ptr, int w, int h, IntBuffer ints, int[] array, int offset) {
        MacCursorCalls.attachInt(ptr, w, h, ints, array, offset);
    }

    @Override
    protected void _attachByte(long ptr, int w, int h, ByteBuffer bytes, byte[] array, int offset) {
        MacCursorCalls.attachByte(ptr, w, h, bytes, array, offset);
    }

    @Override
    public String toString() {
        return "MacPixels ["+getWidth()+"x"+getHeight()+"]: "+super.toString();
    }
}
