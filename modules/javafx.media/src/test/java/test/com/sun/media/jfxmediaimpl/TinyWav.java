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
import java.util.Arrays;

/**
 * The tiny WAV file the media tests build everything on: a canonical 44 byte RIFF/WAVE header (8 bit
 * mono PCM at 8 kHz) followed by 20 bytes - one sample each - of silence. Nothing is ever played from
 * it; it exists so a {@code Locator}, a connection holder and a pipeline can be built without any audio
 * hardware.
 * <p>
 * The header alone is what makes it recognisable. For a file URI {@code Locator} takes
 * {@code audio/x-wav} from the {@code .wav} extension and then confirms it against the file's first
 * {@code MediaUtils.MAX_FILE_SIGNATURE_LENGTH} bytes - 22 of them, which is all
 * {@code MediaUtils.fileSignatureToContentType} is ever given: it reads "RIFF" at 0..3, "WAVE" at 8..11,
 * "fmt " at 12..15 and the format tag at 20..21, which has to say PCM or IEEE float. A bare 44 byte
 * header with an empty data chunk would therefore be accepted just the same; no sample buys any part of
 * the content type.
 * <p>
 * What the samples buy is a duration. 20 bytes of 8 bit mono at 8 kHz is 2.5 ms, the value
 * {@code JfxMediaNativeTest.playerOverATinyWavFileDisposesWithoutLeaks} asserts back out of
 * {@code MediaPlayer.getDuration} once the pipeline has parsed the file, and a header with an empty data
 * chunk would leave that assertion nothing to measure.
 * <p>
 * A silent sample here is not a zero one: RIFF/WAVE stores 8 bit PCM unsigned, so the silent level is
 * the 0x80 midpoint of 0..255 and a zero filled data chunk would be full scale negative DC - a click,
 * not silence. {@code JfxMediaNativeTest.theTinyWavFixtureIsSilentAndNotZeroFilled} is the one thing
 * that holds this file to that.
 */
final class TinyWav {

    /** The size of {@link #bytes()}: a 44 byte header plus 20 bytes of samples. */
    static final int SIZE = 64;

    /**
     * Where the data chunk's samples start, which is the size of the canonical RIFF/WAVE header before
     * them. The chunk itself starts at 36, where its tag is, and its length follows at 40; both are part
     * of the header this counts.
     */
    static final int HEADER_SIZE = 44;

    /** The silent level of unsigned 8 bit PCM, which is the midpoint of 0..255 and not zero. */
    private static final byte SILENT_SAMPLE = (byte) 0x80;

    private TinyWav() {
    }

    /**
     * @return the file's content, a fresh array on every call
     */
    static byte[] bytes() {
        int dataBytes = SIZE - HEADER_SIZE;
        ByteBuffer buffer = ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(ascii("RIFF"));
        // The RIFF chunk size: the 36 header bytes that follow this field, plus the samples. Spelled
        // out the way the format does rather than derived from HEADER_SIZE, which leaves
        // JfxMediaNativeTest.theTinyWavFixtureIsSilentAndNotZeroFilled one field it can check a moved
        // HEADER_SIZE against.
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
        buffer.putInt(dataBytes);
        byte[] wav = buffer.array();
        // The fill starts where the header just written ends, so it can neither leave a gap before the
        // samples nor reach back into a header field.
        Arrays.fill(wav, buffer.position(), SIZE, SILENT_SAMPLE);
        return wav;
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
