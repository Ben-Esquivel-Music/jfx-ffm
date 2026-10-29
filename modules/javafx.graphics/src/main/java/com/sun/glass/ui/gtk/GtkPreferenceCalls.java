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
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import javafx.scene.paint.Color;
import static com.sun.glass.ui.gtk.GtkApplicationCalls.GTK_SETTINGS_GET_DEFAULT;
import static com.sun.glass.ui.gtk.GtkApplicationCalls.G_OBJECT_GET_ONE;
import static com.sun.glass.ui.gtk.GtkApplicationCalls.cString;
import static com.sun.glass.ui.gtk.GtkGlassNative.GIO;
import static com.sun.glass.ui.gtk.GtkGlassNative.GOBJECT;
import static com.sun.glass.ui.gtk.GtkGlassNative.GTK;
import static com.sun.glass.ui.gtk.GtkGlassNative.LIB_GIO;
import static com.sun.glass.ui.gtk.GtkGlassNative.LIB_GOBJECT;
import static com.sun.glass.ui.gtk.GtkGlassNative.LIB_GTK;
import static com.sun.glass.ui.gtk.GtkGlassNative.bind;
import static com.sun.glass.ui.gtk.GtkGlassNative.bounded;
import static com.sun.glass.ui.gtk.GtkGlassNative.reportException;
import static com.sun.glass.ui.gtk.GtkGlassNative.unexpected;
import static com.sun.glass.ui.gtk.GtkGlassNative.upcallStub;
import static com.sun.glass.ui.gtk.GtkPixelsCalls.G_OBJECT_UNREF;
import static com.sun.glass.ui.gtk.GtkRobotCalls.G_FREE_CALL;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * The platform preferences of the Linux glass:
 * {@code GtkApplication.getPlatformPreferences} ({@code GlassApplication.cpp} at commit {@code 033187ad90} ->
 * {@code PlatformSupport.cpp}), and the GtkSettings and GNetworkMonitor handlers that {@code PlatformSupport}
 * connected from {@code _init}, which are upcall stubs of this class.
 */
final class GtkPreferenceCalls {

    /** {@code GdkColor}: {@code guint32 pixel}, then {@code guint16 red, green, blue}. */
    private static final long GDK_COLOR_SIZE = 12;

    /** {@code G_CONNECT_AFTER} of {@code gsignal.h}. */
    private static final int G_CONNECT_AFTER = 1;

    /** {@code GtkStyle *gtk_style_new(void)}. */
    private static final MethodHandle GTK_STYLE_NEW = bind(GTK, LIB_GTK, "gtk_style_new",
            FunctionDescriptor.of(ADDRESS));

    /** {@code gboolean gtk_style_lookup_color(GtkStyle *style, const gchar *color_name, GdkColor *color)}. */
    private static final MethodHandle GTK_STYLE_LOOKUP_COLOR = bind(GTK, LIB_GTK, "gtk_style_lookup_color",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));

    /**
     * {@code GParamSpec *g_object_class_find_property(GObjectClass *oclass, const gchar *property_name)}; the
     * class comes from the first pointer of the instance, which is what {@code G_OBJECT_GET_CLASS} reads.
     */
    private static final MethodHandle G_OBJECT_CLASS_FIND_PROPERTY = bind(GOBJECT, LIB_GOBJECT,
            "g_object_class_find_property", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));

    /** {@code GNetworkMonitor *g_network_monitor_get_default(void)}. */
    private static final MethodHandle G_NETWORK_MONITOR_GET_DEFAULT = bind(GIO, LIB_GIO,
            "g_network_monitor_get_default", FunctionDescriptor.of(ADDRESS));

    /** {@code gboolean g_network_monitor_get_network_metered(GNetworkMonitor *monitor)}. */
    private static final MethodHandle G_NETWORK_MONITOR_GET_NETWORK_METERED = bind(GIO, LIB_GIO,
            "g_network_monitor_get_network_metered", FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /**
     * {@code gulong g_signal_connect_data(gpointer instance, const gchar *detailed_signal, GCallback c_handler,
     * gpointer data, GClosureNotify destroy_data, GConnectFlags connect_flags)}.
     */
    static final MethodHandle G_SIGNAL_CONNECT_DATA = bind(GOBJECT, LIB_GOBJECT, "g_signal_connect_data",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT));

    /** {@code void g_signal_handler_disconnect(gpointer instance, gulong handler_id)}. */
    static final MethodHandle G_SIGNAL_HANDLER_DISCONNECT = bind(GOBJECT, LIB_GOBJECT,
            "g_signal_handler_disconnect", FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG));

    /** {@code void (*)(GObject *, GParamSpec *, gpointer)}. */
    private static final MemorySegment SETTING_CHANGED_CALLBACK = upcallStub(GtkPreferenceCalls.class,
            "onSettingChanged",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));

    /** {@code void (*)(GNetworkMonitor *, gboolean, gpointer)}. */
    private static final MemorySegment NETWORK_CHANGED_CALLBACK = upcallStub(GtkPreferenceCalls.class,
            "onNetworkChanged",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS));

    /** {@code PlatformSupport::OBSERVED_SETTINGS} ({@code PlatformSupport.h}), in its order. */
    private static final String[] OBSERVED_SETTINGS = {
        "notify::gtk-theme-name",
        "notify::gtk-enable-animations",
        "notify::gtk-overlay-scrolling"
    };

    /** The {@code lookupColorName -> prefColorName} pairs of {@code collectPreferences}, in its order. */
    private static final String[] PREFERENCE_COLORS = {
        "theme_fg_color", "theme_bg_color", "theme_base_color", "theme_selected_bg_color",
        "theme_selected_fg_color", "insensitive_bg_color", "insensitive_fg_color", "insensitive_base_color",
        "theme_unfocused_fg_color", "theme_unfocused_bg_color", "theme_unfocused_base_color",
        "theme_unfocused_selected_bg_color", "theme_unfocused_selected_fg_color", "borders", "unfocused_borders",
        "warning_color", "error_color", "success_color"
    };

    /**
     * The one {@code PlatformSupport} of the process, which {@code _init} created and {@code _terminateLoop}
     * deleted ({@code GlassApplication.cpp}); {@code null} outside that window, where
     * {@code getPlatformPreferences} answered {@code NULL}.
     */
    private static PlatformSupport platformSupport;

    /**
     * {@code PlatformSupport} ({@code PlatformSupport.cpp}): the GtkSettings and GNetworkMonitor signal handlers
     * that report a preference change to the application, and the preferences last reported.
     */
    private static final class PlatformSupport {

        private final GtkApplication application;
        private final MemorySegment settings;
        private final long[] settingChangedHandlers = new long[OBSERVED_SETTINGS.length];
        private final long networkChangedHandler;
        private Map<String, Object> preferences;

        private PlatformSupport(GtkApplication application) throws Throwable {
            this.application = application;
            settings = (MemorySegment) GTK_SETTINGS_GET_DEFAULT.invokeExact();
            try (Arena call = Arena.ofConfined()) {
                if (settings.address() != 0) {
                    for (int i = 0; i < OBSERVED_SETTINGS.length; i++) {
                        settingChangedHandlers[i] = (long) G_SIGNAL_CONNECT_DATA.invokeExact(settings,
                                call.allocateFrom(OBSERVED_SETTINGS[i]), SETTING_CHANGED_CALLBACK,
                                MemorySegment.NULL, MemorySegment.NULL, G_CONNECT_AFTER);
                    }
                }
                MemorySegment monitor = (MemorySegment) G_NETWORK_MONITOR_GET_DEFAULT.invokeExact();
                networkChangedHandler = (long) G_SIGNAL_CONNECT_DATA.invokeExact(monitor,
                        call.allocateFrom("network-changed"), NETWORK_CHANGED_CALLBACK, MemorySegment.NULL,
                        MemorySegment.NULL, G_CONNECT_AFTER);
            }
        }

        /** {@code ~PlatformSupport}. */
        private void dispose() throws Throwable {
            MemorySegment current = (MemorySegment) GTK_SETTINGS_GET_DEFAULT.invokeExact();
            for (long handler : settingChangedHandlers) {
                if (handler != 0) {
                    G_SIGNAL_HANDLER_DISCONNECT.invokeExact(current, handler);
                }
            }
            MemorySegment monitor = (MemorySegment) G_NETWORK_MONITOR_GET_DEFAULT.invokeExact();
            G_SIGNAL_HANDLER_DISCONNECT.invokeExact(monitor, networkChangedHandler);
        }
    }

    /**
     * The {@code new PlatformSupport(env, obj)} that {@code Java_com_sun_glass_ui_gtk_GtkApplication__1init}
     * ran last ({@code GlassApplication.cpp}): connects the preference handlers for the application's lifetime.
     */
    static void platformSupportCreate(GtkApplication application) {
        try {
            platformSupport = new PlatformSupport(application);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * The {@code delete platformSupport} of
     * {@code Java_com_sun_glass_ui_gtk_GtkApplication__1terminateLoop} ({@code GlassApplication.cpp}).
     */
    static void platformSupportDestroy() {
        PlatformSupport support = platformSupport;
        if (support != null) {
            platformSupport = null;
            try {
                support.dispose();
            } catch (Throwable t) {
                throw unexpected(t);
            }
        }
    }

    /**
     * {@code Java_com_sun_glass_ui_gtk_GtkApplication_getPlatformPreferences} ({@code GlassApplication.cpp}):
     * {@code platformSupport->collectPreferences()}, or {@code null} before {@code _init} and after
     * {@code _terminateLoop}.
     */
    static Map<String, Object> platformPreferences() {
        return platformSupport == null ? null : collectPreferences();
    }

    /**
     * {@code PlatformSupport::collectPreferences} ({@code PlatformSupport.cpp}). A colour the style does not define, a
     * {@code NULL} theme name and an absent {@code gtk-overlay-scrolling} property leave their key out, as the C did.
     */
    private static Map<String, Object> collectPreferences() {
        try (Arena call = Arena.ofConfined()) {
            Map<String, Object> prefs = new HashMap<>();
            MemorySegment style = (MemorySegment) GTK_STYLE_NEW.invokeExact();
            if (style.address() == 0) {
                return null;
            }
            MemorySegment color = call.allocate(GDK_COLOR_SIZE);
            for (String name : PREFERENCE_COLORS) {
                putColor(prefs, style, call.allocateFrom(name), color, "GTK." + name);
            }
            G_OBJECT_UNREF.invokeExact(style);

            MemorySegment settings = (MemorySegment) GTK_SETTINGS_GET_DEFAULT.invokeExact();
            if (settings.address() != 0) {
                MemorySegment out = call.allocate(ADDRESS);
                G_OBJECT_GET_ONE.invokeExact(settings, call.allocateFrom("gtk-theme-name"), out,
                        MemorySegment.NULL);
                MemorySegment themeName = out.get(ADDRESS, 0);
                putString(prefs, "GTK.theme_name", themeName);
                G_FREE_CALL.invokeExact(themeName);

                MemorySegment flag = call.allocate(JAVA_INT);
                flag.set(JAVA_INT, 0, 1);
                G_OBJECT_GET_ONE.invokeExact(settings, call.allocateFrom("gtk-enable-animations"), flag,
                        MemorySegment.NULL);
                putBoolean(prefs, "GTK.enable_animations", flag.get(JAVA_INT, 0) != 0);

                MemorySegment settingsClass = bounded(settings, ADDRESS.byteSize()).get(ADDRESS, 0);
                MemorySegment overlay = (MemorySegment) G_OBJECT_CLASS_FIND_PROPERTY.invokeExact(settingsClass,
                        call.allocateFrom("gtk-overlay-scrolling"));
                if (overlay.address() != 0) {
                    flag.set(JAVA_INT, 0, 1);
                    G_OBJECT_GET_ONE.invokeExact(settings, call.allocateFrom("gtk-overlay-scrolling"), flag,
                            MemorySegment.NULL);
                    putBoolean(prefs, "GTK.overlay_scrolling", flag.get(JAVA_INT, 0) != 0);
                }
            }

            MemorySegment monitor = (MemorySegment) G_NETWORK_MONITOR_GET_DEFAULT.invokeExact();
            int metered = (int) G_NETWORK_MONITOR_GET_NETWORK_METERED.invokeExact(monitor);
            putBoolean(prefs, "GTK.network_metered", metered != 0);
            return prefs;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code putColor} ({@code PlatformSupport.cpp}): a throw is reported and the key left out, which is what
     * {@code CHECK_JNI_EXCEPTION} did.
     */
    private static void putColor(Map<String, Object> prefs, MemorySegment style, MemorySegment lookupName,
                                 MemorySegment color, String key) throws Throwable {
        if ((int) GTK_STYLE_LOOKUP_COLOR.invokeExact(style, lookupName, color) == 0) {
            return;
        }
        int red = Short.toUnsignedInt(color.get(JAVA_SHORT, 4));
        int green = Short.toUnsignedInt(color.get(JAVA_SHORT, 6));
        int blue = Short.toUnsignedInt(color.get(JAVA_SHORT, 8));
        try {
            prefs.put(key, Color.rgb(channel(red), channel(green), channel(blue), 1.0));
        } catch (Throwable t) {
            reportException(t);
        }
    }

    /** {@code (int) (CLAMP((double) c / 65535.0, 0.0, 1.0) * 255.0)} of {@code putColor}. */
    private static int channel(int value) {
        double scaled = value / 65535.0;
        return (int) (Math.max(0.0, Math.min(1.0, scaled)) * 255.0);
    }

    /**
     * {@code putString} ({@code PlatformSupport.cpp}): the value decoded as {@code NewStringUTF} decoded it, and
     * nothing at all for a {@code NULL} pointer, whose {@code NewStringUTF} answered {@code NULL}.
     */
    private static void putString(Map<String, Object> prefs, String key, MemorySegment value) throws Throwable {
        if (value.address() == 0) {
            return;
        }
        prefs.put(key, JniStringCodec.fromNewStringUtf(cString(value)));
    }

    private static void putBoolean(Map<String, Object> prefs, String key, boolean value) {
        prefs.put(key, value ? Boolean.TRUE : Boolean.FALSE);
    }

    /**
     * {@code notifySettingChanged} ({@code PlatformSupport.cpp}), the {@code notify::} handler of the three
     * observed GtkSettings properties.
     */
    static void onSettingChanged(MemorySegment object, MemorySegment paramSpec, MemorySegment data) {
        updatePreferences();
    }

    /** {@code notifyNetworkChanged} ({@code PlatformSupport.cpp}), the GNetworkMonitor handler. */
    static void onNetworkChanged(MemorySegment monitor, int available, MemorySegment data) {
        updatePreferences();
    }

    /**
     * {@code PlatformSupport::updatePreferences} ({@code PlatformSupport.cpp}). Nothing may leave this method: it
     * runs in an upcall stub, and the C reported and cleared every exception of this path.
     */
    private static void updatePreferences() {
        try {
            PlatformSupport support = platformSupport;
            if (support == null) {
                return;
            }
            Map<String, Object> newPreferences = collectPreferences();
            boolean preferencesChanged;
            try {
                preferencesChanged = newPreferences != null && !newPreferences.equals(support.preferences);
            } catch (Throwable t) {
                reportException(t);
                return;
            }
            if (preferencesChanged) {
                support.preferences = newPreferences;
                Map<String, Object> unmodifiable = Collections.unmodifiableMap(newPreferences);
                try {
                    support.application.notifyPlatformPreferencesChanged(unmodifiable);
                } catch (Throwable t) {
                    reportException(t);
                }
            }
        } catch (Throwable t) {
            reportException(t);
        }
    }

    private GtkPreferenceCalls() {
    }
}
