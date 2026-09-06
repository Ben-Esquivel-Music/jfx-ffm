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
 * The WAV file the media tests actually play: a canonical 44 byte RIFF/WAVE header (16 bit signed mono
 * PCM at 8 kHz) followed by a 440 Hz sine tone. Its sibling {@link TinyWav} exists to be recognised and
 * parsed and is never played; this one exists to be decoded, and every property a test asserts about a
 * player is a property of this file.
 * <p>
 * Three of those properties are load bearing, and none of them is arbitrary.
 * <p>
 * <b>The duration is exact.</b> The data chunk is {@code frames * 2} bytes at 8000 frames per second, so
 * a frame count that is a whole number of hundredths of a second gives a duration with no rounding
 * anywhere: {@link #ONE_SECOND_FRAMES} is exactly 1.000 s and {@link #CLIP_FRAMES} exactly 0.050 s. That
 * is what lets {@code MediaPlaybackTest} assert {@code MediaPlayer.getDuration} to a tolerance that would
 * catch a sample-rate or a block-align mistake rather than one wide enough to hide it.
 * <p>
 * <b>The tone is not silence.</b> A pipeline that prerolls, reports a track and reaches end of stream
 * proves the state machine ran; it does not prove that any sample was decoded, because a wholly broken
 * decoder that emits the right number of zero buffers looks identical. The 440 Hz tone is what separates
 * the two: the {@code spectrum} element sits in the audio bin ahead of the sink
 * ({@code CGstPipelineFactory::CreateAudioBin}), so its magnitudes come from decoded samples and nothing
 * else, and a band above {@code NativeAudioSpectrum.DEFAULT_THRESHOLD} cannot be produced by silence.
 * <p>
 * <b>The tone is quiet, and it stays audible anyway.</b> The amplitude is a tenth of full scale, -20 dBFS,
 * which is a modest beep on a developer's machine rather than a full scale one, and still some 40 dB
 * clear of the -60 dB floor the spectrum reports for silence. Turning the player's volume down instead
 * would not be portable: on Windows and macOS {@code AUDIO_VOLUME} is the sink itself and is downstream
 * of the spectrum, but on Linux it is a separate {@code volume} element that the factory inserts
 * <em>before</em> the spectrum, so a muted player would report a silent spectrum there and a loud one
 * everywhere else. The amplitude is in the file for that reason; tests do not touch the volume.
 * <p>
 * The tone also ends where it began. 440 Hz at 8 kHz is 11 cycles per 200 frames, so any frame count that
 * is a multiple of 200 - both of the ones here are - holds a whole number of cycles and the file neither
 * starts nor ends on a discontinuity. That keeps the spectrum free of the broadband click an arbitrary
 * cut would add, which is what would otherwise make "some band is above the floor" pass for the wrong
 * reason.
 */
final class SineWav {

    /** Frames per second. 8 kHz keeps the one second file at 16 kB and leaves 440 Hz well under Nyquist. */
    static final int SAMPLE_RATE = 8_000;

    /** Mono: one sample per frame, and one audio track for a test to count. */
    static final int CHANNELS = 1;

    /** Signed 16 bit little-endian samples, which is what {@code WAVE_FORMAT_PCM} means at this width. */
    static final int BITS_PER_SAMPLE = 16;

    /** The tone. A' above middle C, and a whole number of cycles in every frame count used here. */
    static final int TONE_HZ = 440;

    /** The size of the canonical RIFF/WAVE header the samples follow. */
    static final int HEADER_SIZE = 44;

    /** Exactly 1.000 s: what {@code MediaPlaybackTest} asserts back out of {@code getDuration}. */
    static final int ONE_SECOND_FRAMES = SAMPLE_RATE;

    /**
     * Exactly 0.050 s, for the {@code AudioClip} cycles: twenty of them are twenty complete
     * create-preroll-play-finish-dispose rounds, which is the point, and a second each would make the
     * test twenty seconds long for no more coverage.
     */
    static final int CLIP_FRAMES = SAMPLE_RATE / 20;

    /** The tone's amplitude, as a fraction of full scale. */
    private static final double AMPLITUDE = 0.1;

    private SineWav() {
    }

    /**
     * @param frames the number of audio frames
     * @return the size of the file {@link #bytes} builds for {@code frames}
     */
    static int size(int frames) {
        return HEADER_SIZE + frames * CHANNELS * BITS_PER_SAMPLE / 8;
    }

    /**
     * @param frames the number of audio frames
     * @return how long {@code frames} lasts, in seconds
     */
    static double seconds(int frames) {
        return (double) frames / SAMPLE_RATE;
    }

    /**
     * @param frames the number of audio frames; a multiple of 200 holds a whole number of tone cycles
     * @return the file's content, a fresh array on every call
     */
    static byte[] bytes(int frames) {
        int dataBytes = frames * CHANNELS * BITS_PER_SAMPLE / 8;
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE + dataBytes).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(ascii("RIFF"));
        // The RIFF chunk size: the 36 header bytes that follow this field, plus the samples.
        buffer.putInt(36 + dataBytes);
        buffer.put(ascii("WAVE"));
        buffer.put(ascii("fmt "));
        buffer.putInt(16);                                                  // PCM header size
        buffer.putShort((short) 1);                                         // WAVE_FORMAT_PCM
        buffer.putShort((short) CHANNELS);
        buffer.putInt(SAMPLE_RATE);
        buffer.putInt(SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8);        // byte rate
        buffer.putShort((short) (CHANNELS * BITS_PER_SAMPLE / 8));          // block align
        buffer.putShort((short) BITS_PER_SAMPLE);
        buffer.put(ascii("data"));
        buffer.putInt(dataBytes);
        for (int frame = 0; frame < frames; frame++) {
            double angle = 2.0 * Math.PI * TONE_HZ * frame / SAMPLE_RATE;
            buffer.putShort((short) Math.round(AMPLITUDE * Short.MAX_VALUE * Math.sin(angle)));
        }
        return buffer.array();
    }

    /**
     * Writes {@link #bytes} to {@code file}.
     *
     * @param file the file to write
     * @param frames the number of audio frames
     * @return {@code file}
     * @throws IOException if the write fails
     */
    static Path writeTo(Path file, int frames) throws IOException {
        Files.write(file, bytes(frames));
        return file;
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }
}
