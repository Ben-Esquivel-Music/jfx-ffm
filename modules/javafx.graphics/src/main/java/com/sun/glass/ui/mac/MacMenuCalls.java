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

import com.sun.glass.ui.MenuItem.Callback;
import com.sun.glass.ui.Pixels;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.invoke.MethodHandle;
import static com.sun.glass.ui.mac.MacGlassNative.bind;
import static com.sun.glass.ui.mac.MacGlassNative.mainThreadDetached;
import static com.sun.glass.ui.mac.MacGlassNative.mainThreadDetachedError;
import static com.sun.glass.ui.mac.MacGlassNative.reportException;
import static com.sun.glass.ui.mac.MacGlassNative.unexpected;
import static com.sun.glass.ui.mac.MacGlassNative.utf16;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_CHAR;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The natives of {@code MacMenuBarDelegate} and {@code MacMenuDelegate}: the {@code gmac_menubar_*} and
 * {@code gmac_menu_*} exports, which this class binds in header order when it initializes, the
 * {@code GmacMenuImage} of an icon, and {@code _setCallback}, which has no export. FX thread.
 * <p>
 * Every native opened with {@code GLASS_ASSERT_MAIN_JAVA_THREAD}, whose exception HotSpot kept pending while the body
 * ran on (PB11), and closed with {@code GLASS_CHECK_EXCEPTION}. Each call evaluates {@code gmac_main_thread_detached}
 * first, makes its downcall all the same and reports the error once afterwards; none throws it. Where the JNI then
 * crashed on the main thread's NULL {@code JNIEnv} (latent bug L34), the export carries on. An icon's
 * {@code Pixels.attachData} runs before the downcall, where the JNI ran it inside the export's body: what it throws
 * replaced the assertion's exception under JNI, so it is reported in its place, after the downcall.
 * <p>
 * Titles cross as their UTF-16 code units, NULL for null, from a confined arena for the call; the exports copy them.
 * None of these downcalls may be critical: they run AppKit, and {@code gmac_menubar_insert},
 * {@code gmac_menubar_remove} and {@code gmac_menu_remove} dial {@code GmacMenuCallbacks} slots before they return.
 */
final class MacMenuCalls {

    static final StructLayout GMAC_MENU_IMAGE_LAYOUT = MemoryLayout.structLayout(
            ADDRESS.withName("image"),
            JAVA_INT.withName("width"),
            JAVA_INT.withName("height"),
            JAVA_FLOAT.withName("scale_x"),
            JAVA_FLOAT.withName("scale_y"));

    private static final long IMAGE = imageOffset("image");
    private static final long WIDTH = imageOffset("width");
    private static final long HEIGHT = imageOffset("height");
    private static final long SCALE_X = imageOffset("scale_x");
    private static final long SCALE_Y = imageOffset("scale_y");

    private static final MethodHandle GMAC_MENUBAR_CREATE = bind("gmac_menubar_create",
            FunctionDescriptor.of(ADDRESS));
    private static final MethodHandle GMAC_MENUBAR_INSERT = bind("gmac_menubar_insert",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT));
    private static final MethodHandle GMAC_MENUBAR_REMOVE = bind("gmac_menubar_remove",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT));
    private static final MethodHandle GMAC_MENU_CREATE = bind("gmac_menu_create",
            FunctionDescriptor.of(ADDRESS, JAVA_LONG, ADDRESS, JAVA_INT, JAVA_INT));
    private static final MethodHandle GMAC_MENU_ITEM_CREATE = bind("gmac_menu_item_create",
            FunctionDescriptor.of(ADDRESS, JAVA_LONG, ADDRESS, JAVA_INT, JAVA_CHAR, JAVA_INT, ADDRESS, JAVA_INT,
                    JAVA_INT));
    private static final MethodHandle GMAC_MENU_INSERT = bind("gmac_menu_insert",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT));
    private static final MethodHandle GMAC_MENU_REMOVE = bind("gmac_menu_remove",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT));
    private static final MethodHandle GMAC_MENU_SET_TITLE = bind("gmac_menu_set_title",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT));
    private static final MethodHandle GMAC_MENU_SET_SHORTCUT = bind("gmac_menu_set_shortcut",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_CHAR, JAVA_INT));
    private static final MethodHandle GMAC_MENU_SET_IMAGE = bind("gmac_menu_set_image",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    private static final MethodHandle GMAC_MENU_SET_ENABLED = bind("gmac_menu_set_enabled",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
    private static final MethodHandle GMAC_MENU_SET_CHECKED = bind("gmac_menu_set_checked",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));

    /**
     * A downcall that answers nothing. Its lambdas need a block body: as a lambda's expression body,
     * {@code invokeExact} would be typed to answer {@code Object}.
     */
    @FunctionalInterface
    private interface Downcall {

        void call() throws Throwable;
    }

    /** {@code gmac_menu_create}, so that a test can stand where AppKit would. */
    @FunctionalInterface
    interface MenuFactory {

        long create(long menuId, MemorySegment title, int titleLength, int enabled) throws Throwable;
    }

    /** {@code gmac_menu_item_create}, so that a test can stand where AppKit would. */
    @FunctionalInterface
    interface ItemFactory {

        long create(long menuId, MemorySegment title, int titleLength, char shortcut, int modifiers,
                    MemorySegment icon, int enabled, int checked) throws Throwable;
    }

    /** {@code gmac_menu_set_image}, so that a test can stand where AppKit would. */
    @FunctionalInterface
    interface ImageSetter {

        void set(MemorySegment menu, MemorySegment icon) throws Throwable;
    }

    private MacMenuCalls() {
    }

    /** {@code MacMenuBarDelegate._createMenuBar}: a {@code GlassMenubar*}, never released (latent bug L9). */
    static long createMenuBar() {
        boolean detached = mainThreadDetached();
        long menubar;
        try {
            menubar = ((MemorySegment) GMAC_MENUBAR_CREATE.invokeExact()).address();
        } catch (Throwable t) {
            throw unexpected(t);
        }
        reportIfDetached(detached);
        return menubar;
    }

    /** {@code MacMenuBarDelegate._insert}; validates the main menu's items before it returns. */
    static void menubarInsert(long menubar, long menu, int pos) {
        run(() -> {
            GMAC_MENUBAR_INSERT.invokeExact(address(menubar), address(menu), pos);
        });
    }

    /** {@code MacMenuBarDelegate._remove}; disposes of {@code menu} when the menu bar held it. */
    static void menubarRemove(long menubar, long menu, int pos) {
        run(() -> {
            GMAC_MENUBAR_REMOVE.invokeExact(address(menubar), address(menu), pos);
        });
    }

    static long createMenu(long menuId, String title, boolean enabled) {
        return createMenu(menuId, title, enabled, MacMenuCalls::menuCreate);
    }

    /**
     * {@code MacMenuDelegate._createMenu}. {@code menuId} is the id the delegate was registered under before the
     * call; it is forgotten when the export made no {@code GlassMenu}, which no {@code menu_disposed} will forget.
     */
    static long createMenu(long menuId, String title, boolean enabled, MenuFactory factory) {
        boolean detached = mainThreadDetached();
        long menu;
        try (Arena arena = Arena.ofConfined()) {
            menu = factory.create(menuId, utf16(arena, title), length(title), flag(enabled));
        } catch (Throwable t) {
            throw unexpected(t);
        }
        forgetIfUnmade(menuId, menu);
        reportIfDetached(detached);
        return menu;
    }

    static long createMenuItem(long menuId, String title, char shortcut, int modifiers, Pixels icon,
                               boolean enabled, boolean checked) {
        return createMenuItem(menuId, title, shortcut, modifiers, icon, enabled, checked, MacMenuCalls::itemCreate);
    }

    /**
     * {@code MacMenuDelegate._createMenuItem}, with {@code menuId} as for {@link #createMenu}. A null icon passes
     * NULL, as the JNI set no image without a {@code Pixels}.
     */
    static long createMenuItem(long menuId, String title, char shortcut, int modifiers, Pixels icon,
                               boolean enabled, boolean checked, ItemFactory factory) {
        Throwable pending = mainThreadDetached() ? mainThreadDetachedError() : null;
        long item;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment image = MemorySegment.NULL;
            if (icon != null) {
                image = arena.allocate(GMAC_MENU_IMAGE_LAYOUT);
                pending = attachIcon(icon, image, pending);
            }
            item = factory.create(menuId, utf16(arena, title), length(title), shortcut, modifiers, image,
                    flag(enabled), flag(checked));
        } catch (Throwable t) {
            throw unexpected(t);
        }
        forgetIfUnmade(menuId, item);
        report(pending);
        return item;
    }

    /** {@code MacMenuDelegate._insert}: {@code submenu} 0 adds a separator. */
    static void insert(long menu, long submenu, int pos) {
        run(() -> {
            GMAC_MENU_INSERT.invokeExact(address(menu), address(submenu), pos);
        });
    }

    /** {@code MacMenuDelegate._remove}: {@code submenu} 0 removes the item at {@code pos}. */
    static void remove(long menu, long submenu, int pos) {
        run(() -> {
            GMAC_MENU_REMOVE.invokeExact(address(menu), address(submenu), pos);
        });
    }

    static void setTitle(long menu, String title) {
        run(() -> {
            try (Arena arena = Arena.ofConfined()) {
                GMAC_MENU_SET_TITLE.invokeExact(address(menu), utf16(arena, title), length(title));
            }
        });
    }

    static void setShortcut(long menu, char shortcut, int modifiers) {
        run(() -> {
            GMAC_MENU_SET_SHORTCUT.invokeExact(address(menu), shortcut, modifiers);
        });
    }

    static void setPixels(long menu, Pixels pixels) {
        setPixels(menu, pixels, MacMenuCalls::setImage);
    }

    /**
     * {@code MacMenuDelegate._setPixels}: a null {@code pixels} passes NULL, which removes the item's image. As
     * messaging the nil {@code GlassMenu} did, a NULL {@code menu} calls no {@code attachData} and passes NULL.
     */
    static void setPixels(long menu, Pixels pixels, ImageSetter setter) {
        Throwable pending = mainThreadDetached() ? mainThreadDetachedError() : null;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment icon = MemorySegment.NULL;
            if (menu != 0L && pixels != null) {
                icon = arena.allocate(GMAC_MENU_IMAGE_LAYOUT);
                pending = attachIcon(pixels, icon, pending);
            }
            setter.set(address(menu), icon);
        } catch (Throwable t) {
            throw unexpected(t);
        }
        report(pending);
    }

    static void setEnabled(long menu, boolean enabled) {
        run(() -> {
            GMAC_MENU_SET_ENABLED.invokeExact(address(menu), flag(enabled));
        });
    }

    static void setChecked(long menu, boolean checked) {
        run(() -> {
            GMAC_MENU_SET_CHECKED.invokeExact(address(menu), flag(checked));
        });
    }

    /**
     * {@code MacMenuDelegate._setCallback}, which has no export: the JNI replaced the global reference to the
     * callback of the {@code GlassMenu} at {@code menu}, so the registry entry of {@code menuId}, that
     * {@code GlassMenu}'s id, takes {@code callback}. The JNI crashed on a NULL {@code menu} (latent bug L35), for
     * which nothing changes.
     */
    static void setCallback(long menuId, long menu, Callback callback) {
        boolean detached = mainThreadDetached();
        if (menu != 0L) {
            MacMenuCallbacks.setCallback(menuId, callback);
        }
        reportIfDetached(detached);
    }

    /** The frame of the natives without an icon: the downcall, then the detached report. */
    private static void run(Downcall downcall) {
        boolean detached = mainThreadDetached();
        try {
            downcall.call();
        } catch (Throwable t) {
            throw unexpected(t);
        }
        reportIfDetached(detached);
    }

    /**
     * Writes the {@code GmacMenuImage} of {@code pixels} to {@code icon}: the +1 {@code NSImage*}
     * {@code Pixels.attachData} wrote, NULL when it wrote none, and the size and scales the JNI read from the
     * {@code Pixels}' fields. Answers what {@code attachData} threw, which replaced {@code pending} under JNI, else
     * {@code pending}. The export takes whatever image was written all the same, as the JNI took its {@code image}.
     */
    private static Throwable attachIcon(Pixels pixels, MemorySegment icon, Throwable pending) {
        Throwable reported = pending;
        try {
            MacCursorCalls.attachData(pixels, icon.asSlice(IMAGE, ADDRESS.byteSize()));
        } catch (Throwable t) {
            reported = t;
        }
        icon.set(JAVA_INT, WIDTH, pixels.getWidthUnsafe());
        icon.set(JAVA_INT, HEIGHT, pixels.getHeightUnsafe());
        icon.set(JAVA_FLOAT, SCALE_X, pixels.getScaleXUnsafe());
        icon.set(JAVA_FLOAT, SCALE_Y, pixels.getScaleYUnsafe());
        return reported;
    }

    private static void forgetIfUnmade(long menuId, long menu) {
        if (menu == 0L) {
            MacMenuCallbacks.unregister(menuId);
        }
    }

    private static long menuCreate(long menuId, MemorySegment title, int titleLength, int enabled)
            throws Throwable {
        return ((MemorySegment) GMAC_MENU_CREATE.invokeExact(menuId, title, titleLength, enabled)).address();
    }

    private static long itemCreate(long menuId, MemorySegment title, int titleLength, char shortcut, int modifiers,
                                   MemorySegment icon, int enabled, int checked) throws Throwable {
        return ((MemorySegment) GMAC_MENU_ITEM_CREATE.invokeExact(menuId, title, titleLength, shortcut, modifiers,
                icon, enabled, checked)).address();
    }

    private static void setImage(MemorySegment menu, MemorySegment icon) throws Throwable {
        GMAC_MENU_SET_IMAGE.invokeExact(menu, icon);
    }

    private static MemorySegment address(long pointer) {
        return MemorySegment.ofAddress(pointer);
    }

    private static int length(String s) {
        return s != null ? s.length() : 0;
    }

    private static int flag(boolean value) {
        return value ? 1 : 0;
    }

    private static void reportIfDetached(boolean detached) {
        if (detached) {
            reportException(mainThreadDetachedError());
        }
    }

    private static void report(Throwable pending) {
        if (pending != null) {
            reportException(pending);
        }
    }

    private static long imageOffset(String member) {
        return GMAC_MENU_IMAGE_LAYOUT.byteOffset(PathElement.groupElement(member));
    }
}
