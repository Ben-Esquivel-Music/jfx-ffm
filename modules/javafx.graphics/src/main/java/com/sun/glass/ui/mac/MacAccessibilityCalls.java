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
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.OptionalLong;
import java.util.function.ObjLongConsumer;
import static com.sun.glass.ui.mac.MacAccessibleCallbacks.DOUBLE1;
import static com.sun.glass.ui.mac.MacAccessibleCallbacks.FLOAT1;
import static com.sun.glass.ui.mac.MacAccessibleCallbacks.FLOAT2;
import static com.sun.glass.ui.mac.MacAccessibleCallbacks.FLOAT3;
import static com.sun.glass.ui.mac.MacAccessibleCallbacks.FLOAT4;
import static com.sun.glass.ui.mac.MacAccessibleCallbacks.GMAC_VARIANT_LAYOUT;
import static com.sun.glass.ui.mac.MacAccessibleCallbacks.INT1;
import static com.sun.glass.ui.mac.MacAccessibleCallbacks.INT2;
import static com.sun.glass.ui.mac.MacAccessibleCallbacks.INT_ARRAY;
import static com.sun.glass.ui.mac.MacAccessibleCallbacks.INT_ARRAY_COUNT;
import static com.sun.glass.ui.mac.MacAccessibleCallbacks.LONG_ARRAY;
import static com.sun.glass.ui.mac.MacAccessibleCallbacks.LONG_ARRAY_COUNT;
import static com.sun.glass.ui.mac.MacAccessibleCallbacks.UTF8;
import static com.sun.glass.ui.mac.MacAccessibleCallbacks.UTF8_LENGTH;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_OK;
import static com.sun.glass.ui.mac.MacGlassNative.bind;
import static com.sun.glass.ui.mac.MacGlassNative.bounded;
import static com.sun.glass.ui.mac.MacGlassNative.free;
import static com.sun.glass.ui.mac.MacGlassNative.newStringUtf;
import static com.sun.glass.ui.mac.MacGlassNative.unexpected;
import static com.sun.glass.ui.mac.MacGlassNative.utf16;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BOOLEAN;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The natives of {@code MacAccessible}: the {@code gmac_a11y_*} exports, which this class binds in header order when
 * it initializes, for {@code _createAccessiblePeer}, {@code _invalidateParent}, {@code GlassAccessibleToMacAccessible},
 * {@code idToMacVariant} and {@code NSAccessibilityUnignoredChildren}, and the AppKit, CoreFoundation and Objective-C
 * runtime functions that the other bodies were one call of (audit g4 §9). {@code _initIDs} has no counterpart here:
 * {@code MacAccessibleCallbacks.install} stands in its place.
 * <p>
 * FX thread, which is the AppKit main thread, most of these inside a {@code GmacAccessibleCallbacks} slot; no JNI
 * body checked the thread or for an exception, and none of these does. None of these downcalls may be critical: they
 * message objects AppKit passed, and {@code NSAccessibilityPostNotification}, {@code NSAccessibilityUnignoredAncestor}
 * and {@code gmac_a11y_unignored_children} dial slots before they return. Strings into the library cross as their
 * UTF-16 code units from a confined arena for the call, which the export copies, and so do the ids of a
 * {@code long[]}, which the JNI read in place.
 */
final class MacAccessibilityCalls {

    /** The names {@code _initEnum} made a string of rather than look up: none of them is an AppKit symbol. */
    static final String CUSTOM_NAME_PREFIX = "AX";

    /** The bundle whose data symbols {@code _initEnum} read: the umbrella that re-exports AppKit's. */
    static final String COCOA_BUNDLE_IDENTIFIER = "com.apple.Cocoa";

    private static final MethodHandle GMAC_A11Y_CREATE_PEER = bind("gmac_a11y_create_peer",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_LONG));
    private static final MethodHandle GMAC_A11Y_INVALIDATE_PARENT = bind("gmac_a11y_invalidate_parent",
            FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle GMAC_A11Y_PEER_ID = bind("gmac_a11y_peer_id",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS));
    private static final MethodHandle GMAC_A11Y_ID_TO_VARIANT = bind("gmac_a11y_id_to_variant",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
    private static final MethodHandle GMAC_A11Y_UNIGNORED_CHILDREN = bind("gmac_a11y_unignored_children",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));

    /** {@code gmac_a11y_create_peer}, so that a test can stand where AppKit would. */
    @FunctionalInterface
    interface PeerFactory {

        long create(MemorySegment role, int roleLength, long peerId) throws Throwable;
    }

    /** {@code gmac_a11y_peer_id}, so that a test can stand where a peer would. */
    @FunctionalInterface
    interface PeerIdReader {

        long read(MemorySegment object) throws Throwable;
    }

    /** {@code gmac_a11y_id_to_variant}, so that a test can stand where an object would. */
    @FunctionalInterface
    interface VariantReader {

        int read(MemorySegment object, int type, MemorySegment out) throws Throwable;
    }

    /** {@code gmac_a11y_unignored_children}, so that a test can stand where AppKit would. */
    @FunctionalInterface
    interface ChildrenReader {

        int read(MemorySegment ids, int count, MemorySegment outIds, MemorySegment outCount) throws Throwable;
    }

    private MacAccessibilityCalls() {
    }

    /**
     * {@code _initEnum} of the enum whose constants are {@code constants}: stores in each constant's {@code ptr},
     * through {@code ptr}, the {@code NSString*} its {@code toString()} names, and answers whether every constant got
     * one. A name that begins with {@value #CUSTOM_NAME_PREFIX} gets a new string of itself, +1, which is never
     * released (latent bug 7); any other name the value of the {@value #COCOA_BUNDLE_IDENTIFIER} bundle's data symbol
     * of that name. The first name without one answers false and leaves the later constants' {@code ptr} as they are.
     */
    static <E extends Enum<E>> boolean initEnum(E[] constants, ObjLongConsumer<E> ptr) {
        if (constants.length == 0) {
            return false;
        }
        MemorySegment bundle = cocoaBundle();
        if (bundle.address() == 0) {
            return false;
        }
        for (E constant : constants) {
            OptionalLong pointer = enumPointer(bundle, constant.toString());
            if (pointer.isEmpty()) {
                return false;
            }
            ptr.accept(constant, pointer.getAsLong());
        }
        return true;
    }

    /** What {@link #initEnum} stores for a constant called {@code name}, or empty where it answers false. */
    static OptionalLong enumPointer(String name) {
        MemorySegment bundle = cocoaBundle();
        return bundle.address() != 0 ? enumPointer(bundle, name) : OptionalLong.empty();
    }

    static long createAccessiblePeer(MacAccessible accessible, String role) {
        return createAccessiblePeer(accessible, role, MacAccessibilityCalls::createPeer);
    }

    /**
     * {@code _createAccessiblePeer}: the +1 peer of {@code accessible}'s role, which {@code accessible} owns. Its id
     * is a new one per call, because {@code getNativeAccessible} makes a peer again after {@code dispose}, and is
     * registered before the call, which dials no slot for it before it returns. A NULL peer gets no
     * {@code peer_released}, so its id is forgotten here, as the JNI took no global reference without a peer.
     */
    static long createAccessiblePeer(MacAccessible accessible, String role, PeerFactory factory) {
        long peerId = MacAccessibleCallbacks.register(accessible);
        try (Arena arena = Arena.ofConfined()) {
            long peer = factory.create(utf16(arena, role), role != null ? role.length() : 0, peerId);
            if (peer == 0) {
                MacAccessibleCallbacks.forget(peerId);
            }
            return peer;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code _destroyAccessiblePeer}: {@code [peer release]}, which every peer responds to, through the
     * {@code objc_release} it compiles to.
     */
    static void destroyAccessiblePeer(long peer) {
        try {
            ObjectiveC.OBJC_RELEASE.invokeExact(address(peer));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code _invalidateParent}: an {@code AccessibleBase} peer forgets the parent it cached. */
    static void invalidateParent(long peer) {
        try {
            GMAC_A11Y_INVALIDATE_PARENT.invokeExact(address(peer));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code isEqualToString}: {@code [string1 isEqualToString: string2]}, a literal comparison of two
     * {@code NSString}s, which {@code CFEqual} makes of toll-free bridged strings too. A NULL side answers false, as
     * messaging nil, or comparing with nil, answered NO, where {@code CFEqual} would halt.
     */
    static boolean isEqualToString(long string1, long string2) {
        if (string1 == 0 || string2 == 0) {
            return false;
        }
        try {
            return (boolean) CoreFoundation.CF_EQUAL.invokeExact(address(string1), address(string2));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code NSAccessibilityUnignoredAncestor}: a borrowed element, or 0. */
    static long unignoredAncestor(long element) {
        try {
            return ((MemorySegment) AppKit.NS_ACCESSIBILITY_UNIGNORED_ANCESTOR.invokeExact(address(element)))
                    .address();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    static long[] unignoredChildren(long[] children) {
        return unignoredChildren(children, MacAccessibilityCalls::readUnignoredChildren);
    }

    /**
     * {@code NSAccessibilityUnignoredChildren} of the elements {@code children} holds, borrowed ids both ways. A null
     * array answers null, as the JNI did; any other {@code GMAC_ERR_NO_DATA} is the allocation that failed where the
     * JNI's {@code NewLongArray} threw {@link OutOfMemoryError}.
     */
    static long[] unignoredChildren(long[] children, ChildrenReader reader) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment ids = children != null && children.length > 0
                    ? arena.allocateFrom(JAVA_LONG, children) : MemorySegment.NULL;
            MemorySegment outIds = arena.allocate(ADDRESS);
            MemorySegment outCount = arena.allocate(JAVA_INT);
            int status;
            try {
                status = reader.read(ids, children != null ? children.length : MacAccessibleCallbacks.NULL_COUNT,
                        outIds, outCount);
            } catch (Throwable t) {
                throw unexpected(t);
            }
            MemorySegment block = outIds.get(ADDRESS, 0);
            try {
                if (status != GMAC_OK) {
                    if (children == null) {
                        return null;
                    }
                    throw new OutOfMemoryError("gmac_a11y_unignored_children could not allocate the children");
                }
                int count = outCount.get(JAVA_INT, 0);
                return bounded(block, count * JAVA_LONG.byteSize()).toArray(JAVA_LONG);
            } finally {
                free(block);
            }
        }
    }

    /** {@code NSAccessibilityPostNotification}, to whatever AppKit's observers do with it. */
    static void postNotification(long element, long notification) {
        try {
            AppKit.NS_ACCESSIBILITY_POST_NOTIFICATION.invokeExact(address(element), address(notification));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code NSAccessibilityActionDescription}, decoded as the JNI's {@code NewStringUTF} decoded its
     * {@code UTF8String}; null for none.
     */
    static String actionDescription(long action) {
        MemorySegment description;
        try {
            description = (MemorySegment) AppKit.NS_ACCESSIBILITY_ACTION_DESCRIPTION.invokeExact(address(action));
        } catch (Throwable t) {
            throw unexpected(t);
        }
        return MacClipboardCalls.utf8String(description);
    }

    /** {@code NSAccessibilityRoleDescription}, decoded as {@link #actionDescription} is; null for none. */
    static String roleDescription(long role, long subrole) {
        MemorySegment description;
        try {
            description = (MemorySegment) AppKit.NS_ACCESSIBILITY_ROLE_DESCRIPTION.invokeExact(address(role),
                    address(subrole));
        } catch (Throwable t) {
            throw unexpected(t);
        }
        return MacClipboardCalls.utf8String(description);
    }

    static MacVariant idToMacVariant(long object, int type) {
        return idToMacVariant(object, type, MacAccessibilityCalls::readVariant);
    }

    /**
     * {@code idToMacVariant}: {@code object}, borrowed, read as {@code type}, with the fields the JNI set for that
     * type, and the defaults of a new {@link MacVariant} for the others. {@code GMAC_ERR_NO_DATA} is the allocation
     * that failed where the JNI's {@code NewStringUTF}, {@code NewLongArray} or {@code NewIntArray} threw
     * {@link OutOfMemoryError}.
     */
    static MacVariant idToMacVariant(long object, int type, VariantReader reader) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(GMAC_VARIANT_LAYOUT);
            int status;
            try {
                status = reader.read(address(object), type, out);
            } catch (Throwable t) {
                throw unexpected(t);
            }
            try {
                if (status != GMAC_OK) {
                    throw new OutOfMemoryError("gmac_a11y_id_to_variant could not allocate the value of type "
                            + type);
                }
                return toMacVariant(type, out);
            } finally {
                free(out.get(ADDRESS, UTF8));
                free(out.get(ADDRESS, LONG_ARRAY));
                free(out.get(ADDRESS, INT_ARRAY));
            }
        }
    }

    static MacAccessible glassAccessibleToMacAccessible(long object) {
        return glassAccessibleToMacAccessible(object, MacAccessibilityCalls::readPeerId);
    }

    /**
     * {@code GlassAccessibleToMacAccessible}: the {@code MacAccessible} of the peer {@code object}, null for 0. An
     * object that is not a peer raises an {@code NSException} for the unrecognized selector, as it did under JNI.
     */
    static MacAccessible glassAccessibleToMacAccessible(long object, PeerIdReader reader) {
        long peerId;
        try {
            peerId = reader.read(address(object));
        } catch (Throwable t) {
            throw unexpected(t);
        }
        return MacAccessibleCallbacks.accessible(peerId);
    }

    private static MacVariant toMacVariant(int type, MemorySegment out) {
        MacVariant variant = new MacVariant();
        variant.type = type;
        switch (type) {
            case MacVariant.NSNumber_Boolean, MacVariant.NSNumber_Int -> variant.int1 = out.get(JAVA_INT, INT1);
            case MacVariant.NSNumber_Double -> variant.double1 = out.get(JAVA_DOUBLE, DOUBLE1);
            case MacVariant.NSString -> variant.string = newStringUtf(out.get(ADDRESS, UTF8),
                    out.get(JAVA_INT, UTF8_LENGTH));
            case MacVariant.NSArray_id -> {
                int count = out.get(JAVA_INT, LONG_ARRAY_COUNT);
                if (count >= 0) {
                    variant.longArray = bounded(out.get(ADDRESS, LONG_ARRAY), count * JAVA_LONG.byteSize())
                            .toArray(JAVA_LONG);
                }
            }
            case MacVariant.NSArray_int -> {
                int count = out.get(JAVA_INT, INT_ARRAY_COUNT);
                if (count >= 0) {
                    variant.intArray = bounded(out.get(ADDRESS, INT_ARRAY), count * JAVA_INT.byteSize())
                            .toArray(JAVA_INT);
                }
            }
            case MacVariant.NSValue_point, MacVariant.NSValue_size -> {
                variant.float1 = out.get(JAVA_FLOAT, FLOAT1);
                variant.float2 = out.get(JAVA_FLOAT, FLOAT2);
            }
            case MacVariant.NSValue_rectangle -> {
                variant.float1 = out.get(JAVA_FLOAT, FLOAT1);
                variant.float2 = out.get(JAVA_FLOAT, FLOAT2);
                variant.float3 = out.get(JAVA_FLOAT, FLOAT3);
                variant.float4 = out.get(JAVA_FLOAT, FLOAT4);
            }
            case MacVariant.NSValue_range -> {
                variant.int1 = out.get(JAVA_INT, INT1);
                variant.int2 = out.get(JAVA_INT, INT2);
            }
            default -> {
            }
        }
        return variant;
    }

    /** The bundle {@code CFBundleGetBundleWithIdentifier} answers, borrowed, or NULL. */
    private static MemorySegment cocoaBundle() {
        MemorySegment identifier = createString(COCOA_BUNDLE_IDENTIFIER);
        try {
            return (MemorySegment) CoreFoundation.CF_BUNDLE_GET_BUNDLE_WITH_IDENTIFIER.invokeExact(identifier);
        } catch (Throwable t) {
            throw unexpected(t);
        } finally {
            release(identifier);
        }
    }

    /**
     * Empty where the JNI's string of the name was nil or the bundle had no data symbol of that name; an "AX" name's
     * string, +1, otherwise the symbol's value, which may be NULL.
     */
    private static OptionalLong enumPointer(MemorySegment bundle, String name) {
        MemorySegment string = createString(name);
        if (string.address() == 0) {
            return OptionalLong.empty();
        }
        if (name.startsWith(CUSTOM_NAME_PREFIX)) {
            return OptionalLong.of(string.address());
        }
        try {
            MemorySegment data;
            try {
                data = (MemorySegment) CoreFoundation.CF_BUNDLE_GET_DATA_POINTER_FOR_NAME.invokeExact(bundle, string);
            } catch (Throwable t) {
                throw unexpected(t);
            }
            if (data.address() == 0) {
                return OptionalLong.empty();
            }
            return OptionalLong.of(bounded(data, ADDRESS.byteSize()).get(ADDRESS, 0).address());
        } finally {
            release(string);
        }
    }

    /** {@code [[NSString alloc] initWithCharacters:length:]}, +1, which keeps every code unit. */
    private static MemorySegment createString(String s) {
        try (Arena arena = Arena.ofConfined()) {
            return (MemorySegment) CoreFoundation.CF_STRING_CREATE_WITH_CHARACTERS.invokeExact(MemorySegment.NULL,
                    utf16(arena, s), (long) s.length());
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static void release(MemorySegment object) {
        try {
            CoreFoundation.CF_RELEASE.invokeExact(object);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static long createPeer(MemorySegment role, int roleLength, long peerId) throws Throwable {
        return ((MemorySegment) GMAC_A11Y_CREATE_PEER.invokeExact(role, roleLength, peerId)).address();
    }

    private static long readPeerId(MemorySegment object) throws Throwable {
        return (long) GMAC_A11Y_PEER_ID.invokeExact(object);
    }

    private static int readVariant(MemorySegment object, int type, MemorySegment out) throws Throwable {
        return (int) GMAC_A11Y_ID_TO_VARIANT.invokeExact(object, type, out);
    }

    private static int readUnignoredChildren(MemorySegment ids, int count, MemorySegment outIds,
                                             MemorySegment outCount) throws Throwable {
        return (int) GMAC_A11Y_UNIGNORED_CHILDREN.invokeExact(ids, count, outIds, outCount);
    }

    private static MemorySegment address(long pointer) {
        return MemorySegment.ofAddress(pointer);
    }

    /** Bound on the first call that needs them, not when the class initializes. */
    private static final class AppKit {

        private static final SystemLibrary LIBRARY = SystemLibrary.framework("AppKit");

        static final MethodHandle NS_ACCESSIBILITY_POST_NOTIFICATION = LIBRARY.bind("NSAccessibilityPostNotification",
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
        static final MethodHandle NS_ACCESSIBILITY_UNIGNORED_ANCESTOR = LIBRARY.bind(
                "NSAccessibilityUnignoredAncestor", FunctionDescriptor.of(ADDRESS, ADDRESS));
        static final MethodHandle NS_ACCESSIBILITY_ROLE_DESCRIPTION = LIBRARY.bind("NSAccessibilityRoleDescription",
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
        static final MethodHandle NS_ACCESSIBILITY_ACTION_DESCRIPTION = LIBRARY.bind(
                "NSAccessibilityActionDescription", FunctionDescriptor.of(ADDRESS, ADDRESS));
    }

    private static final class CoreFoundation {

        private static final SystemLibrary LIBRARY = SystemLibrary.framework("CoreFoundation");

        /* Boolean is one byte. */
        static final MethodHandle CF_EQUAL = LIBRARY.bind("CFEqual",
                FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS, ADDRESS));
        static final MethodHandle CF_BUNDLE_GET_BUNDLE_WITH_IDENTIFIER = LIBRARY.bind(
                "CFBundleGetBundleWithIdentifier", FunctionDescriptor.of(ADDRESS, ADDRESS));
        static final MethodHandle CF_BUNDLE_GET_DATA_POINTER_FOR_NAME = LIBRARY.bind(
                "CFBundleGetDataPointerForName", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
        static final MethodHandle CF_STRING_CREATE_WITH_CHARACTERS = LIBRARY.bind("CFStringCreateWithCharacters",
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG));
        static final MethodHandle CF_RELEASE = LIBRARY.bind("CFRelease", FunctionDescriptor.ofVoid(ADDRESS));
    }

    private static final class ObjectiveC {

        static final MethodHandle OBJC_RELEASE = SystemLibrary.libobjc().bind("objc_release",
                FunctionDescriptor.ofVoid(ADDRESS));
    }
}
