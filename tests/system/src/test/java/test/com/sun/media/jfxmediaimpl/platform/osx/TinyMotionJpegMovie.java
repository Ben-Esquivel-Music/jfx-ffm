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

package test.com.sun.media.jfxmediaimpl.platform.osx;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * A short Photo-JPEG movie, generated rather than checked in.
 * <p>
 * {@link AVFVideoDisposeRaceApp} needs video that AVFoundation will decode, because the display link
 * whose lifetime is under test is only created for a track with visual media and is only started when
 * {@code -outputMediaDataWillChange:} says pixel buffers are coming. The repository has no video file
 * at all, and adding a binary to it is not the way this fork makes test media: {@code SineWav} and
 * {@code TinyWav} in {@code modules/javafx.media/src/test} generate their WAV files at run time, and
 * this is the same idea for the one video format a JVM can write without carrying an encoder.
 *
 * <h2>Why Photo-JPEG, and why the container looks the way it does</h2>
 * The sample payloads are whole JFIF images from {@link ImageIO}, so the only thing hand written here
 * is the container. That is deliberate: a bit error in an entropy coded bitstream is unfindable, while
 * a QuickTime movie box tree is short, declarative and checkable by eye. Motion JPEG is also the one
 * non-trivial codec macOS decodes natively without a third party component - it is why QuickTime X
 * plays MJPEG AVI files at all - so {@code jpeg} is the sample description this writes.
 * <p>
 * The file is a QuickTime movie (major brand {@code qt}, padded to four characters) carrying an
 * {@code .mp4} name, which is not a contradiction but a consequence of the two independent readers it
 * has to satisfy:
 * <ul>
 * <li>AVFoundation identifies the format from the bytes, and the QuickTime brand is the one under which
 *     a {@code jpeg} sample entry is unambiguously the Photo-JPEG it is meant to be.</li>
 * <li>JavaFX identifies it from the name. {@code Locator.init()} calls
 *     {@code MediaUtils.filenameToContentType} first for a {@code file:} URI and only falls back to the
 *     file signature when that answers nothing, and {@code .mp4} answers {@code video/mp4} - the type
 *     {@code OSXPlatform} claims and, on macOS, the one type {@code GSTPlatform} leaves to it.
 *     {@code .mov} is not in the extension table of {@code MediaUtils} at all, so a movie named for
 *     what it is would reach the signature sniffer and be rejected before anything could play it.</li>
 * </ul>
 * The two {@code hdlr} boxes carry QuickTime component types with the subtype sitting where an ISO
 * reader looks for {@code handler_type}, and their name field is a single zero byte, which is both an
 * empty Pascal string and an empty C string. The tree is therefore readable either way round, so
 * nothing here depends on guessing which dialect AVFoundation brings to the file.
 */
final class TinyMotionJpegMovie {

    /** Frame width. A multiple of 16, so no JPEG macroblock is padded and no chroma plane is odd. */
    static final int WIDTH = 128;

    /** Frame height, for the same reason. */
    static final int HEIGHT = 96;

    /** Movie and media timescale. 600 is the classic QuickTime value and divides 30 exactly. */
    static final int TIMESCALE = 600;

    /** Duration of one sample in {@link #TIMESCALE} units: 600/20 is 30 frames per second. */
    static final int FRAME_DURATION = 20;

    /**
     * How many frames the movie holds. Five seconds at 30 fps, which is an order of magnitude more than
     * the few hundred milliseconds {@link AVFVideoDisposeRaceApp} plays before it disposes: end of media
     * must never arrive first, because a player that has finished has stopped its display link and a
     * dispose that follows one tests nothing.
     */
    static final int FRAME_COUNT = 150;

    /** ISO-639-2/T "und", packed as three five bit letters offset from 0x60. */
    private static final int LANGUAGE_UNDETERMINED = 0x55C4;

    /** 72 dpi as a 16.16 fixed point number, which is what every QuickTime writer puts here. */
    private static final int RESOLUTION_72_DPI = 0x00480000;

    /** The identity transform in the fixed point form {@code tkhd} and {@code mvhd} share. */
    private static final int[] IDENTITY_MATRIX = {
        0x00010000, 0, 0,
        0, 0x00010000, 0,
        0, 0, 0x40000000
    };

    private TinyMotionJpegMovie() {
    }

    /** The duration of the movie in seconds, exactly as its header declares it. */
    static double seconds() {
        return (double) (FRAME_COUNT * FRAME_DURATION) / TIMESCALE;
    }

    /**
     * Writes the movie to {@code file} and returns it. Every frame differs from the one before it - a
     * white bar sweeps across a two axis gradient - so no two samples are identical, and a decoder that
     * produced one frame and repeated it could not pass for one that produced them all.
     */
    static Path write(Path file) throws IOException {
        List<byte[]> samples = new ArrayList<>(FRAME_COUNT);
        for (int index = 0; index < FRAME_COUNT; index++) {
            samples.add(encodeFrame(index));
        }

        byte[] ftyp = box("ftyp", concat(fourCC("qt  "), int32(0x20050300), fourCC("qt  ")));
        byte[] mdat = box("mdat", concat(samples.toArray(new byte[0][])));
        byte[] moov = moov(samples, ftyp.length + 8);

        Files.write(file, concat(ftyp, mdat, moov));
        return file;
    }

    /** One frame as a complete JFIF image; that is the whole of a Photo-JPEG sample. */
    private static byte[] encodeFrame(int index) throws IOException {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        int[] row = new int[WIDTH];
        int bar = (index * 5) % WIDTH;
        for (int y = 0; y < HEIGHT; y++) {
            int green = (y * 255) / (HEIGHT - 1);
            for (int x = 0; x < WIDTH; x++) {
                int red = (x * 255) / (WIDTH - 1);
                row[x] = Math.abs(x - bar) < 6
                        ? 0x00FFFFFF
                        : (red << 16) | (green << 8) | ((index * 3) & 0xFF);
            }
            image.setRGB(0, y, WIDTH, 1, row, 0, WIDTH);
        }

        ByteArrayOutputStream encoded = new ByteArrayOutputStream(8192);
        if (!ImageIO.write(image, "jpg", encoded)) {
            throw new IOException("this JDK has no JPEG writer, so no movie can be generated");
        }
        return encoded.toByteArray();
    }

    /** The whole header tree. {@code chunkOffset} is where the first sample sits in the finished file. */
    private static byte[] moov(List<byte[]> samples, int chunkOffset) {
        int duration = FRAME_COUNT * FRAME_DURATION;

        byte[] mvhd = box("mvhd", concat(
                int32(0),                       // version 0, no flags
                int32(0), int32(0),             // creation and modification time
                int32(TIMESCALE), int32(duration),
                int32(0x00010000),              // rate 1.0
                int16(0x0100), int16(0),        // volume 1.0, reserved
                int32(0), int32(0),             // reserved
                matrix(),
                zeros(24),                      // pre_defined
                int32(2)));                     // next_track_ID

        byte[] tkhd = box("tkhd", concat(
                int32(0x00000007),              // version 0; enabled, in movie, in preview
                int32(0), int32(0),             // creation and modification time
                int32(1),                       // track_ID
                int32(0),                       // reserved
                int32(duration),
                int32(0), int32(0),             // reserved
                int16(0), int16(0),             // layer, alternate_group
                int16(0), int16(0),             // volume 0 for a video track, reserved
                matrix(),
                int32(WIDTH << 16), int32(HEIGHT << 16)));

        byte[] mdhd = box("mdhd", concat(
                int32(0),                       // version 0, no flags
                int32(0), int32(0),             // creation and modification time
                int32(TIMESCALE), int32(duration),
                int16(LANGUAGE_UNDETERMINED), int16(0)));

        byte[] vmhd = box("vmhd", concat(
                int32(0x00000001),              // version 0; flags 1 is required here
                int16(0),                       // graphics mode: copy
                int16(0), int16(0), int16(0))); // opcolor

        byte[] dinf = box("dinf", box("dref", concat(
                int32(0), int32(1),
                box("url ", int32(0x00000001))))); // flag 1: the data is in this file

        byte[] stbl = box("stbl", concat(
                box("stsd", concat(int32(0), int32(1), sampleEntry())),
                box("stts", concat(int32(0), int32(1), int32(FRAME_COUNT), int32(FRAME_DURATION))),
                box("stsc", concat(int32(0), int32(1), int32(1), int32(FRAME_COUNT), int32(1))),
                box("stsz", concat(int32(0), int32(0), int32(FRAME_COUNT), sampleSizes(samples))),
                box("stco", concat(int32(0), int32(1), int32(chunkOffset)))));

        byte[] minf = box("minf", concat(vmhd, handler("dhlr", "alis"), dinf, stbl));
        byte[] mdia = box("mdia", concat(mdhd, handler("mhlr", "vide"), minf));
        return box("moov", concat(mvhd, box("trak", concat(tkhd, mdia))));
    }

    /**
     * The Photo-JPEG visual sample entry. It needs no codec configuration box: the samples are self
     * describing JFIF, so the 78 bytes of the standard visual sample entry are the whole of it.
     */
    private static byte[] sampleEntry() {
        return box("jpeg", concat(
                zeros(6), int16(1),             // reserved, data_reference_index
                int16(0), int16(0),             // version, revision level
                int32(0),                       // vendor
                int32(0), int32(0),             // temporal and spatial quality
                int16(WIDTH), int16(HEIGHT),
                int32(RESOLUTION_72_DPI), int32(RESOLUTION_72_DPI),
                int32(0),                       // data size
                int16(1),                       // frames per sample
                compressorName("Photo - JPEG"),
                int16(24),                      // depth
                int16(-1)));                    // no colour table
    }

    /**
     * An {@code hdlr} box readable in either dialect: the QuickTime component type goes where an ISO
     * reader expects the word it ignores, the subtype goes where it expects {@code handler_type}, and
     * the name is one zero byte, which is an empty string in both.
     */
    private static byte[] handler(String componentType, String subtype) {
        return box("hdlr", concat(
                int32(0),
                fourCC(componentType), fourCC(subtype),
                int32(0), int32(0), int32(0),   // manufacturer, component flags, flags mask
                zeros(1)));
    }

    /** A 32 byte Pascal string, which is how a sample entry carries the name of its compressor. */
    private static byte[] compressorName(String name) {
        byte[] text = name.getBytes(StandardCharsets.US_ASCII);
        byte[] field = new byte[32];
        field[0] = (byte) text.length;
        System.arraycopy(text, 0, field, 1, text.length);
        return field;
    }

    private static byte[] sampleSizes(List<byte[]> samples) {
        ByteBuffer sizes = ByteBuffer.allocate(4 * samples.size());
        for (byte[] sample : samples) {
            sizes.putInt(sample.length);
        }
        return sizes.array();
    }

    private static byte[] matrix() {
        ByteBuffer buffer = ByteBuffer.allocate(4 * IDENTITY_MATRIX.length);
        for (int value : IDENTITY_MATRIX) {
            buffer.putInt(value);
        }
        return buffer.array();
    }

    private static byte[] box(String type, byte[] payload) {
        return ByteBuffer.allocate(8 + payload.length)
                .putInt(8 + payload.length)
                .put(fourCC(type))
                .put(payload)
                .array();
    }

    private static byte[] fourCC(String type) {
        byte[] bytes = type.getBytes(StandardCharsets.US_ASCII);
        if (bytes.length != 4) {
            throw new IllegalArgumentException("not a four character code: " + type);
        }
        return bytes;
    }

    private static byte[] int32(int value) {
        return ByteBuffer.allocate(4).putInt(value).array();
    }

    private static byte[] int16(int value) {
        return ByteBuffer.allocate(2).putShort((short) value).array();
    }

    private static byte[] zeros(int count) {
        return new byte[count];
    }

    private static byte[] concat(byte[]... parts) {
        int size = 0;
        for (byte[] part : parts) {
            size += part.length;
        }
        ByteBuffer buffer = ByteBuffer.allocate(size);
        for (byte[] part : parts) {
            buffer.put(part);
        }
        return buffer.array();
    }
}
