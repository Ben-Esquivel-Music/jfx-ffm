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

package test.com.sun.glass.ui.mac;

import com.sun.glass.ui.Pixels;
import com.sun.glass.ui.Size;
import com.sun.glass.ui.mac.MacGlassNativeShim;
import com.sun.glass.ui.mac.MacGlassNativeShim.BestSizeQuery;
import com.sun.glass.ui.mac.MacGlassNativeShim.CursorFactory;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The order {@code MacCursor._createCursor} and {@code _getBestSize} keep around their downcalls, with a recording
 * stand-in where {@code gmac_cursor_create} and {@code gmac_cursor_best_size} would be: both run AppKit's
 * {@code NSCursor}, which no test may.
 * <p>
 * {@code _createCursor} builds the cursor from whatever {@code Pixels.attachData} left in the slot, and afterwards
 * reports the one exception the JNI left pending: what {@code attachData} threw, which replaced the detached error as
 * HotSpot's {@code JNI_ENTRY} replaced it, or else the detached error. While detached, {@code attachData} still runs
 * and reports its own detached error first. {@code _getBestSize} reports the detached error after its query and
 * returns the {@code Size} all the same. Every call runs on a fresh thread, whose uncaught-exception handler is where
 * {@code Application.reportException} delivers. AppKit's {@code NSApp} is nil before the first call into the library
 * and after the last.
 */
@EnabledOnOs(OS.MAC)
public class MacCursorNativeTest {

    private static final String DETACHED = "java.lang.RuntimeException: Main Java thread is detached.";

    /** What the stand-in answers for a cursor: no {@code NSCursor} exists. */
    private static final long CURSOR = 0xC0C0L;

    /** {@code 0x11223344} as the little-endian bytes {@code gmac_image_create} reads. */
    private static final int PIXEL = 0x11223344;
    private static final String PIXEL_BYTES = "44332211";

    private static final int READBACK_CAPACITY = 64;

    private int attachedBefore;

    @BeforeAll
    static void loadGlass() {
        MacGlassNatives.require();
        assertTrue(MacGlassNativeShim.nsAppIsNil(), "an NSApplication exists before the first call into libglass");
        MacGlassNativeShim.loadLibrary();
        assertFalse(MacGlassNativeShim.isMainThread(), "the calls must run off the main thread to be detachable");
    }

    @AfterAll
    static void noApplicationExistsAfterTheLastCall() {
        assertTrue(MacGlassNativeShim.nsAppIsNil(), "an NSApplication exists after the last call into libglass");
    }

    @BeforeEach
    void keepTheAttachedFlag() {
        attachedBefore = MacGlassNativeShim.setMainJavaAttached(0);
    }

    @AfterEach
    void restoreTheAttachedFlag() {
        MacGlassNativeShim.setMainJavaAttached(attachedBefore);
    }

    @Test
    public void theCursorIsMadeFromTheImageOfThePixelsAndNothingIsReported() throws Exception {
        Pixels pixels = MacGlassNativeShim.newPixels(2, 1, ByteBuffer.wrap(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}));
        Outcome outcome = onFreshThread(true, events -> MacGlassNativeShim.createCursor(3, 4, pixels,
                recordingFactory(events)));
        assertEquals(CURSOR, outcome.result());
        assertEquals(List.of("create(3, 4, image 0102030405060708)"), outcome.events());
    }

    @Test
    public void whatAttachDataThrewIsReportedAfterTheCursorIsMadeFromWhatItLeftInTheSlot() throws Exception {
        RefusingPixels pixels = new RefusingPixels(true);
        Outcome outcome = onFreshThread(true, events -> MacGlassNativeShim.createCursor(5, 6, pixels,
                recordingFactory(events)));
        assertEquals(CURSOR, outcome.result());
        assertEquals(List.of("create(5, 6, image " + PIXEL_BYTES + ")",
                "report java.lang.IllegalStateException: attach refused"), outcome.events());
        assertEquals(1, pixels.attaches.size());
    }

    @Test
    public void anAttachDataThatThrewBeforeWritingLeavesTheCursorToNull() throws Exception {
        RefusingPixels pixels = new RefusingPixels(false);
        Outcome outcome = onFreshThread(true, events -> MacGlassNativeShim.createCursor(0, 0, pixels,
                recordingFactory(events)));
        assertEquals(List.of("create(0, 0, NULL)", "report java.lang.IllegalStateException: attach refused"),
                outcome.events());
    }

    @Test
    public void aDetachedCreateMakesTheCursorFromTheImageAndReportsTheAttachAndThenItsOwnDetachedError()
            throws Exception {
        Pixels pixels = MacGlassNativeShim.newPixels(2, 1, ByteBuffer.wrap(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}));
        Outcome outcome = onFreshThread(false, events -> MacGlassNativeShim.createCursor(7, 8, pixels,
                recordingFactory(events)));
        assertEquals(CURSOR, outcome.result());
        assertEquals(List.of("report " + DETACHED, "create(7, 8, image 0102030405060708)", "report " + DETACHED),
                outcome.events());
    }

    @Test
    public void whatADetachedAttachDataThrewIsReportedInPlaceOfTheDetachedError() throws Exception {
        RefusingPixels pixels = new RefusingPixels(true);
        Outcome outcome = onFreshThread(false, events -> MacGlassNativeShim.createCursor(7, 8, pixels,
                recordingFactory(events)));
        assertEquals(CURSOR, outcome.result());
        assertEquals(List.of("report " + DETACHED, "create(7, 8, image " + PIXEL_BYTES + ")",
                "report java.lang.IllegalStateException: attach refused"), outcome.events());
        assertEquals(List.of("attachInt"), pixels.attaches);
    }

    @Test
    public void theBestSizeIsWhatTheQueryWroteForTheRequestedSize() throws Exception {
        Outcome outcome = onFreshThread(true, events -> MacGlassNativeShim.bestSize(-1, 40,
                recordingQuery(events, 17, 23)));
        Size size = (Size) outcome.result();
        assertEquals(17, size.width);
        assertEquals(23, size.height);
        assertEquals(List.of("query(-1, 40) out (0, 0)"), outcome.events());
    }

    @Test
    public void aDetachedBestSizeReportsOnceAfterItsQueryAndReturnsTheSizeTheQueryWrote() throws Exception {
        Outcome outcome = onFreshThread(false, events -> MacGlassNativeShim.bestSize(0, 0,
                recordingQuery(events, 24, 28)));
        assertNull(outcome.thrown());
        Size size = (Size) outcome.result();
        assertEquals(24, size.width);
        assertEquals(28, size.height);
        assertEquals(List.of("query(0, 0) out (0, 0)", "report " + DETACHED), outcome.events());
    }

    /** What a call answered or threw, and, in order, what the stand-in saw and what the thread reported. */
    private record Outcome(Object result, Throwable thrown, List<String> events) {
    }

    @FunctionalInterface
    private interface RecordingCall {

        Object call(List<String> events) throws Exception;
    }

    /** Runs {@code call} on a fresh platform thread, with the main thread attached to Java or not. */
    private static Outcome onFreshThread(boolean mainThreadAttached, RecordingCall call) throws Exception {
        MacGlassNativeShim.setMainJavaAttached(mainThreadAttached ? 1 : 0);
        List<String> events = new CopyOnWriteArrayList<>();
        FutureTask<Object> task = new FutureTask<>(() -> call.call(events));
        Thread thread = Thread.ofPlatform().name("MacCursorNativeTest-call").unstarted(task);
        thread.setUncaughtExceptionHandler((t, e) -> events.add("report " + e));
        thread.start();
        try {
            return new Outcome(task.get(30, TimeUnit.SECONDS), null, List.copyOf(events));
        } catch (ExecutionException e) {
            return new Outcome(null, e.getCause(), List.copyOf(events));
        }
    }

    /** Records the image it is given, read back and released as {@code gmac_cursor_create} would release it. */
    private static CursorFactory recordingFactory(List<String> events) {
        return (image, x, y) -> {
            events.add("create(" + x + ", " + y + ", " + describe(image) + ")");
            return CURSOR;
        };
    }

    /** Records the request and what the out-parameters held, then writes {@code width} and {@code height}. */
    private static BestSizeQuery recordingQuery(List<String> events, int width, int height) {
        return (requestedWidth, requestedHeight, outWidth, outHeight) -> {
            events.add("query(" + requestedWidth + ", " + requestedHeight + ") out (" + outWidth.get(JAVA_INT, 0)
                    + ", " + outHeight.get(JAVA_INT, 0) + ")");
            outWidth.set(JAVA_INT, 0, width);
            outHeight.set(JAVA_INT, 0, height);
        };
    }

    private static String describe(MemorySegment image) {
        if (image.address() == 0) {
            return "NULL";
        }
        return "image " + HexFormat.of().formatHex(
                MacGlassNativeShim.inspectAndReleaseImage(image, READBACK_CAPACITY).pixels());
    }

    /**
     * A 1x1 {@code Pixels} whose attach throws: after writing the image of {@link #PIXEL} into the slot, through the
     * production {@code _attachInt}, when {@code writeFirst}.
     */
    private static final class RefusingPixels extends Pixels {

        final List<String> attaches = new CopyOnWriteArrayList<>();
        private final boolean writeFirst;

        RefusingPixels(boolean writeFirst) {
            super(1, 1, IntBuffer.allocate(1));
            this.writeFirst = writeFirst;
        }

        @Override
        protected void _fillDirectByteBuffer(ByteBuffer bb) {
        }

        @Override
        protected void _attachInt(long ptr, int w, int h, IntBuffer ints, int[] array, int offset) {
            attaches.add("attachInt");
            if (writeFirst) {
                MacGlassNativeShim.attachInt(ptr, 1, 1, null, new int[] {PIXEL}, 0);
            }
            throw new IllegalStateException("attach refused");
        }

        @Override
        protected void _attachByte(long ptr, int w, int h, ByteBuffer bytes, byte[] array, int offset) {
            throw new AssertionError("Pixels of ints attach as ints");
        }
    }
}
