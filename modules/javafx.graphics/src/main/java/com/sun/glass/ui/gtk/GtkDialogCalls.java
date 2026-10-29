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

import com.sun.glass.ui.CommonDialogs;
import com.sun.javafx.font.JniStringCodec;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.Charset;
import static com.sun.glass.ui.gtk.GtkApplicationCalls.cString;
import static com.sun.glass.ui.gtk.GtkGlassNative.GDK;
import static com.sun.glass.ui.gtk.GtkGlassNative.GLIB;
import static com.sun.glass.ui.gtk.GtkGlassNative.GTK;
import static com.sun.glass.ui.gtk.GtkGlassNative.LIB_GDK;
import static com.sun.glass.ui.gtk.GtkGlassNative.LIB_GLIB;
import static com.sun.glass.ui.gtk.GtkGlassNative.LIB_GTK;
import static com.sun.glass.ui.gtk.GtkGlassNative.bind;
import static com.sun.glass.ui.gtk.GtkGlassNative.bounded;
import static com.sun.glass.ui.gtk.GtkGlassNative.reportException;
import static com.sun.glass.ui.gtk.GtkGlassNative.unexpected;
import static com.sun.glass.ui.gtk.GtkPixelsCalls.GDK_DISPLAY_GET_DEFAULT;
import static com.sun.glass.ui.gtk.GtkPixelsCalls.G_OBJECT_UNREF;
import static com.sun.glass.ui.gtk.GtkRobotCalls.G_FREE_CALL;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The common dialogs of the Linux glass: {@code GtkCommonDialogs._showFileChooser} and {@code _showFolderChooser}
 * ({@code GlassCommonDialogs.cpp} at commit {@code 033187ad90}).
 */
final class GtkDialogCalls {

    /** {@code GTK_FILE_CHOOSER_ACTION_OPEN}, {@code _SAVE} and {@code _SELECT_FOLDER} of {@code gtkfilechooser.h}. */
    private static final int GTK_FILE_CHOOSER_ACTION_OPEN = 0;

    private static final int GTK_FILE_CHOOSER_ACTION_SAVE = 1;

    private static final int GTK_FILE_CHOOSER_ACTION_SELECT_FOLDER = 2;

    /** {@code GTK_RESPONSE_ACCEPT} of {@code gtkdialog.h}. */
    private static final int GTK_RESPONSE_ACCEPT = -3;

    /** {@code GSList}: {@code gpointer data; GSList *next;}. */
    private static final long GSLIST_SIZE = 16;

    /**
     * {@code GtkFileChooserNative *gtk_file_chooser_native_new(const gchar *title, GtkWindow *parent,
     * GtkFileChooserAction action, const gchar *accept_label, const gchar *cancel_label)}.
     */
    private static final MethodHandle GTK_FILE_CHOOSER_NATIVE_NEW = bind(GTK, LIB_GTK,
            "gtk_file_chooser_native_new", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS,
                    ADDRESS));

    /** {@code void gtk_file_chooser_set_current_name(GtkFileChooser *, const gchar *name)}. */
    private static final MethodHandle GTK_FILE_CHOOSER_SET_CURRENT_NAME = bind(GTK, LIB_GTK,
            "gtk_file_chooser_set_current_name", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));

    /** {@code void gtk_file_chooser_set_do_overwrite_confirmation(GtkFileChooser *, gboolean)}. */
    private static final MethodHandle GTK_FILE_CHOOSER_SET_DO_OVERWRITE_CONFIRMATION = bind(GTK, LIB_GTK,
            "gtk_file_chooser_set_do_overwrite_confirmation", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));

    /** {@code void gtk_file_chooser_set_select_multiple(GtkFileChooser *, gboolean)}. */
    private static final MethodHandle GTK_FILE_CHOOSER_SET_SELECT_MULTIPLE = bind(GTK, LIB_GTK,
            "gtk_file_chooser_set_select_multiple", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));

    /** {@code gboolean gtk_file_chooser_set_current_folder(GtkFileChooser *, const gchar *filename)}. */
    private static final MethodHandle GTK_FILE_CHOOSER_SET_CURRENT_FOLDER = bind(GTK, LIB_GTK,
            "gtk_file_chooser_set_current_folder", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    /** {@code void gtk_file_chooser_add_filter(GtkFileChooser *, GtkFileFilter *)}. */
    private static final MethodHandle GTK_FILE_CHOOSER_ADD_FILTER = bind(GTK, LIB_GTK,
            "gtk_file_chooser_add_filter", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));

    /** {@code void gtk_file_chooser_set_filter(GtkFileChooser *, GtkFileFilter *)}. */
    private static final MethodHandle GTK_FILE_CHOOSER_SET_FILTER = bind(GTK, LIB_GTK,
            "gtk_file_chooser_set_filter", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));

    /** {@code GtkFileFilter *gtk_file_chooser_get_filter(GtkFileChooser *)}. */
    private static final MethodHandle GTK_FILE_CHOOSER_GET_FILTER = bind(GTK, LIB_GTK,
            "gtk_file_chooser_get_filter", FunctionDescriptor.of(ADDRESS, ADDRESS));

    /** {@code GSList *gtk_file_chooser_get_filenames(GtkFileChooser *)}. */
    private static final MethodHandle GTK_FILE_CHOOSER_GET_FILENAMES = bind(GTK, LIB_GTK,
            "gtk_file_chooser_get_filenames", FunctionDescriptor.of(ADDRESS, ADDRESS));

    /** {@code gchar *gtk_file_chooser_get_filename(GtkFileChooser *)}. */
    private static final MethodHandle GTK_FILE_CHOOSER_GET_FILENAME = bind(GTK, LIB_GTK,
            "gtk_file_chooser_get_filename", FunctionDescriptor.of(ADDRESS, ADDRESS));

    /** {@code GtkFileFilter *gtk_file_filter_new(void)}. */
    private static final MethodHandle GTK_FILE_FILTER_NEW = bind(GTK, LIB_GTK, "gtk_file_filter_new",
            FunctionDescriptor.of(ADDRESS));

    /** {@code void gtk_file_filter_set_name(GtkFileFilter *, const gchar *name)}. */
    private static final MethodHandle GTK_FILE_FILTER_SET_NAME = bind(GTK, LIB_GTK, "gtk_file_filter_set_name",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));

    /** {@code void gtk_file_filter_add_pattern(GtkFileFilter *, const gchar *pattern)}. */
    private static final MethodHandle GTK_FILE_FILTER_ADD_PATTERN = bind(GTK, LIB_GTK,
            "gtk_file_filter_add_pattern", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));

    /** {@code gint gtk_native_dialog_run(GtkNativeDialog *)}: shows the dialog and runs a nested main loop. */
    private static final MethodHandle GTK_NATIVE_DIALOG_RUN = bind(GTK, LIB_GTK, "gtk_native_dialog_run",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** {@code guint g_slist_length(GSList *)}. */
    private static final MethodHandle G_SLIST_LENGTH = bind(GLIB, LIB_GLIB, "g_slist_length",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** {@code GSList *g_slist_nth(GSList *, guint n)}. */
    private static final MethodHandle G_SLIST_NTH = bind(GLIB, LIB_GLIB, "g_slist_nth",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));

    /** {@code GSList *g_slist_append(GSList *, gpointer data)}. */
    private static final MethodHandle G_SLIST_APPEND = bind(GLIB, LIB_GLIB, "g_slist_append",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));

    /** {@code gint g_slist_index(GSList *, gconstpointer data)}. */
    private static final MethodHandle G_SLIST_INDEX = bind(GLIB, LIB_GLIB, "g_slist_index",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    /** {@code void g_slist_free(GSList *)}. */
    private static final MethodHandle G_SLIST_FREE = bind(GLIB, LIB_GLIB, "g_slist_free",
            FunctionDescriptor.ofVoid(ADDRESS));

    /** {@code GdkWindow *gdk_x11_window_lookup_for_display(GdkDisplay *, Window)}. */
    private static final MethodHandle GDK_X11_WINDOW_LOOKUP_FOR_DISPLAY = bind(GDK, LIB_GDK,
            "gdk_x11_window_lookup_for_display", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG));

    /** {@code void gdk_window_get_user_data(GdkWindow *, gpointer *data)}. */
    private static final MethodHandle GDK_WINDOW_GET_USER_DATA = bind(GDK, LIB_GDK, "gdk_window_get_user_data",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));

    /** {@code GtkWidget *gtk_widget_get_toplevel(GtkWidget *)}. */
    private static final MethodHandle GTK_WIDGET_GET_TOPLEVEL = bind(GTK, LIB_GTK, "gtk_widget_get_toplevel",
            FunctionDescriptor.of(ADDRESS, ADDRESS));

    /**
     * {@code CommonDialogs.ExtensionFilter.extensionsToArray}, which is private and documented as "called from
     * native" - JNI never saw access control. Reached the same way {@link GtkPixelsCalls#PIXELS_ATTACH_DATA} is.
     */
    private static final MethodHandle EXTENSIONS_TO_ARRAY = extensionsToArray();

    private static MethodHandle extensionsToArray() {
        try {
            return MethodHandles.privateLookupIn(CommonDialogs.ExtensionFilter.class, MethodHandles.lookup())
                    .findVirtual(CommonDialogs.ExtensionFilter.class, "extensionsToArray",
                            MethodType.methodType(String[].class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * {@code Java_com_sun_glass_ui_gtk_GtkCommonDialogs__1showFileChooser} ({@code GlassCommonDialogs.cpp}): a
     * {@code GtkFileChooserNative} run in a nested main loop, and the files it was left with.
     * <p>
     * All four strings enter GTK as the modified UTF-8 that {@code GetStringUTFChars} produced. The chosen file
     * names come back as {@code new String(bytes)} with the default charset, which is what the C built them with,
     * and the selected filter as the index of the {@code GtkFileFilter} in the list that was added.
     *
     * @param parent the X11 window id of the owner, or 0
     */
    static CommonDialogs.FileChooserResult showFileChooser(long parent, String folder, String name, String title,
                                                           int type, boolean multiple,
                                                           CommonDialogs.ExtensionFilter[] filters,
                                                           int defaultFilterIndex) {
        try (Arena call = Arena.ofConfined()) {
            int action = type == 0 ? GTK_FILE_CHOOSER_ACTION_OPEN : GTK_FILE_CHOOSER_ACTION_SAVE;
            MemorySegment chooser = (MemorySegment) GTK_FILE_CHOOSER_NATIVE_NEW.invokeExact(
                    modifiedUtf8(call, title), parentWindow(parent), action, MemorySegment.NULL,
                    MemorySegment.NULL);
            MemorySegment filterList = MemorySegment.NULL;
            try {
                if (action == GTK_FILE_CHOOSER_ACTION_SAVE) {
                    GTK_FILE_CHOOSER_SET_CURRENT_NAME.invokeExact(chooser, modifiedUtf8(call, name));
                    GTK_FILE_CHOOSER_SET_DO_OVERWRITE_CONFIRMATION.invokeExact(chooser, 1);
                }
                GTK_FILE_CHOOSER_SET_SELECT_MULTIPLE.invokeExact(chooser, multiple ? 1 : 0);
                int folderSet = (int) GTK_FILE_CHOOSER_SET_CURRENT_FOLDER.invokeExact(chooser,
                        modifiedUtf8(call, folder));
                filterList = setupFileFilters(call, chooser, filters, defaultFilterIndex);

                String[] fileNames = null;
                if ((int) GTK_NATIVE_DIALOG_RUN.invokeExact(chooser) == GTK_RESPONSE_ACCEPT) {
                    MemorySegment names = (MemorySegment) GTK_FILE_CHOOSER_GET_FILENAMES.invokeExact(chooser);
                    int count = (int) G_SLIST_LENGTH.invokeExact(names);
                    if (count > 0) {
                        fileNames = new String[count];
                        for (int i = 0; i < count; i++) {
                            MemorySegment node = (MemorySegment) G_SLIST_NTH.invokeExact(names, i);
                            MemorySegment file = bounded(node, GSLIST_SIZE).get(ADDRESS, 0);
                            fileNames[i] = new String(cString(file), Charset.defaultCharset());
                        }
                        for (int i = 0; i < count; i++) {
                            MemorySegment node = (MemorySegment) G_SLIST_NTH.invokeExact(names, i);
                            G_FREE_CALL.invokeExact(bounded(node, GSLIST_SIZE).get(ADDRESS, 0));
                        }
                        G_SLIST_FREE.invokeExact(names);
                    }
                }
                if (fileNames == null) {
                    fileNames = new String[0];
                }
                MemorySegment selected = (MemorySegment) GTK_FILE_CHOOSER_GET_FILTER.invokeExact(chooser);
                int index = (int) G_SLIST_INDEX.invokeExact(filterList, selected);
                try {
                    return CommonDialogs.createFileChooserResult(fileNames, filters, index);
                } catch (Throwable t) {
                    // LOG_EXCEPTION: the C reported it and returned the NULL that CallStaticObjectMethod left
                    reportException(t);
                    return null;
                }
            } finally {
                // freed on every path out, as the C freed them: gtk_native_dialog_run dispatches events, whose calls
                // into Java go through the upcall stubs
                G_SLIST_FREE.invokeExact(filterList);
                G_OBJECT_UNREF.invokeExact(chooser);
            }
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code Java_com_sun_glass_ui_gtk_GtkCommonDialogs__1showFolderChooser} ({@code GlassCommonDialogs.cpp}): the
     * chosen folder, decoded as {@code NewStringUTF} decoded it - which is not how {@code _showFileChooser} decoded
     * its file names.
     *
     * @param parent the X11 window id of the owner, or 0
     */
    static String showFolderChooser(long parent, String folder, String title) {
        try (Arena call = Arena.ofConfined()) {
            MemorySegment chooser = (MemorySegment) GTK_FILE_CHOOSER_NATIVE_NEW.invokeExact(
                    modifiedUtf8(call, title), parentWindow(parent), GTK_FILE_CHOOSER_ACTION_SELECT_FOLDER,
                    MemorySegment.NULL, MemorySegment.NULL);
            try {
                if (folder != null) {
                    int set = (int) GTK_FILE_CHOOSER_SET_CURRENT_FOLDER.invokeExact(chooser,
                            modifiedUtf8(call, folder));
                }
                String result = null;
                if ((int) GTK_NATIVE_DIALOG_RUN.invokeExact(chooser) == GTK_RESPONSE_ACCEPT) {
                    MemorySegment name = (MemorySegment) GTK_FILE_CHOOSER_GET_FILENAME.invokeExact(chooser);
                    try {
                        result = name.address() == 0 ? null : JniStringCodec.fromNewStringUtf(cString(name));
                    } finally {
                        G_FREE_CALL.invokeExact(name);
                    }
                }
                return result;
            } finally {
                G_OBJECT_UNREF.invokeExact(chooser);
            }
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code setup_GtkFileFilters} ({@code GlassCommonDialogs.cpp}): the filters, in the order they were added, as
     * the {@code GSList} whose index {@code showFileChooser} answers.
     */
    private static MemorySegment setupFileFilters(Arena call, MemorySegment chooser,
                                                  CommonDialogs.ExtensionFilter[] filters, int defaultFilterIndex)
            throws Throwable {
        if (filters.length == 0) {
            return MemorySegment.NULL;
        }
        MemorySegment list = MemorySegment.NULL;
        for (int i = 0; i < filters.length; i++) {
            MemorySegment filter = (MemorySegment) GTK_FILE_FILTER_NEW.invokeExact();
            GTK_FILE_FILTER_SET_NAME.invokeExact(filter, modifiedUtf8(call, filters[i].getDescription()));
            for (String extension : (String[]) EXTENSIONS_TO_ARRAY.invokeExact(filters[i])) {
                GTK_FILE_FILTER_ADD_PATTERN.invokeExact(filter, modifiedUtf8(call, extension));
            }
            GTK_FILE_CHOOSER_ADD_FILTER.invokeExact(chooser, filter);
            if (defaultFilterIndex == i) {
                GTK_FILE_CHOOSER_SET_FILTER.invokeExact(chooser, filter);
            }
            list = (MemorySegment) G_SLIST_APPEND.invokeExact(list, filter);
        }
        return list;
    }

    /**
     * The {@code GtkWindow} of the owner, which the C read from its {@code WindowContext} - here reached from the
     * X11 window id through GDK. An owner whose {@code GdkWindow} does not exist yet has the id 0 and gives no
     * parent, where the C still had its unrealized {@code GtkWindow}: the dialog is then not transient for it.
     */
    private static MemorySegment parentWindow(long xid) throws Throwable {
        if (xid == 0) {
            return MemorySegment.NULL;
        }
        MemorySegment display = (MemorySegment) GDK_DISPLAY_GET_DEFAULT.invokeExact();
        MemorySegment window = (MemorySegment) GDK_X11_WINDOW_LOOKUP_FOR_DISPLAY.invokeExact(display, xid);
        if (window.address() == 0) {
            return MemorySegment.NULL;
        }
        try (Arena call = Arena.ofConfined()) {
            MemorySegment out = call.allocate(ADDRESS);
            GDK_WINDOW_GET_USER_DATA.invokeExact(window, out);
            MemorySegment widget = out.get(ADDRESS, 0);
            if (widget.address() == 0) {
                return MemorySegment.NULL;
            }
            return (MemorySegment) GTK_WIDGET_GET_TOPLEVEL.invokeExact(widget);
        }
    }

    /**
     * {@code GetStringUTFChars}: the modified UTF-8 bytes of {@code text}, NUL-terminated, or {@code NULL} for a
     * {@code null} string, which is what {@code jstring_to_utf_get} left the pointer at.
     */
    static MemorySegment modifiedUtf8(Arena call, String text) {
        return text == null ? MemorySegment.NULL : call.allocateFrom(JAVA_BYTE, JniStringCodec.toModifiedUtf8(text));
    }

    private GtkDialogCalls() {
    }
}
