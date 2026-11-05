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

package com.sun.glass.ui.mac;

import com.sun.glass.ui.View;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.StructLayout;
import java.lang.invoke.MethodHandle;
import java.nio.Buffer;
import java.util.Map;
import static com.sun.glass.ui.mac.MacGlassNative.bind;
import static com.sun.glass.ui.mac.MacGlassNative.mainThreadDetached;
import static com.sun.glass.ui.mac.MacGlassNative.mainThreadDetachedError;
import static com.sun.glass.ui.mac.MacGlassNative.reportException;
import static com.sun.glass.ui.mac.MacGlassNative.unexpected;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The natives of {@code MacView}: the {@code gmac_view_*} exports, which this class binds in header order when it
 * initializes, and the {@code GmacViewCaps} that {@code _create} builds from the device details.
 * <p>
 * A NULL view answers 0 at once, as the JNI natives returned before their {@code GLASS_ASSERT_MAIN_JAVA_THREAD}: the
 * export returns, and the render-thread calls return before they evaluate {@code gmac_main_thread_detached}. For any
 * other view, and for {@code _getMultiClickTime_impl}, which has none, each call whose native asserted evaluates it
 * where the assertion ran, makes its downcall all the same, and then raises the {@code RuntimeException} that the
 * assertion left pending as the native's end did: it reports it once where the native closed with
 * {@code GLASS_CHECK_EXCEPTION}, and throws it where the native had no closing check. An upload does so only past the
 * checks its native made before the assertion; {@code _setParent}, whose native was the assertion alone, has no
 * downcall. {@code _create} evaluates nothing, as its native crashed on the NULL {@code JNIEnv} there.
 * <p>
 * The render-thread calls {@code _begin}, {@code _end} and {@code _getNativeFrameBuffer} can meet a detached main
 * thread. View makes the others on the event thread, the AppKit main thread, where the assertion cannot fire, unless
 * {@code glass.disableThreadChecks} lets them run elsewhere.
 */
final class MacViewCalls {

    /**
     * What {@code _getMultiClickMaxX_impl} and {@code _getMultiClickMaxY_impl} answered: AppKit does not give the
     * value, and most Macs use 3. Their assertion could fire only if the main thread detached between those calls in
     * {@code MacView}'s initializer, after {@code _getMultiClickTime_impl}'s found it attached: a race this does not
     * reproduce.
     */
    static final int MULTI_CLICK_MAX = 3;

    static final String MTL_COMMAND_QUEUE_KEY = "mtlCommandQueue";
    static final String SHARE_CONTEXT_KEY = "shareContextPtr";
    static final String CONTEXT_KEY = "contextPtr";
    static final String MTL_FOR_SW_KEY = "useMTLInGlassForSW";
    static final String VSYNC_ENABLED_KEY = "isVsyncEnabled";

    /** The bytes of a pixel of {@code _uploadPixelsByteArray}, in its overflow and length checks. */
    private static final int BYTES_PER_PIXEL = 4;

    static final StructLayout GMAC_VIEW_CAPS_LAYOUT = MemoryLayout.structLayout(
            ADDRESS.withName("mtl_command_queue"),
            ADDRESS.withName("share_context"),
            ADDRESS.withName("context"),
            JAVA_INT.withName("depth_bits"),
            JAVA_INT.withName("hidpi_aware"),
            JAVA_INT.withName("mtl_for_sw"),
            JAVA_INT.withName("vsync_enabled"));

    private static final long MTL_COMMAND_QUEUE = offsetOf("mtl_command_queue");
    private static final long SHARE_CONTEXT = offsetOf("share_context");
    private static final long CONTEXT = offsetOf("context");
    private static final long DEPTH_BITS = offsetOf("depth_bits");
    private static final long HIDPI_AWARE = offsetOf("hidpi_aware");
    private static final long MTL_FOR_SW = offsetOf("mtl_for_sw");
    private static final long VSYNC_ENABLED = offsetOf("vsync_enabled");

    /**
     * A downcall that answers nothing, given the view. Its lambdas need a block body: as a lambda's expression body,
     * {@code invokeExact} would be typed to answer {@code Object}.
     */
    @FunctionalInterface
    interface VoidDowncall {

        void call(MemorySegment view) throws Throwable;
    }

    /** A downcall that answers {@code int32_t}, given the view. */
    @FunctionalInterface
    interface IntDowncall {

        int call(MemorySegment view) throws Throwable;
    }

    /** A downcall that answers {@code int64_t}, given the view. */
    @FunctionalInterface
    interface LongDowncall {

        long call(MemorySegment view) throws Throwable;
    }

    /** {@code gmac_view_multi_click_time_ms}, so that a test can stand where AppKit would. */
    @FunctionalInterface
    interface MultiClickTimeQuery {

        long query() throws Throwable;
    }

    /** {@code gmac_view_create}, so that a test can stand where AppKit would. */
    @FunctionalInterface
    interface ViewFactory {

        long create(long viewId, MemorySegment caps) throws Throwable;
    }

    private static final MethodHandle GMAC_VIEW_CREATE = bind("gmac_view_create",
            FunctionDescriptor.of(ADDRESS, JAVA_LONG, ADDRESS));
    private static final MethodHandle GMAC_VIEW_GET_NATIVE_FRAME_BUFFER = bind("gmac_view_get_native_frame_buffer",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS));
    private static final MethodHandle GMAC_VIEW_GET_X = bind("gmac_view_get_x",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle GMAC_VIEW_GET_Y = bind("gmac_view_get_y",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle GMAC_VIEW_CLOSE = bind("gmac_view_close",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle GMAC_VIEW_BEGIN = bind("gmac_view_begin", FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle GMAC_VIEW_END = bind("gmac_view_end", FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle GMAC_VIEW_SCHEDULE_REPAINT = bind("gmac_view_schedule_repaint",
            FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle GMAC_VIEW_ENTER_FULLSCREEN = bind("gmac_view_enter_fullscreen",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT));
    private static final MethodHandle GMAC_VIEW_EXIT_FULLSCREEN = bind("gmac_view_exit_fullscreen",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
    private static final MethodHandle GMAC_VIEW_UPLOAD_PIXELS_DIRECT = bind("gmac_view_upload_pixels_direct",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_FLOAT, JAVA_FLOAT));
    private static final MethodHandle GMAC_VIEW_UPLOAD_PIXELS_BYTES = bind("gmac_view_upload_pixels_bytes",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_FLOAT,
                    JAVA_FLOAT));
    private static final MethodHandle GMAC_VIEW_UPLOAD_PIXELS_INTS = bind("gmac_view_upload_pixels_ints",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_FLOAT,
                    JAVA_FLOAT));
    private static final MethodHandle GMAC_VIEW_ENABLE_INPUT_METHOD_EVENTS = bind(
            "gmac_view_enable_input_method_events", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
    private static final MethodHandle GMAC_VIEW_FINISH_INPUT_METHOD_COMPOSITION = bind(
            "gmac_view_finish_input_method_composition", FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle GMAC_VIEW_MULTI_CLICK_TIME_MS = bind("gmac_view_multi_click_time_ms",
            FunctionDescriptor.of(JAVA_LONG));

    private MacViewCalls() {
    }

    static long create(MacViewCallbacks.Receiver view, Map<?, ?> caps) {
        return create(view, caps, MacViewCalls::viewCreate);
    }

    /**
     * {@code _create}. The id is registered first, because AppKit dials slots for it before the export returns, and
     * {@code notify_dispose} removes the id of a view that was made. A NULL answer, or a throw, which can come only
     * before the export ran (allocating the caps, or a symbol the library lacks), made no {@code GlassViewDelegate}
     * to dial the id or to dispose of it, so the id is removed at once: the JNI took its global reference only in the
     * delegate. The caps live in a confined arena for the call.
     */
    static long create(MacViewCallbacks.Receiver view, Map<?, ?> caps, ViewFactory factory) {
        long viewId = MacViewCallbacks.register(view);
        long created;
        try (Arena arena = Arena.ofConfined()) {
            created = factory.create(viewId, viewCaps(arena, caps));
        } catch (Throwable t) {
            MacViewCallbacks.unregister(viewId);
            throw unexpected(t);
        }
        if (created == 0L) {
            MacViewCallbacks.unregister(viewId);
        }
        return created;
    }

    /**
     * The {@code GmacViewCaps} of the device details, NULL for a null map. A member keeps its default when the map
     * has no entry for its key or maps it to null. Each key is read once, where the JNI read only the keys of the
     * layer it made, two of them twice. What a {@code Map.get} or an unboxing throws is reported and the member keeps
     * its default, as the JNI's {@code GLASS_CHECK_EXCEPTION} after each of them reported it.
     */
    static MemorySegment viewCaps(SegmentAllocator allocator, Map<?, ?> caps) {
        if (caps == null) {
            return MemorySegment.NULL;
        }
        MemorySegment struct = allocator.allocate(GMAC_VIEW_CAPS_LAYOUT);
        struct.set(ADDRESS, MTL_COMMAND_QUEUE, MemorySegment.ofAddress(address(caps, MTL_COMMAND_QUEUE_KEY)));
        struct.set(ADDRESS, SHARE_CONTEXT, MemorySegment.ofAddress(address(caps, SHARE_CONTEXT_KEY)));
        struct.set(ADDRESS, CONTEXT, MemorySegment.ofAddress(address(caps, CONTEXT_KEY)));
        struct.set(JAVA_INT, DEPTH_BITS, depthBits(caps));
        struct.set(JAVA_INT, HIDPI_AWARE, flag(caps, View.Capability.kHiDPIAwareKey));
        struct.set(JAVA_INT, MTL_FOR_SW, flag(caps, MTL_FOR_SW_KEY));
        struct.set(JAVA_INT, VSYNC_ENABLED, vsyncEnabled(caps));
        return struct;
    }

    /** Render thread, between {@link #begin} and {@link #end}. */
    static long getNativeFrameBuffer(long view) {
        return getNativeFrameBuffer(view, v -> (long) GMAC_VIEW_GET_NATIVE_FRAME_BUFFER.invokeExact(v));
    }

    /**
     * The frame of {@code _getNativeFrameBuffer}: 0 for the NULL view, before the assertion; else the downcall, then
     * the detached report of its closing {@code GLASS_CHECK_EXCEPTION}, and the frame buffer.
     */
    static long getNativeFrameBuffer(long view, LongDowncall downcall) {
        if (view == 0L) {
            return 0L;
        }
        boolean detached = mainThreadDetached();
        long frameBuffer;
        try {
            frameBuffer = downcall.call(MemorySegment.ofAddress(view));
        } catch (Throwable t) {
            throw unexpected(t);
        }
        if (detached) {
            reportException(mainThreadDetachedError());
        }
        return frameBuffer;
    }

    static int getX(long view) {
        return reportIfDetachedAfter(view, v -> (int) GMAC_VIEW_GET_X.invokeExact(v));
    }

    static int getY(long view) {
        return reportIfDetachedAfter(view, v -> (int) GMAC_VIEW_GET_Y.invokeExact(v));
    }

    static boolean close(long view) {
        return reportIfDetachedAfter(view, v -> (int) GMAC_VIEW_CLOSE.invokeExact(v)) != 0;
    }

    /**
     * {@code _setParent}, whose native was its assertion alone: nothing for the NULL view, which returned before it;
     * else the detached throw, as the exception left pending surfaced on return, whatever the parent.
     */
    static void setParent(long view) {
        if (view != 0L && mainThreadDetached()) {
            throw mainThreadDetachedError();
        }
    }

    /** Render thread: pushes the thread's autorelease pool, which {@link #end} pops on the same thread. */
    static void begin(long view) {
        throwIfDetachedAfter(view, v -> {
            GMAC_VIEW_BEGIN.invokeExact(v);
        });
    }

    /** Render thread, after {@link #begin}. */
    static void end(long view) {
        throwIfDetachedAfter(view, v -> {
            GMAC_VIEW_END.invokeExact(v);
        });
    }

    /**
     * The frame of {@code _begin} and {@code _end}, which had no closing check: nothing for the NULL view, before the
     * assertion; else the downcall, then the detached throw, as the exception left pending surfaced on return.
     */
    static void throwIfDetachedAfter(long view, VoidDowncall downcall) {
        if (view == 0L) {
            return;
        }
        throwIfDetachedAfter(view, true, downcall);
    }

    /**
     * The frame of the uploads, which had no closing check either: the downcall, then the detached throw where the
     * native got as far as its assertion, {@code asserts}. The export repeats the checks the native made before the
     * assertion, the NULL view's among them, and makes those after it.
     */
    static void throwIfDetachedAfter(long view, boolean asserts, VoidDowncall downcall) {
        boolean detached = asserts && mainThreadDetached();
        try {
            downcall.call(MemorySegment.ofAddress(view));
        } catch (Throwable t) {
            throw unexpected(t);
        }
        if (detached) {
            throw mainThreadDetachedError();
        }
    }

    static void scheduleRepaint(long view) {
        runReportingIfDetached(view, v -> {
            GMAC_VIEW_SCHEDULE_REPAINT.invokeExact(v);
        });
    }

    static boolean enterFullscreen(long view, boolean animate, boolean keepRatio, boolean hideCursor) {
        return reportIfDetachedAfter(view, v -> (int) GMAC_VIEW_ENTER_FULLSCREEN.invokeExact(v, flag(animate),
                flag(keepRatio), flag(hideCursor))) != 0;
    }

    static void exitFullscreen(long view, boolean animate) {
        runReportingIfDetached(view, v -> {
            GMAC_VIEW_EXIT_FULLSCREEN.invokeExact(v, flag(animate));
        });
    }

    /**
     * Nothing for a null buffer, before the call, as the JNI returned; else its {@link #baseAddress} crosses, and the
     * native got as far as its assertion for any view but the NULL one.
     */
    static void uploadPixelsDirect(long view, Buffer pixels, int width, int height, float scaleX, float scaleY) {
        if (pixels == null) {
            return;
        }
        throwIfDetachedAfter(view, view != 0L, v -> {
            GMAC_VIEW_UPLOAD_PIXELS_DIRECT.invokeExact(v, baseAddress(pixels), width, height, scaleX, scaleY);
        });
    }

    /**
     * Nothing for a null array, before the call, as the JNI returned. Otherwise the whole array is copied into a
     * confined arena for the call, where the JNI held it in a critical region, and the export checks the offset and
     * the image against its length.
     */
    static void uploadPixelsBytes(long view, byte[] pixels, int offset, int width, int height, float scaleX,
                                  float scaleY) {
        if (pixels == null) {
            return;
        }
        throwIfDetachedAfter(view, arrayUploadAsserts(view, offset, width, height, BYTES_PER_PIXEL), v -> {
            try (Arena arena = Arena.ofConfined()) {
                GMAC_VIEW_UPLOAD_PIXELS_BYTES.invokeExact(v, arena.allocateFrom(JAVA_BYTE, pixels), pixels.length,
                        offset, width, height, scaleX, scaleY);
            }
        });
    }

    /** {@link #uploadPixelsBytes} for ints: the export takes the array's length in ints. */
    static void uploadPixelsInts(long view, int[] pixels, int offset, int width, int height, float scaleX,
                                 float scaleY) {
        if (pixels == null) {
            return;
        }
        throwIfDetachedAfter(view, arrayUploadAsserts(view, offset, width, height, 1), v -> {
            try (Arena arena = Arena.ofConfined()) {
                GMAC_VIEW_UPLOAD_PIXELS_INTS.invokeExact(v, arena.allocateFrom(JAVA_INT, pixels), pixels.length,
                        offset, width, height, scaleX, scaleY);
            }
        });
    }

    /**
     * Whether {@code _uploadPixelsByteArray} or, for one element per pixel, {@code _uploadPixelsIntArray} of a
     * non-null array got as far as its assertion: past its checks of the view, the offset, the size and the overflow
     * of the image in {@code elementsPerPixel} elements a pixel, in the native's {@code int} arithmetic.
     */
    static boolean arrayUploadAsserts(long view, int offset, int width, int height, int elementsPerPixel) {
        return view != 0L && offset >= 0 && width > 0 && height > 0
                && width <= ((Integer.MAX_VALUE - offset) / elementsPerPixel) / height;
    }

    /**
     * The base address of a direct {@code pixels}, as {@code GetDirectBufferAddress} gave it, whatever its position.
     * A buffer that is not direct has no address, so it crosses as NULL, as {@code GetDirectBufferAddress} answered.
     */
    static MemorySegment baseAddress(Buffer pixels) {
        if (!pixels.isDirect()) {
            return MemorySegment.NULL;
        }
        return MemorySegment.ofBuffer(pixels.duplicate().clear());
    }

    static void enableInputMethodEvents(long view, boolean enable) {
        runReportingIfDetached(view, v -> {
            GMAC_VIEW_ENABLE_INPUT_METHOD_EVENTS.invokeExact(v, flag(enable));
        });
    }

    static void finishInputMethodComposition(long view) {
        runReportingIfDetached(view, v -> {
            GMAC_VIEW_FINISH_INPUT_METHOD_COMPOSITION.invokeExact(v);
        });
    }

    /**
     * The frame of the natives that closed with {@code GLASS_CHECK_EXCEPTION}: the downcall, which answers 0 at once
     * for the NULL view, then, for any other view, the detached report.
     */
    static int reportIfDetachedAfter(long view, IntDowncall downcall) {
        boolean detached = view != 0L && mainThreadDetached();
        int result;
        try {
            result = downcall.call(MemorySegment.ofAddress(view));
        } catch (Throwable t) {
            throw unexpected(t);
        }
        if (detached) {
            reportException(mainThreadDetachedError());
        }
        return result;
    }

    /** {@link #reportIfDetachedAfter} for the natives that answered nothing. */
    static void runReportingIfDetached(long view, VoidDowncall downcall) {
        reportIfDetachedAfter(view, v -> {
            downcall.call(v);
            return 0;
        });
    }

    static long multiClickTime() {
        return multiClickTime(() -> (long) GMAC_VIEW_MULTI_CLICK_TIME_MS.invokeExact());
    }

    /**
     * {@code _getMultiClickTime_impl}, which had no closing check: the downcall, then the detached throw, as the
     * exception left pending surfaced on return.
     */
    static long multiClickTime(MultiClickTimeQuery query) {
        boolean detached = mainThreadDetached();
        long milliseconds;
        try {
            milliseconds = query.query();
        } catch (Throwable t) {
            throw unexpected(t);
        }
        if (detached) {
            throw mainThreadDetachedError();
        }
        return milliseconds;
    }

    private static long viewCreate(long viewId, MemorySegment caps) throws Throwable {
        return ((MemorySegment) GMAC_VIEW_CREATE.invokeExact(viewId, caps)).address();
    }

    /** A {@code Long} raw address; 0, which is NULL, for none. */
    private static long address(Map<?, ?> caps, Object key) {
        try {
            Object value = caps.get(key);
            return value != null ? (Long) value : 0L;
        } catch (Throwable t) {
            reportException(t);
            return 0L;
        }
    }

    /** {@code View.Capability.k3dDepthKey}, an {@code Integer}; 0 for none. */
    private static int depthBits(Map<?, ?> caps) {
        try {
            Object value = caps.get(View.Capability.k3dDepthKey);
            return value != null ? (Integer) value : 0;
        } catch (Throwable t) {
            reportException(t);
            return 0;
        }
    }

    /** A {@code Boolean}: 1 for {@code TRUE}, else, and for none, 0. */
    private static int flag(Map<?, ?> caps, Object key) {
        try {
            Object value = caps.get(key);
            return value != null && (Boolean) value ? 1 : 0;
        } catch (Throwable t) {
            reportException(t);
            return 0;
        }
    }

    /** A {@code Long}: 1 when it is {@code 1L}, else 0; 1 for none. */
    private static int vsyncEnabled(Map<?, ?> caps) {
        try {
            Object value = caps.get(VSYNC_ENABLED_KEY);
            return value == null || (Long) value == 1L ? 1 : 0;
        } catch (Throwable t) {
            reportException(t);
            return 1;
        }
    }

    private static int flag(boolean value) {
        return value ? 1 : 0;
    }

    private static long offsetOf(String member) {
        return GMAC_VIEW_CAPS_LAYOUT.byteOffset(PathElement.groupElement(member));
    }
}
