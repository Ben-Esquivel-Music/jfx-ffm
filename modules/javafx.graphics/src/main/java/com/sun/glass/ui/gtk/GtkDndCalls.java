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

package com.sun.glass.ui.gtk;

import com.sun.glass.ui.gtk.GtkCallbackTables.Glass;
import com.sun.javafx.font.JniStringCodec;
import java.io.ByteArrayOutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import static com.sun.glass.ui.gtk.GtkApplicationCalls.cString;
import static com.sun.glass.ui.gtk.GtkCallbackTables.DND_VALUE_BYTES;
import static com.sun.glass.ui.gtk.GtkCallbackTables.DND_VALUE_FILES;
import static com.sun.glass.ui.gtk.GtkCallbackTables.DND_VALUE_IMAGE;
import static com.sun.glass.ui.gtk.GtkCallbackTables.DND_VALUE_STRING;
import static com.sun.glass.ui.gtk.GtkCallbackTables.ERR_NOT_IN_DRAG;
import static com.sun.glass.ui.gtk.GtkCallbackTables.GGTK_DND_TARGET_VALUE_LAYOUT;
import static com.sun.glass.ui.gtk.GtkCallbackTables.NOT_IN_DRAG_MESSAGE;
import static com.sun.glass.ui.gtk.GtkDialogCalls.modifiedUtf8;
import static com.sun.glass.ui.gtk.GtkGlassNative.bounded;
import static com.sun.glass.ui.gtk.GtkGlassNative.reportException;
import static com.sun.glass.ui.gtk.GtkGlassNative.unexpected;
import static com.sun.glass.ui.gtk.GtkRobotCalls.G_FREE_CALL;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The natives of {@code GtkDnDClipboard} at commit {@code 033187ad90} ({@code GlassDnDClipboard.cpp}, with the drag
 * code of {@code glass_dnd.cpp}). Each replaces the {@code Java_com_sun_glass_ui_gtk_GtkDnDClipboard_*} function it
 * names, whose body is now the {@code ggtk_dnd_*} function of {@code glass_gtk_api.h} (THE NATIVES) the method calls
 * ({@link GtkCallbackTables.Glass}); what the JNI function did with its Java arguments and result is done here, in its
 * order, around the downcall, on the thread that calls it. {@code pushTargetActionToSystem} does nothing in
 * {@code GtkDnDClipboard} itself, as its body did. The functions the header marks UPCALLS run Java inside the downcall
 * (the callback tables, nested main loops); their segments live in an arena that closes only when the downcall has
 * returned.
 */
final class GtkDndCalls {

    /** {@code Java_com_sun_glass_ui_gtk_GtkDnDClipboard_isOwner} ({@code GlassDnDClipboard.cpp}). */
    static boolean dndIsOwner() {
        try {
            return (int) Glass.DND_IS_OWNER.invokeExact() != 0;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code Java_com_sun_glass_ui_gtk_GtkDnDClipboard_pushToSystemImpl} ({@code GlassDnDClipboard.cpp},
     * {@code execute_dnd} of {@code glass_dnd.cpp}): the keys of {@code data} in its iteration order, each as the
     * modified UTF-8 {@code GetStringUTFChars} gave and NUL-terminated, back to back, then
     * {@code ggtk_dnd_push_to_system}, which runs the whole drag and answers the performed action. With
     * {@code supported} 0 no drag starts and no key is walked, as before. The values are read through the
     * {@code source_get_data} slot while the drag runs, so {@code data} must be the drag in progress
     * ({@link GtkDnDClipboard#dragInProgress()}).
     * <p>
     * The JNI walked the keys after it had created and shown the drag widget. A throw of that walk (only
     * {@code OutOfMemoryError} or {@code ConcurrentModificationException} on a {@code HashMap}) was reported, then
     * {@code jni_exception} asked the already-cleared {@code Throwable} for its message, which left a
     * {@code NullPointerException} pending, and the native queued the widget's destruction and returned with it: the
     * report and that {@code NullPointerException} are reproduced here, but no drag widget is created first. A
     * {@code null} key, on which the JNI's {@code GetStringUTFChars} crashed, takes the same path.
     */
    static int dndPushToSystem(HashMap<String, Object> data, int supported) {
        byte[] keys = new byte[0];
        int count = 0;
        if (supported != 0) {
            try {
                ByteArrayOutputStream block = new ByteArrayOutputStream();
                for (String key : data.keySet()) {
                    block.writeBytes(JniStringCodec.toModifiedUtf8(key));
                    count++;
                }
                keys = block.toByteArray();
            } catch (Throwable t) {
                reportException(t);
                throw new NullPointerException();
            }
        }
        try (Arena call = Arena.ofConfined()) {
            MemorySegment block = call.allocate(Math.max(1, keys.length));
            MemorySegment.copy(keys, 0, block, JAVA_BYTE, 0, keys.length);
            return (int) Glass.DND_PUSH_TO_SYSTEM.invokeExact(block, count, supported);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code Java_com_sun_glass_ui_gtk_GtkDnDClipboard_supportedSourceActionsFromSystem}: the
     * {@code Clipboard.ACTION_*} bits the source of the drag that last entered a Glass window offers.
     *
     * @throws IllegalStateException if no drag has entered one, as {@code check_state_in_drag} threw
     */
    static int dndSupportedSourceActions() {
        int status;
        int actions;
        try (Arena call = Arena.ofConfined()) {
            MemorySegment outActions = call.allocate(JAVA_INT);
            status = (int) Glass.DND_TARGET_GET_SUPPORTED_ACTIONS.invokeExact(outActions);
            actions = outActions.get(JAVA_INT, 0);
        } catch (Throwable t) {
            throw unexpected(t);
        }
        if (status == ERR_NOT_IN_DRAG) {
            throw new IllegalStateException(NOT_IN_DRAG_MESSAGE);
        }
        return actions;
    }

    /**
     * The {@code String[]} {@link #dndMimesFromSystem} answered last and the number of the C's mime list it was built
     * from: the JNI returned one array per drag enter ({@code enter_ctx.mimes}), and the C numbers the list it
     * computes once per drag enter. Only touched on the thread that iterates the main context.
     */
    private static String[] dndMimes;
    private static long dndMimesGeneration;

    /**
     * {@code Java_com_sun_glass_ui_gtk_GtkDnDClipboard_mimesFromSystem} ({@code dnd_target_get_mimes},
     * {@code glass_dnd.cpp}): the mime types of the drag that last entered a Glass window. The first call of a drag
     * walks its targets and fetches its uri list through a nested main loop, inside the downcall. As the JNI did once
     * per drag enter, every string the C collected is decoded as {@code NewStringUTF} decoded it and added, in order,
     * to a new {@code HashSet}, whose {@code toArray(new String[size])} is the result - or, for a list already
     * answered, the very array answered for it.
     *
     * @throws IllegalStateException if no drag has entered a Glass window, as {@code check_state_in_drag} threw
     */
    static String[] dndMimesFromSystem() {
        try (Arena call = Arena.ofConfined()) {
            MemorySegment outStrings = call.allocate(ADDRESS);
            MemorySegment outCount = call.allocate(JAVA_INT);
            MemorySegment outGeneration = call.allocate(JAVA_LONG);
            int result = (int) Glass.DND_TARGET_GET_MIMES.invokeExact(outStrings, outCount, outGeneration);
            if (result == ERR_NOT_IN_DRAG) {
                throw new IllegalStateException(NOT_IN_DRAG_MESSAGE);
            }
            MemorySegment strings = outStrings.get(ADDRESS, 0);
            try {
                reportHookOutOfMemory(result);
                long generation = outGeneration.get(JAVA_LONG, 0);
                if (dndMimes != null && generation == dndMimesGeneration) {
                    return dndMimes;
                }
                int count = outCount.get(JAVA_INT, 0);
                HashSet<String> set = new HashSet<>();
                long offset = 0;
                for (int i = 0; i < count; i++) {
                    byte[] mime = cString(MemorySegment.ofAddress(strings.address() + offset));
                    set.add(JniStringCodec.fromNewStringUtf(mime));
                    offset += mime.length + 1;
                }
                String[] mimes = set.toArray(new String[set.size()]);
                dndMimes = mimes;
                dndMimesGeneration = generation;
                return mimes;
            } finally {
                G_FREE_CALL.invokeExact(strings);
            }
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code Java_com_sun_glass_ui_gtk_GtkDnDClipboard_popFromSystem} ({@code dnd_target_get_data},
     * {@code glass_dnd.cpp}): the drag's data for {@code mimeType}, fetched by {@code ggtk_dnd_target_get_data}
     * through nested main loops inside the downcall.
     * <p>
     * A {@code null} {@code mimeType}: the JNI threw the {@code IllegalStateException} when no drag had entered a
     * Glass window and otherwise crashed in {@code GetStringUTFChars}; here the drag is checked the same way and a
     * {@code NullPointerException} is thrown instead of the crash.
     *
     * @throws IllegalStateException if no drag has entered a Glass window, as {@code check_state_in_drag} threw
     */
    static Object dndPopFromSystem(String mimeType) {
        if (mimeType == null) {
            dndSupportedSourceActions();
            throw new NullPointerException();
        }
        try (Arena call = Arena.ofConfined()) {
            MemorySegment mime = modifiedUtf8(call, mimeType);
            MemorySegment value = call.allocate(GGTK_DND_TARGET_VALUE_LAYOUT);
            int result = (int) Glass.DND_TARGET_GET_DATA.invokeExact(mime, value);
            if (result == ERR_NOT_IN_DRAG) {
                throw new IllegalStateException(NOT_IN_DRAG_MESSAGE);
            }
            MemorySegment data = value.get(ADDRESS, targetValueOffset("data"));
            try {
                reportHookOutOfMemory(result);
                return dndTargetValue(value, data);
            } finally {
                G_FREE_CALL.invokeExact(data);
            }
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code dnd_target_value_to_java} ({@code glass_dnd.cpp}): the {@code String}, {@code ByteBuffer},
     * {@code GtkPixels} or {@code String[]} a {@code GgtkDndTargetValue} stands for, built as the JNI built it -
     * the bytes as {@code NewStringUTF} decoded them, copies of the C's bytes wrapped. The JNI reported a throw of
     * each of its steps ({@code EXCEPTION_OCCURED}) and answered {@code null}, as this does (only an
     * {@code OutOfMemoryError} can be thrown there). The file list stores each path at its record's index, which
     * {@code dnd_target_uris_to_value} counts over the file URIs alone; an index outside the array is still the
     * {@code ArrayIndexOutOfBoundsException} {@code SetObjectArrayElement} raised, reported, and the element stays
     * {@code null}. Commit {@code 033187ad90} counted that index over all URIs of the list, which put a file URI
     * following a non-file one outside an array sized by the file count.
     */
    private static Object dndTargetValue(MemorySegment value, MemorySegment data) {
        int kind = value.get(JAVA_INT, targetValueOffset("kind"));
        int count = value.get(JAVA_INT, targetValueOffset("count"));
        try {
            return switch (kind) {
                case DND_VALUE_STRING -> JniStringCodec.fromNewStringUtf(bounded(data, count).toArray(JAVA_BYTE));
                case DND_VALUE_BYTES -> ByteBuffer.wrap(bounded(data, count).toArray(JAVA_BYTE));
                case DND_VALUE_IMAGE -> new GtkPixels(value.get(JAVA_INT, targetValueOffset("width")),
                        value.get(JAVA_INT, targetValueOffset("height")),
                        ByteBuffer.wrap(bounded(data, count).toArray(JAVA_BYTE)));
                case DND_VALUE_FILES -> dndFiles(data, count, value.get(JAVA_INT, targetValueOffset("records")));
                default -> null;
            };
        } catch (Throwable t) {
            reportException(t);
            return null;
        }
    }

    /** The {@code String[count]} of the {@code records} file records at {@code data} ({@code GGTK_DND_VALUE_FILES}). */
    private static String[] dndFiles(MemorySegment data, int count, int records) {
        String[] files = new String[count];
        long offset = 0;
        for (int r = 0; r < records; r++) {
            MemorySegment header = bounded(MemorySegment.ofAddress(data.address() + offset), 8);
            int index = header.get(JAVA_INT_UNALIGNED, 0);
            int length = header.get(JAVA_INT_UNALIGNED, 4);
            String path = null;
            if (length >= 0) {
                path = JniStringCodec.fromNewStringUtf(bounded(MemorySegment.ofAddress(data.address() + offset + 8),
                        length).toArray(JAVA_BYTE));
            }
            if (index >= 0 && index < count) {
                files[index] = path;
            } else {
                reportException(new ArrayIndexOutOfBoundsException("Index " + index + " out of bounds for length "
                        + count));
            }
            offset += 8 + (length >= 0 ? ((length + 1 + 3) & ~3) : 0);
        }
        return files;
    }

    /** The {@code OutOfMemoryError}s {@code glass_throw_oom} reported for event hooks the C could not allocate. */
    private static void reportHookOutOfMemory(int reports) {
        for (int i = 0; i < reports; i++) {
            reportException(new OutOfMemoryError("Failed to allocate event hook"));
        }
    }

    private static long targetValueOffset(String name) {
        return GGTK_DND_TARGET_VALUE_LAYOUT.byteOffset(PathElement.groupElement(name));
    }

    private GtkDndCalls() {
    }
}
