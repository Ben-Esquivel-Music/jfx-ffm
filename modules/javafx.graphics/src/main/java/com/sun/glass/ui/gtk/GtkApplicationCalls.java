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
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import static com.sun.glass.ui.gtk.GtkCallbackTables.ERR_GTK_VERSION;
import static com.sun.glass.ui.gtk.GtkGlassNative.GDK;
import static com.sun.glass.ui.gtk.GtkGlassNative.GLIB;
import static com.sun.glass.ui.gtk.GtkGlassNative.GOBJECT;
import static com.sun.glass.ui.gtk.GtkGlassNative.GTK;
import static com.sun.glass.ui.gtk.GtkGlassNative.LIBC;
import static com.sun.glass.ui.gtk.GtkGlassNative.LIB_C;
import static com.sun.glass.ui.gtk.GtkGlassNative.LIB_GDK;
import static com.sun.glass.ui.gtk.GtkGlassNative.LIB_GLIB;
import static com.sun.glass.ui.gtk.GtkGlassNative.LIB_GOBJECT;
import static com.sun.glass.ui.gtk.GtkGlassNative.LIB_GTK;
import static com.sun.glass.ui.gtk.GtkGlassNative.bind;
import static com.sun.glass.ui.gtk.GtkGlassNative.bounded;
import static com.sun.glass.ui.gtk.GtkGlassNative.unexpected;
import static com.sun.glass.ui.gtk.GtkMainLoopCalls.GTK_MAIN_QUIT;
import static com.sun.glass.ui.gtk.GtkPixelsCalls.GDK_DISPLAY_GET_DEFAULT;
import static com.sun.glass.ui.gtk.GtkRobotCalls.FPUTS;
import static com.sun.glass.ui.gtk.GtkRobotCalls.GDK_SCREEN_GET_DEFAULT;
import static com.sun.glass.ui.gtk.GtkRobotCalls.G_FREE_CALL;
import static com.sun.glass.ui.gtk.GtkRobotCalls.stderr;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The calls of {@code GtkApplication} that are not its sources, screens, preferences or key lock: the queries
 * {@code _openURI}, {@code staticView_getMultiClickTime}, {@code staticView_getMultiClickMaxX} and
 * {@code _supportsTransparentWindows} ({@code GlassApplication.cpp} at commit {@code 033187ad90}), and the natives
 * {@code _initGTK}, {@code _init}, {@code _runLoop}, {@code _terminateLoop} and {@code _getKeyCodeForChar}. Its
 * C-string helpers serve the whole binding.
 * <p>
 * Each native replaces the {@code Java_com_sun_glass_ui_gtk_*} function of commit {@code 033187ad90} it names; the
 * body of that function is the {@code ggtk_*} function of {@code glass_gtk_api.h} (THE NATIVES) the method calls
 * ({@link GtkCallbackTables.Glass}) - for {@code _terminateLoop}, {@code gtk_main_quit} itself - and what the JNI
 * function did with its Java arguments and result - the conversions, its calls into Java, the exception it threw or
 * left pending - is done here, in its order, around the downcall. A method runs on the thread that calls it, which is
 * the thread the JNI native ran on.
 */
final class GtkApplicationCalls {

    /** {@code gboolean gtk_show_uri(GdkScreen *screen, const gchar *uri, guint32 timestamp, GError **error)}. */
    private static final MethodHandle GTK_SHOW_URI = bind(GTK, LIB_GTK, "gtk_show_uri",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS));

    /** {@code void g_error_free(GError *error)}. */
    private static final MethodHandle G_ERROR_FREE = bind(GLIB, LIB_GLIB, "g_error_free",
            FunctionDescriptor.ofVoid(ADDRESS));

    /** {@code GtkSettings *gtk_settings_get_default(void)}. */
    static final MethodHandle GTK_SETTINGS_GET_DEFAULT = bind(GTK, LIB_GTK, "gtk_settings_get_default",
            FunctionDescriptor.of(ADDRESS));

    /**
     * {@code void g_object_get(gpointer object, const gchar *first_property_name, ...)}, linked for the one shape
     * the C used: one property, the pointer its value is written through, and the {@code NULL} terminator. The
     * same linkage serves a {@code gint *}, a {@code gboolean *} and a {@code gchar **}.
     */
    static final MethodHandle G_OBJECT_GET_ONE = bind(GOBJECT, LIB_GOBJECT, "g_object_get",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS), Linker.Option.firstVariadicArg(2));

    /** {@code gboolean gdk_display_supports_composite(GdkDisplay *display)}. */
    private static final MethodHandle GDK_DISPLAY_SUPPORTS_COMPOSITE = bind(GDK, LIB_GDK,
            "gdk_display_supports_composite", FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** {@code gboolean gdk_screen_is_composited(GdkScreen *screen)}. */
    private static final MethodHandle GDK_SCREEN_IS_COMPOSITED = bind(GDK, LIB_GDK, "gdk_screen_is_composited",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** {@code size_t strlen(const char *s)} of libc. */
    private static final MethodHandle STRLEN = bind(LIBC, LIB_C, "strlen", FunctionDescriptor.of(JAVA_LONG, ADDRESS));

    /** {@code GError.message}: after {@code GQuark domain} and {@code gint code}. */
    static final long G_ERROR_MESSAGE_OFFSET = 8;

    /** The {@code static gint multi_click_time = -1} of {@code staticView_getMultiClickTime}. */
    private static int multiClickTime = -1;

    /** The {@code static gint multi_click_dist = -1} of {@code staticView_getMultiClickMaxX}. */
    private static int multiClickDist = -1;

    /**
     * {@code Java_com_sun_glass_ui_gtk_GtkApplication__1openURI} ({@code GlassApplication.cpp}):
     * {@code gtk_show_uri(NULL, uri, 0, &error)} with the URI as the modified UTF-8 {@code GetStringUTFChars} gave
     * ({@link JniStringCodec}); on failure {@code Error opening URI <uri> : <message>} (or
     * {@code Unspecified error.} without a {@code GError}) on libc's {@code stderr} and {@code g_error_free}.
     * Called from whatever thread asks, as the C was. Where the C would have crashed on a {@code null} URI this
     * throws {@link NullPointerException}; the C's branch for a failed {@code GetStringUTFChars} (an
     * {@code OutOfMemoryError} left pending and {@code Error: Converted URI string is null} printed) is an
     * {@code OutOfMemoryError} thrown by the conversion here.
     *
     * @return 0 when the URI was shown, -1 otherwise
     */
    static int openURI(String uri) {
        byte[] uriChars = JniStringCodec.toModifiedUtf8(uri);
        try (Arena call = Arena.ofConfined()) {
            MemorySegment errorOut = call.allocate(ADDRESS);
            MemorySegment uriC = call.allocateFrom(JAVA_BYTE, uriChars);
            int success = (int) GTK_SHOW_URI.invokeExact(MemorySegment.NULL, uriC, 0, errorOut);
            if (success == 0) {
                MemorySegment error = errorOut.get(ADDRESS, 0);
                byte[] message;
                if (error.address() == 0) {
                    message = "Unspecified error.".getBytes(StandardCharsets.US_ASCII);
                } else {
                    MemorySegment text = bounded(error, G_ERROR_MESSAGE_OFFSET + ADDRESS.byteSize())
                            .get(ADDRESS, G_ERROR_MESSAGE_OFFSET);
                    message = cStringBytes(text);
                }
                ByteArrayOutputStream line = new ByteArrayOutputStream();
                line.writeBytes("Error opening URI ".getBytes(StandardCharsets.US_ASCII));
                line.write(uriChars, 0, uriChars.length - 1);
                line.writeBytes(" : ".getBytes(StandardCharsets.US_ASCII));
                line.writeBytes(message);
                line.write('\n');
                line.write(0);
                int written = (int) FPUTS.invokeExact(call.allocateFrom(JAVA_BYTE, line.toByteArray()), stderr());
                if (error.address() != 0) {
                    G_ERROR_FREE.invokeExact(error);
                }
            }
            return success != 0 ? 0 : -1;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code Java_com_sun_glass_ui_gtk_GtkApplication_staticView_1getMultiClickTime} ({@code GlassApplication.cpp}).
     */
    static long multiClickTime() {
        if (multiClickTime == -1) {
            multiClickTime = gtkSettingInt("gtk-double-click-time", multiClickTime);
        }
        return multiClickTime;
    }

    /**
     * {@code Java_com_sun_glass_ui_gtk_GtkApplication_staticView_1getMultiClickMaxX} ({@code GlassApplication.cpp}).
     * {@code staticView_getMultiClickMaxY} called it and answered the same value.
     */
    static int multiClickMaxX() {
        if (multiClickDist == -1) {
            multiClickDist = gtkSettingInt("gtk-double-click-distance", multiClickDist);
        }
        return multiClickDist;
    }

    /** {@code Java_com_sun_glass_ui_gtk_GtkApplication__1supportsTransparentWindows} ({@code GlassApplication.cpp}). */
    static boolean supportsTransparentWindows() {
        try {
            MemorySegment display = (MemorySegment) GDK_DISPLAY_GET_DEFAULT.invokeExact();
            if ((int) GDK_DISPLAY_SUPPORTS_COMPOSITE.invokeExact(display) == 0) {
                return false;
            }
            MemorySegment screen = (MemorySegment) GDK_SCREEN_GET_DEFAULT.invokeExact();
            return (int) GDK_SCREEN_IS_COMPOSITED.invokeExact(screen) != 0;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code g_object_get(gtk_settings_get_default(), name, &value, NULL)} where {@code value} held {@code current}
     * before the call, as the C's static did; answers what it holds afterwards.
     */
    private static int gtkSettingInt(String name, int current) {
        try (Arena call = Arena.ofConfined()) {
            MemorySegment value = call.allocate(JAVA_INT);
            value.set(JAVA_INT, 0, current);
            MemorySegment settings = (MemorySegment) GTK_SETTINGS_GET_DEFAULT.invokeExact();
            G_OBJECT_GET_ONE.invokeExact(settings, call.allocateFrom(name), value, MemorySegment.NULL);
            return value.get(JAVA_INT, 0);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** The bytes of a C string before its terminator; {@code (null)} for {@code NULL}, as glibc's {@code %s} prints. */
    private static byte[] cStringBytes(MemorySegment text) throws Throwable {
        if (text.address() == 0) {
            return "(null)".getBytes(StandardCharsets.US_ASCII);
        }
        return cString(text);
    }

    /**
     * The bytes of a C string before its terminator, for every place the C ran {@code strlen} on the pointer: a
     * {@code NULL} is a {@link NullPointerException} here, where the C dereferenced it. Only {@link #cStringBytes}
     * prints {@code (null)}, which is what its {@code fprintf("%s", ...)} callers needed.
     */
    static byte[] cString(MemorySegment text) throws Throwable {
        if (text.address() == 0) {
            throw new NullPointerException("NULL C string");
        }
        long length = (long) STRLEN.invokeExact(text);
        return bounded(text, length).toArray(JAVA_BYTE);
    }

    /**
     * {@code NewStringUTF}: the modified UTF-8 of a C string, or {@code null} for a {@code NULL} pointer, which is
     * what {@code NewStringUTF} answered for one.
     */
    static String newStringUtf(MemorySegment text) throws Throwable {
        return text.address() == 0 ? null : JniStringCodec.fromNewStringUtf(cString(text));
    }

    /**
     * {@code Java_com_sun_glass_ui_gtk_GtkApplication__1initGTK} ({@code GlassApplication.cpp}):
     * {@code ggtk_application_init_gtk} on the thread that constructs the application - GTK initialized and the GDK
     * lock entered. When GTK is older than the build's minimum the JNI native returned with an
     * {@code UnsupportedOperationException} pending; the C hands its message back in a {@code g_malloc}'d block, and
     * it is thrown here once the downcall has returned, decoded as {@code ThrowNew} decoded it. The JNI native's
     * first statement, {@code ExceptionClear}, never had anything to clear.
     *
     * @throws UnsupportedOperationException if GTK is older than the build's minimum
     */
    static void applicationInitGtk(int version, boolean verbose, float uiScale) {
        boolean tooOld;
        String message;
        try (Arena call = Arena.ofConfined()) {
            MemorySegment outError = call.allocate(ADDRESS);
            int status = (int) Glass.APPLICATION_INIT_GTK.invokeExact(version, verbose ? 1 : 0, uiScale, outError);
            MemorySegment error = outError.get(ADDRESS, 0);
            tooOld = status == ERR_GTK_VERSION;
            try {
                message = tooOld ? newStringUtf(error) : null;
            } finally {
                G_FREE_CALL.invokeExact(error);
            }
        } catch (Throwable t) {
            throw unexpected(t);
        }
        if (tooOld) {
            throw new UnsupportedOperationException(message);
        }
    }

    /**
     * {@code Java_com_sun_glass_ui_gtk_GtkApplication__1init} ({@code GlassApplication.cpp}):
     * {@code ggtk_application_init} on the toolkit thread - GDK's event handler, the screen signals and the root
     * window's PropertyNotify; from here on events reach the callback tables. The JNI native first stored its
     * {@code JNIEnv} in {@code mainEnv} for the JNI calls of the event code, which calls Java through the tables now.
     */
    static void applicationInit(long eventHandler, boolean disableGrab) {
        try {
            Glass.APPLICATION_INIT.invokeExact(eventHandler, disableGrab ? 1 : 0);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code Java_com_sun_glass_ui_gtk_GtkApplication__1runLoop} ({@code GlassApplication.cpp}) after its first two
     * statements, which {@code GtkApplication._runLoop} performs: {@code ggtk_application_run_loop} on the toolkit
     * thread - the GDK error trap unless {@code noErrorTrap}, {@code gtk_main} until
     * {@link #applicationTerminateLoop} quits it, then the GDK lock is left. Every event of the toolkit is dispatched
     * inside this downcall.
     */
    static void applicationRunLoop(boolean noErrorTrap) {
        try {
            Glass.APPLICATION_RUN_LOOP.invokeExact(noErrorTrap ? 1 : 0);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code Java_com_sun_glass_ui_gtk_GtkApplication__1terminateLoop} ({@code GlassApplication.cpp}): its first
     * statement, {@code gtk_main_quit()}, bound from GTK directly; the {@code delete platformSupport} that followed
     * it is {@code GtkApplication}'s {@link GtkPreferenceCalls#platformSupportDestroy} call.
     */
    static void applicationTerminateLoop() {
        try {
            GTK_MAIN_QUIT.invokeExact();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code Java_com_sun_glass_ui_gtk_GtkApplication__1getKeyCodeForChar} ({@code glass_key.cpp}): the
     * {@code KeyEvent.VK_*} code of the UTF-16 code unit {@code c}; {@code hint} is ignored, as it was.
     */
    static int applicationKeyCodeForChar(char c, int hint) {
        try {
            return (int) Glass.APPLICATION_GET_KEY_CODE_FOR_CHAR.invokeExact(c, hint);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private GtkApplicationCalls() {
    }
}
