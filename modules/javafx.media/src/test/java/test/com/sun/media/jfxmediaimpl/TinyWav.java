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

package test.com.sun.media.jfxmediaimpl;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The smallest playable file the media tests need: a canonical 44 byte RIFF/WAVE header (8 bit mono PCM
 * at 8 kHz) followed by 20 bytes of silence, which is the shortest thing
 * {@code MediaUtils.fileSignatureToContentType} accepts as {@code audio/x-wav}. Nothing is ever played
 * from it; it exists so a {@code Locator}, a connection holder and a pipeline can be built without any
 * audio hardware.
 */
final class TinyWav {

    /** The size of {@link #bytes()}: a 44 byte header plus 20 bytes of samples. */
    static final int SIZE = 64;

    private TinyWav() {
    }

    /**
     * @return the file's content, a fresh array on every call
     */
    static byte[] bytes() {
        int dataBytes = SIZE - 44;
        ByteBuffer buffer = ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(ascii("RIFF"));
        buffer.putInt(36 + dataBytes);
        buffer.put(ascii("WAVE"));
        buffer.put(ascii("fmt "));
        buffer.putInt(16);              // PCM header size
        buffer.putShort((short) 1);     // PCM
        buffer.putShort((short) 1);     // mono
        buffer.putInt(8000);            // sample rate
        buffer.putInt(8000);            // byte rate
        buffer.putShort((short) 1);     // block align
        buffer.putShort((short) 8);     // bits per sample
        buffer.put(ascii("data"));
        buffer.putInt(dataBytes);       // the silence follows, already zeroed
        return buffer.array();
    }

    /**
     * Writes {@link #bytes()} to {@code file}.
     *
     * @param file the file to write
     * @return {@code file}
     * @throws IOException if the write fails
     */
    static Path writeTo(Path file) throws IOException {
        Files.write(file, bytes());
        return file;
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }
}
