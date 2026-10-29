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

import com.sun.glass.ui.Clipboard;
import com.sun.glass.ui.Pixels;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import static com.sun.glass.ui.gtk.GtkApplicationCalls.newStringUtf;
import static com.sun.glass.ui.gtk.GtkClipboardReadCalls.cStringEquals;
import static com.sun.glass.ui.gtk.GtkClipboardReadCalls.cStringLength;
import static com.sun.glass.ui.gtk.GtkClipboardReadCalls.getUtf;
import static com.sun.glass.ui.gtk.GtkGlassNative.GDK;
import static com.sun.glass.ui.gtk.GtkGlassNative.GDK_PIXBUF;
import static com.sun.glass.ui.gtk.GtkGlassNative.GLIB;
import static com.sun.glass.ui.gtk.GtkGlassNative.GTK;
import static com.sun.glass.ui.gtk.GtkGlassNative.LIB_GDK;
import static com.sun.glass.ui.gtk.GtkGlassNative.LIB_GDK_PIXBUF;
import static com.sun.glass.ui.gtk.GtkGlassNative.LIB_GLIB;
import static com.sun.glass.ui.gtk.GtkGlassNative.LIB_GTK;
import static com.sun.glass.ui.gtk.GtkGlassNative.bind;
import static com.sun.glass.ui.gtk.GtkGlassNative.reportException;
import static com.sun.glass.ui.gtk.GtkGlassNative.unexpected;
import static com.sun.glass.ui.gtk.GtkGlassNative.upcallStub;
import static com.sun.glass.ui.gtk.GtkPixelsCalls.G_OBJECT_UNREF;
import static com.sun.glass.ui.gtk.GtkPixelsCalls.PIXELS_ATTACH_DATA;
import static com.sun.glass.ui.gtk.GtkPreferenceCalls.G_SIGNAL_CONNECT_DATA;
import static com.sun.glass.ui.gtk.GtkPreferenceCalls.G_SIGNAL_HANDLER_DISCONNECT;
import static com.sun.glass.ui.gtk.GtkRobotCalls.G_FREE_CALL;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The system clipboard of the Linux glass as its owner: {@code GtkSystemClipboard} init, dispose, isOwner and
 * pushToSystem ({@code GlassSystemClipboard.cpp} at commit {@code 033187ad90}), and the GTK callbacks that serve and
 * clear what it pushed. Every clipboard handle is declared here, in the binding's order ({@link GtkGlassNative#link});
 * {@link GtkClipboardReadCalls} reads the clipboard with them.
 */
final class GtkClipboardCalls {

    /**
     * {@code GDK_SELECTION_CLIPBOARD}, which {@code gdkselection.h} defines as
     * {@code (GdkAtom) GUINT_TO_POINTER(69)}. A {@code GdkAtom} is an opaque pointer; every one of them is passed
     * here as a {@code long}, which is how the System V and AAPCS64 ABIs pass a pointer argument.
     */
    private static final long GDK_SELECTION_CLIPBOARD = 69;

    /** {@code GtkTargetEntry}: {@code gchar *target; guint flags; guint info;}. */
    private static final long GTK_TARGET_ENTRY_SIZE = 16;

    /** {@code FILE_PREFIX} and the two {@code URI_LIST_*} below are the constants of {@code glass_general.h}. */
    static final String FILE_PREFIX = "file://";

    static final String URI_LIST_COMMENT_PREFIX = "#";

    static final String URI_LIST_LINE_BREAK = "\r\n";

    /** {@code GtkClipboard *gtk_clipboard_get(GdkAtom selection)}. */
    private static final MethodHandle GTK_CLIPBOARD_GET = bind(GTK, LIB_GTK, "gtk_clipboard_get",
            FunctionDescriptor.of(ADDRESS, JAVA_LONG));

    /** {@code GdkAtom gdk_atom_intern_static_string(const gchar *atom_name)}. */
    private static final MethodHandle GDK_ATOM_INTERN_STATIC_STRING = bind(GDK, LIB_GDK,
            "gdk_atom_intern_static_string", FunctionDescriptor.of(JAVA_LONG, ADDRESS));

    /** {@code GdkAtom gdk_atom_intern(const gchar *atom_name, gboolean only_if_exists)}. */
    static final MethodHandle GDK_ATOM_INTERN = bind(GDK, LIB_GDK, "gdk_atom_intern",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT));

    /** {@code gchar *gdk_atom_name(GdkAtom atom)}. */
    static final MethodHandle GDK_ATOM_NAME = bind(GDK, LIB_GDK, "gdk_atom_name",
            FunctionDescriptor.of(ADDRESS, JAVA_LONG));

    /** {@code GtkTargetList *gtk_target_list_new(const GtkTargetEntry *targets, guint ntargets)}. */
    private static final MethodHandle GTK_TARGET_LIST_NEW = bind(GTK, LIB_GTK, "gtk_target_list_new",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));

    /** {@code void gtk_target_list_add(GtkTargetList *list, GdkAtom target, guint flags, guint info)}. */
    private static final MethodHandle GTK_TARGET_LIST_ADD = bind(GTK, LIB_GTK, "gtk_target_list_add",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, JAVA_INT, JAVA_INT));

    /** {@code void gtk_target_list_add_text_targets(GtkTargetList *list, guint info)}. */
    private static final MethodHandle GTK_TARGET_LIST_ADD_TEXT_TARGETS = bind(GTK, LIB_GTK,
            "gtk_target_list_add_text_targets", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));

    /** {@code void gtk_target_list_add_image_targets(GtkTargetList *list, guint info, gboolean writable)}. */
    private static final MethodHandle GTK_TARGET_LIST_ADD_IMAGE_TARGETS = bind(GTK, LIB_GTK,
            "gtk_target_list_add_image_targets", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT));

    /** {@code void gtk_target_list_unref(GtkTargetList *list)}. */
    private static final MethodHandle GTK_TARGET_LIST_UNREF = bind(GTK, LIB_GTK, "gtk_target_list_unref",
            FunctionDescriptor.ofVoid(ADDRESS));

    /**
     * {@code GtkTargetEntry *gtk_target_table_new_from_list(GtkTargetList *list, gint *n_targets)}; an empty list
     * answers {@code NULL}, which is what the C read as "clear the clipboard".
     */
    private static final MethodHandle GTK_TARGET_TABLE_NEW_FROM_LIST = bind(GTK, LIB_GTK,
            "gtk_target_table_new_from_list", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));

    /** {@code void gtk_target_table_free(GtkTargetEntry *targets, gint n_targets)}. */
    private static final MethodHandle GTK_TARGET_TABLE_FREE = bind(GTK, LIB_GTK, "gtk_target_table_free",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));

    /**
     * {@code gboolean gtk_clipboard_set_with_data(GtkClipboard *, const GtkTargetEntry *, guint n_targets,
     * GtkClipboardGetFunc, GtkClipboardClearFunc, gpointer user_data)}; the C ignored the result.
     */
    private static final MethodHandle GTK_CLIPBOARD_SET_WITH_DATA = bind(GTK, LIB_GTK,
            "gtk_clipboard_set_with_data", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS,
                    ADDRESS, ADDRESS));

    /** {@code gchar *gtk_clipboard_wait_for_text(GtkClipboard *)}; iterates the main loop. */
    static final MethodHandle GTK_CLIPBOARD_WAIT_FOR_TEXT = bind(GTK, LIB_GTK,
            "gtk_clipboard_wait_for_text", FunctionDescriptor.of(ADDRESS, ADDRESS));

    /** {@code gchar **gtk_clipboard_wait_for_uris(GtkClipboard *)}; iterates the main loop. */
    static final MethodHandle GTK_CLIPBOARD_WAIT_FOR_URIS = bind(GTK, LIB_GTK,
            "gtk_clipboard_wait_for_uris", FunctionDescriptor.of(ADDRESS, ADDRESS));

    /**
     * {@code GtkSelectionData *gtk_clipboard_wait_for_contents(GtkClipboard *, GdkAtom target)}; iterates the
     * main loop.
     */
    static final MethodHandle GTK_CLIPBOARD_WAIT_FOR_CONTENTS = bind(GTK, LIB_GTK,
            "gtk_clipboard_wait_for_contents", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG));

    /** {@code GdkPixbuf *gtk_clipboard_wait_for_image(GtkClipboard *)}; iterates the main loop. */
    static final MethodHandle GTK_CLIPBOARD_WAIT_FOR_IMAGE = bind(GTK, LIB_GTK,
            "gtk_clipboard_wait_for_image", FunctionDescriptor.of(ADDRESS, ADDRESS));

    /**
     * {@code gboolean gtk_clipboard_wait_for_targets(GtkClipboard *, GdkAtom **targets, gint *n_targets)};
     * iterates the main loop, and writes {@code NULL} and 0 when there are none.
     */
    static final MethodHandle GTK_CLIPBOARD_WAIT_FOR_TARGETS = bind(GTK, LIB_GTK,
            "gtk_clipboard_wait_for_targets", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));

    /** {@code gboolean gtk_targets_include_text(GdkAtom *targets, gint n_targets)}. */
    static final MethodHandle GTK_TARGETS_INCLUDE_TEXT = bind(GTK, LIB_GTK, "gtk_targets_include_text",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));

    /** {@code gboolean gtk_targets_include_image(GdkAtom *targets, gint n_targets, gboolean writable)}. */
    static final MethodHandle GTK_TARGETS_INCLUDE_IMAGE = bind(GTK, LIB_GTK, "gtk_targets_include_image",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));

    /** {@code GdkAtom gtk_selection_data_get_target(const GtkSelectionData *)}. */
    private static final MethodHandle GTK_SELECTION_DATA_GET_TARGET = bind(GTK, LIB_GTK,
            "gtk_selection_data_get_target", FunctionDescriptor.of(JAVA_LONG, ADDRESS));

    /** {@code gint gtk_selection_data_get_length(const GtkSelectionData *)}. */
    static final MethodHandle GTK_SELECTION_DATA_GET_LENGTH = bind(GTK, LIB_GTK,
            "gtk_selection_data_get_length", FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** {@code const guchar *gtk_selection_data_get_data(const GtkSelectionData *)}. */
    static final MethodHandle GTK_SELECTION_DATA_GET_DATA = bind(GTK, LIB_GTK,
            "gtk_selection_data_get_data", FunctionDescriptor.of(ADDRESS, ADDRESS));

    /** {@code void gtk_selection_data_free(GtkSelectionData *)}. */
    static final MethodHandle GTK_SELECTION_DATA_FREE = bind(GTK, LIB_GTK, "gtk_selection_data_free",
            FunctionDescriptor.ofVoid(ADDRESS));

    /** {@code gboolean gtk_selection_data_set_text(GtkSelectionData *, const gchar *str, gint len)}. */
    private static final MethodHandle GTK_SELECTION_DATA_SET_TEXT = bind(GTK, LIB_GTK,
            "gtk_selection_data_set_text", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));

    /**
     * {@code void gtk_selection_data_set(GtkSelectionData *, GdkAtom type, gint format, const guchar *data,
     * gint length)}.
     */
    private static final MethodHandle GTK_SELECTION_DATA_SET = bind(GTK, LIB_GTK, "gtk_selection_data_set",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, JAVA_INT, ADDRESS, JAVA_INT));

    /** {@code gboolean gtk_selection_data_set_uris(GtkSelectionData *, gchar **uris)}. */
    private static final MethodHandle GTK_SELECTION_DATA_SET_URIS = bind(GTK, LIB_GTK,
            "gtk_selection_data_set_uris", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    /** {@code gboolean gtk_selection_data_set_pixbuf(GtkSelectionData *, GdkPixbuf *)}. */
    private static final MethodHandle GTK_SELECTION_DATA_SET_PIXBUF = bind(GTK, LIB_GTK,
            "gtk_selection_data_set_pixbuf", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    /** {@code gboolean gdk_pixbuf_get_has_alpha(const GdkPixbuf *)}. */
    static final MethodHandle GDK_PIXBUF_GET_HAS_ALPHA = bind(GDK_PIXBUF, LIB_GDK_PIXBUF,
            "gdk_pixbuf_get_has_alpha", FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** {@code int gdk_pixbuf_get_width(const GdkPixbuf *)}. */
    static final MethodHandle GDK_PIXBUF_GET_WIDTH = bind(GDK_PIXBUF, LIB_GDK_PIXBUF,
            "gdk_pixbuf_get_width", FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** {@code int gdk_pixbuf_get_height(const GdkPixbuf *)}. */
    static final MethodHandle GDK_PIXBUF_GET_HEIGHT = bind(GDK_PIXBUF, LIB_GDK_PIXBUF,
            "gdk_pixbuf_get_height", FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** {@code int gdk_pixbuf_get_rowstride(const GdkPixbuf *)}. */
    static final MethodHandle GDK_PIXBUF_GET_ROWSTRIDE = bind(GDK_PIXBUF, LIB_GDK_PIXBUF,
            "gdk_pixbuf_get_rowstride", FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** {@code guint g_strv_length(gchar **str_array)}. */
    static final MethodHandle G_STRV_LENGTH = bind(GLIB, LIB_GLIB, "g_strv_length",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** {@code void g_strfreev(gchar **str_array)}. */
    static final MethodHandle G_STRFREEV = bind(GLIB, LIB_GLIB, "g_strfreev",
            FunctionDescriptor.ofVoid(ADDRESS));

    /** {@code gchar *g_filename_from_uri(const gchar *uri, gchar **hostname, GError **error)}. */
    static final MethodHandle G_FILENAME_FROM_URI = bind(GLIB, LIB_GLIB, "g_filename_from_uri",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS));

    /** {@code gchar *g_filename_to_uri(const gchar *filename, const gchar *hostname, GError **error)}. */
    private static final MethodHandle G_FILENAME_TO_URI = bind(GLIB, LIB_GLIB, "g_filename_to_uri",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS));

    /** {@code void (*GtkClipboardOwnerChange)(GtkClipboard *, GdkEventOwnerChange *, gpointer)}. */
    private static final MemorySegment CLIPBOARD_OWNER_CHANGE_CALLBACK = upcallStub(GtkClipboardCalls.class,
            "onClipboardOwnerChanged",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));

    /** {@code void (*GtkClipboardGetFunc)(GtkClipboard *, GtkSelectionData *, guint info, gpointer user_data)}. */
    private static final MemorySegment CLIPBOARD_GET_CALLBACK = upcallStub(GtkClipboardCalls.class,
            "onClipboardGetData",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, ADDRESS));

    /** {@code void (*GtkClipboardClearFunc)(GtkClipboard *, gpointer user_data)}. */
    private static final MemorySegment CLIPBOARD_CLEAR_CALLBACK = upcallStub(GtkClipboardCalls.class,
            "onClipboardClearData",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));

    /**
     * The content maps handed to GTK, keyed by the id that stands in for the JNI global reference the C passed as
     * the {@code gpointer}. An entry lives exactly as long as the global reference did: from
     * {@link #clipboardPush} until GTK's clear callback.
     */
    private static final Map<Long, Map<String, Object>> CLIPBOARD_DATA = new ConcurrentHashMap<>();

    /**
     * The clipboard peers connected to the {@code owner-change} signal, keyed the same way; the C passed its
     * {@code jclipboard} global reference as the {@code gpointer} of {@code g_signal_connect}.
     */
    private static final Map<Long, Clipboard> CLIPBOARD_PEERS = new ConcurrentHashMap<>();

    private static final AtomicLong NEXT_CLIPBOARD_ID = new AtomicLong(1);

    private static MemorySegment clipboard;
    private static boolean isClipboardOwner;
    private static boolean isClipboardUpdatedByGlass;

    /** The id of the peer in {@link #CLIPBOARD_PEERS}, standing in for the {@code static jobject jclipboard}. */
    private static long clipboardPeerId;

    private static long ownerChangeHandlerId;
    private static boolean atomsInitialized;

    static long mimeTextPlainTarget;
    static long mimeTextUriListTarget;
    static long mimeJavaImage;
    static long mimeFilesTarget;

    /**
     * {@code init_atoms} ({@code GlassSystemClipboard.cpp}), less the {@code String} method ids and the
     * {@code "UTF-8"} global reference it cached for JNI.
     * <p>
     * {@code gdk_atom_intern_static_string} keeps the pointer it is given instead of copying the name, which is
     * why the C passed string literals; these names are therefore allocated in {@link Arena#global()} and live as
     * long as the process, exactly as the literals in {@code .rodata} did.
     */
    static void initAtoms() throws Throwable {
        if (atomsInitialized) {
            return;
        }
        Arena forever = Arena.global();
        mimeTextPlainTarget = (long) GDK_ATOM_INTERN_STATIC_STRING.invokeExact(forever.allocateFrom("text/plain"));
        mimeTextUriListTarget = (long) GDK_ATOM_INTERN_STATIC_STRING.invokeExact(
                forever.allocateFrom("text/uri-list"));
        mimeJavaImage = (long) GDK_ATOM_INTERN_STATIC_STRING.invokeExact(
                forever.allocateFrom("application/x-java-rawimage"));
        mimeFilesTarget = (long) GDK_ATOM_INTERN_STATIC_STRING.invokeExact(
                forever.allocateFrom("application/x-java-file-list"));
        atomsInitialized = true;
    }

    static MemorySegment getClipboard() throws Throwable {
        if (clipboard == null) {
            clipboard = (MemorySegment) GTK_CLIPBOARD_GET.invokeExact(GDK_SELECTION_CLIPBOARD);
        }
        return clipboard;
    }

    /**
     * {@code Java_com_sun_glass_ui_gtk_GtkSystemClipboard_init} ({@code GlassSystemClipboard.cpp}). A second
     * {@code init} overwrote the handler id and leaked the first reference, and its {@code ERROR0} message was
     * compiled out of a build without {@code VERBOSE} ({@code glass_general.h} at commit {@code 033187ad90}); the
     * first handler stayed connected to its own peer, which the registry reproduces.
     */
    static void clipboardInit(Clipboard peer) {
        try (Arena call = Arena.ofConfined()) {
            long id = NEXT_CLIPBOARD_ID.getAndIncrement();
            CLIPBOARD_PEERS.put(id, peer);
            clipboardPeerId = id;
            ownerChangeHandlerId = (long) G_SIGNAL_CONNECT_DATA.invokeExact(getClipboard(),
                    call.allocateFrom("owner-change"), CLIPBOARD_OWNER_CHANGE_CALLBACK, MemorySegment.ofAddress(id),
                    MemorySegment.NULL, 0);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code Java_com_sun_glass_ui_gtk_GtkSystemClipboard_dispose} ({@code GlassSystemClipboard.cpp}). */
    static void clipboardDispose() {
        try {
            G_SIGNAL_HANDLER_DISCONNECT.invokeExact(getClipboard(), ownerChangeHandlerId);
            CLIPBOARD_PEERS.remove(clipboardPeerId);
            ownerChangeHandlerId = 0;
            clipboardPeerId = 0;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code Java_com_sun_glass_ui_gtk_GtkSystemClipboard_isOwner} ({@code GlassSystemClipboard.cpp}). */
    static boolean clipboardIsOwner() {
        return isClipboardOwner;
    }

    /**
     * {@code clipboard_owner_changed_callback} ({@code GlassSystemClipboard.cpp}): this process owns the
     * clipboard exactly when the change was the one it made itself, and the peer is told either way.
     */
    static void onClipboardOwnerChanged(MemorySegment gtkClipboard, MemorySegment event, MemorySegment data) {
        isClipboardOwner = isClipboardUpdatedByGlass;
        isClipboardUpdatedByGlass = false;
        try {
            // the C called this through a global reference it never checked; a missing one crashed it
            CLIPBOARD_PEERS.get(data.address()).contentChanged();
        } catch (Throwable t) {
            reportException(t);
        }
    }

    /**
     * {@code Java_com_sun_glass_ui_gtk_GtkSystemClipboard_pushToSystem} ({@code GlassSystemClipboard.cpp}): keeps
     * {@code data} until GTK clears the clipboard. The {@code supportedActions} argument was unused.
     */
    static void clipboardPush(Map<String, Object> data) {
        try (Arena call = Arena.ofConfined()) {
            long id = NEXT_CLIPBOARD_ID.getAndIncrement();
            CLIPBOARD_DATA.put(id, data);
            initAtoms();
            MemorySegment count = call.allocate(JAVA_INT);
            MemorySegment targets = dataToTargets(call, data, count);
            if (targets.address() != 0) {
                int ntargets = count.get(JAVA_INT, 0);
                int set = (int) GTK_CLIPBOARD_SET_WITH_DATA.invokeExact(getClipboard(), targets, ntargets,
                        CLIPBOARD_GET_CALLBACK, CLIPBOARD_CLEAR_CALLBACK, MemorySegment.ofAddress(id));
                GTK_TARGET_TABLE_FREE.invokeExact(targets, ntargets);
            } else {
                // targets == NULL means that we want to clear the clipboard: passing NULL would make
                // gtk_clipboard_set_with_data print a Gtk-CRITICAL assertion, passing 0 as n_targets does not
                MemorySegment dummy = call.allocate(GTK_TARGET_ENTRY_SIZE);
                dummy.set(ADDRESS, 0, call.allocateFrom("MIME_DUMMY_TARGET"));
                dummy.set(JAVA_INT, 8, 0);
                dummy.set(JAVA_INT, 12, 0);
                int set = (int) GTK_CLIPBOARD_SET_WITH_DATA.invokeExact(getClipboard(), dummy, 0,
                        CLIPBOARD_GET_CALLBACK, CLIPBOARD_CLEAR_CALLBACK, MemorySegment.ofAddress(id));
            }
            isClipboardUpdatedByGlass = true;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code data_to_targets} and {@code add_target_from_jstring} ({@code GlassSystemClipboard.cpp}). The table is
     * GTK's; the caller frees it.
     */
    private static MemorySegment dataToTargets(Arena call, Map<String, Object> data, MemorySegment count)
            throws Throwable {
        MemorySegment list = (MemorySegment) GTK_TARGET_LIST_NEW.invokeExact(MemorySegment.NULL, 0);
        for (String key : data.keySet()) {
            byte[] mime = getUtf(key);
            if (cStringEquals(mime, "text/plain")) {
                GTK_TARGET_LIST_ADD_TEXT_TARGETS.invokeExact(list, 0);
            } else if (cStringEquals(mime, "application/x-java-rawimage")) {
                GTK_TARGET_LIST_ADD_IMAGE_TARGETS.invokeExact(list, 0, 1);
            } else if (cStringEquals(mime, "application/x-java-file-list")) {
                GTK_TARGET_LIST_ADD.invokeExact(list, mimeTextUriListTarget, 0, 0);
            } else {
                long atom = (long) GDK_ATOM_INTERN.invokeExact(call.allocateFrom(JAVA_BYTE, mime), 0);
                GTK_TARGET_LIST_ADD.invokeExact(list, atom, 0, 0);
            }
        }
        MemorySegment targets = (MemorySegment) GTK_TARGET_TABLE_NEW_FROM_LIST.invokeExact(list, count);
        GTK_TARGET_LIST_UNREF.invokeExact(list);
        return targets;
    }

    /**
     * {@code set_data_func} and {@code set_data} ({@code GlassSystemClipboard.cpp}): what this process serves when
     * another client asks for one of the targets it offered. Nothing may leave this method: it runs in an upcall
     * stub, and every step of the C reported and cleared its exceptions.
     */
    static void onClipboardGetData(MemorySegment gtkClipboard, MemorySegment selectionData, int info,
                                   MemorySegment user) {
        try (Arena call = Arena.ofConfined()) {
            Map<String, Object> data = CLIPBOARD_DATA.get(user.address());
            long target = (long) GTK_SELECTION_DATA_GET_TARGET.invokeExact(selectionData);
            MemorySegment one = call.allocate(JAVA_LONG);
            one.set(JAVA_LONG, 0, target);
            MemorySegment name = (MemorySegment) GDK_ATOM_NAME.invokeExact(target);
            try {
                if ((int) GTK_TARGETS_INCLUDE_TEXT.invokeExact(one, 1) != 0) {
                    Object result = data.get("text/plain");
                    if (result instanceof String text) {
                        setTextData(call, selectionData, text);
                    }
                } else if ((int) GTK_TARGETS_INCLUDE_IMAGE.invokeExact(one, 1, 1) != 0) {
                    Object result = data.get("application/x-java-rawimage");
                    if (result instanceof GtkPixels pixels) {
                        setImageData(call, selectionData, pixels);
                    }
                } else if (target == mimeTextUriListTarget) {
                    setUriData(call, selectionData, data);
                } else {
                    // NewStringUTF(NULL) answered NULL, and the C looked that key up as it stood
                    Object result = data.get(newStringUtf(name));
                    if (result instanceof String text) {
                        setStringData(call, selectionData, target, text);
                    } else if (result instanceof ByteBuffer buffer) {
                        setByteBufferData(call, selectionData, target, buffer);
                    }
                }
            } finally {
                // the C's set_data reached its g_free(name) whatever the helpers left behind
                G_FREE_CALL.invokeExact(name);
            }
        } catch (Throwable t) {
            reportException(t);
        }
    }

    /** {@code set_text_data}: the UTF-8 bytes up to their first NUL. */
    private static void setTextData(Arena call, MemorySegment selectionData, String text) throws Throwable {
        byte[] bytes = getUtf(text);
        int set = (int) GTK_SELECTION_DATA_SET_TEXT.invokeExact(selectionData, call.allocateFrom(JAVA_BYTE, bytes),
                cStringLength(bytes));
    }

    /** {@code set_jstring_data}: the same bytes, as the requested target with an 8-bit format. */
    private static void setStringData(Arena call, MemorySegment selectionData, long target, String text)
            throws Throwable {
        byte[] bytes = getUtf(text);
        GTK_SELECTION_DATA_SET.invokeExact(selectionData, target, 8, call.allocateFrom(JAVA_BYTE, bytes),
                cStringLength(bytes));
    }

    /** {@code set_bytebuffer_data}: the whole backing array, whatever the buffer's position and limit. */
    private static void setByteBufferData(Arena call, MemorySegment selectionData, long target, ByteBuffer buffer)
            throws Throwable {
        byte[] raw = buffer.array();
        GTK_SELECTION_DATA_SET.invokeExact(selectionData, target, 8, call.allocateFrom(JAVA_BYTE, raw), raw.length);
    }

    /** {@code set_image_data}: the {@code GdkPixbuf} the pixels attach to, unreferenced afterwards either way. */
    private static void setImageData(Arena call, MemorySegment selectionData, Pixels pixels) throws Throwable {
        MemorySegment slot = call.allocate(ADDRESS);
        boolean threw = false;
        try {
            PIXELS_ATTACH_DATA.invokeExact(pixels, slot.address());
        } catch (Throwable t) {
            reportException(t);
            threw = true;
        }
        MemorySegment pixbuf = slot.get(ADDRESS, 0);
        if (!threw) {
            int set = (int) GTK_SELECTION_DATA_SET_PIXBUF.invokeExact(selectionData, pixbuf);
        }
        G_OBJECT_UNREF.invokeExact(pixbuf);
    }

    /**
     * {@code set_uri_data} ({@code GlassSystemClipboard.cpp}). A file that is not a {@code String} leaves a hole in
     * the array, which GTK reads as its end.
     */
    private static void setUriData(Arena call, MemorySegment selectionData, Map<String, Object> data)
            throws Throwable {
        byte[] url = null;
        if (data.containsKey("text/uri-list")) {
            Object value = data.get("text/uri-list");
            if (value instanceof String text) {
                url = getUtf(text);
            }
        }
        Object[] files = null;
        int filesCount = 0;
        if (data.containsKey("application/x-java-file-list")) {
            Object value = data.get("application/x-java-file-list");
            if (value != null) {
                files = (Object[]) value;
                filesCount = files.length;
            }
        }
        if (url == null && filesCount == 0) {
            return;
        }
        int uriCount = filesCount + (url != null ? 1 : 0);
        // uris must be a NULL-terminated array of strings
        MemorySegment uris = call.allocate(ADDRESS, uriCount + 1);
        int i = 0;
        if (filesCount > 0) {
            for (; i < filesCount; i++) {
                if (files[i] instanceof String file) {
                    MemorySegment path = call.allocateFrom(JAVA_BYTE, getUtf(file));
                    MemorySegment uri = (MemorySegment) G_FILENAME_TO_URI.invokeExact(path, MemorySegment.NULL,
                            MemorySegment.NULL);
                    uris.setAtIndex(ADDRESS, i, uri);
                }
            }
        }
        if (url != null) {
            uris.setAtIndex(ADDRESS, i, call.allocateFrom(JAVA_BYTE, url));
        }
        // http://www.ietf.org/rfc/rfc2483.txt
        int set = (int) GTK_SELECTION_DATA_SET_URIS.invokeExact(selectionData, uris);
        for (int j = 0; j < filesCount; j++) {
            G_FREE_CALL.invokeExact(uris.getAtIndex(ADDRESS, j));
        }
    }

    /** {@code clear_data_func} ({@code GlassSystemClipboard.cpp}): GTK owns the clipboard no more. */
    static void onClipboardClearData(MemorySegment gtkClipboard, MemorySegment user) {
        try {
            CLIPBOARD_DATA.remove(user.address());
        } catch (Throwable t) {
            reportException(t);
        }
    }

    private GtkClipboardCalls() {
    }
}
