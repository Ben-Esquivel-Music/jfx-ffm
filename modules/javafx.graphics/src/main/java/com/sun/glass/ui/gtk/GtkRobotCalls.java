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

import com.sun.glass.ui.GlassRobot;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import static com.sun.glass.ui.gtk.GtkGlassNative.GDK;
import static com.sun.glass.ui.gtk.GtkGlassNative.GDK_PIXBUF;
import static com.sun.glass.ui.gtk.GtkGlassNative.GIO;
import static com.sun.glass.ui.gtk.GtkGlassNative.GLIB;
import static com.sun.glass.ui.gtk.GtkGlassNative.LIBC;
import static com.sun.glass.ui.gtk.GtkGlassNative.LIB_C;
import static com.sun.glass.ui.gtk.GtkGlassNative.LIB_GDK;
import static com.sun.glass.ui.gtk.GtkGlassNative.LIB_GDK_PIXBUF;
import static com.sun.glass.ui.gtk.GtkGlassNative.LIB_GIO;
import static com.sun.glass.ui.gtk.GtkGlassNative.LIB_GLIB;
import static com.sun.glass.ui.gtk.GtkGlassNative.LIB_X11;
import static com.sun.glass.ui.gtk.GtkGlassNative.LIB_XTST;
import static com.sun.glass.ui.gtk.GtkGlassNative.X11;
import static com.sun.glass.ui.gtk.GtkGlassNative.XTST;
import static com.sun.glass.ui.gtk.GtkGlassNative.address;
import static com.sun.glass.ui.gtk.GtkGlassNative.addresslessDowncall;
import static com.sun.glass.ui.gtk.GtkGlassNative.bind;
import static com.sun.glass.ui.gtk.GtkGlassNative.bounded;
import static com.sun.glass.ui.gtk.GtkGlassNative.optional;
import static com.sun.glass.ui.gtk.GtkGlassNative.stderrVariable;
import static com.sun.glass.ui.gtk.GtkGlassNative.unexpected;
import static com.sun.glass.ui.gtk.GtkKeyCalls.findGdkKeycodeForKeyval;
import static com.sun.glass.ui.gtk.GtkKeyCalls.findGdkKeyvalForGlassKeycode;
import static com.sun.glass.ui.gtk.GtkPixelsCalls.GDK_DISPLAY_GET_DEFAULT;
import static com.sun.glass.ui.gtk.GtkPixelsCalls.G_OBJECT_UNREF;
import static com.sun.glass.ui.gtk.GtkPixelsCalls.convertBgraToRgba;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The robot of the Linux glass and the helpers it called: {@code GtkRobot} ({@code GlassRobot.cpp} at commit
 * {@code 033187ad90}), {@code getUIScale} ({@code glass_screen.cpp}), {@code glass_settings_get_guint_opt} and
 * {@code glass_gdk_display_get_pointer} ({@code glass_general.cpp}), and the {@code wrapped_g_settings_schema_*}
 * lookups ({@code wrapped.c}).
 * <p>
 * Its handles are every handle of the binding's robot-and-key-lock section, in that section's order, so that
 * {@link GtkGlassNative#link} binds them where the binding always has; {@link GtkKeyCalls}, {@link GtkScreenCalls} and
 * the clipboard, dialog and drag classes use several of them from here.
 */
final class GtkRobotCalls {

    /** {@code DEFAULT_DPI} of {@code glass_screen.cpp}. */
    static final int DEFAULT_DPI = 96;

    static final String XTEST_MISSING = "Glass Robot needs XTest extension to work";

    /** {@code Display *gdk_x11_get_default_xdisplay(void)}. */
    private static final MethodHandle GDK_X11_GET_DEFAULT_XDISPLAY = bind(GDK, LIB_GDK,
            "gdk_x11_get_default_xdisplay", FunctionDescriptor.of(ADDRESS));

    /** {@code gint gdk_x11_get_default_screen(void)}. */
    private static final MethodHandle GDK_X11_GET_DEFAULT_SCREEN = bind(GDK, LIB_GDK, "gdk_x11_get_default_screen",
            FunctionDescriptor.of(JAVA_INT));

    /** {@code Display *gdk_x11_display_get_xdisplay(GdkDisplay *display)}. */
    static final MethodHandle GDK_X11_DISPLAY_GET_XDISPLAY = bind(GDK, LIB_GDK,
            "gdk_x11_display_get_xdisplay", FunctionDescriptor.of(ADDRESS, ADDRESS));

    /** {@code GdkScreen *gdk_screen_get_default(void)}. */
    static final MethodHandle GDK_SCREEN_GET_DEFAULT = bind(GDK, LIB_GDK, "gdk_screen_get_default",
            FunctionDescriptor.of(ADDRESS));

    /** {@code gdouble gdk_screen_get_resolution(GdkScreen *screen)}. */
    private static final MethodHandle GDK_SCREEN_GET_RESOLUTION = bind(GDK, LIB_GDK, "gdk_screen_get_resolution",
            FunctionDescriptor.of(JAVA_DOUBLE, ADDRESS));

    /** {@code GdkDeviceManager *gdk_display_get_device_manager(GdkDisplay *display)}. */
    private static final MethodHandle GDK_DISPLAY_GET_DEVICE_MANAGER = bind(GDK, LIB_GDK,
            "gdk_display_get_device_manager", FunctionDescriptor.of(ADDRESS, ADDRESS));

    /** {@code GdkDevice *gdk_device_manager_get_client_pointer(GdkDeviceManager *device_manager)}. */
    private static final MethodHandle GDK_DEVICE_MANAGER_GET_CLIENT_POINTER = bind(GDK, LIB_GDK,
            "gdk_device_manager_get_client_pointer", FunctionDescriptor.of(ADDRESS, ADDRESS));

    /** {@code void gdk_device_get_position(GdkDevice *device, GdkScreen **screen, gint *x, gint *y)}. */
    private static final MethodHandle GDK_DEVICE_GET_POSITION = bind(GDK, LIB_GDK, "gdk_device_get_position",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS));

    /** {@code GdkWindow *gdk_get_default_root_window(void)}. */
    private static final MethodHandle GDK_GET_DEFAULT_ROOT_WINDOW = bind(GDK, LIB_GDK, "gdk_get_default_root_window",
            FunctionDescriptor.of(ADDRESS));

    /** {@code GdkPixbuf *gdk_pixbuf_get_from_window(GdkWindow *window, gint src_x, gint src_y, gint w, gint h)}. */
    private static final MethodHandle GDK_PIXBUF_GET_FROM_WINDOW = bind(GDK, LIB_GDK, "gdk_pixbuf_get_from_window",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));

    /** {@code GdkKeymap *gdk_keymap_get_for_display(GdkDisplay *display)}. */
    static final MethodHandle GDK_KEYMAP_GET_FOR_DISPLAY = bind(GDK, LIB_GDK, "gdk_keymap_get_for_display",
            FunctionDescriptor.of(ADDRESS, ADDRESS));

    /** {@code guint gdk_keyval_to_lower(guint keyval)}. */
    static final MethodHandle GDK_KEYVAL_TO_LOWER = bind(GDK, LIB_GDK, "gdk_keyval_to_lower",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT));

    /**
     * {@code gboolean gdk_keymap_get_entries_for_keyval(GdkKeymap *keymap, guint keyval, GdkKeymapKey **keys,
     * gint *n_keys)}.
     */
    static final MethodHandle GDK_KEYMAP_GET_ENTRIES_FOR_KEYVAL = bind(GDK, LIB_GDK,
            "gdk_keymap_get_entries_for_keyval", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));

    /**
     * {@code gboolean gdk_keymap_translate_keyboard_state(GdkKeymap *keymap, guint hardware_keycode,
     * GdkModifierType state, gint group, guint *keyval, gint *effective_group, gint *level,
     * GdkModifierType *consumed_modifiers)}.
     */
    static final MethodHandle GDK_KEYMAP_TRANSLATE_KEYBOARD_STATE = bind(GDK, LIB_GDK,
            "gdk_keymap_translate_keyboard_state", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT,
                    JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));

    /** {@code GdkPixbuf *gdk_pixbuf_add_alpha(const GdkPixbuf *pixbuf, gboolean substitute_color, guchar r, g, b)}. */
    static final MethodHandle GDK_PIXBUF_ADD_ALPHA = bind(GDK_PIXBUF, LIB_GDK_PIXBUF, "gdk_pixbuf_add_alpha",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_BYTE, JAVA_BYTE, JAVA_BYTE));

    /** {@code guchar *gdk_pixbuf_get_pixels(const GdkPixbuf *pixbuf)}. */
    static final MethodHandle GDK_PIXBUF_GET_PIXELS = bind(GDK_PIXBUF, LIB_GDK_PIXBUF, "gdk_pixbuf_get_pixels",
            FunctionDescriptor.of(ADDRESS, ADDRESS));

    /** {@code void g_free(gpointer mem)}. */
    static final MethodHandle G_FREE_CALL = bind(GLIB, LIB_GLIB, "g_free", FunctionDescriptor.ofVoid(ADDRESS));

    /** {@code GHashTable *g_hash_table_new(GHashFunc hash_func, GEqualFunc key_equal_func)}. */
    static final MethodHandle G_HASH_TABLE_NEW = bind(GLIB, LIB_GLIB, "g_hash_table_new",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));

    /** {@code guint g_direct_hash(gconstpointer v)}. */
    static final MemorySegment G_DIRECT_HASH = address(GLIB, LIB_GLIB, "g_direct_hash");

    /** {@code gboolean g_direct_equal(gconstpointer v1, gconstpointer v2)}. */
    static final MemorySegment G_DIRECT_EQUAL = address(GLIB, LIB_GLIB, "g_direct_equal");

    /** {@code gboolean g_hash_table_insert(GHashTable *hash_table, gpointer key, gpointer value)}. */
    static final MethodHandle G_HASH_TABLE_INSERT = bind(GLIB, LIB_GLIB, "g_hash_table_insert",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));

    /** {@code void g_hash_table_iter_init(GHashTableIter *iter, GHashTable *hash_table)}. */
    static final MethodHandle G_HASH_TABLE_ITER_INIT = bind(GLIB, LIB_GLIB, "g_hash_table_iter_init",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));

    /** {@code gboolean g_hash_table_iter_next(GHashTableIter *iter, gpointer *key, gpointer *value)}. */
    static final MethodHandle G_HASH_TABLE_ITER_NEXT = bind(GLIB, LIB_GLIB, "g_hash_table_iter_next",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));

    /** {@code GSettings *g_settings_new(const gchar *schema_id)}. */
    private static final MethodHandle G_SETTINGS_NEW = bind(GIO, LIB_GIO, "g_settings_new",
            FunctionDescriptor.of(ADDRESS, ADDRESS));

    /** {@code guint g_settings_get_uint(GSettings *settings, const gchar *key)}. */
    private static final MethodHandle G_SETTINGS_GET_UINT = bind(GIO, LIB_GIO, "g_settings_get_uint",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    /**
     * The four {@code dlsym(RTLD_DEFAULT, ...)} lookups of {@code wrapped.c}, resolved in {@code libgio-2.0.so.0},
     * the library that defines them; {@code NULL} when it does not, which the callers below treat as the C treated a
     * failed {@code dlsym}.
     */
    private static final MemorySegment G_SETTINGS_SCHEMA_SOURCE_GET_DEFAULT = optional(GIO, LIB_GIO,
            "g_settings_schema_source_get_default");
    private static final MemorySegment G_SETTINGS_SCHEMA_SOURCE_LOOKUP = optional(GIO, LIB_GIO,
            "g_settings_schema_source_lookup");
    private static final MemorySegment G_SETTINGS_SCHEMA_HAS_KEY = optional(GIO, LIB_GIO,
            "g_settings_schema_has_key");
    private static final MemorySegment G_SETTINGS_SCHEMA_UNREF = optional(GIO, LIB_GIO, "g_settings_schema_unref");

    /** {@code GSettingsSchemaSource *(*)(void)}. */
    private static final MethodHandle SCHEMA_SOURCE_GET_DEFAULT = addresslessDowncall(
            FunctionDescriptor.of(ADDRESS));

    /** {@code GSettingsSchema *(*)(GSettingsSchemaSource *source, const gchar *schema_id, gboolean recursive)}. */
    private static final MethodHandle SCHEMA_SOURCE_LOOKUP = addresslessDowncall(
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT));

    /** {@code gboolean (*)(GSettingsSchema *schema, const gchar *name)}. */
    private static final MethodHandle SCHEMA_HAS_KEY = addresslessDowncall(
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    /** {@code void (*)(GSettingsSchema *schema)}. */
    private static final MethodHandle SCHEMA_UNREF = addresslessDowncall(FunctionDescriptor.ofVoid(ADDRESS));

    /** {@code Bool XQueryExtension(Display *, const char *name, int *major, int *first_event, int *first_error)}. */
    private static final MethodHandle X_QUERY_EXTENSION = bind(X11, LIB_X11, "XQueryExtension",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));

    /**
     * {@code int XWarpPointer(Display *, Window src_w, Window dest_w, int src_x, int src_y, unsigned int src_width,
     * unsigned int src_height, int dest_x, int dest_y)}.
     */
    private static final MethodHandle X_WARP_POINTER = bind(X11, LIB_X11, "XWarpPointer",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT,
                    JAVA_INT, JAVA_INT));

    /** {@code Window XRootWindow(Display *, int screen_number)}. */
    private static final MethodHandle X_ROOT_WINDOW = bind(X11, LIB_X11, "XRootWindow",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT));

    /** {@code int XSync(Display *, Bool discard)}. */
    private static final MethodHandle X_SYNC = bind(X11, LIB_X11, "XSync", FunctionDescriptor.of(JAVA_INT, ADDRESS,
            JAVA_INT));

    /** {@code Atom XInternAtom(Display *, const char *atom_name, Bool only_if_exists)}. */
    static final MethodHandle X_INTERN_ATOM = bind(X11, LIB_X11, "XInternAtom",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_INT));

    /**
     * {@code Bool XkbQueryExtension(Display *, int *opcode, int *event_base, int *error_base, int *major,
     * int *minor)}.
     */
    static final MethodHandle XKB_QUERY_EXTENSION = bind(X11, LIB_X11, "XkbQueryExtension",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));

    /** {@code Status XkbGetState(Display *, unsigned int device_spec, XkbStatePtr state)}. */
    static final MethodHandle XKB_GET_STATE = bind(X11, LIB_X11, "XkbGetState",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));

    /**
     * {@code Bool XkbGetNamedIndicator(Display *, Atom name, int *ndx, Bool *state, XkbIndicatorMapPtr map,
     * Bool *real)}.
     */
    static final MethodHandle XKB_GET_NAMED_INDICATOR = bind(X11, LIB_X11, "XkbGetNamedIndicator",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS, ADDRESS, ADDRESS));

    /** {@code Bool XTestQueryExtension(Display *, int *event_base, int *error_base, int *major, int *minor)}. */
    private static final MethodHandle X_TEST_QUERY_EXTENSION = bind(XTST, LIB_XTST, "XTestQueryExtension",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));

    /** {@code int XTestGrabControl(Display *, Bool impervious)}. */
    private static final MethodHandle X_TEST_GRAB_CONTROL = bind(XTST, LIB_XTST, "XTestGrabControl",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));

    /** {@code int XTestFakeKeyEvent(Display *, unsigned int keycode, Bool is_press, unsigned long delay)}. */
    private static final MethodHandle X_TEST_FAKE_KEY_EVENT = bind(XTST, LIB_XTST, "XTestFakeKeyEvent",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_LONG));

    /** {@code int XTestFakeButtonEvent(Display *, unsigned int button, Bool is_press, unsigned long delay)}. */
    private static final MethodHandle X_TEST_FAKE_BUTTON_EVENT = bind(XTST, LIB_XTST, "XTestFakeButtonEvent",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_LONG));

    /** {@code char *getenv(const char *name)}: the C environment, not {@link System#getenv}'s snapshot. */
    private static final MethodHandle GETENV = bind(LIBC, LIB_C, "getenv", FunctionDescriptor.of(ADDRESS, ADDRESS));

    /** {@code int atoi(const char *nptr)}. */
    private static final MethodHandle ATOI = bind(LIBC, LIB_C, "atoi", FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** {@code int fputs(const char *s, FILE *stream)}. */
    static final MethodHandle FPUTS = bind(LIBC, LIB_C, "fputs",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    /** {@code int fflush(FILE *stream)}. */
    private static final MethodHandle FFLUSH = bind(LIBC, LIB_C, "fflush", FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** The {@code FILE *stderr} variable of libc; read at each use, as {@code fprintf(stderr, ...)} reads it. */
    private static final MemorySegment STDERR = stderrVariable();

    private static boolean xtestCheckDone;
    private static int xtestAvailable;

    private static final boolean[] WRAPPED_LOADED = new boolean[4];

    /**
     * {@code Java_com_sun_glass_ui_gtk_GtkRobot__1keyPress} ({@code GlassRobot.cpp}). When XTest is missing the C
     * left an {@code UnsupportedOperationException} pending and carried on into the XTest calls, so the exception
     * reached Java only when the native returned; here it is thrown at the same point, after the calls.
     */
    static void robotKeyPress(int code) {
        boolean missing = checkXTest();
        keyButton(code, true);
        throwIfXTestMissing(missing);
    }

    /** {@code Java_com_sun_glass_ui_gtk_GtkRobot__1keyRelease} ({@code GlassRobot.cpp}): see {@link #robotKeyPress}. */
    static void robotKeyRelease(int code) {
        boolean missing = checkXTest();
        keyButton(code, false);
        throwIfXTestMissing(missing);
    }

    /**
     * {@code Java_com_sun_glass_ui_gtk_GtkRobot__1mouseMove} ({@code GlassRobot.cpp}); like the C, it rounds the
     * {@code float} product with the {@code double} {@code rint}.
     */
    static void robotMouseMove(int x, int y) {
        try {
            MemorySegment xdisplay = (MemorySegment) GDK_X11_GET_DEFAULT_XDISPLAY.invokeExact();
            boolean missing = checkXTest();
            MemorySegment screen = (MemorySegment) GDK_SCREEN_GET_DEFAULT.invokeExact();
            float uiScale = getUIScale(screen);
            x = (int) Math.rint(x * uiScale);
            y = (int) Math.rint(y * uiScale);
            int screenNumber = (int) GDK_X11_GET_DEFAULT_SCREEN.invokeExact();
            long root = (long) X_ROOT_WINDOW.invokeExact(xdisplay, screenNumber);
            int warped = (int) X_WARP_POINTER.invokeExact(xdisplay, 0L, root, 0, 0, 0, 0, x, y);
            int synced = (int) X_SYNC.invokeExact(xdisplay, 0);
            throwIfXTestMissing(missing);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code Java_com_sun_glass_ui_gtk_GtkRobot__1mousePress} ({@code GlassRobot.cpp}). */
    static void robotMousePress(int buttons) {
        boolean missing = checkXTest();
        mouseButtons(buttons, true);
        throwIfXTestMissing(missing);
    }

    /** {@code Java_com_sun_glass_ui_gtk_GtkRobot__1mouseRelease} ({@code GlassRobot.cpp}). */
    static void robotMouseRelease(int buttons) {
        boolean missing = checkXTest();
        mouseButtons(buttons, false);
        throwIfXTestMissing(missing);
    }

    /** {@code Java_com_sun_glass_ui_gtk_GtkRobot__1mouseWheel} ({@code GlassRobot.cpp}). */
    static void robotMouseWheel(int amt) {
        try {
            MemorySegment xdisplay = (MemorySegment) GDK_X11_GET_DEFAULT_XDISPLAY.invokeExact();
            int repeat = Math.abs(amt);
            int button = amt < 0 ? 4 : 5;
            boolean missing = checkXTest();
            for (int i = 0; i < repeat; i++) {
                int pressed = (int) X_TEST_FAKE_BUTTON_EVENT.invokeExact(xdisplay, button, 1, 0L);
                int released = (int) X_TEST_FAKE_BUTTON_EVENT.invokeExact(xdisplay, button, 0, 0L);
            }
            int synced = (int) X_SYNC.invokeExact(xdisplay, 0);
            throwIfXTestMissing(missing);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code Java_com_sun_glass_ui_gtk_GtkRobot__1getMouseX} ({@code GlassRobot.cpp}). */
    static int robotGetMouseX() {
        return pointerCoordinate(true);
    }

    /** {@code Java_com_sun_glass_ui_gtk_GtkRobot__1getMouseY} ({@code GlassRobot.cpp}). */
    static int robotGetMouseY() {
        return pointerCoordinate(false);
    }

    /**
     * {@code Java_com_sun_glass_ui_gtk_GtkRobot__1getScreenCapture} ({@code GlassRobot.cpp}), guards included. Like
     * the C, it reads the pixels as {@code width * 4} bytes per row, and copies them into {@code data} as
     * native-order ints, as {@code SetIntArrayRegion} did.
     */
    static void robotGetScreenCapture(int x, int y, int width, int height, int[] data) {
        if (data == null) {
            return;
        }
        if (width <= 0 || height <= 0) {
            return;
        }
        int maxPixels = Integer.MAX_VALUE / 4;
        if (width >= maxPixels / height) {
            return;
        }
        int numPixels = width * height;
        if (numPixels > data.length) {
            return;
        }
        try {
            MemorySegment rootWindow = (MemorySegment) GDK_GET_DEFAULT_ROOT_WINDOW.invokeExact();
            MemorySegment tmp = (MemorySegment) GDK_PIXBUF_GET_FROM_WINDOW.invokeExact(rootWindow, x, y, width,
                    height);
            if (tmp.address() == 0) {
                return;
            }
            MemorySegment screenshot = (MemorySegment) GDK_PIXBUF_ADD_ALPHA.invokeExact(tmp, 0, (byte) 0, (byte) 0,
                    (byte) 0);
            G_OBJECT_UNREF.invokeExact(tmp);
            if (screenshot.address() == 0) {
                return;
            }
            MemorySegment pixels = (MemorySegment) GDK_PIXBUF_GET_PIXELS.invokeExact(screenshot);
            MemorySegment rgba = convertBgraToRgba(bounded(pixels, (long) width * 4 * height), 0, width * 4,
                    height);
            if (rgba.address() != 0) {
                MemorySegment.copy(rgba, JAVA_INT_UNALIGNED, 0, data, 0, numPixels);
                G_FREE_CALL.invokeExact(rgba);
            }
            G_OBJECT_UNREF.invokeExact(screenshot);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code getUIScale} ({@code glass_screen.cpp}). No production C calls the C copy any more; it is kept only as the
     * oracle {@code GtkRobotParityTest} checks this method against.
     */
    static float getUIScale(MemorySegment screen) {
        float uiScale;
        if (GtkApplication.overrideUIScale > 0.0f) {
            uiScale = GtkApplication.overrideUIScale;
        } else {
            try (Arena call = Arena.ofConfined()) {
                double resolution = (double) GDK_SCREEN_GET_RESOLUTION.invokeExact(screen);
                uiScale = (float) (resolution / DEFAULT_DPI);
                if ((int) resolution == DEFAULT_DPI) {
                    MemorySegment scaleStr = (MemorySegment) GETENV.invokeExact(call.allocateFrom("GDK_SCALE"));
                    int gdkScale = scaleStr.address() == 0 ? -1 : (int) ATOI.invokeExact(scaleStr);
                    if (gdkScale > 0) {
                        uiScale = (float) gdkScale;
                    } else {
                        int gnomeScale = settingsGetGuintOpt("org.gnome.desktop.interface", "scaling-factor", 0);
                        if (gnomeScale != 0) {
                            uiScale = (float) Integer.toUnsignedLong(gnomeScale);
                        }
                    }
                }
            } catch (Throwable t) {
                throw unexpected(t);
            }
        }
        return uiScale;
    }

    /**
     * {@code glass_settings_get_guint_opt} ({@code glass_general.cpp}) over the {@code wrapped_g_settings_schema_*}
     * lookups of {@code wrapped.c}, with the {@code jdk.gtk.verbose} messages of both on the C {@code stderr}. Like
     * the C it leaks the schema when the key is missing and the {@code GSettings} it reads.
     *
     * @return the unsigned setting as an {@code int}, or {@code defval}
     */
    static int settingsGetGuintOpt(String schemaName, String keyName, int defval) {
        try (Arena call = Arena.ofConfined()) {
            MemorySegment schemaId = call.allocateFrom(schemaName);
            MemorySegment key = call.allocateFrom(keyName);
            MemorySegment source = MemorySegment.NULL;
            if (wrapped(0, G_SETTINGS_SCHEMA_SOURCE_GET_DEFAULT, "g_settings_schema_source_get_default")) {
                source = (MemorySegment) SCHEMA_SOURCE_GET_DEFAULT.invokeExact(G_SETTINGS_SCHEMA_SOURCE_GET_DEFAULT);
            }
            if (source.address() == 0) {
                if (GtkApplication.verbose) {
                    printStderr("No schema source dir found!\n");
                }
                return defval;
            }
            MemorySegment schema = MemorySegment.NULL;
            if (wrapped(1, G_SETTINGS_SCHEMA_SOURCE_LOOKUP, "g_settings_schema_source_lookup")) {
                schema = (MemorySegment) SCHEMA_SOURCE_LOOKUP.invokeExact(G_SETTINGS_SCHEMA_SOURCE_LOOKUP, source,
                        schemaId, 1);
            }
            if (schema.address() == 0) {
                if (GtkApplication.verbose) {
                    printStderr("schema '" + schemaName + "' not found!\n");
                }
                return defval;
            }
            int hasKey = 0;
            if (wrapped(2, G_SETTINGS_SCHEMA_HAS_KEY, "g_settings_schema_has_key")) {
                hasKey = (int) SCHEMA_HAS_KEY.invokeExact(G_SETTINGS_SCHEMA_HAS_KEY, schema, key);
            }
            if (hasKey == 0) {
                if (GtkApplication.verbose) {
                    printStderr("key '" + keyName + "' not found in schema '" + schemaName + "'!\n");
                }
                return defval;
            }
            if (GtkApplication.verbose) {
                printStderr("found schema '" + schemaName + "' and key '" + keyName + "'\n");
            }
            MemorySegment settings = (MemorySegment) G_SETTINGS_NEW.invokeExact(schemaId);
            if (wrapped(3, G_SETTINGS_SCHEMA_UNREF, "g_settings_schema_unref")) {
                SCHEMA_UNREF.invokeExact(G_SETTINGS_SCHEMA_UNREF, schema);
            }
            return (int) G_SETTINGS_GET_UINT.invokeExact(settings, key);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code checkXTest} ({@code GlassRobot.cpp}), memoised as the C memoised it; answers whether XTest is missing. */
    private static boolean checkXTest() {
        if (!xtestCheckDone) {
            try (Arena call = Arena.ofConfined()) {
                MemorySegment majorOpcode = call.allocate(JAVA_INT);
                MemorySegment firstEvent = call.allocate(JAVA_INT);
                MemorySegment firstError = call.allocate(JAVA_INT);
                MemorySegment eventBase = call.allocate(JAVA_INT);
                MemorySegment errorBase = call.allocate(JAVA_INT);
                MemorySegment major = call.allocate(JAVA_INT);
                MemorySegment minor = call.allocate(JAVA_INT);
                MemorySegment display = (MemorySegment) GDK_X11_GET_DEFAULT_XDISPLAY.invokeExact();
                xtestAvailable = (int) X_QUERY_EXTENSION.invokeExact(display, call.allocateFrom("XTEST"), majorOpcode,
                        firstEvent, firstError);
                if (xtestAvailable != 0) {
                    display = (MemorySegment) GDK_X11_GET_DEFAULT_XDISPLAY.invokeExact();
                    int queried = (int) X_TEST_QUERY_EXTENSION.invokeExact(display, eventBase, errorBase, major,
                            minor);
                    int majorp = major.get(JAVA_INT, 0);
                    int minorp = minor.get(JAVA_INT, 0);
                    if (majorp < 2 || (majorp == 2 && minorp < 2)) {
                        xtestAvailable = 0;
                    } else {
                        display = (MemorySegment) GDK_X11_GET_DEFAULT_XDISPLAY.invokeExact();
                        int grabbed = (int) X_TEST_GRAB_CONTROL.invokeExact(display, 1);
                    }
                }
            } catch (Throwable t) {
                throw unexpected(t);
            }
            xtestCheckDone = true;
        }
        return xtestAvailable == 0;
    }

    private static void throwIfXTestMissing(boolean missing) {
        if (missing) {
            throw new UnsupportedOperationException(XTEST_MISSING);
        }
    }

    /** {@code keyButton} ({@code GlassRobot.cpp}). */
    private static void keyButton(int code, boolean press) {
        try {
            MemorySegment xdisplay = (MemorySegment) GDK_X11_GET_DEFAULT_XDISPLAY.invokeExact();
            int gdkKeyval = findGdkKeyvalForGlassKeycode(code);
            if (gdkKeyval == -1) {
                return;
            }
            int keycode = findGdkKeycodeForKeyval(gdkKeyval);
            if (keycode == -1) {
                return;
            }
            int faked = (int) X_TEST_FAKE_KEY_EVENT.invokeExact(xdisplay, keycode, press ? 1 : 0, 0L);
            int synced = (int) X_SYNC.invokeExact(xdisplay, 0);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code mouseButtons} ({@code GlassRobot.cpp}). */
    private static void mouseButtons(int buttons, boolean press) {
        try {
            MemorySegment xdisplay = (MemorySegment) GDK_X11_GET_DEFAULT_XDISPLAY.invokeExact();
            int isPress = press ? 1 : 0;
            if ((buttons & GlassRobot.MOUSE_LEFT_BTN) != 0) {
                int faked = (int) X_TEST_FAKE_BUTTON_EVENT.invokeExact(xdisplay, 1, isPress, 0L);
            }
            if ((buttons & GlassRobot.MOUSE_MIDDLE_BTN) != 0) {
                int faked = (int) X_TEST_FAKE_BUTTON_EVENT.invokeExact(xdisplay, 2, isPress, 0L);
            }
            if ((buttons & GlassRobot.MOUSE_RIGHT_BTN) != 0) {
                int faked = (int) X_TEST_FAKE_BUTTON_EVENT.invokeExact(xdisplay, 3, isPress, 0L);
            }
            if ((buttons & GlassRobot.MOUSE_BACK_BTN) != 0) {
                int faked = (int) X_TEST_FAKE_BUTTON_EVENT.invokeExact(xdisplay, 8, isPress, 0L);
            }
            if ((buttons & GlassRobot.MOUSE_FORWARD_BTN) != 0) {
                int faked = (int) X_TEST_FAKE_BUTTON_EVENT.invokeExact(xdisplay, 9, isPress, 0L);
            }
            int synced = (int) X_SYNC.invokeExact(xdisplay, 0);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** The shared body of {@code _getMouseX} and {@code _getMouseY}, {@code glass_gdk_display_get_pointer} included. */
    private static int pointerCoordinate(boolean wantX) {
        try (Arena call = Arena.ofConfined()) {
            MemorySegment coordinate = call.allocate(JAVA_INT);
            MemorySegment display = (MemorySegment) GDK_DISPLAY_GET_DEFAULT.invokeExact();
            MemorySegment manager = (MemorySegment) GDK_DISPLAY_GET_DEVICE_MANAGER.invokeExact(display);
            MemorySegment pointer = (MemorySegment) GDK_DEVICE_MANAGER_GET_CLIENT_POINTER.invokeExact(manager);
            MemorySegment x = wantX ? coordinate : MemorySegment.NULL;
            MemorySegment y = wantX ? MemorySegment.NULL : coordinate;
            GDK_DEVICE_GET_POSITION.invokeExact(pointer, MemorySegment.NULL, x, y);
            MemorySegment screen = (MemorySegment) GDK_SCREEN_GET_DEFAULT.invokeExact();
            return (int) Math.rint(coordinate.get(JAVA_INT, 0) / getUIScale(screen));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * The first-use half of a {@code wrapped_*} function of {@code wrapped.c}: whether its symbol exists, and the
     * {@code loaded <name>} line (flushed) the C printed under {@code jdk.gtk.verbose} the first time it found it.
     */
    private static boolean wrapped(int index, MemorySegment symbol, String name) throws Throwable {
        boolean present = symbol.address() != 0;
        if (!WRAPPED_LOADED[index]) {
            WRAPPED_LOADED[index] = true;
            if (GtkApplication.verbose && present) {
                printStderr("loaded " + name + "\n");
                int flushed = (int) FFLUSH.invokeExact(stderr());
            }
        }
        return present;
    }

    /** {@code fprintf(stderr, ...)} of already-formatted text. */
    private static void printStderr(String text) throws Throwable {
        try (Arena call = Arena.ofConfined()) {
            int written = (int) FPUTS.invokeExact(call.allocateFrom(text), stderr());
        }
    }

    static MemorySegment stderr() {
        return STDERR.get(ADDRESS, 0);
    }

    private GtkRobotCalls() {
    }
}
