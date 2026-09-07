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

package test.com.sun.javafx.iio;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import test.com.sun.javafx.iio.JpegTestSupport.Decoded;
import test.com.sun.javafx.iio.JpegTestSupport.RecordingListener;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the decoder from {@code InputStream}s that behave badly in every way the contract permits,
 * and pins what comes out.
 * <p>
 * This is the part of the decoder a migration is most likely to break. The stream is not read by
 * Java: libjpeg pulls from it through {@code imageio_fill_input_buffer} and
 * {@code imageio_skip_input_data}, which call back into {@code InputStream.read} and
 * {@code InputStream.skip} from inside a {@code setjmp} scope, several C frames below the Java call.
 * A short read, a zero-length read, an early end of stream and a thrown {@code IOException} each
 * take a different route out of that scope, and only one of them is an error. Getting the callback
 * mechanism right but the exhaustion cases wrong produces a decoder that works on files and
 * misbehaves on sockets.
 * <p>
 * These tests need no golden file: each one derives its expectation from a decode of the same bytes
 * through a well-behaved stream, so they state a relationship ("chunking the reads does not change
 * the image") rather than a captured constant. Where the outcome is genuinely a property of the
 * current implementation rather than of the format - an empty read being treated as end of file, an
 * under-sized skip being ignored - the test says so and pins the behaviour rather than claiming it
 * is specified.
 */
public class JpegStreamCallbackTest {

    /**
     * Big enough to span several 4096-byte {@code STREAMBUF_SIZE} fills: high-quality noise, so the
     * encoder cannot compress it down to a couple of kilobytes and the exhaustion cases land in the
     * middle of the entropy-coded data rather than in the header.
     */
    private static byte[] fixture;

    /** The same bytes through a stream that does nothing unusual. */
    private static Decoded reference;

    /** The fixture with an 8 KB comment segment, the only marker that reaches {@code skip}. */
    private static byte[] withComment;

    private static Decoded commentReference;

    /** Serves the first read in full and then goes wrong; 4096 is exactly one buffer fill. */
    private static final int ONE_BUFFER = 4096;

    @BeforeAll
    static void decodeReference() throws IOException {
        JpegNatives.require();
        fixture = JpegTestSupport.writeJpeg(JpegTestSupport.noisePattern(160, 120, 20260101L),
                0.95f, false);
        assertTrue(fixture.length > 2 * ONE_BUFFER, () -> "the fixture must span several buffer"
                + " fills for these tests to mean anything; it is " + fixture.length + " bytes");
        reference = JpegTestSupport.decode(new Fixture(fixture), JpegTestSupport.FULL, null);
        withComment = JpegTestSupport.insertAfterFirstSegment(fixture,
                JpegTestSupport.commentSegment(2 * ONE_BUFFER));
        commentReference = JpegTestSupport.decode(new Fixture(withComment), JpegTestSupport.FULL,
                null);
    }

    /** A clean decode of a valid file reports no warnings at all - the baseline for the rest. */
    @Test
    void aWellBehavedStreamProducesNoWarnings() throws IOException {
        RecordingListener listener = new RecordingListener();
        JpegTestSupport.decode(new Fixture(fixture), JpegTestSupport.FULL, listener);
        assertEquals(List.of(), listener.warnings(),
                "decoding a valid JPEG from a well-behaved stream must not warn");
    }

    /**
     * A stream that never returns more than seven bytes at a time decodes to exactly the same image.
     * The C fills its buffer from whatever a single {@code read} returns and carries on, so short
     * reads are a normal event, not an error - which is what makes reading from a socket work.
     */
    @Test
    void shortReadsProduceTheSameImage() throws IOException {
        Fixture stream = new Fixture(fixture) {
            @Override
            protected int transfer(byte[] buffer, int offset, int length) {
                return super.transfer(buffer, offset, Math.min(length, 7));
            }
        };
        Decoded decoded = JpegTestSupport.decode(stream, JpegTestSupport.FULL, null);
        assertSameImage(reference, decoded, "seven-byte reads");
        // A stream that filled the 4096-byte buffer every time would be read about
        // fixture.length / 4096 times; a tenth of the file's length in calls is far past anything a
        // whole-buffer reader could produce, and leaves room for the decoder stopping at EOI rather
        // than at the last byte.
        assertTrue(stream.arrayReads > fixture.length / 10,
                () -> "the stream should have been read in small pieces, but read(byte[],int,int)"
                        + " was called only " + stream.arrayReads + " times for " + fixture.length
                        + " bytes");
    }

    /**
     * <b>Behaviour pin, not a specification.</b> {@code InputStream.read(byte[], int, int)} is
     * allowed to return 0 only when the requested length is 0, so a stream that returns 0 here is
     * misbehaving - but {@code imageio_fill_input_buffer} tests {@code ret <= 0} and treats the two
     * alike: it warns, fabricates an {@code FF D9} EOI marker, and lets libjpeg finish the image from
     * what it already has. The decode therefore succeeds, with a truncated picture and a warning,
     * rather than failing.
     * <p>
     * The consequence pinned here is that 0 and -1 are indistinguishable: cutting the same stream at
     * the same offset by either route produces byte-identical output. A rewrite that distinguishes
     * them - by looping on 0, say, which is arguably more correct - changes what applications get
     * from a slow stream, so it must be a deliberate, separate change.
     */
    @Test
    void aReadReturningZeroIsTreatedAsEndOfStream() throws IOException {
        RecordingListener afterZero = new RecordingListener();
        Decoded zeroCut = JpegTestSupport.decode(new StopsAfter(fixture, ONE_BUFFER, 0),
                JpegTestSupport.FULL, afterZero);
        RecordingListener afterEof = new RecordingListener();
        Decoded eofCut = JpegTestSupport.decode(new StopsAfter(fixture, ONE_BUFFER, -1),
                JpegTestSupport.FULL, afterEof);

        assertSameImage(zeroCut, eofCut, "a read returning 0 against a read returning -1");
        assertEquals(afterEof.warnings(), afterZero.warnings(),
                "a read returning 0 must warn exactly as an end of stream does");
        assertTrue(afterZero.warnings().contains(null), () -> "the source manager reports a missing"
                + " EOI by passing an int 0 through a String parameter, so a null warning is what"
                + " arrives; see JpegWarningOrderTest for why that is pinned rather than fixed."
                + " Warnings were " + afterZero.warnings());
    }

    /**
     * End of stream in the middle of the image is a warning, not a failure: the image comes back,
     * short of data and different from the reference, and the caller is told through the listener.
     * That is the behaviour {@code LoadCorruptJPEGTest} depends on and every truncated download
     * relies on.
     */
    @Test
    void endOfStreamPartwayThroughStillProducesAnImage() throws IOException {
        RecordingListener listener = new RecordingListener();
        Decoded decoded = JpegTestSupport.decode(new StopsAfter(fixture, ONE_BUFFER, -1),
                JpegTestSupport.FULL, listener);

        assertEquals(reference.width(), decoded.width(), "the size comes from the header, which was"
                + " read before the stream ended");
        assertEquals(reference.height(), decoded.height());
        assertEquals(reference.pixels().length, decoded.pixels().length,
                "a short stream still fills the whole buffer");
        assertFalse(Arrays.equals(reference.pixels(), decoded.pixels()),
                "losing everything after the first 4096 bytes must change the decoded pixels;"
                        + " identical output would mean the stream was not really cut");
        assertFalse(listener.warnings().isEmpty(), "a truncated stream must warn");
        assertEquals(100.0f, listener.progress().get(listener.progress().size() - 1).floatValue(),
                "the decode runs to the end of the image even though the data ran out");
    }

    /**
     * An {@code IOException} thrown while the entropy-coded data is being read comes back out of
     * {@code load} as the very same object.
     * <p>
     * The route it takes is the reason this test exists. The exception is thrown by Java code called
     * from C, inside libjpeg's {@code setjmp} scope; the source manager notices it with
     * {@code ExceptionCheck} and calls {@code error_exit}, which {@code longjmp}s out of libjpeg to
     * the handler in {@code decompressIndirect}; that handler finds an exception already pending,
     * declines to replace it with a JPEG error, and returns false. {@code JPEGImageLoader.load} then
     * rethrows it unchanged. Preserving the identity - not merely the type - is what
     * {@code assertSame} is for: an implementation that wraps, replaces or reorders it would still
     * throw {@code IOException} and would still be a change.
     */
    @Test
    void anIOExceptionFromReadPropagatesUnchanged() {
        IOException failure = new IOException("stream failed mid-image");
        Fixture stream = new ThrowsAfter(fixture, ONE_BUFFER, failure);
        IOException thrown = assertThrows(IOException.class,
                () -> JpegTestSupport.decode(stream, JpegTestSupport.FULL, null),
                "a stream failure during decoding must not be swallowed");
        assertSame(failure, thrown, () -> "load must rethrow the stream's own IOException, but threw "
                + thrown + " caused by " + thrown.getCause());
    }

    /**
     * The same, one stage earlier: a failure during header parsing surfaces from the constructor,
     * because that is where {@code initDecompressor} reads. The loader is disposed on the way out,
     * so the native decompressor is not leaked - and, in particular, the JVM is still alive to run
     * the next assertion.
     */
    @Test
    void anIOExceptionFromTheFirstReadPropagatesUnchanged() {
        IOException failure = new IOException("stream failed before the header");
        Fixture stream = new ThrowsAfter(fixture, 0, failure);
        IOException thrown = assertThrows(IOException.class,
                () -> JpegTestSupport.newLoader(stream),
                "a stream failure while reading the header must not be swallowed");
        assertSame(failure, thrown, () -> "the constructor must rethrow the stream's own"
                + " IOException, but threw " + thrown + " caused by " + thrown.getCause());
    }

    /**
     * <b>Behaviour pin, not a specification.</b> {@code InputStream.skip} may skip fewer bytes than
     * asked, and {@code imageio_skip_input_data} does not loop: it calls {@code skip} once and
     * accepts whatever it gets. The bytes that were not skipped are then read as if they were the
     * next marker, and libjpeg's marker scanner discards them as "extraneous bytes before marker" -
     * a warning, not an error - so the image still decodes, byte for byte, as it would have.
     * <p>
     * Reaching this path at all takes some doing: the skip has to be larger than the 4096-byte
     * buffer, and the only marker large enough that the decoder skips rather than saves is a comment,
     * so the fixture carries an 8 KB comment segment full of zeros. Zeros matter - the marker scanner
     * has to be able to discard them all without finding a stray {@code 0xFF}.
     * <p>
     * The warning libjpeg raises for those bytes ({@code JWRN_EXTRANEOUS_DATA}) cannot be observed
     * from Java, and that is pinned too. A comment sits before SOS, so it is consumed by
     * {@code jpeg_read_header} inside {@code initDecompressor} - which runs in the
     * {@code JPEGImageLoader} constructor, before any caller has had the chance to attach a listener.
     * The warning is delivered to a loader with no listeners and dropped. It has a second effect that
     * outlives it: libjpeg reports only the first warning per decompress object, so this dropped
     * warning silences every later one for this image.
     * <p>
     * A rewrite that loops until the skip is complete would be more correct and would also make the
     * extraneous bytes - and hence that whole chain - disappear. That is a behaviour change and
     * belongs in its own commit.
     */
    @Test
    void aSkipThatSkipsTooLittleIsIgnoredAndTheImageIsUnchanged() throws IOException {
        RecordingListener listener = new RecordingListener();
        ShortSkip stream = new ShortSkip(withComment, 100);
        Decoded decoded = JpegTestSupport.decode(stream, JpegTestSupport.FULL, listener);

        assertTrue(stream.skips > 0, "the fixture's comment segment should have forced a call to"
                + " InputStream.skip; without one this test proves nothing");
        assertSameImage(commentReference, decoded, "a skip that skips 100 bytes at a time");
        assertEquals(List.of(), listener.warnings(), () -> "a short skip must not change what the"
                + " application sees: the bytes the stream failed to skip are read back and"
                + " discarded by libjpeg's marker scanner while the constructor is still parsing the"
                + " header, so no listener exists to hear about it. Warnings were "
                + listener.warnings());
    }

    /**
     * <b>Behaviour pin.</b> A stream that claims to have read more than it was asked for is clamped
     * to the buffer length rather than believed - {@code if (ret > sb->bufferLength) ret =
     * sb->bufferLength;} - so the decoder never reads past the end of its own buffer. The clamp is
     * one line, easy to lose in a rewrite, and losing it reads uninitialised memory into the image.
     */
    @Test
    void aReadThatOverstatesItsCountIsClamped() throws IOException {
        Fixture stream = new Fixture(fixture) {
            @Override
            protected int transfer(byte[] buffer, int offset, int length) {
                int count = super.transfer(buffer, offset, length);
                // Only lie about a completely filled buffer: overstating a partial read would make
                // the decoder consume stale bytes, which is a different bug from the one under test.
                return count == length ? count + 1000 : count;
            }
        };
        Decoded decoded = JpegTestSupport.decode(stream, JpegTestSupport.FULL, null);
        assertSameImage(reference, decoded, "a stream that overstates its read count");
    }

    /**
     * The decoder touches the stream through exactly two methods. It never asks how much is
     * {@code available} - a stream that returns 0 from {@code available()}, as many do, is not
     * special-cased anywhere - never uses the single-byte {@code read()}, and never closes the
     * stream: closing belongs to whoever opened it.
     */
    @Test
    void onlyBulkReadAndSkipAreEverCalled() throws IOException {
        Fixture stream = new Fixture(fixture) {
            @Override
            public int available() {
                super.available();
                return 0; // A stream that reports nothing available must still decode.
            }
        };
        JpegTestSupport.decode(stream, JpegTestSupport.FULL, null);

        assertTrue(stream.arrayReads > 0, "the image has to come from somewhere");
        assertEquals(0, stream.availableCalls, "the decoder must not consult available()");
        assertEquals(0, stream.singleByteReads, "the decoder must not use the single-byte read()");
        assertEquals(0, stream.closeCalls, "the decoder must not close a stream it did not open");
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private static void assertSameImage(Decoded expected, Decoded actual, String what) {
        assertNotNull(actual, what + " produced no image at all");
        assertEquals(expected.type(), actual.type(), what + " changed the image type");
        assertEquals(expected.width(), actual.width(), what + " changed the width");
        assertEquals(expected.height(), actual.height(), what + " changed the height");
        assertEquals(expected.stride(), actual.stride(), what + " changed the stride");
        assertArrayEquals(expected.pixels(), actual.pixels(), what + " changed the decoded pixels");
    }

    /**
     * A plain, correct stream over a byte array that counts what the decoder does to it. Subclasses
     * override {@link #transfer} to misbehave; overriding that rather than
     * {@code read(byte[], int, int)} keeps the counting intact.
     */
    private static class Fixture extends InputStream {

        protected final byte[] data;
        protected int position;

        int singleByteReads;
        int arrayReads;
        int skips;
        int availableCalls;
        int closeCalls;

        Fixture(byte[] data) {
            this.data = data;
        }

        @Override
        public int read() {
            singleByteReads++;
            return position < data.length ? data[position++] & 0xFF : -1;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            arrayReads++;
            return transfer(buffer, offset, length);
        }

        protected int transfer(byte[] buffer, int offset, int length) {
            if (position >= data.length) {
                return -1;
            }
            int count = Math.min(length, data.length - position);
            System.arraycopy(data, position, buffer, offset, count);
            position += count;
            return count;
        }

        @Override
        public long skip(long count) {
            skips++;
            long skipped = Math.max(0, Math.min(count, data.length - position));
            position += (int) skipped;
            return skipped;
        }

        @Override
        public int available() {
            availableCalls++;
            return data.length - position;
        }

        @Override
        public void close() {
            closeCalls++;
        }
    }

    /** Serves {@code prefix} bytes, then returns {@code ending} - 0 or -1 - for ever. */
    private static final class StopsAfter extends Fixture {

        private final int prefix;
        private final int ending;

        StopsAfter(byte[] data, int prefix, int ending) {
            super(data);
            this.prefix = prefix;
            this.ending = ending;
        }

        @Override
        protected int transfer(byte[] buffer, int offset, int length) {
            if (position >= prefix) {
                return ending;
            }
            return super.transfer(buffer, offset, Math.min(length, prefix - position));
        }
    }

    /** Serves {@code prefix} bytes, then throws one particular exception object. */
    private static final class ThrowsAfter extends Fixture {

        private final int prefix;
        private final IOException failure;

        ThrowsAfter(byte[] data, int prefix, IOException failure) {
            super(data);
            this.prefix = prefix;
            this.failure = failure;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            arrayReads++;
            if (position >= prefix) {
                throw failure;
            }
            return transfer(buffer, offset, Math.min(length, prefix - position));
        }
    }

    /** Skips at most {@code limit} bytes per call, however many were asked for. */
    private static final class ShortSkip extends Fixture {

        private final long limit;

        ShortSkip(byte[] data, long limit) {
            super(data);
            this.limit = limit;
        }

        @Override
        public long skip(long count) {
            return super.skip(Math.min(count, limit));
        }
    }
}
