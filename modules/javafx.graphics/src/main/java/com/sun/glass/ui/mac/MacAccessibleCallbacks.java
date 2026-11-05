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

import com.sun.glass.ui.mac.MacGlassNative.SystemLibrary;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import static com.sun.glass.ui.mac.MacAppCallbacks.GMAC_UPCALL_OK;
import static com.sun.glass.ui.mac.MacAppCallbacks.GMAC_UPCALL_THREW;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_A11Y_INSTALL_CALLBACKS;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_OFFSETOF_VARIANT;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_OK;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_SIZEOF_ACCESSIBLE_CALLBACKS;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_SIZEOF_VARIANT;
import static com.sun.glass.ui.mac.MacGlassNative.bounded;
import static com.sun.glass.ui.mac.MacGlassNative.reportException;
import static com.sun.glass.ui.mac.MacGlassNative.unexpected;
import static com.sun.glass.ui.mac.MacGlassNative.upcallStub;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_CHAR;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * {@code GmacAccessibleCallbacks}: the upcalls of {@code MacAccessible}'s native side, for the peers
 * {@code gmac_a11y_create_peer} made. A peer crosses as the id {@link #register} gave it, which names its
 * {@link Receiver}, as the JNI's global reference named the {@code MacAccessible}. The entry is strong, as that
 * reference was, so a disposed {@code MacAccessible} stays reachable, and its slots keep running, until its peer is
 * deallocated. {@code peer_released}, which stands where {@code dealloc} deleted the reference, is the only slot that
 * forgets an id, and it runs on whichever thread released the peer last. An id the registry does not hold runs
 * nothing.
 * <p>
 * No Java exception may escape a slot: each target reports what it throws through
 * {@code Application.reportException}, as the {@code GLASS_CHECK_EXCEPTION} after each JNI call did, and answers
 * {@code GMAC_UPCALL_THREW}, else {@code GMAC_UPCALL_OK}. A target writes its out-parameters only when it returns
 * normally, so after a throw the C reads the defaults it set, which are what the JNI read after an exception.
 * <p>
 * Every block a target hands the C comes from libSystem's {@code malloc}, because the C takes it over and frees it
 * with {@code free}: the ids of a {@code long[]}, the UTF-16 units of a string and the {@code GmacVariant} blocks of a
 * {@link MacVariant}. The ids a target answers, and those the C passes in, are borrowed, as the JNI's were.
 */
final class MacAccessibleCallbacks {

    /** The slots in table order: the index of a name is its slot number. */
    static final List<String> SLOT_NAMES = List.of("attribute_names", "attribute_value", "is_attribute_settable",
            "set_value", "index_of_child", "array_attribute_count", "array_attribute_values",
            "parameterized_attribute_names", "attribute_value_for_parameter", "action_names", "action_description",
            "perform_action", "is_ignored", "hit_test", "focused_ui_element", "peer_released");

    private static final FunctionDescriptor ID_OUT_OUT = FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS,
            ADDRESS);

    static final List<FunctionDescriptor> SLOT_DESCRIPTORS = List.of(
            ID_OUT_OUT,
            ID_OUT_OUT,
            ID_OUT_OUT,
            ID_OUT_OUT,
            ID_OUT_OUT,
            ID_OUT_OUT,
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS),
            ID_OUT_OUT,
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, ADDRESS, ADDRESS),
            ID_OUT_OUT,
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, ADDRESS, ADDRESS),
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS),
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS),
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_FLOAT, JAVA_FLOAT, ADDRESS),
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS),
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG));

    private static final List<String> SLOT_TARGETS = List.of("attributeNames", "attributeValue",
            "isAttributeSettable", "setValue", "indexOfChild", "arrayAttributeCount", "arrayAttributeValues",
            "parameterizedAttributeNames", "attributeValueForParameter", "actionNames", "actionDescription",
            "performAction", "isIgnored", "hitTest", "focusedUIElement", "peerReleased");

    static final StructLayout GMAC_ACCESSIBLE_CALLBACKS_LAYOUT = MemoryLayout.structLayout(
            SLOT_NAMES.stream().map(ADDRESS::withName).toArray(MemoryLayout[]::new));

    /** One {@link MacVariant} but for its unused {@code stringArray}; LP64, 120 bytes. */
    static final StructLayout GMAC_VARIANT_LAYOUT = MemoryLayout.structLayout(
            JAVA_INT.withName("type"),
            JAVA_INT.withName("int1"),
            JAVA_INT.withName("int2"),
            JAVA_INT.withName("location"),
            JAVA_INT.withName("length"),
            JAVA_FLOAT.withName("float1"),
            JAVA_FLOAT.withName("float2"),
            JAVA_FLOAT.withName("float3"),
            JAVA_FLOAT.withName("float4"),
            JAVA_INT.withName("long_array_count"),
            JAVA_LONG.withName("long1"),
            JAVA_DOUBLE.withName("double1"),
            ADDRESS.withName("key"),
            ADDRESS.withName("long_array"),
            ADDRESS.withName("int_array"),
            JAVA_INT.withName("int_array_count"),
            JAVA_INT.withName("chars_length"),
            ADDRESS.withName("chars"),
            ADDRESS.withName("utf8"),
            ADDRESS.withName("variant_array"),
            JAVA_INT.withName("variant_array_count"),
            JAVA_INT.withName("utf8_length"));

    static final long VARIANT_SIZE = GMAC_VARIANT_LAYOUT.byteSize();

    static final long TYPE = variantOffset("type");
    static final long INT1 = variantOffset("int1");
    static final long INT2 = variantOffset("int2");
    static final long LOCATION = variantOffset("location");
    static final long LENGTH = variantOffset("length");
    static final long FLOAT1 = variantOffset("float1");
    static final long FLOAT2 = variantOffset("float2");
    static final long FLOAT3 = variantOffset("float3");
    static final long FLOAT4 = variantOffset("float4");
    static final long LONG_ARRAY_COUNT = variantOffset("long_array_count");
    static final long LONG1 = variantOffset("long1");
    static final long DOUBLE1 = variantOffset("double1");
    static final long KEY = variantOffset("key");
    static final long LONG_ARRAY = variantOffset("long_array");
    static final long INT_ARRAY = variantOffset("int_array");
    static final long INT_ARRAY_COUNT = variantOffset("int_array_count");
    static final long CHARS_LENGTH = variantOffset("chars_length");
    static final long CHARS = variantOffset("chars");
    static final long UTF8 = variantOffset("utf8");
    static final long VARIANT_ARRAY = variantOffset("variant_array");
    static final long VARIANT_ARRAY_COUNT = variantOffset("variant_array_count");
    static final long UTF8_LENGTH = variantOffset("utf8_length");

    /** The count of an array that is null: its block is NULL. */
    static final int NULL_COUNT = -1;

    /** Where the slots of one peer go: the {@code MacAccessible} methods the JNI called on it, in slot order. */
    interface Receiver {

        long[] accessibilityAttributeNames();

        MacVariant accessibilityAttributeValue(long attribute);

        boolean accessibilityIsAttributeSettable(long attribute);

        void accessibilitySetValue(long value, long attribute);

        long accessibilityIndexOfChild(long child);

        int accessibilityArrayAttributeCount(long attribute);

        long[] accessibilityArrayAttributeValues(long attribute, int index, int maxCount);

        long[] accessibilityParameterizedAttributeNames();

        MacVariant accessibilityAttributeValueForParameter(long attribute, long parameter);

        long[] accessibilityActionNames();

        String accessibilityActionDescription(long action);

        void accessibilityPerformAction(long action);

        boolean accessibilityIsIgnored();

        long accessibilityHitTest(float x, float y);

        long accessibilityFocusedUIElement();
    }

    @FunctionalInterface
    private interface SlotBody {

        void run(Receiver receiver) throws Throwable;
    }

    private static final Map<Long, Receiver> PEERS = new ConcurrentHashMap<>();

    private static final AtomicLong NEXT_PEER_ID = new AtomicLong(1);

    private static final MemorySegment[] STUBS = createStubs();

    private MacAccessibleCallbacks() {
    }

    /**
     * Once, from {@code MacAccessible}'s class initialization, before the first {@code gmac_a11y_create_peer}: checks
     * the layouts of the table and of {@code GmacVariant}, then installs the table. The library copies it, so its
     * memory may go once the call returns.
     */
    static void install() {
        MacAppCallbacks.requireSize("GmacAccessibleCallbacks", GMAC_ACCESSIBLE_CALLBACKS_LAYOUT,
                probe(GMAC_SIZEOF_ACCESSIBLE_CALLBACKS));
        MacAppCallbacks.requireLayout("GmacVariant", GMAC_VARIANT_LAYOUT, GMAC_SIZEOF_VARIANT, GMAC_OFFSETOF_VARIANT);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment table = arena.allocate(GMAC_ACCESSIBLE_CALLBACKS_LAYOUT);
            for (int slot = 0; slot < STUBS.length; slot++) {
                table.setAtIndex(ADDRESS, slot, STUBS[slot]);
            }
            int status = (int) GMAC_A11Y_INSTALL_CALLBACKS.invokeExact(table);
            if (status != GMAC_OK) {
                throw new IllegalStateException("gmac_a11y_install_callbacks answered " + status);
            }
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    static MemorySegment stub(int slot) {
        return STUBS[slot];
    }

    /** A new nonzero id naming {@code accessible}: before the {@code gmac_a11y_create_peer} of its peer. */
    static long register(MacAccessible accessible) {
        return register(new AccessibleTarget(accessible));
    }

    static long register(Receiver receiver) {
        long peerId = NEXT_PEER_ID.getAndIncrement();
        PEERS.put(peerId, receiver);
        return peerId;
    }

    /** The id of a peer {@code gmac_a11y_create_peer} did not make, for which no slot can ever run. */
    static void forget(long peerId) {
        PEERS.remove(peerId);
    }

    static boolean isRegistered(long peerId) {
        return PEERS.containsKey(peerId);
    }

    /** The {@code MacAccessible} the id names, as the JNI's {@code getJAccessible} answered it; null for none. */
    static MacAccessible accessible(long peerId) {
        return PEERS.get(peerId) instanceof AccessibleTarget target ? target.accessible() : null;
    }

    private static int attributeNames(long peerId, MemorySegment outIds, MemorySegment outCount) {
        return dial(peerId, receiver -> writeIds(receiver.accessibilityAttributeNames(), outIds, outCount));
    }

    private static int attributeValue(long peerId, MemorySegment attribute, MemorySegment outValue) {
        return dial(peerId, receiver -> writeVariant(receiver.accessibilityAttributeValue(attribute.address()),
                outValue));
    }

    private static int isAttributeSettable(long peerId, MemorySegment attribute, MemorySegment outSettable) {
        return dial(peerId, receiver -> writeFlag(receiver.accessibilityIsAttributeSettable(attribute.address()),
                outSettable));
    }

    private static int setValue(long peerId, MemorySegment value, MemorySegment attribute) {
        return dial(peerId, receiver -> receiver.accessibilitySetValue(value.address(), attribute.address()));
    }

    private static int indexOfChild(long peerId, MemorySegment child, MemorySegment outIndex) {
        return dial(peerId, receiver -> {
            long index = receiver.accessibilityIndexOfChild(child.address());
            bounded(outIndex, JAVA_LONG.byteSize()).set(JAVA_LONG, 0, index);
        });
    }

    private static int arrayAttributeCount(long peerId, MemorySegment attribute, MemorySegment outCount) {
        return dial(peerId, receiver -> {
            int count = receiver.accessibilityArrayAttributeCount(attribute.address());
            bounded(outCount, JAVA_INT.byteSize()).set(JAVA_INT, 0, count);
        });
    }

    private static int arrayAttributeValues(long peerId, MemorySegment attribute, int index, int maxCount,
                                            MemorySegment outIds, MemorySegment outCount) {
        return dial(peerId, receiver -> writeIds(
                receiver.accessibilityArrayAttributeValues(attribute.address(), index, maxCount), outIds, outCount));
    }

    private static int parameterizedAttributeNames(long peerId, MemorySegment outIds, MemorySegment outCount) {
        return dial(peerId, receiver -> writeIds(receiver.accessibilityParameterizedAttributeNames(), outIds,
                outCount));
    }

    private static int attributeValueForParameter(long peerId, MemorySegment attribute, MemorySegment parameter,
                                                  MemorySegment outValue) {
        return dial(peerId, receiver -> writeVariant(
                receiver.accessibilityAttributeValueForParameter(attribute.address(), parameter.address()), outValue));
    }

    private static int actionNames(long peerId, MemorySegment outIds, MemorySegment outCount) {
        return dial(peerId, receiver -> writeIds(receiver.accessibilityActionNames(), outIds, outCount));
    }

    private static int actionDescription(long peerId, MemorySegment action, MemorySegment outChars,
                                         MemorySegment outLength) {
        return dial(peerId, receiver -> writeChars(receiver.accessibilityActionDescription(action.address()),
                outChars, outLength));
    }

    private static int performAction(long peerId, MemorySegment action) {
        return dial(peerId, receiver -> receiver.accessibilityPerformAction(action.address()));
    }

    private static int isIgnored(long peerId, MemorySegment outIgnored) {
        return dial(peerId, receiver -> writeFlag(receiver.accessibilityIsIgnored(), outIgnored));
    }

    private static int hitTest(long peerId, float x, float y, MemorySegment outElement) {
        return dial(peerId, receiver -> writeElement(receiver.accessibilityHitTest(x, y), outElement));
    }

    private static int focusedUIElement(long peerId, MemorySegment outElement) {
        return dial(peerId, receiver -> writeElement(receiver.accessibilityFocusedUIElement(), outElement));
    }

    /** Touches only the registry, which is safe on the foreign thread that may have released the peer. */
    private static int peerReleased(long peerId) {
        return dial(peerId, receiver -> PEERS.remove(peerId));
    }

    private static int dial(long peerId, SlotBody body) {
        try {
            Receiver receiver = PEERS.get(peerId);
            if (receiver != null) {
                body.run(receiver);
            }
            return GMAC_UPCALL_OK;
        } catch (Throwable t) {
            reportException(t);
            return GMAC_UPCALL_THREW;
        }
    }

    /** A null array leaves the NULL block and the count of -1 the C set, which it reads as nil. */
    private static void writeIds(long[] ids, MemorySegment outIds, MemorySegment outCount) throws Throwable {
        if (ids == null) {
            return;
        }
        MemorySegment block = idBlock(ids);
        bounded(outIds, ADDRESS.byteSize()).set(ADDRESS, 0, block);
        bounded(outCount, JAVA_INT.byteSize()).set(JAVA_INT, 0, ids.length);
    }

    /** A null string leaves the NULL units the C set, which it reads as nil. */
    private static void writeChars(String s, MemorySegment outChars, MemorySegment outLength) throws Throwable {
        if (s == null) {
            return;
        }
        MemorySegment units = charBlock(s);
        bounded(outChars, ADDRESS.byteSize()).set(ADDRESS, 0, units);
        bounded(outLength, JAVA_INT.byteSize()).set(JAVA_INT, 0, s.length());
    }

    private static void writeFlag(boolean value, MemorySegment outFlag) {
        bounded(outFlag, JAVA_INT.byteSize()).set(JAVA_INT, 0, value ? 1 : 0);
    }

    private static void writeElement(long element, MemorySegment outElement) {
        bounded(outElement, ADDRESS.byteSize()).set(ADDRESS, 0, MemorySegment.ofAddress(element));
    }

    /**
     * A null variant leaves the null variant the C set. Otherwise the variant is built apart, with its blocks, and
     * copied to {@code outValue} only once it is whole; if building it fails, its blocks are freed and
     * {@code outValue} keeps the null variant.
     */
    private static void writeVariant(MacVariant variant, MemorySegment outValue) throws Throwable {
        if (variant == null) {
            return;
        }
        List<MemorySegment> blocks = new ArrayList<>();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment value = arena.allocate(GMAC_VARIANT_LAYOUT);
            fill(variant, value, blocks);
            MemorySegment.copy(value, 0, bounded(outValue, VARIANT_SIZE), 0, VARIANT_SIZE);
        } catch (Throwable t) {
            for (MemorySegment block : blocks) {
                LibSystem.free(block);
            }
            throw t;
        }
    }

    /**
     * Writes every member of {@code value}, which may be uninitialized memory: what {@code variantToID} read from the
     * {@link MacVariant}'s fields, with a null array as a NULL block and a count of -1, a null string as NULL units,
     * and a null element of {@code variantArray} as a null variant, of which the C makes nil, as it made nil of a
     * null element.
     */
    private static void fill(MacVariant variant, MemorySegment value, List<MemorySegment> blocks) throws Throwable {
        value.fill((byte) 0);
        value.set(JAVA_INT, LONG_ARRAY_COUNT, NULL_COUNT);
        value.set(JAVA_INT, INT_ARRAY_COUNT, NULL_COUNT);
        value.set(JAVA_INT, VARIANT_ARRAY_COUNT, NULL_COUNT);
        if (variant == null) {
            return;
        }
        value.set(JAVA_INT, TYPE, variant.type);
        value.set(JAVA_INT, INT1, variant.int1);
        value.set(JAVA_INT, INT2, variant.int2);
        value.set(JAVA_INT, LOCATION, variant.location);
        value.set(JAVA_INT, LENGTH, variant.length);
        value.set(JAVA_FLOAT, FLOAT1, variant.float1);
        value.set(JAVA_FLOAT, FLOAT2, variant.float2);
        value.set(JAVA_FLOAT, FLOAT3, variant.float3);
        value.set(JAVA_FLOAT, FLOAT4, variant.float4);
        value.set(JAVA_LONG, LONG1, variant.long1);
        value.set(JAVA_DOUBLE, DOUBLE1, variant.double1);
        value.set(ADDRESS, KEY, MemorySegment.ofAddress(variant.key));
        if (variant.longArray != null) {
            value.set(ADDRESS, LONG_ARRAY, tracked(idBlock(variant.longArray), blocks));
            value.set(JAVA_INT, LONG_ARRAY_COUNT, variant.longArray.length);
        }
        if (variant.intArray != null) {
            value.set(ADDRESS, INT_ARRAY, tracked(intBlock(variant.intArray), blocks));
            value.set(JAVA_INT, INT_ARRAY_COUNT, variant.intArray.length);
        }
        if (variant.string != null) {
            value.set(ADDRESS, CHARS, tracked(charBlock(variant.string), blocks));
            value.set(JAVA_INT, CHARS_LENGTH, variant.string.length());
        }
        if (variant.variantArray != null) {
            MacVariant[] elements = variant.variantArray;
            MemorySegment block = elements.length > 0
                    ? tracked(LibSystem.malloc(elements.length * VARIANT_SIZE), blocks) : MemorySegment.NULL;
            value.set(ADDRESS, VARIANT_ARRAY, block);
            value.set(JAVA_INT, VARIANT_ARRAY_COUNT, elements.length);
            for (int index = 0; index < elements.length; index++) {
                fill(elements[index], block.asSlice(index * VARIANT_SIZE, VARIANT_SIZE), blocks);
            }
        }
    }

    private static MemorySegment tracked(MemorySegment block, List<MemorySegment> blocks) {
        if (block.address() != 0) {
            blocks.add(block);
        }
        return block;
    }

    /** The ids of a {@code long[]}, which are {@code id}s on LP64; NULL for none, which the C never reads. */
    private static MemorySegment idBlock(long[] ids) throws Throwable {
        if (ids.length == 0) {
            return MemorySegment.NULL;
        }
        MemorySegment block = LibSystem.malloc(ids.length * JAVA_LONG.byteSize());
        MemorySegment.copy(ids, 0, block, JAVA_LONG, 0, ids.length);
        return block;
    }

    private static MemorySegment intBlock(int[] values) throws Throwable {
        if (values.length == 0) {
            return MemorySegment.NULL;
        }
        MemorySegment block = LibSystem.malloc(values.length * JAVA_INT.byteSize());
        MemorySegment.copy(values, 0, block, JAVA_INT, 0, values.length);
        return block;
    }

    /** Never NULL, not even for an empty string: the C reads NULL units as nil. */
    private static MemorySegment charBlock(String s) throws Throwable {
        MemorySegment block = LibSystem.malloc(Math.max(1, s.length()) * JAVA_CHAR.byteSize());
        MemorySegment.copy(s.toCharArray(), 0, block, JAVA_CHAR, 0, s.length());
        return block;
    }

    private static int probe(MethodHandle sizeOf) {
        try {
            return (int) sizeOf.invokeExact();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static MemorySegment[] createStubs() {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        MemorySegment[] stubs = new MemorySegment[SLOT_NAMES.size()];
        try {
            for (int slot = 0; slot < stubs.length; slot++) {
                FunctionDescriptor descriptor = SLOT_DESCRIPTORS.get(slot);
                MethodHandle target = lookup.findStatic(MacAccessibleCallbacks.class, SLOT_TARGETS.get(slot),
                        descriptor.toMethodType());
                stubs[slot] = upcallStub(target, descriptor);
            }
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
        return stubs;
    }

    private static long variantOffset(String member) {
        return GMAC_VARIANT_LAYOUT.byteOffset(PathElement.groupElement(member));
    }

    /** The {@code MacAccessible} behind a peer: the methods the JNI called by their cached IDs. */
    private record AccessibleTarget(MacAccessible accessible) implements Receiver {

        @Override
        public long[] accessibilityAttributeNames() {
            return accessible.accessibilityAttributeNames();
        }

        @Override
        public MacVariant accessibilityAttributeValue(long attribute) {
            return accessible.accessibilityAttributeValue(attribute);
        }

        @Override
        public boolean accessibilityIsAttributeSettable(long attribute) {
            return accessible.accessibilityIsAttributeSettable(attribute);
        }

        @Override
        public void accessibilitySetValue(long value, long attribute) {
            accessible.accessibilitySetValue(value, attribute);
        }

        @Override
        public long accessibilityIndexOfChild(long child) {
            return accessible.accessibilityIndexOfChild(child);
        }

        @Override
        public int accessibilityArrayAttributeCount(long attribute) {
            return accessible.accessibilityArrayAttributeCount(attribute);
        }

        @Override
        public long[] accessibilityArrayAttributeValues(long attribute, int index, int maxCount) {
            return accessible.accessibilityArrayAttributeValues(attribute, index, maxCount);
        }

        @Override
        public long[] accessibilityParameterizedAttributeNames() {
            return accessible.accessibilityParameterizedAttributeNames();
        }

        @Override
        public MacVariant accessibilityAttributeValueForParameter(long attribute, long parameter) {
            return accessible.accessibilityAttributeValueForParameter(attribute, parameter);
        }

        @Override
        public long[] accessibilityActionNames() {
            return accessible.accessibilityActionNames();
        }

        @Override
        public String accessibilityActionDescription(long action) {
            return accessible.accessibilityActionDescription(action);
        }

        @Override
        public void accessibilityPerformAction(long action) {
            accessible.accessibilityPerformAction(action);
        }

        @Override
        public boolean accessibilityIsIgnored() {
            return accessible.accessibilityIsIgnored();
        }

        @Override
        public long accessibilityHitTest(float x, float y) {
            return accessible.accessibilityHitTest(x, y);
        }

        @Override
        public long accessibilityFocusedUIElement() {
            return accessible.accessibilityFocusedUIElement();
        }
    }

    /** The heap the C frees the slots' blocks from. */
    private static final class LibSystem {

        private static final SystemLibrary LIBRARY = SystemLibrary.libSystem();

        private static final MethodHandle MALLOC = LIBRARY.bind("malloc", FunctionDescriptor.of(ADDRESS, JAVA_LONG));
        private static final MethodHandle FREE = LIBRARY.bind("free", FunctionDescriptor.ofVoid(ADDRESS));

        static MemorySegment malloc(long byteSize) throws Throwable {
            MemorySegment block = (MemorySegment) MALLOC.invokeExact(byteSize);
            if (block.address() == 0) {
                throw new OutOfMemoryError("malloc(" + byteSize + ") answered NULL");
            }
            return bounded(block, byteSize);
        }

        static void free(MemorySegment block) throws Throwable {
            FREE.invokeExact(block);
        }
    }
}
