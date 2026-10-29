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

import com.sun.glass.ui.Pixels;
import com.sun.glass.ui.gtk.GtkCallbackTables.Glass;
import com.sun.glass.ui.gtk.GtkGlassNative.SetView;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import static com.sun.glass.ui.gtk.GtkGlassNative.reportException;
import static com.sun.glass.ui.gtk.GtkGlassNative.unexpected;
import static com.sun.glass.ui.gtk.GtkPixelsCalls.PIXELS_ATTACH_DATA;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_CHAR;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The natives of {@code GtkWindow} at commit {@code 033187ad90} ({@code GlassWindow.cpp}). Each replaces the
 * {@code Java_com_sun_glass_ui_gtk_GtkWindow_*} function it names, whose body is now the {@code ggtk_window_*}
 * function of {@code glass_gtk_api.h} (THE NATIVES) the method calls ({@link GtkCallbackTables.Glass}); what the JNI
 * function did with its Java arguments and result - the conversions, its calls into Java, the exception it threw or
 * left pending - is done here, in its order, around the downcall. A method runs on the thread that calls it, which is
 * the thread the JNI native ran on. The functions the header marks UPCALLS run Java inside the downcall (the callback
 * tables, nested main loops); their segments live in an arena that closes only when the downcall has returned.
 */
final class GtkWindowCalls {

    /**
     * {@code Java_com_sun_glass_ui_gtk_GtkWindow__1createWindow} ({@code GlassWindow.cpp}): a new
     * {@code WindowContextTop} whose slots receive {@code windowId}. {@code visualId} is
     * {@code GtkApplication.visualID}, which the JNI read inside the constructor. Dials only the application table's
     * {@code get_application_name}.
     *
     * @return the {@code WindowContext *}, never 0
     */
    static long windowCreate(long owner, long screen, int mask, long visualId, long windowId) {
        try {
            MemorySegment window = (MemorySegment) Glass.WINDOW_CREATE.invokeExact(MemorySegment.ofAddress(owner),
                    screen, mask, visualId, windowId);
            return window.address();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code Java_com_sun_glass_ui_gtk_GtkWindow__1close}: {@code destroy_and_delete_ctx}, whose
     * {@code notify_destroy} runs inside the downcall; {@code true}, the JNI's "return value not used".
     */
    static boolean windowClose(long window) {
        try {
            return (int) Glass.WINDOW_CLOSE.invokeExact(MemorySegment.ofAddress(window)) != 0;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code Java_com_sun_glass_ui_gtk_GtkWindow__1setView} ({@code GlassWindow.cpp}): {@code ggtk_window_set_view}
     * with {@code view}, the {@code GlassView *} of the {@code View} ({@code View.ptr}, which the JNI read with
     * {@code GetLongField}), 0 for none. The {@code EXIT} that {@code set_view} sends the view the window held reaches
     * Java inside the downcall; what it throws is for {@code GtkWindow._setView} to throw.
     */
    static SetView windowSetView(long window, long view) {
        try (Arena call = Arena.ofConfined()) {
            MemorySegment outViewId = call.allocate(JAVA_LONG);
            int result = (int) Glass.WINDOW_SET_VIEW.invokeExact(MemorySegment.ofAddress(window),
                    MemorySegment.ofAddress(view), outViewId);
            return new SetView(result != 0, outViewId.get(JAVA_LONG, 0));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code Java_com_sun_glass_ui_gtk_GtkWindow__1updateViewSize}: {@code update_view_size}. */
    static void windowUpdateViewSize(long window) {
        try {
            Glass.WINDOW_UPDATE_VIEW_SIZE.invokeExact(MemorySegment.ofAddress(window));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code Java_com_sun_glass_ui_gtk_GtkWindow_minimizeImpl}: {@code set_minimized}. */
    static void windowMinimize(long window, boolean minimize) {
        try {
            Glass.WINDOW_MINIMIZE.invokeExact(MemorySegment.ofAddress(window), minimize ? 1 : 0);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code Java_com_sun_glass_ui_gtk_GtkWindow_maximizeImpl}: {@code set_maximized}, {@code wasMaximized} unused. */
    static void windowMaximize(long window, boolean maximize, boolean wasMaximized) {
        try {
            Glass.WINDOW_MAXIMIZE.invokeExact(MemorySegment.ofAddress(window), maximize ? 1 : 0,
                    wasMaximized ? 1 : 0);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code Java_com_sun_glass_ui_gtk_GtkWindow__1setBounds}: {@code set_bounds} and its notifications. */
    static void windowSetBounds(long window, int x, int y, boolean xSet, boolean ySet, int w, int h, int cw, int ch,
                                float xGravity, float yGravity) {
        try {
            Glass.WINDOW_SET_BOUNDS.invokeExact(MemorySegment.ofAddress(window), x, y, xSet ? 1 : 0, ySet ? 1 : 0, w,
                    h, cw, ch, xGravity, yGravity);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code Java_com_sun_glass_ui_gtk_GtkWindow_setVisibleImpl}: {@code WindowContextTop::set_visible}. */
    static void windowSetVisible(long window, boolean visible) {
        try {
            Glass.WINDOW_SET_VISIBLE.invokeExact(MemorySegment.ofAddress(window), visible ? 1 : 0);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code Java_com_sun_glass_ui_gtk_GtkWindow__1setResizable}: {@code set_resizable}; {@code true}. */
    static boolean windowSetResizable(long window, boolean resizable) {
        try {
            return (int) Glass.WINDOW_SET_RESIZABLE.invokeExact(MemorySegment.ofAddress(window), resizable ? 1 : 0)
                    != 0;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code Java_com_sun_glass_ui_gtk_GtkWindow__1requestFocus}: {@code gtk_window_present}; {@code true}. */
    static boolean windowRequestFocus(long window, int event) {
        try {
            return (int) Glass.WINDOW_REQUEST_FOCUS.invokeExact(MemorySegment.ofAddress(window), event) != 0;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code Java_com_sun_glass_ui_gtk_GtkWindow__1setFocusable}: {@code gtk_window_set_accept_focus}. */
    static void windowSetFocusable(long window, boolean focusable) {
        try {
            Glass.WINDOW_SET_FOCUSABLE.invokeExact(MemorySegment.ofAddress(window), focusable ? 1 : 0);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code Java_com_sun_glass_ui_gtk_GtkWindow__1grabFocus}: {@code grab_focus}. */
    static boolean windowGrabFocus(long window) {
        try {
            return (int) Glass.WINDOW_GRAB_FOCUS.invokeExact(MemorySegment.ofAddress(window)) != 0;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code Java_com_sun_glass_ui_gtk_GtkWindow__1ungrabFocus}: {@code ungrab_focus} and its upcall. */
    static void windowUngrabFocus(long window) {
        try {
            Glass.WINDOW_UNGRAB_FOCUS.invokeExact(MemorySegment.ofAddress(window));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code Java_com_sun_glass_ui_gtk_GtkWindow__1setTitle} ({@code GlassWindow.cpp}): the title's UTF-16 code units
     * as {@code GetStringChars} gave them - copied as {@code char}s, lone surrogates included, never through a
     * charset - and their count, {@code NULL} for a {@code null} title; the C converts them with
     * {@code g_utf16_to_utf8}. An empty title is a non-{@code NULL} block, as {@code GetStringChars} answered one.
     */
    static boolean windowSetTitle(long window, String title) {
        try (Arena call = Arena.ofConfined()) {
            MemorySegment units = MemorySegment.NULL;
            int length = 0;
            if (title != null) {
                char[] chars = title.toCharArray();
                length = chars.length;
                units = call.allocate(JAVA_CHAR, Math.max(1, length));
                MemorySegment.copy(chars, 0, units, JAVA_CHAR, 0, length);
            }
            return (int) Glass.WINDOW_SET_TITLE.invokeExact(MemorySegment.ofAddress(window), units, length) != 0;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code Java_com_sun_glass_ui_gtk_GtkWindow__1setLevel}: {@code set_level}. */
    static void windowSetLevel(long window, int level) {
        try {
            Glass.WINDOW_SET_LEVEL.invokeExact(MemorySegment.ofAddress(window), level);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code Java_com_sun_glass_ui_gtk_GtkWindow__1setAlpha}: {@code gtk_window_set_opacity}. */
    static void windowSetAlpha(long window, float alpha) {
        try {
            Glass.WINDOW_SET_ALPHA.invokeExact(MemorySegment.ofAddress(window), alpha);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code Java_com_sun_glass_ui_gtk_GtkWindow__1setBackground}: {@code set_background}; {@code true}. */
    static boolean windowSetBackground(long window, float r, float g, float b) {
        try {
            return (int) Glass.WINDOW_SET_BACKGROUND.invokeExact(MemorySegment.ofAddress(window), r, g, b) != 0;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code Java_com_sun_glass_ui_gtk_GtkWindow__1setEnabled}: {@code set_enabled}. */
    static void windowSetEnabled(long window, boolean enabled) {
        try {
            Glass.WINDOW_SET_ENABLED.invokeExact(MemorySegment.ofAddress(window), enabled ? 1 : 0);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code Java_com_sun_glass_ui_gtk_GtkWindow__1setMinimumSize}: {@code false} for a negative size. */
    static boolean windowSetMinimumSize(long window, int w, int h) {
        try {
            return (int) Glass.WINDOW_SET_MINIMUM_SIZE.invokeExact(MemorySegment.ofAddress(window), w, h) != 0;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code Java_com_sun_glass_ui_gtk_GtkWindow__1setSystemMinimumSize}: {@code false} for a negative size. */
    static boolean windowSetSystemMinimumSize(long window, int w, int h) {
        try {
            return (int) Glass.WINDOW_SET_SYSTEM_MINIMUM_SIZE.invokeExact(MemorySegment.ofAddress(window), w, h) != 0;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code Java_com_sun_glass_ui_gtk_GtkWindow__1setMaximumSize}: {@code false} for a zero size. */
    static boolean windowSetMaximumSize(long window, int w, int h) {
        try {
            return (int) Glass.WINDOW_SET_MAXIMUM_SIZE.invokeExact(MemorySegment.ofAddress(window), w, h) != 0;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code Java_com_sun_glass_ui_gtk_GtkWindow__1setIcon} ({@code GlassWindow.cpp}): {@code pixels.attachData}
     * with the address of a {@code NULL} {@code GdkPixbuf *}, as the JNI passed the address of its local - a throw
     * of it reported as {@code EXCEPTION_OCCURED} reported it - then {@code ggtk_window_set_icon} with whatever was
     * written there, which sets the icon unless that threw and releases the pixbuf in either case.
     */
    static void windowSetIcon(long window, Pixels pixels) {
        try (Arena call = Arena.ofConfined()) {
            MemorySegment slot = call.allocate(ADDRESS);
            boolean threw = false;
            if (pixels != null) {
                try {
                    PIXELS_ATTACH_DATA.invokeExact(pixels, slot.address());
                } catch (Throwable t) {
                    reportException(t);
                    threw = true;
                }
            }
            Glass.WINDOW_SET_ICON.invokeExact(MemorySegment.ofAddress(window), slot.get(ADDRESS, 0), threw ? 1 : 0);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code Java_com_sun_glass_ui_gtk_GtkWindow__1toFront}: {@code gdk_window_raise}. */
    static void windowToFront(long window) {
        try {
            Glass.WINDOW_TO_FRONT.invokeExact(MemorySegment.ofAddress(window));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code Java_com_sun_glass_ui_gtk_GtkWindow__1toBack}: {@code gdk_window_lower}. */
    static void windowToBack(long window) {
        try {
            Glass.WINDOW_TO_BACK.invokeExact(MemorySegment.ofAddress(window));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code Java_com_sun_glass_ui_gtk_GtkWindow__1setCursorType}: the {@code GdkCursor} of {@code type}. */
    static void windowSetCursorType(long window, int type) {
        try {
            Glass.WINDOW_SET_CURSOR_TYPE.invokeExact(MemorySegment.ofAddress(window), type);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code Java_com_sun_glass_ui_gtk_GtkWindow__1setCustomCursor}: {@code cursor} is the {@code GdkCursor *} of the
     * {@code Cursor}, which the JNI read from its {@code ptr} field.
     */
    static void windowSetCursor(long window, long cursor) {
        try {
            Glass.WINDOW_SET_CURSOR.invokeExact(MemorySegment.ofAddress(window), MemorySegment.ofAddress(cursor));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code Java_com_sun_glass_ui_gtk_GtkWindow__1showSystemMenu}: {@code gdk_window_show_window_menu}. */
    static void windowShowSystemMenu(long window, int x, int y) {
        try {
            Glass.WINDOW_SHOW_SYSTEM_MENU.invokeExact(MemorySegment.ofAddress(window), x, y);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code Java_com_sun_glass_ui_gtk_GtkWindow_isVisible}: {@code gtk_widget_get_visible}. */
    static boolean windowIsVisible(long window) {
        try {
            return (int) Glass.WINDOW_IS_VISIBLE.invokeExact(MemorySegment.ofAddress(window)) != 0;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code Java_com_sun_glass_ui_gtk_GtkWindow__1getNativeWindowImpl}: the X11 window, 0 before it is realized. */
    static long windowNativeWindow(long window) {
        try {
            return (long) Glass.WINDOW_GET_NATIVE_WINDOW.invokeExact(MemorySegment.ofAddress(window));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private GtkWindowCalls() {
    }
}
