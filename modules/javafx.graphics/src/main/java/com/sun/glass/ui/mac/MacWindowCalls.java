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
import java.nio.ByteBuffer;
import java.util.function.LongSupplier;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_POOL_POP;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_POOL_PUSH;
import static com.sun.glass.ui.mac.MacGlassNative.bind;
import static com.sun.glass.ui.mac.MacGlassNative.mainThreadDetached;
import static com.sun.glass.ui.mac.MacGlassNative.mainThreadDetachedError;
import static com.sun.glass.ui.mac.MacGlassNative.reportException;
import static com.sun.glass.ui.mac.MacGlassNative.unexpected;
import static com.sun.glass.ui.mac.MacGlassNative.utf16;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The natives of {@code MacWindow}: the {@code gmac_window_*} exports, which this class binds in header order when it
 * initializes, and {@code _isRightToLeftLayoutDirection} over CoreFoundation. FX thread.
 * <p>
 * The calls that take a window answer 0 at once for a NULL one, as the JNI natives returned before their
 * {@code GLASS_ASSERT_MAIN_JAVA_THREAD}. Otherwise each evaluates {@code gmac_main_thread_detached} where that
 * assertion ran, makes its downcall all the same and reports the error once afterwards, where the closing
 * {@code GLASS_CHECK_EXCEPTION} did; none throws it. {@link #toFront} had no assertion.
 */
final class MacWindowCalls {

    /** {@code kCFLocaleLanguageDirectionRightToLeft}, which is {@code NSLocaleLanguageDirectionRightToLeft}. */
    static final long LANGUAGE_DIRECTION_RIGHT_TO_LEFT = 2;

    /** A downcall that answers {@code int32_t}, given the non-NULL window. */
    @FunctionalInterface
    interface Downcall {

        int call(MemorySegment window) throws Throwable;
    }

    /**
     * A downcall that answers nothing, given the non-NULL window. Its lambdas need a block body: as a lambda's
     * expression body, {@code invokeExact} would be typed to answer {@code Object}.
     */
    @FunctionalInterface
    interface VoidDowncall {

        void call(MemorySegment window) throws Throwable;
    }

    /** {@code gmac_window_create}, so that a test can stand where AppKit would. */
    @FunctionalInterface
    interface WindowFactory {

        long create(long owner, long screen, int styleMask, long windowId);
    }

    private static final MethodHandle GMAC_WINDOW_CREATE = bind("gmac_window_create",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_LONG));
    private static final MethodHandle GMAC_WINDOW_CLOSE = bind("gmac_window_close",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle GMAC_WINDOW_LEAVE_NATIVE_FULL_SCREEN = bind(
            "gmac_window_leave_native_full_screen", FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle GMAC_WINDOW_SET_VIEW = bind("gmac_window_set_view",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle GMAC_WINDOW_SET_MENUBAR = bind("gmac_window_set_menubar",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle GMAC_WINDOW_MINIMIZE = bind("gmac_window_minimize",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle GMAC_WINDOW_MAXIMIZE = bind("gmac_window_maximize",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
    private static final MethodHandle GMAC_WINDOW_SET_BOUNDS = bind("gmac_window_set_bounds",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT,
                    JAVA_INT, JAVA_FLOAT, JAVA_FLOAT));
    private static final MethodHandle GMAC_WINDOW_SET_VISIBLE = bind("gmac_window_set_visible",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle GMAC_WINDOW_SET_RESIZABLE = bind("gmac_window_set_resizable",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle GMAC_WINDOW_REQUEST_FOCUS = bind("gmac_window_request_focus",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle GMAC_WINDOW_SET_FOCUSABLE = bind("gmac_window_set_focusable",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
    private static final MethodHandle GMAC_WINDOW_SET_TITLE = bind("gmac_window_set_title",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
    private static final MethodHandle GMAC_WINDOW_SET_LEVEL = bind("gmac_window_set_level",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
    private static final MethodHandle GMAC_WINDOW_SET_ALPHA = bind("gmac_window_set_alpha",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_FLOAT));
    private static final MethodHandle GMAC_WINDOW_SET_BACKGROUND = bind("gmac_window_set_background",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT));
    private static final MethodHandle GMAC_WINDOW_SET_ENABLED = bind("gmac_window_set_enabled",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
    private static final MethodHandle GMAC_WINDOW_SET_MINIMUM_SIZE = bind("gmac_window_set_minimum_size",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
    private static final MethodHandle GMAC_WINDOW_SET_MAXIMUM_SIZE = bind("gmac_window_set_maximum_size",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
    private static final MethodHandle GMAC_WINDOW_SET_ICON = bind("gmac_window_set_icon",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT));
    private static final MethodHandle GMAC_WINDOW_SET_DARK_FRAME = bind("gmac_window_set_dark_frame",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
    private static final MethodHandle GMAC_WINDOW_TO_FRONT = bind("gmac_window_to_front",
            FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle GMAC_WINDOW_TO_BACK = bind("gmac_window_to_back",
            FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle GMAC_WINDOW_GRAB_FOCUS = bind("gmac_window_grab_focus",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle GMAC_WINDOW_UNGRAB_FOCUS = bind("gmac_window_ungrab_focus",
            FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle GMAC_WINDOW_PERFORM_DRAG = bind("gmac_window_perform_drag",
            FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle GMAC_WINDOW_PERFORM_TITLE_BAR_DOUBLE_CLICK_ACTION = bind(
            "gmac_window_perform_title_bar_double_click_action", FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle GMAC_WINDOW_SET_BUTTON_STYLE = bind("gmac_window_set_button_style",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT));
    private static final MethodHandle GMAC_WINDOW_SET_BUTTON_APPEARANCE = bind("gmac_window_set_button_appearance",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));

    private MacWindowCalls() {
    }

    static long create(MacWindowCallbacks.Receiver window, long owner, long screen, int styleMask) {
        return create(window, owner, screen, styleMask, MacWindowCalls::windowCreate);
    }

    /**
     * {@code _createWindow}. The id is registered first, because AppKit dials slots for it before the export
     * returns, and only {@code notify_dispose} removes it.
     */
    static long create(MacWindowCallbacks.Receiver window, long owner, long screen, int styleMask,
                       WindowFactory factory) {
        boolean detached = mainThreadDetached();
        long windowId = MacWindowCallbacks.register(window);
        long created = factory.create(owner, screen, styleMask, windowId);
        reportIfDetached(detached);
        return created;
    }

    static boolean close(long window) {
        return call(window, w -> (int) GMAC_WINDOW_CLOSE.invokeExact(w)) != 0;
    }

    /**
     * {@code _setView}. {@code view} answers the {@code GlassView3D*} of {@code View.ptr}, 0 for no view. It is read
     * only once the window has left native full screen, where the JNI read the field: the nested loop of that wait
     * runs Java, which can close the view.
     */
    static boolean setView(long window, LongSupplier view) {
        return call(window, w -> {
            GMAC_WINDOW_LEAVE_NATIVE_FULL_SCREEN.invokeExact(w);
            return (int) GMAC_WINDOW_SET_VIEW.invokeExact(w, MemorySegment.ofAddress(view.getAsLong()));
        }) != 0;
    }

    /** {@code menubar} is the {@code GlassMenubar*} of {@code MacMenuBarDelegate.ptr}. */
    static boolean setMenubar(long window, long menubar) {
        return call(window, w -> (int) GMAC_WINDOW_SET_MENUBAR.invokeExact(w, MemorySegment.ofAddress(menubar))) != 0;
    }

    static boolean minimize(long window, boolean minimize) {
        return call(window, w -> (int) GMAC_WINDOW_MINIMIZE.invokeExact(w, flag(minimize))) != 0;
    }

    static boolean maximize(long window, boolean maximize, boolean isZoomed) {
        return call(window, w -> (int) GMAC_WINDOW_MAXIMIZE.invokeExact(w, flag(maximize), flag(isZoomed))) != 0;
    }

    static void setBounds(long window, int x, int y, boolean xSet, boolean ySet, int width, int height,
                          int contentWidth, int contentHeight, float xGravity, float yGravity) {
        run(window, w -> {
            GMAC_WINDOW_SET_BOUNDS.invokeExact(w, x, y, flag(xSet), flag(ySet), width, height, contentWidth,
                    contentHeight, xGravity, yGravity);
        });
    }

    static boolean setVisible(long window, boolean visible) {
        return call(window, w -> (int) GMAC_WINDOW_SET_VISIBLE.invokeExact(w, flag(visible))) != 0;
    }

    static boolean setResizable(long window, boolean resizable) {
        return call(window, w -> (int) GMAC_WINDOW_SET_RESIZABLE.invokeExact(w, flag(resizable))) != 0;
    }

    static boolean requestFocus(long window) {
        return call(window, w -> (int) GMAC_WINDOW_REQUEST_FOCUS.invokeExact(w)) != 0;
    }

    static void setFocusable(long window, boolean focusable) {
        run(window, w -> {
            GMAC_WINDOW_SET_FOCUSABLE.invokeExact(w, flag(focusable));
        });
    }

    /** The code units of {@code title} live in a confined arena for the call; a null title crosses as NULL and 0. */
    static boolean setTitle(long window, String title) {
        return call(window, w -> {
            try (Arena arena = Arena.ofConfined()) {
                int length = title != null ? title.length() : 0;
                return (int) GMAC_WINDOW_SET_TITLE.invokeExact(w, utf16(arena, title), length);
            }
        }) != 0;
    }

    static void setLevel(long window, int level) {
        run(window, w -> {
            GMAC_WINDOW_SET_LEVEL.invokeExact(w, level);
        });
    }

    static void setAlpha(long window, float alpha) {
        run(window, w -> {
            GMAC_WINDOW_SET_ALPHA.invokeExact(w, alpha);
        });
    }

    static boolean setBackground(long window, float red, float green, float blue) {
        return call(window, w -> (int) GMAC_WINDOW_SET_BACKGROUND.invokeExact(w, red, green, blue)) != 0;
    }

    static void setEnabled(long window, boolean enabled) {
        run(window, w -> {
            GMAC_WINDOW_SET_ENABLED.invokeExact(w, flag(enabled));
        });
    }

    static boolean setMinimumSize(long window, int width, int height) {
        return call(window, w -> (int) GMAC_WINDOW_SET_MINIMUM_SIZE.invokeExact(w, width, height)) != 0;
    }

    static boolean setMaximumSize(long window, int width, int height) {
        return call(window, w -> (int) GMAC_WINDOW_SET_MAXIMUM_SIZE.invokeExact(w, width, height)) != 0;
    }

    /**
     * The base address of a direct {@code pixels}, as {@code GetDirectBufferAddress} gave it, whatever its position.
     * A buffer that is not direct has no address, so it crosses as NULL, as a null one does: both remove the icon.
     */
    static void setIcon(long window, ByteBuffer pixels, int width, int height) {
        run(window, w -> {
            GMAC_WINDOW_SET_ICON.invokeExact(w, baseAddress(pixels), width, height);
        });
    }

    static void setDarkFrame(long window, boolean dark) {
        run(window, w -> {
            GMAC_WINDOW_SET_DARK_FRAME.invokeExact(w, flag(dark));
        });
    }

    static void toFront(long window) {
        runUnasserted(window, w -> {
            GMAC_WINDOW_TO_FRONT.invokeExact(w);
        });
    }

    static void toBack(long window) {
        run(window, w -> {
            GMAC_WINDOW_TO_BACK.invokeExact(w);
        });
    }

    static boolean grabFocus(long window) {
        return call(window, w -> (int) GMAC_WINDOW_GRAB_FOCUS.invokeExact(w)) != 0;
    }

    static void ungrabFocus(long window) {
        run(window, w -> {
            GMAC_WINDOW_UNGRAB_FOCUS.invokeExact(w);
        });
    }

    static void performDrag(long window) {
        run(window, w -> {
            GMAC_WINDOW_PERFORM_DRAG.invokeExact(w);
        });
    }

    static void performTitleBarDoubleClickAction(long window) {
        run(window, w -> {
            GMAC_WINDOW_PERFORM_TITLE_BAR_DOUBLE_CLICK_ACTION.invokeExact(w);
        });
    }

    static void setButtonStyle(long window, int toolbarStyle, boolean buttonsVisible) {
        run(window, w -> {
            GMAC_WINDOW_SET_BUTTON_STYLE.invokeExact(w, toolbarStyle, flag(buttonsVisible));
        });
    }

    static void setButtonAppearance(long window, boolean dark) {
        run(window, w -> {
            GMAC_WINDOW_SET_BUTTON_APPEARANCE.invokeExact(w, flag(dark));
        });
    }

    /**
     * {@code _isRightToLeftLayoutDirection}, which has no window: its assertion ran before its whole body, and the
     * body ran inside a {@code GLASS_POOL_ENTER}/{@code EXIT} pair, which {@code gmac_pool_push}/{@code _pop} are.
     */
    static boolean isRightToLeftLayoutDirection() {
        boolean detached = mainThreadDetached();
        boolean rightToLeft;
        try {
            GMAC_POOL_PUSH.invokeExact();
            try {
                rightToLeft = preferredLanguageDirection() == LANGUAGE_DIRECTION_RIGHT_TO_LEFT;
            } finally {
                GMAC_POOL_POP.invokeExact();
            }
        } catch (Throwable t) {
            throw unexpected(t);
        }
        reportIfDetached(detached);
        return rightToLeft;
    }

    /**
     * The character direction of the language of the first preferred language, through the CoreFoundation functions
     * that the JNI's toll-free bridged {@code NSLocale} messages ran. No step is guarded, as none was: with no
     * preferred language {@code CFArrayGetValueAtIndex} traps, as {@code -[NSArray objectAtIndex:]} did.
     */
    static long preferredLanguageDirection() {
        try {
            MemorySegment languages = (MemorySegment) CoreFoundation.CF_LOCALE_COPY_PREFERRED_LANGUAGES.invokeExact();
            MemorySegment first = (MemorySegment) CoreFoundation.CF_ARRAY_GET_VALUE_AT_INDEX.invokeExact(languages,
                    0L);
            MemorySegment locale = (MemorySegment) CoreFoundation.CF_LOCALE_CREATE.invokeExact(MemorySegment.NULL,
                    first);
            MemorySegment languageCodeKey = (MemorySegment) CoreFoundation.K_CF_LOCALE_LANGUAGE_CODE.invokeExact();
            MemorySegment languageCode = (MemorySegment) CoreFoundation.CF_LOCALE_GET_VALUE.invokeExact(locale,
                    languageCodeKey);
            long direction = (long) CoreFoundation.CF_LOCALE_GET_LANGUAGE_CHARACTER_DIRECTION.invokeExact(
                    languageCode);
            CoreFoundation.CF_RELEASE.invokeExact(locale);
            CoreFoundation.CF_RELEASE.invokeExact(languages);
            return direction;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * The frame of the JNI natives that returned a value: 0 for the NULL window, before the assertion; else the
     * downcall, then the detached report.
     */
    static int call(long window, Downcall downcall) {
        if (window == 0L) {
            return 0;
        }
        boolean detached = mainThreadDetached();
        int result;
        try {
            result = downcall.call(MemorySegment.ofAddress(window));
        } catch (Throwable t) {
            throw unexpected(t);
        }
        reportIfDetached(detached);
        return result;
    }

    /** {@link #call} for the natives that returned nothing. */
    static void run(long window, VoidDowncall downcall) {
        call(window, w -> {
            downcall.call(w);
            return 0;
        });
    }

    /** {@link #run} without the assertion, which {@code _toFront} alone lacked. */
    static void runUnasserted(long window, VoidDowncall downcall) {
        if (window == 0L) {
            return;
        }
        try {
            downcall.call(MemorySegment.ofAddress(window));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** What {@link #setIcon} gives {@code gmac_window_set_icon} for {@code pixels}. */
    static MemorySegment baseAddress(ByteBuffer pixels) {
        if (pixels == null || !pixels.isDirect()) {
            return MemorySegment.NULL;
        }
        return MemorySegment.ofBuffer(pixels.duplicate().clear());
    }

    private static long windowCreate(long owner, long screen, int styleMask, long windowId) {
        try {
            MemorySegment created = (MemorySegment) GMAC_WINDOW_CREATE.invokeExact(MemorySegment.ofAddress(owner),
                    MemorySegment.ofAddress(screen), styleMask, windowId);
            return created.address();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static int flag(boolean value) {
        return value ? 1 : 0;
    }

    private static void reportIfDetached(boolean detached) {
        if (detached) {
            reportException(mainThreadDetachedError());
        }
    }

    /** Bound on the first {@link #preferredLanguageDirection}, not with the window exports. */
    private static final class CoreFoundation {

        private static final SystemLibrary LIBRARY = SystemLibrary.framework("CoreFoundation");

        static final MethodHandle CF_LOCALE_COPY_PREFERRED_LANGUAGES = LIBRARY.bind("CFLocaleCopyPreferredLanguages",
                FunctionDescriptor.of(ADDRESS));
        static final MethodHandle CF_ARRAY_GET_VALUE_AT_INDEX = LIBRARY.bind("CFArrayGetValueAtIndex",
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG));
        static final MethodHandle CF_LOCALE_CREATE = LIBRARY.bind("CFLocaleCreate",
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
        static final MethodHandle CF_LOCALE_GET_VALUE = LIBRARY.bind("CFLocaleGetValue",
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
        /* CFLocaleLanguageDirection is a CFIndex. */
        static final MethodHandle CF_LOCALE_GET_LANGUAGE_CHARACTER_DIRECTION = LIBRARY.bind(
                "CFLocaleGetLanguageCharacterDirection", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
        static final MethodHandle CF_RELEASE = LIBRARY.bind("CFRelease", FunctionDescriptor.ofVoid(ADDRESS));
        static final MethodHandle K_CF_LOCALE_LANGUAGE_CODE = LIBRARY.bindDataValue("kCFLocaleLanguageCode");
    }
}
