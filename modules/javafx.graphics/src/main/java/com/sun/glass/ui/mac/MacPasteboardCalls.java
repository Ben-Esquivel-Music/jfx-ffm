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

import com.sun.glass.ui.Pixels;
import com.sun.javafx.font.JniStringCodec;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.StructLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_ERR_NO_DATA;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_OFFSETOF_PASTEBOARD_ITEM;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_OFFSETOF_PASTEBOARD_REP;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_OK;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_SIZEOF_PASTEBOARD_ITEM;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_SIZEOF_PASTEBOARD_REP;
import static com.sun.glass.ui.mac.MacGlassNative.bind;
import static com.sun.glass.ui.mac.MacGlassNative.bounded;
import static com.sun.glass.ui.mac.MacGlassNative.free;
import static com.sun.glass.ui.mac.MacGlassNative.mainThreadDetached;
import static com.sun.glass.ui.mac.MacGlassNative.mainThreadDetachedError;
import static com.sun.glass.ui.mac.MacGlassNative.reportException;
import static com.sun.glass.ui.mac.MacGlassNative.unexpected;
import static com.sun.glass.ui.mac.MacGlassNative.utf16;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The natives of {@code MacPasteboard}: the {@code gmac_pasteboard_*} exports, which this class binds in header order
 * when it initializes, the {@code GmacPasteboardItem}s that {@code _putItemsFromArray} builds from its items, and the
 * blocks the exports answer, decoded as the JNI decoded them.
 * <p>
 * Every call evaluates {@code gmac_main_thread_detached} first, where its JNI body ran
 * {@code GLASS_ASSERT_MAIN_JAVA_THREAD}, and reports the error once, where the first {@code GLASS_CHECK_EXCEPTION}
 * of that body reported it: after the downcall, and in {@code putItems} before the first {@code Pixels.attachData}.
 * None throws it, as no body did. None of these downcalls may be critical: they talk to the pasteboard server,
 * decode images or begin a drag. Every block they answer is freed with {@code gmac_free}.
 */
final class MacPasteboardCalls {

    static final int GMAC_PASTEBOARD_REP_BAD = -1;
    static final int GMAC_PASTEBOARD_REP_NONE = 0;
    static final int GMAC_PASTEBOARD_REP_STRING = 1;
    static final int GMAC_PASTEBOARD_REP_BYTES = 2;
    static final int GMAC_PASTEBOARD_REP_IMAGE = 3;
    static final int GMAC_PASTEBOARD_ITEM_NULL = -1;

    static final StructLayout GMAC_PASTEBOARD_REP_LAYOUT = MemoryLayout.structLayout(
            ADDRESS.withName("uti"),
            JAVA_INT.withName("uti_length"),
            JAVA_INT.withName("kind"),
            ADDRESS.withName("data"),
            JAVA_INT.withName("data_length"),
            JAVA_INT.withName("reserved"));

    static final StructLayout GMAC_PASTEBOARD_ITEM_LAYOUT = MemoryLayout.structLayout(
            ADDRESS.withName("reps"),
            JAVA_INT.withName("rep_count"),
            JAVA_INT.withName("reserved"));

    private static final long UTI = repOffset("uti");
    private static final long UTI_LENGTH = repOffset("uti_length");
    private static final long KIND = repOffset("kind");
    private static final long DATA = repOffset("data");
    private static final long DATA_LENGTH = repOffset("data_length");
    private static final long REPS = itemOffset("reps");
    private static final long REP_COUNT = itemOffset("rep_count");

    /* No table is installed for these, so they are checked here, before putItems first hands them to C. */
    static {
        MacAppCallbacks.requireLayout("GmacPasteboardRep", GMAC_PASTEBOARD_REP_LAYOUT, GMAC_SIZEOF_PASTEBOARD_REP,
                GMAC_OFFSETOF_PASTEBOARD_REP);
        MacAppCallbacks.requireLayout("GmacPasteboardItem", GMAC_PASTEBOARD_ITEM_LAYOUT, GMAC_SIZEOF_PASTEBOARD_ITEM,
                GMAC_OFFSETOF_PASTEBOARD_ITEM);
    }

    /**
     * The bytes of a {@code byte[]} value the C may read: the drag image offset's UTI reads 8 whatever the length
     * (latent bug L1), so a shorter array is padded with zeros (L24).
     */
    private static final int MIN_BYTES_READ = 8;

    private static final MethodHandle GMAC_PASTEBOARD_CREATE_SYSTEM = bind("gmac_pasteboard_create_system",
            FunctionDescriptor.of(ADDRESS, JAVA_INT));
    private static final MethodHandle GMAC_PASTEBOARD_CREATE_USER = bind("gmac_pasteboard_create_user",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
    private static final MethodHandle GMAC_PASTEBOARD_GET_NAME = bind("gmac_pasteboard_get_name",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle GMAC_PASTEBOARD_GET_UTIS = bind("gmac_pasteboard_get_utis",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle GMAC_PASTEBOARD_GET_ITEM_AS_RAW_IMAGE = bind(
            "gmac_pasteboard_get_item_as_raw_image", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS,
                    ADDRESS));
    private static final MethodHandle GMAC_PASTEBOARD_GET_ITEM_STRING = bind("gmac_pasteboard_get_item_string",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle GMAC_PASTEBOARD_GET_ITEM_BYTES = bind("gmac_pasteboard_get_item_bytes",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle GMAC_PASTEBOARD_PUT_ITEMS = bind("gmac_pasteboard_put_items",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT));
    private static final MethodHandle GMAC_PASTEBOARD_CLEAR = bind("gmac_pasteboard_clear",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS));
    private static final MethodHandle GMAC_PASTEBOARD_GET_SEED = bind("gmac_pasteboard_get_seed",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS));
    private static final MethodHandle GMAC_PASTEBOARD_RELEASE = bind("gmac_pasteboard_release",
            FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle GMAC_PASTEBOARD_GET_ALLOWED_OPERATION = bind(
            "gmac_pasteboard_get_allowed_operation", FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** A downcall that answers a scalar, with the call's arena for what it passes, so that a test can stand in. */
    @FunctionalInterface
    interface Downcall {

        long call(SegmentAllocator allocator) throws Throwable;
    }

    /**
     * A downcall that answers a status, and a block and its length through two out-parameters, with the call's arena
     * for what it passes, so that a test can stand where the pasteboard would be.
     */
    @FunctionalInterface
    interface BlockQuery {

        int query(SegmentAllocator allocator, MemorySegment outBlock, MemorySegment outLength) throws Throwable;
    }

    /** {@code gmac_pasteboard_put_items} for the items Java built, so that a test can stand in. */
    @FunctionalInterface
    interface ItemsDowncall {

        long put(MemorySegment items, int itemCount) throws Throwable;
    }

    /** What a {@link BlockQuery} answered: its status, the block's bytes for {@code GMAC_OK}, and the length. */
    private record Answer(int status, byte[] bytes, int length) {
    }

    private MacPasteboardCalls() {
    }

    /** {@code _createSystemPasteboard}: +0, or 0 for a type that is neither General nor DragAndDrop. */
    static long createSystem(int type) {
        return call(allocator -> ((MemorySegment) GMAC_PASTEBOARD_CREATE_SYSTEM.invokeExact(type)).address());
    }

    /** {@code _createUserPasteboard}: +1, never balanced (latent bug L3). */
    static long createUser(String name) {
        return call(allocator -> ((MemorySegment) GMAC_PASTEBOARD_CREATE_USER.invokeExact(utf16(allocator, name),
                length(name))).address());
    }

    static String name(long pasteboard) {
        return name((allocator, outUtf8, outLength) ->
                (int) GMAC_PASTEBOARD_GET_NAME.invokeExact(pasteboard(pasteboard), outUtf8, outLength));
    }

    /** {@code _getName}: the strings-out convention, decoded as {@code NewStringUTF} decoded it. */
    static String name(BlockQuery query) {
        boolean detached = mainThreadDetached();
        Answer answer = answer(query);
        reportIfDetached(detached);
        return answer.status() == GMAC_OK ? JniStringCodec.fromNewStringUtf(answer.bytes()) : null;
    }

    static String[][] utis(long pasteboard) {
        return utis((allocator, outBlock, outLength) ->
                (int) GMAC_PASTEBOARD_GET_UTIS.invokeExact(pasteboard(pasteboard), outBlock, outLength));
    }

    /** {@code _getUTFs}: null when the pasteboard has no item, else one entry per item, null for one without UTIs. */
    static String[][] utis(BlockQuery query) {
        boolean detached = mainThreadDetached();
        Answer answer = answer(query);
        reportIfDetached(detached);
        return answer.status() == GMAC_OK ? parseUtis(answer.bytes()) : null;
    }

    static byte[] rawImage(long pasteboard, int index) {
        return rawImage((allocator, outBlock, outLength) -> (int) GMAC_PASTEBOARD_GET_ITEM_AS_RAW_IMAGE.invokeExact(
                pasteboard(pasteboard), index, outBlock, outLength));
    }

    /** {@code _getItemAsRawImage}: the block, verbatim, or null. */
    static byte[] rawImage(BlockQuery query) {
        boolean detached = mainThreadDetached();
        Answer answer = answer(query);
        reportIfDetached(detached);
        return answer.status() == GMAC_OK ? answer.bytes() : null;
    }

    static String itemString(long pasteboard, int index, String uti) {
        return itemString((allocator, outUtf8, outLength) -> (int) GMAC_PASTEBOARD_GET_ITEM_STRING.invokeExact(
                pasteboard(pasteboard), index, utf16(allocator, uti), length(uti), outUtf8, outLength));
    }

    /**
     * {@code _getItemStringForUTF}: null, or what the JNI's {@code createUTF} gave
     * {@code new String(byte[], "UTF-8")}.
     */
    static String itemString(BlockQuery query) {
        boolean detached = mainThreadDetached();
        Answer answer = answer(query);
        reportIfDetached(detached);
        return answer.status() == GMAC_OK ? new String(answer.bytes(), StandardCharsets.UTF_8) : null;
    }

    static byte[] itemBytes(long pasteboard, int index, String uti) {
        return itemBytes((allocator, outBytes, outLength) -> (int) GMAC_PASTEBOARD_GET_ITEM_BYTES.invokeExact(
                pasteboard(pasteboard), index, utf16(allocator, uti), length(uti), outBytes, outLength));
    }

    /**
     * {@code _getItemBytesForUTF}: the bytes, or null. For data whose length is negative as a {@code jsize}, the JNI's
     * {@code NewByteArray} threw a {@code NegativeArraySizeException}, which HotSpot's {@code JNI_ENTRY} let replace
     * the exception the detached assertion left pending, and the {@code GLASS_CHECK_EXCEPTION} of the JNI's
     * {@code ByteArrayFromNSData} reported it alone.
     */
    static byte[] itemBytes(BlockQuery query) {
        boolean detached = mainThreadDetached();
        Answer answer = answer(query);
        if (answer.status() == GMAC_ERR_NO_DATA && answer.length() < 0) {
            reportException(new NegativeArraySizeException(String.valueOf(answer.length())));
            return null;
        }
        reportIfDetached(detached);
        return answer.status() == GMAC_OK ? answer.bytes() : null;
    }

    static long putItems(long pasteboard, Object[] items, int supportedActions) {
        return putItems(items, (array, itemCount) -> (long) GMAC_PASTEBOARD_PUT_ITEMS.invokeExact(
                pasteboard(pasteboard), array, itemCount, supportedActions));
    }

    /**
     * {@code _putItemsFromArray}: the {@code GmacPasteboardItem} of every item, in a confined arena for the call. The
     * JNI reported the detached assertion at the {@code GLASS_CHECK_EXCEPTION} after it read the first item, before
     * it called {@code Pixels.attachData}, which Java now calls for every {@code Pixels} value before the downcall,
     * in the JNI's order.
     */
    static long putItems(Object[] items, ItemsDowncall downcall) {
        reportIfDetached(mainThreadDetached());
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment array = arena.allocate(GMAC_PASTEBOARD_ITEM_LAYOUT, Math.max(1, items.length));
            for (int i = 0; i < items.length; i++) {
                setItem(arena, array.asSlice(i * GMAC_PASTEBOARD_ITEM_LAYOUT.byteSize(), GMAC_PASTEBOARD_ITEM_LAYOUT),
                        (Object[]) items[i]);
            }
            return downcall.put(array, items.length);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code _clear}: the change count {@code clearContents} gave. */
    static long clear(long pasteboard) {
        return call(allocator -> (long) GMAC_PASTEBOARD_CLEAR.invokeExact(pasteboard(pasteboard)));
    }

    /** {@code _getSeed}: {@code -[NSPasteboard changeCount]}. */
    static long seed(long pasteboard) {
        return call(allocator -> (long) GMAC_PASTEBOARD_GET_SEED.invokeExact(pasteboard(pasteboard)));
    }

    /** {@code _release}: {@code -[NSPasteboard releaseGlobally]}. */
    static void release(long pasteboard) {
        call(allocator -> {
            GMAC_PASTEBOARD_RELEASE.invokeExact(pasteboard(pasteboard));
            return 0L;
        });
    }

    /** {@code _getAllowedOperation}: the {@code Clipboard.ACTION_*} a drop may perform. */
    static int allowedOperation(long pasteboard) {
        return (int) call(allocator -> (int) GMAC_PASTEBOARD_GET_ALLOWED_OPERATION.invokeExact(
                pasteboard(pasteboard)));
    }

    /** The frame of the natives that answered a scalar: the downcall, then the detached report. */
    static long call(Downcall downcall) {
        boolean detached = mainThreadDetached();
        long result;
        try (Arena arena = Arena.ofConfined()) {
            result = downcall.call(arena);
        } catch (Throwable t) {
            throw unexpected(t);
        }
        reportIfDetached(detached);
        return result;
    }

    /**
     * The block of {@code gmac_pasteboard_get_utis}: native-endian {@code int32_t}s, unaligned, the item count, then
     * per item its UTI count and per UTI its byte count and bytes, which the JNI's {@code createUTF} gave
     * {@code new String(byte[], "UTF-8")}. The JNI made a {@code String[]} only for an item with a UTI.
     */
    static String[][] parseUtis(byte[] block) {
        MemorySegment segment = MemorySegment.ofArray(block);
        long offset = 0;
        int itemCount = segment.get(JAVA_INT_UNALIGNED, offset);
        offset += Integer.BYTES;
        String[][] utis = new String[itemCount][];
        for (int i = 0; i < itemCount; i++) {
            int typeCount = segment.get(JAVA_INT_UNALIGNED, offset);
            offset += Integer.BYTES;
            if (typeCount > 0) {
                String[] types = new String[typeCount];
                for (int j = 0; j < typeCount; j++) {
                    int length = segment.get(JAVA_INT_UNALIGNED, offset);
                    offset += Integer.BYTES;
                    types[j] = new String(block, Math.toIntExact(offset), length, StandardCharsets.UTF_8);
                    offset += length;
                }
                utis[i] = types;
            }
        }
        return utis;
    }

    /** Runs {@code query} with out-parameters of its own and copies the block it answered, which it then frees. */
    private static Answer answer(BlockQuery query) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment outBlock = arena.allocate(ADDRESS);
            MemorySegment outLength = arena.allocate(JAVA_INT);
            int status;
            try {
                status = query.query(arena, outBlock, outLength);
            } catch (Throwable t) {
                throw unexpected(t);
            }
            MemorySegment block = outBlock.get(ADDRESS, 0);
            int length = outLength.get(JAVA_INT, 0);
            try {
                byte[] bytes = status == GMAC_OK ? bounded(block, length).toArray(JAVA_BYTE) : null;
                return new Answer(status, bytes, length);
            } finally {
                free(block);
            }
        }
    }

    /** A null item makes no {@code NSPasteboardItem}: {@code GMAC_PASTEBOARD_ITEM_NULL}. */
    private static void setItem(Arena arena, MemorySegment item, Object[] representations) {
        if (representations == null) {
            item.set(JAVA_INT, REP_COUNT, GMAC_PASTEBOARD_ITEM_NULL);
            return;
        }
        long size = GMAC_PASTEBOARD_REP_LAYOUT.byteSize();
        MemorySegment reps = arena.allocate(GMAC_PASTEBOARD_REP_LAYOUT, Math.max(1, representations.length));
        for (int j = 0; j < representations.length; j++) {
            setRepresentation(arena, reps.asSlice(j * size, size), (Object[]) representations[j]);
        }
        item.set(ADDRESS, REPS, reps);
        item.set(JAVA_INT, REP_COUNT, representations.length);
    }

    /**
     * One {@code {UTI, value}} array, tagged with the Java kind of its value, which the C checks against what its UTI
     * takes (L26). An array of another length is BAD, with its length, which the C logs.
     */
    private static void setRepresentation(Arena arena, MemorySegment rep, Object[] representation) {
        if (representation.length != 2) {
            rep.set(JAVA_INT, KIND, GMAC_PASTEBOARD_REP_BAD);
            rep.set(JAVA_INT, DATA_LENGTH, representation.length);
            return;
        }
        String uti = (String) representation[MacPasteboard.UtfIndex];
        rep.set(ADDRESS, UTI, utf16(arena, uti));
        rep.set(JAVA_INT, UTI_LENGTH, length(uti));
        switch (representation[MacPasteboard.ObjectIndex]) {
            case String string -> {
                rep.set(JAVA_INT, KIND, GMAC_PASTEBOARD_REP_STRING);
                rep.set(ADDRESS, DATA, utf16(arena, string));
                rep.set(JAVA_INT, DATA_LENGTH, string.length());
            }
            case byte[] bytes -> {
                MemorySegment data = arena.allocate(Math.max(bytes.length, MIN_BYTES_READ));
                MemorySegment.copy(bytes, 0, data, JAVA_BYTE, 0, bytes.length);
                rep.set(JAVA_INT, KIND, GMAC_PASTEBOARD_REP_BYTES);
                rep.set(ADDRESS, DATA, data);
                rep.set(JAVA_INT, DATA_LENGTH, bytes.length);
            }
            case Pixels pixels -> {
                rep.set(JAVA_INT, KIND, GMAC_PASTEBOARD_REP_IMAGE);
                rep.set(ADDRESS, DATA, attachData(arena, pixels));
            }
            case null, default -> rep.set(JAVA_INT, KIND, GMAC_PASTEBOARD_REP_NONE);
        }
    }

    /**
     * The +1 {@code NSImage*} {@code Pixels.attachData} wrote, which the C releases whatever the UTI, or NULL. What
     * it throws is reported, as the {@code GLASS_CHECK_EXCEPTION} that closed the JNI's
     * {@code SetNSPasteboardItemValueForUtf} reported it, and the slot is read all the same, as the JNI read its
     * {@code image}.
     */
    private static MemorySegment attachData(Arena arena, Pixels pixels) {
        MemorySegment slot = arena.allocate(ADDRESS);
        try {
            MacCursorCalls.attachData(pixels, slot);
        } catch (Throwable t) {
            reportException(t);
        }
        return slot.get(ADDRESS, 0);
    }

    private static MemorySegment pasteboard(long pasteboard) {
        return MemorySegment.ofAddress(pasteboard);
    }

    private static int length(String s) {
        return s != null ? s.length() : 0;
    }

    private static void reportIfDetached(boolean detached) {
        if (detached) {
            reportException(mainThreadDetachedError());
        }
    }

    private static long repOffset(String member) {
        return GMAC_PASTEBOARD_REP_LAYOUT.byteOffset(PathElement.groupElement(member));
    }

    private static long itemOffset(String member) {
        return GMAC_PASTEBOARD_ITEM_LAYOUT.byteOffset(PathElement.groupElement(member));
    }
}
