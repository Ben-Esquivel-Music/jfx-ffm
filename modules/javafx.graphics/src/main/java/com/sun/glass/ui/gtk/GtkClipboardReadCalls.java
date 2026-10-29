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

import com.sun.javafx.font.JniStringCodec;
import java.io.ByteArrayOutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import static com.sun.glass.ui.gtk.GtkApplicationCalls.cString;
import static com.sun.glass.ui.gtk.GtkApplicationCalls.newStringUtf;
import static com.sun.glass.ui.gtk.GtkClipboardCalls.FILE_PREFIX;
import static com.sun.glass.ui.gtk.GtkClipboardCalls.GDK_ATOM_INTERN;
import static com.sun.glass.ui.gtk.GtkClipboardCalls.GDK_ATOM_NAME;
import static com.sun.glass.ui.gtk.GtkClipboardCalls.GDK_PIXBUF_GET_HAS_ALPHA;
import static com.sun.glass.ui.gtk.GtkClipboardCalls.GDK_PIXBUF_GET_HEIGHT;
import static com.sun.glass.ui.gtk.GtkClipboardCalls.GDK_PIXBUF_GET_ROWSTRIDE;
import static com.sun.glass.ui.gtk.GtkClipboardCalls.GDK_PIXBUF_GET_WIDTH;
import static com.sun.glass.ui.gtk.GtkClipboardCalls.GTK_CLIPBOARD_WAIT_FOR_CONTENTS;
import static com.sun.glass.ui.gtk.GtkClipboardCalls.GTK_CLIPBOARD_WAIT_FOR_IMAGE;
import static com.sun.glass.ui.gtk.GtkClipboardCalls.GTK_CLIPBOARD_WAIT_FOR_TARGETS;
import static com.sun.glass.ui.gtk.GtkClipboardCalls.GTK_CLIPBOARD_WAIT_FOR_TEXT;
import static com.sun.glass.ui.gtk.GtkClipboardCalls.GTK_CLIPBOARD_WAIT_FOR_URIS;
import static com.sun.glass.ui.gtk.GtkClipboardCalls.GTK_SELECTION_DATA_FREE;
import static com.sun.glass.ui.gtk.GtkClipboardCalls.GTK_SELECTION_DATA_GET_DATA;
import static com.sun.glass.ui.gtk.GtkClipboardCalls.GTK_SELECTION_DATA_GET_LENGTH;
import static com.sun.glass.ui.gtk.GtkClipboardCalls.GTK_TARGETS_INCLUDE_IMAGE;
import static com.sun.glass.ui.gtk.GtkClipboardCalls.GTK_TARGETS_INCLUDE_TEXT;
import static com.sun.glass.ui.gtk.GtkClipboardCalls.G_FILENAME_FROM_URI;
import static com.sun.glass.ui.gtk.GtkClipboardCalls.G_STRFREEV;
import static com.sun.glass.ui.gtk.GtkClipboardCalls.G_STRV_LENGTH;
import static com.sun.glass.ui.gtk.GtkClipboardCalls.URI_LIST_COMMENT_PREFIX;
import static com.sun.glass.ui.gtk.GtkClipboardCalls.URI_LIST_LINE_BREAK;
import static com.sun.glass.ui.gtk.GtkClipboardCalls.getClipboard;
import static com.sun.glass.ui.gtk.GtkClipboardCalls.initAtoms;
import static com.sun.glass.ui.gtk.GtkClipboardCalls.mimeFilesTarget;
import static com.sun.glass.ui.gtk.GtkClipboardCalls.mimeJavaImage;
import static com.sun.glass.ui.gtk.GtkClipboardCalls.mimeTextPlainTarget;
import static com.sun.glass.ui.gtk.GtkClipboardCalls.mimeTextUriListTarget;
import static com.sun.glass.ui.gtk.GtkGlassNative.bounded;
import static com.sun.glass.ui.gtk.GtkGlassNative.unexpected;
import static com.sun.glass.ui.gtk.GtkPixelsCalls.G_OBJECT_UNREF;
import static com.sun.glass.ui.gtk.GtkPixelsCalls.convertBgraToRgba;
import static com.sun.glass.ui.gtk.GtkRobotCalls.GDK_PIXBUF_ADD_ALPHA;
import static com.sun.glass.ui.gtk.GtkRobotCalls.GDK_PIXBUF_GET_PIXELS;
import static com.sun.glass.ui.gtk.GtkRobotCalls.G_FREE_CALL;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The system clipboard of the Linux glass as a reader: {@code GtkSystemClipboard.popFromSystem} and
 * {@code mimesFromSystem} ({@code GlassSystemClipboard.cpp} at commit {@code 033187ad90}), with the
 * {@code uris_to_java} and {@code glass_gtk_selection_data_get_data_with_length} of {@code glass_general.cpp} that only
 * they called, and the byte-string helpers both clipboard classes use. The atoms and most handles are
 * {@link GtkClipboardCalls}'s, the pixbuf and {@code g_free} ones {@link GtkRobotCalls}', {@code g_object_unref}
 * {@link GtkPixelsCalls}'; this class binds nothing.
 */
final class GtkClipboardReadCalls {

    /**
     * {@code Java_com_sun_glass_ui_gtk_GtkSystemClipboard_popFromSystem} ({@code GlassSystemClipboard.cpp}). The
     * comparisons are made on the modified UTF-8 bytes {@code GetStringUTFChars} produced. Every clipboard wait
     * iterates the GTK main loop, so events are dispatched inside this call.
     */
    static Object clipboardPop(String mime) {
        try (Arena call = Arena.ofConfined()) {
            byte[] cmime = JniStringCodec.toModifiedUtf8(mime);
            initAtoms();
            Object result;
            if (cStringEquals(cmime, "text/plain")) {
                result = clipboardText();
            } else if (cStringEquals(cmime, "text/uri-list")) {
                result = urisToJava(clipboardUris(), false);
            } else if (cStringStartsWith(cmime, "text/")) {
                result = clipboardRaw(call, cmime, true);
            } else if (cStringEquals(cmime, "application/x-java-file-list")) {
                result = urisToJava(clipboardUris(), true);
            } else if (cStringEquals(cmime, "application/x-java-rawimage")) {
                result = clipboardImage();
            } else {
                result = clipboardRaw(call, cmime, false);
            }
            return result;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code get_data_text}: {@code gtk_clipboard_wait_for_text} decoded as {@code createUTF} decoded it. */
    private static Object clipboardText() throws Throwable {
        MemorySegment data = (MemorySegment) GTK_CLIPBOARD_WAIT_FOR_TEXT.invokeExact(getClipboard());
        if (data.address() == 0) {
            return null;
        }
        try {
            return new String(cString(data), StandardCharsets.UTF_8);
        } finally {
            G_FREE_CALL.invokeExact(data);
        }
    }

    /** {@code gtk_clipboard_wait_for_uris}; the caller frees the result with {@code g_strfreev}. */
    private static MemorySegment clipboardUris() throws Throwable {
        return (MemorySegment) GTK_CLIPBOARD_WAIT_FOR_URIS.invokeExact(getClipboard());
    }

    /**
     * {@code uris_to_java} ({@code glass_general.cpp}), which only the clipboard called with an array GTK owns:
     * with {@code files}, the {@code file://} URIs as filesystem paths in a {@code String[]}; without, the other
     * URIs joined by CR LF in one {@code String}, comment lines left out. The array is freed either way.
     * <p>
     * The n-th file URI is stored at index n, which is what the array is sized for. Commit {@code 033187ad90}
     * indexed it by the position in the whole URI list instead, so a file URI that followed a non-file one was
     * written past the end of the array: the {@code ArrayIndexOutOfBoundsException} was reported and the path
     * lost. The DnD target copy in {@code glass_dnd.cpp} was corrected with it.
     */
    private static Object urisToJava(MemorySegment uris, boolean files) throws Throwable {
        if (uris.address() == 0) {
            return null;
        }
        try {
            int size = (int) G_STRV_LENGTH.invokeExact(uris);
            MemorySegment list = bounded(uris, ADDRESS.byteSize() * ((long) size + 1));
            int filesCount = 0;
            for (int i = 0; i < size; i++) {
                if (cStringStartsWith(cString(list.getAtIndex(ADDRESS, i)), FILE_PREFIX)) {
                    filesCount++;
                }
            }
            Object result = null;
            if (files) {
                if (filesCount != 0) {
                    String[] paths = new String[filesCount];
                    int fileIndex = 0;
                    for (int i = 0; i < size; i++) {
                        MemorySegment uri = list.getAtIndex(ADDRESS, i);
                        if (cStringStartsWith(cString(uri), FILE_PREFIX)) {
                            MemorySegment path = (MemorySegment) G_FILENAME_FROM_URI.invokeExact(uri,
                                    MemorySegment.NULL, MemorySegment.NULL);
                            paths[fileIndex++] = newStringUtf(path);
                            G_FREE_CALL.invokeExact(path);
                        }
                    }
                    result = paths;
                }
            } else if (size - filesCount != 0) {
                ByteArrayOutputStream text = new ByteArrayOutputStream();
                for (int i = 0; i < size; i++) {
                    byte[] uri = cString(list.getAtIndex(ADDRESS, i));
                    if (!cStringStartsWith(uri, FILE_PREFIX) && !cStringStartsWith(uri, URI_LIST_COMMENT_PREFIX)) {
                        text.writeBytes(uri);
                        text.writeBytes(URI_LIST_LINE_BREAK.getBytes(StandardCharsets.US_ASCII));
                    }
                }
                byte[] joined = text.toByteArray();
                if (joined.length > 2) {
                    joined = Arrays.copyOf(joined, joined.length - 2);
                }
                result = JniStringCodec.fromNewStringUtf(joined);
            }
            return result;
        } finally {
            G_STRFREEV.invokeExact(uris);
        }
    }

    /**
     * {@code get_data_image} ({@code GlassSystemClipboard.cpp}): a {@code GtkPixels} over a heap {@code ByteBuffer}
     * of {@code rowstride * height} bytes.
     */
    private static Object clipboardImage() throws Throwable {
        MemorySegment pixbuf = (MemorySegment) GTK_CLIPBOARD_WAIT_FOR_IMAGE.invokeExact(getClipboard());
        if (pixbuf.address() == 0) {
            return null;
        }
        if ((int) GDK_PIXBUF_GET_HAS_ALPHA.invokeExact(pixbuf) == 0) {
            MemorySegment withAlpha = (MemorySegment) GDK_PIXBUF_ADD_ALPHA.invokeExact(pixbuf, 0, (byte) 0,
                    (byte) 0, (byte) 0);
            G_OBJECT_UNREF.invokeExact(pixbuf);
            pixbuf = withAlpha;
        }
        int w = (int) GDK_PIXBUF_GET_WIDTH.invokeExact(pixbuf);
        int h = (int) GDK_PIXBUF_GET_HEIGHT.invokeExact(pixbuf);
        int stride = (int) GDK_PIXBUF_GET_ROWSTRIDE.invokeExact(pixbuf);
        if (stride <= 0 || h <= 0 || h > Integer.MAX_VALUE / stride) {
            G_OBJECT_UNREF.invokeExact(pixbuf);
            return null;
        }
        MemorySegment pixels = (MemorySegment) GDK_PIXBUF_GET_PIXELS.invokeExact(pixbuf);
        // the comment of the C calls this RGBA -> BGRA; it is the same permutation
        MemorySegment data = convertBgraToRgba(bounded(pixels, (long) stride * h), 0, stride, h);
        if (data.address() == 0) {
            G_OBJECT_UNREF.invokeExact(pixbuf);
            return null;
        }
        byte[] bytes = data.toArray(JAVA_BYTE);
        G_FREE_CALL.invokeExact(data);
        G_OBJECT_UNREF.invokeExact(pixbuf);
        return new GtkPixels(w, h, ByteBuffer.wrap(bytes));
    }

    /**
     * {@code get_data_raw} ({@code GlassSystemClipboard.cpp}): the selection for the atom of {@code mime}, either
     * decoded as {@code createUTF} decoded it - to its first NUL - or wrapped whole in a {@code ByteBuffer}.
     */
    private static Object clipboardRaw(Arena call, byte[] mime, boolean stringData) throws Throwable {
        long atom = (long) GDK_ATOM_INTERN.invokeExact(call.allocateFrom(JAVA_BYTE, mime), 0);
        MemorySegment data = (MemorySegment) GTK_CLIPBOARD_WAIT_FOR_CONTENTS.invokeExact(getClipboard(), atom);
        if (data.address() == 0) {
            return null;
        }
        try {
            // glass_gtk_selection_data_get_data_with_length (glass_general.cpp)
            int length = (int) GTK_SELECTION_DATA_GET_LENGTH.invokeExact(data);
            MemorySegment raw = (MemorySegment) GTK_SELECTION_DATA_GET_DATA.invokeExact(data);
            if (stringData) {
                return new String(cString(raw), StandardCharsets.UTF_8);
            }
            return ByteBuffer.wrap(bounded(raw, length).toArray(JAVA_BYTE));
        } finally {
            GTK_SELECTION_DATA_FREE.invokeExact(data);
        }
    }

    /**
     * {@code Java_com_sun_glass_ui_gtk_GtkSystemClipboard_mimesFromSystem} ({@code GlassSystemClipboard.cpp}): the
     * targets the clipboard offers, with the first text target reported as {@code text/plain}, the first image
     * target as {@code application/x-java-rawimage} and {@code text/uri-list} split into a file list and a URI
     * list according to what the URIs are. An empty clipboard answers {@code null}: the C's
     * {@code glass_try_malloc0_n(0, ...)} was {@code g_try_malloc0(0)}, which is {@code NULL}.
     */
    static String[] clipboardMimes() {
        try (Arena call = Arena.ofConfined()) {
            initAtoms();
            MemorySegment targetsOut = call.allocate(ADDRESS);
            MemorySegment countOut = call.allocate(JAVA_INT);
            int found = (int) GTK_CLIPBOARD_WAIT_FOR_TARGETS.invokeExact(getClipboard(), targetsOut, countOut);
            MemorySegment targets = targetsOut.get(ADDRESS, 0);
            int ntargets = countOut.get(JAVA_INT, 0);
            try {
                return clipboardMimes(call, targets, ntargets);
            } finally {
                // the C freed the target array on every path out of mimesFromSystem
                G_FREE_CALL.invokeExact(targets);
            }
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static String[] clipboardMimes(Arena call, MemorySegment targets, int ntargets) throws Throwable {
        if (ntargets == 0) {
            return null;
        }
        MemorySegment atoms = bounded(targets, (long) ntargets * JAVA_LONG.byteSize());
        long[] convertible = new long[ntargets * 2];
        int count = 0;
        boolean uriListAdded = false;
        boolean textAdded = false;
        boolean imageAdded = false;
        MemorySegment one = call.allocate(JAVA_LONG);
        for (int i = 0; i < ntargets; i++) {
            long target = atoms.getAtIndex(JAVA_LONG, i);
            one.set(JAVA_LONG, 0, target);
            if ((int) GTK_TARGETS_INCLUDE_TEXT.invokeExact(one, 1) != 0 && !textAdded) {
                convertible[count++] = mimeTextPlainTarget;
                textAdded = true;
            } else if ((int) GTK_TARGETS_INCLUDE_IMAGE.invokeExact(one, 1, 1) != 0 && !imageAdded) {
                convertible[count++] = mimeJavaImage;
                imageAdded = true;
            }
            if (target == mimeTextUriListTarget) {
                if (uriListAdded) {
                    continue;
                }
                MemorySegment uris = clipboardUris();
                if (uris.address() != 0) {
                    try {
                        int size = (int) G_STRV_LENGTH.invokeExact(uris);
                        MemorySegment list = bounded(uris, ADDRESS.byteSize() * ((long) size + 1));
                        int filesCount = 0;
                        for (int j = 0; j < size; j++) {
                            if (cStringStartsWith(cString(list.getAtIndex(ADDRESS, j)), FILE_PREFIX)) {
                                filesCount++;
                            }
                        }
                        if (filesCount != 0) {
                            convertible[count++] = mimeFilesTarget;
                        }
                        if (size - filesCount != 0) {
                            convertible[count++] = mimeTextUriListTarget;
                        }
                    } finally {
                        G_STRFREEV.invokeExact(uris);
                    }
                }
                uriListAdded = true;
            } else {
                convertible[count++] = target;
            }
        }
        String[] result = new String[count];
        for (int i = 0; i < count; i++) {
            MemorySegment name = (MemorySegment) GDK_ATOM_NAME.invokeExact(convertible[i]);
            try {
                result[i] = newStringUtf(name);
            } finally {
                G_FREE_CALL.invokeExact(name);
            }
        }
        return result;
    }

    /**
     * {@code getUTF} ({@code GlassSystemClipboard.cpp}): NUL-terminated standard UTF-8, so a lone surrogate becomes
     * {@code ?} and an embedded U+0000 stays a NUL byte inside the buffer - which every C reader of it stops at.
     */
    static byte[] getUtf(String text) {
        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        return Arrays.copyOf(utf8, utf8.length + 1);
    }

    /**
     * {@code strlen} of a byte buffer: the bytes before its first NUL. {@link #getUtf} keeps the terminator,
     * {@link GtkApplicationCalls#cString} has already cut it off, so the end of the array ends the string too.
     */
    static int cStringLength(byte[] text) {
        int length = 0;
        while (length < text.length && text[length] != 0) {
            length++;
        }
        return length;
    }

    /** {@code g_strcmp0(text, literal) == 0} for an ASCII literal. */
    static boolean cStringEquals(byte[] text, String literal) {
        int length = cStringLength(text);
        if (length != literal.length()) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            if ((text[i] & 0xFF) != literal.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /** {@code g_str_has_prefix(text, prefix)} for an ASCII prefix. */
    private static boolean cStringStartsWith(byte[] text, String prefix) {
        if (cStringLength(text) < prefix.length()) {
            return false;
        }
        for (int i = 0; i < prefix.length(); i++) {
            if ((text[i] & 0xFF) != prefix.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private GtkClipboardReadCalls() {
    }
}
