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

import com.sun.javafx.iio.ImageFrame;
import com.sun.javafx.iio.ImageLoadListener;
import com.sun.javafx.iio.ImageLoader;
import com.sun.javafx.iio.ImageMetadata;
import java.io.IOException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins the loader's lifecycle: what may be called twice, what must fail, and where the failure is
 * raised.
 * <p>
 * The native decompressor is owned by a single {@code long} field and freed by {@code disposeNative},
 * and {@code load} disposes in its own {@code finally} whether it succeeded or not. Everything below
 * follows from that, and none of it is enforced by the type system, so a rewrite can get every pixel
 * right and still free the wrong thing twice.
 *
 * <h2>Two cases the brief asked for and this test deliberately does not contain</h2>
 * Calling {@code load} a second time, and calling {@code load} after {@code dispose}, are the same
 * case, and in this build that case is a native null dereference, not an exception:
 * <ul>
 * <li>{@code load}'s {@code finally} runs {@code dispose()}, which calls {@code disposeNative} and
 *     sets {@code structPointer} to 0. A loader is therefore spent as soon as {@code load} returns -
 *     successfully or not.</li>
 * <li>A second {@code load} passes that 0 straight to {@code startDecompression}, whose first two
 *     statements are {@code imageIODataPtr data = (imageIODataPtr) jlong_to_ptr(ptr);} and
 *     {@code j_decompress_ptr cinfo = (j_decompress_ptr) data-&gt;jpegObj;}. There is no null check
 *     anywhere on that path.</li>
 * </ul>
 * The result is a segmentation fault that takes the surefire fork with it - an {@code hs_err_pid}
 * file and a build that reports a crashed JVM rather than a failed test - so writing those two tests
 * here would turn every run of this module red. They belong in a forked-JVM harness under
 * {@code tests/system}, where a crash can be an expected outcome and asserted as one.
 * <p>
 * This is a real defect, it predates the migration, and it is recorded here rather than fixed:
 * {@code JPEGImageLoader} is production code and this change is test-only. Whatever replaces
 * {@code jpegloader.c} should either keep {@code load} single-shot and reject a spent loader in
 * Java - {@code IllegalStateException} would be the natural choice - or check the pointer before
 * dereferencing it. Either is a behaviour change and needs its own commit.
 */
public class JpegLifecycleTest {

    /** A small, valid baseline JPEG; the lifecycle does not depend on the picture. */
    private static byte[] fixture;

    @BeforeAll
    static void createFixture() {
        JpegNatives.require();
        fixture = JpegTestSupport.writeJpeg(JpegTestSupport.rgbPattern(64, 48),
                JpegTestSupport.QUALITY, false);
    }

    /**
     * {@code dispose} on a loader that has never decoded frees the native decompressor that the
     * constructor allocated. This is the path {@code ImageStorage} takes whenever a caller loads
     * metadata and then abandons the image.
     */
    @Test
    void disposeWithoutLoadIsHarmless() throws IOException {
        ImageLoader loader = JpegTestSupport.newLoader(fixture);
        assertDoesNotThrow(loader::dispose, "disposing an unused loader must not throw");
    }

    /**
     * {@code dispose} is idempotent. It has to be: {@code load} already disposed the loader, and
     * every caller in the codebase disposes again in its own {@code finally}. The guard is the
     * {@code isDisposed} flag plus the zeroed pointer, and dropping either one turns this into a
     * double free.
     */
    @Test
    void disposeIsIdempotent() throws IOException {
        ImageLoader loader = JpegTestSupport.newLoader(fixture);
        loader.dispose();
        assertDoesNotThrow(loader::dispose, "a second dispose must be a no-op, not a double free");
        assertDoesNotThrow(loader::dispose, "and so must a third");
    }

    /**
     * The ordinary caller's sequence: load, then dispose in a {@code finally}. {@code load} has
     * already disposed by then, so this is the double dispose that happens in production on every
     * single image.
     */
    @Test
    void disposeAfterASuccessfulLoadIsHarmless() throws IOException {
        ImageLoader loader = JpegTestSupport.newLoader(fixture);
        ImageFrame frame = loader.load(0, 0, 0, true, true, 1, 1);
        assertNotNull(frame, "the fixture must decode");
        assertDoesNotThrow(loader::dispose, "disposing after load must not free anything twice");
    }

    /**
     * Asking for an image index other than 0 returns null, and does so before the loader is locked
     * or the native decompressor is touched - so the loader is still usable and still has to be
     * disposed. JPEG holds one image per stream; this is how a caller finds that out.
     */
    @Test
    void aSecondImageIndexReturnsNullAndLeavesTheLoaderUsable() throws IOException {
        ImageLoader loader = JpegTestSupport.newLoader(fixture);
        try {
            assertNull(loader.load(1, 0, 0, true, true, 1, 1),
                    "a JPEG stream has exactly one image, so index 1 must return null");
            assertNotNull(loader.load(0, 0, 0, true, true, 1, 1),
                    "the rejected index must not have consumed the loader");
        } finally {
            loader.dispose();
        }
    }

    /**
     * Re-entering {@code load} from the metadata callback is rejected by {@code JPEGImageLoader}'s
     * own lock, and the {@code IllegalStateException} escapes the outer {@code load} unchanged.
     * <p>
     * This is the one form of "load called twice" that can be tested in-process, and it is the
     * interesting one: the metadata callback is delivered from Java, before any native call, so the
     * lock rejects the second entry before the pointer is touched. It also shows the flaw in the
     * lock's placement - {@code accessLock.lock()} sits outside the {@code try}, so this exception
     * skips the {@code finally}, and the loader is left locked for ever. {@code dispose} tests
     * {@code isLocked} first, so this loader can never be freed and its native decompressor leaks
     * for the life of the JVM. That is the current behaviour; it is pinned, not fixed, and it is
     * another reason the ownership rules should be settled as part of the migration.
     */
    @Test
    void aRecursiveLoadFromTheMetadataCallbackIsRejected() throws IOException {
        ImageLoader loader = JpegTestSupport.newLoader(fixture);
        loader.addListener(new ReentrantListener(true));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> loader.load(0, 0, 0, true, true, 1, 1),
                "re-entering load from a listener must be rejected");
        assertEquals("Recursive loading is not allowed.", thrown.getMessage());
    }

    /**
     * Re-entering {@code load} from the progress callback is rejected too, but it comes back
     * wrapped: the progress callback runs from inside {@code decompressIndirect}, so the
     * {@code IllegalStateException} is pending in a JNI frame, {@code error_exit} longjmps out of
     * libjpeg, the handler leaves the pending exception alone, and {@code load}'s
     * {@code catch (Throwable t) -> new IOException(t)} wraps it on the way out.
     * <p>
     * This is the most valuable assertion in this class for the migration. It is the only place a
     * test can observe what happens when Java code called from inside libjpeg's {@code setjmp} scope
     * throws: the exception must survive, must not be replaced by a generic JPEG error, and the
     * loader must still be unlocked and disposed on the way out - which the next two assertions
     * check by disposing again and by decoding a fresh loader afterwards.
     */
    @Test
    void aRecursiveLoadFromTheProgressCallbackSurfacesAsAWrappedIOException() throws IOException {
        ImageLoader loader = JpegTestSupport.newLoader(fixture);
        loader.addListener(new ReentrantListener(false));

        IOException thrown = assertThrows(IOException.class,
                () -> loader.load(0, 0, 0, true, true, 1, 1),
                "an exception thrown inside a native callback must not be swallowed");
        IllegalStateException cause = assertInstanceOf(IllegalStateException.class, thrown.getCause(),
                () -> "load must carry the listener's own exception as the cause, but the cause was "
                        + thrown.getCause());
        assertEquals("Recursive loading is not allowed.", cause.getMessage());

        assertDoesNotThrow(loader::dispose,
                "load must have unlocked and disposed on its way out, even failing from a callback");
        assertNotNull(JpegTestSupport.decode(fixture, JpegTestSupport.FULL),
                "a failure inside a callback must not leave the decoder unusable for everyone else");
    }

    /**
     * Calls {@code load} again from a callback, once. {@code fromMetadata} chooses which callback -
     * before the native decompressor is touched, or from inside it.
     */
    private static final class ReentrantListener implements ImageLoadListener {

        private final boolean fromMetadata;
        private boolean reentered;

        ReentrantListener(boolean fromMetadata) {
            this.fromMetadata = fromMetadata;
        }

        @Override
        public void imageLoadProgress(ImageLoader loader, float percentageComplete) {
            if (!fromMetadata) {
                reenter(loader);
            }
        }

        @Override
        public void imageLoadWarning(ImageLoader loader, String message) {
        }

        @Override
        public void imageLoadMetaData(ImageLoader loader, ImageMetadata metadata) {
            if (fromMetadata) {
                reenter(loader);
            }
        }

        private void reenter(ImageLoader loader) {
            if (reentered) {
                return;
            }
            reentered = true;
            try {
                // The same loader on purpose: two loaders would be independent, each with its own
                // native decompressor, and the lock exists to stop one loader being driven twice.
                loader.load(0, 0, 0, true, true, 1, 1);
            } catch (IOException e) {
                // The recursive call is expected to be refused by the lock, which throws
                // IllegalStateException; an IOException here would mean it got further than that,
                // and letting it escape as an unchecked exception would hide which one happened.
                throw new IllegalStateException("the recursive load failed with an IOException"
                        + " instead of being refused by the lock", e);
            }
        }
    }
}
