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

package com.sun.glass.ui.win;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.invoke.MethodHandle;
import static com.sun.glass.ui.win.WinCallSupport.reportUpcallFailure;
import static com.sun.glass.ui.win.WinCallSupport.requireOk;
import static com.sun.glass.ui.win.WinCallSupport.wideString;
import static com.sun.glass.ui.win.WinGlassNative.bindGlass;
import static com.sun.glass.ui.win.WinGlassNative.bindUser32;
import static com.sun.glass.ui.win.WinGlassNative.unexpected;
import static com.sun.glass.ui.win.WinGlassNative.upcallStub;
import static com.sun.glass.ui.win.WinGlassNative.upcallTarget;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_CHAR;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The {@link WinGlassNative} calls of {@code WinMenuImpl}: the eleven {@code user32} menu calls and the
 * {@code WM_COMMAND} callback table.
 * <p>
 * <b>The menus</b> (ABI 3) are the cleanest {@code WRAPPER} case in the whole of Glass: eleven of
 * {@code GlassMenu.cpp}'s twelve JNI bodies were marshalling around a single {@code user32} menu call
 * each, so all eleven are bound here and no {@code gwin_menu_*} <em>downcall</em> exists or is wanted. The
 * HMENU stays what it always was, an opaque handle value Java owns as a {@code long} ({@code WinMenuImpl.ptr});
 * only {@code MENUITEMINFOW} crosses, from a per-call confined arena, because user32 copies the
 * {@code MIIM_STRING} title into the menu and retains nothing. The twelfth body could not move, because
 * {@code HandleMenuCommand} is <em>called by</em> {@code GlassWindow::WindowProc}'s WM_COMMAND arm from inside
 * {@code DispatchMessage}: it is the two exports {@code GLASS_WIN_ABI_VERSION} 3 adds for the menus,
 * {@code gwin_menu_set_callbacks} and its {@code gwin_sizeof_menu_callbacks} probe. With them installed
 * {@code WinMenuImpl} declares no native at all, and {@code glass.dll} no longer holds a {@code jclass} global
 * ref for it.
 * <p>
 * Line numbers into the {@code native-glass/win} C++ sources ({@code GlassMenu.cpp}, {@code GlassWindow.cpp},
 * {@code Utils.h}, {@code Utils.cpp}, bare {@code :N} and {@code LN} forms included) refer to those files at
 * commit {@code 8492cb03b0} ({@code git show 8492cb03b0:modules/javafx.graphics/src/main/native-glass/win/<file>});
 * a bare number belongs to the C file named last before it.
 */
final class WinMenuCalls {

    /*
     * winuser.h menu flags. GlassMenu.cpp used two overlapping families and the difference is
     * load-bearing, so both are pinned here and neither is normalised into the other:
     *
     *   MIIM_* / MFT_* / MFS_* belong to MENUITEMINFOW, which InsertMenuItemW, SetMenuItemInfoW and
     *   GetMenuItemInfoW take;
     *   MF_* belong to the older positional calls, which InsertMenuW, RemoveMenu, EnableMenuItem and
     *   CheckMenuItem take.
     *
     * MFS_GRAYED is 3 (MF_GRAYED | MF_DISABLED) while MF_GRAYED is 1, and GlassMenu.cpp wrote
     * MFS_GRAYED when inserting (L172, L207) and MF_GRAYED when enabling/disabling (L325, L346).
     * Both are reproduced exactly.
     */
    static final int MIIM_STATE = 0x00000001;
    static final int MIIM_ID = 0x00000002;
    static final int MIIM_SUBMENU = 0x00000004;
    static final int MIIM_DATA = 0x00000020;
    static final int MIIM_STRING = 0x00000040;
    static final int MIIM_FTYPE = 0x00000100;

    static final int MFT_STRING = 0x00000000;
    static final int MFT_SEPARATOR = 0x00000800;

    static final int MFS_ENABLED = 0x00000000;
    static final int MFS_GRAYED = 0x00000003;
    static final int MFS_CHECKED = 0x00000008;
    static final int MFS_UNCHECKED = 0x00000000;

    static final int MF_BYCOMMAND = 0x00000000;
    static final int MF_BYPOSITION = 0x00000400;
    static final int MF_SEPARATOR = 0x00000800;
    static final int MF_ENABLED = 0x00000000;
    static final int MF_GRAYED = 0x00000001;
    static final int MF_CHECKED = 0x00000008;
    static final int MF_UNCHECKED = 0x00000000;

    /**
     * {@code GwinMenuCallbacks} ({@code glass_win_api.h}): one function pointer, the only upcall
     * {@code GlassMenu.cpp} ever made ({@code :83-84}, the one {@code CallStaticBooleanMethod} in the whole
     * file). Unlike {@link WinApplicationCalls#GWIN_APP_CALLBACKS_LAYOUT}'s slot this one <em>returns</em>, and the
     * return is load-bearing.
     */
    static final StructLayout GWIN_MENU_CALLBACKS_LAYOUT = MemoryLayout.structLayout(
            ADDRESS.withName("notify_command"));

    /**
     * {@code MENUITEMINFOW} (winuser.h {@code tagMENUITEMINFOW}) on LLP64, which is the only shape
     * this fork builds for Windows: 80 bytes, with four bytes of padding after the {@code UINT wID}
     * because {@code HMENU hSubMenu} is pointer-aligned, and four more after the {@code UINT cch}
     * because {@code HBITMAP hbmpItem} is.
     * <p>
     * <b>Getting this wrong does not fail loudly.</b> {@code cbSize} is not a checksum: user32 accepts
     * both {@code sizeof(MENUITEMINFOW)} and the pre-Windows-2000 compatibility size
     * {@code sizeof(MENUITEMINFOW) - sizeof(HBITMAP)}, which on x64 is exactly the 72 bytes this
     * layout would have if the first padding were forgotten. Every pointer field would then be eight
     * bytes low and {@code MIIM_STRING} would make user32 dereference whatever sat where
     * {@code hbmpUnchecked} was written - an access violation inside user32, not a rejected call. What
     * actually pins it is {@code WinMenuNativeTest}: the golden transcript it compares against was
     * captured by reading menus <em>built by the JNI</em>, that is by the C compiler's own
     * {@code MENUITEMINFOW}, through this layout. A layout that disagrees with the C struct cannot
     * produce that transcript.
     *
     * @see <a href="https://learn.microsoft.com/windows/win32/api/winuser/ns-winuser-menuiteminfow">MENUITEMINFOW</a>
     */
    static final StructLayout MENUITEMINFOW_LAYOUT = MemoryLayout.structLayout(
            JAVA_INT.withName("cbSize"),
            JAVA_INT.withName("fMask"),
            JAVA_INT.withName("fType"),
            JAVA_INT.withName("fState"),
            JAVA_INT.withName("wID"),
            MemoryLayout.paddingLayout(4),
            ADDRESS.withName("hSubMenu"),
            ADDRESS.withName("hbmpChecked"),
            ADDRESS.withName("hbmpUnchecked"),
            JAVA_LONG.withName("dwItemData"),
            ADDRESS.withName("dwTypeData"),
            JAVA_INT.withName("cch"),
            MemoryLayout.paddingLayout(4),
            ADDRESS.withName("hbmpItem"));

    private static final long NOTIFY_COMMAND_OFFSET =
            GWIN_MENU_CALLBACKS_LAYOUT.byteOffset(PathElement.groupElement("notify_command"));
    private static final int MENUITEMINFOW_SIZE = (int) MENUITEMINFOW_LAYOUT.byteSize();
    private static final long MII_CBSIZE_OFFSET = menuItemInfoOffset("cbSize");
    private static final long MII_FMASK_OFFSET = menuItemInfoOffset("fMask");
    private static final long MII_FTYPE_OFFSET = menuItemInfoOffset("fType");
    private static final long MII_FSTATE_OFFSET = menuItemInfoOffset("fState");
    private static final long MII_WID_OFFSET = menuItemInfoOffset("wID");
    private static final long MII_HSUBMENU_OFFSET = menuItemInfoOffset("hSubMenu");
    private static final long MII_DWTYPEDATA_OFFSET = menuItemInfoOffset("dwTypeData");
    private static final long MII_CCH_OFFSET = menuItemInfoOffset("cch");

    /** {@code int32_t gwin_sizeof_menu_callbacks(void)}; {@link #GWIN_MENU_CALLBACKS_LAYOUT} must agree. */
    private static final MethodHandle GWIN_SIZEOF_MENU_CALLBACKS =
            bindGlass("gwin_sizeof_menu_callbacks", FunctionDescriptor.of(JAVA_INT));

    /** {@code int32_t gwin_menu_set_callbacks(const GwinMenuCallbacks* cb, void* user)}. */
    private static final MethodHandle GWIN_MENU_SET_CALLBACKS = bindGlass("gwin_menu_set_callbacks",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    /*
     * The eleven user32 menu entry points that were the whole of GlassMenu.cpp's eleven functional
     * JNI bodies. An HMENU is a USER handle value, never an address this process may dereference, so
     * it crosses as ADDRESS built by MemorySegment.ofAddress (unrestricted) and is never
     * reinterpret()ed. None of these is critical(true): they pass an off-heap MENUITEMINFOW rather
     * than a Java array, and they re-enter the window manager.
     *
     * EnableMenuItem returns a signed BOOL and CheckMenuItem an unsigned DWORD; the two are NOT
     * interchangeable, and menuEnableItem and menuCheckItem say why.
     */

    /** {@code HMENU CreateMenu(void)}; {@code NULL} on failure. */
    private static final MethodHandle CREATE_MENU = bindUser32("CreateMenu",
            FunctionDescriptor.of(ADDRESS));

    /** {@code BOOL DestroyMenu(HMENU hMenu)}. */
    private static final MethodHandle DESTROY_MENU = bindUser32("DestroyMenu",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** {@code BOOL IsMenu(HMENU hMenu)} - the guard every one of the eleven bodies opened with. */
    private static final MethodHandle IS_MENU = bindUser32("IsMenu",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** {@code int GetMenuItemCount(HMENU hMenu)}; {@code -1} on failure, which empties every loop. */
    private static final MethodHandle GET_MENU_ITEM_COUNT = bindUser32("GetMenuItemCount",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** {@code BOOL GetMenuItemInfoW(HMENU, UINT item, BOOL fByPosition, LPMENUITEMINFOW lpmii)}. */
    private static final MethodHandle GET_MENU_ITEM_INFO_W = bindUser32("GetMenuItemInfoW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));

    /** {@code BOOL SetMenuItemInfoW(HMENU, UINT item, BOOL fByPositon, LPCMENUITEMINFOW lpmii)}. */
    private static final MethodHandle SET_MENU_ITEM_INFO_W = bindUser32("SetMenuItemInfoW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));

    /** {@code BOOL InsertMenuItemW(HMENU, UINT item, BOOL fByPosition, LPCMENUITEMINFOW lpmi)}. */
    private static final MethodHandle INSERT_MENU_ITEM_W = bindUser32("InsertMenuItemW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));

    /**
     * {@code BOOL InsertMenuW(HMENU, UINT uPosition, UINT uFlags, UINT_PTR uIDNewItem, LPCWSTR lpNewItem)}.
     * {@code UINT_PTR} is 64 bits on LLP64; the one call site passes 0.
     */
    private static final MethodHandle INSERT_MENU_W = bindUser32("InsertMenuW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_LONG, ADDRESS));

    /** {@code BOOL RemoveMenu(HMENU, UINT uPosition, UINT uFlags)}. */
    private static final MethodHandle REMOVE_MENU = bindUser32("RemoveMenu",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));

    /** {@code BOOL EnableMenuItem(HMENU, UINT uIDEnableItem, UINT uEnable)} - <b>signed</b>. */
    private static final MethodHandle ENABLE_MENU_ITEM = bindUser32("EnableMenuItem",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));

    /** {@code DWORD CheckMenuItem(HMENU, UINT uIDCheckItem, UINT uCheck)} - <b>unsigned</b>. */
    private static final MethodHandle CHECK_MENU_ITEM = bindUser32("CheckMenuItem",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));

    private WinMenuCalls() {
    }

    private static long menuItemInfoOffset(String field) {
        return MENUITEMINFOW_LAYOUT.byteOffset(PathElement.groupElement(field));
    }

    /**
     * Zeroes {@code info} and writes the two fields every use of {@code MENUITEMINFOW} needs. The
     * zeroing covers both C idioms at once: the four sites that {@code memset} first
     * ({@code GlassMenu.cpp:136,274,301,59}) and the two that do not and instead assign all twelve
     * fields ({@code :168-182}, {@code :203-215}). The only difference is the padding at offsets
     * 20-23 and 68-71, which held stack garbage in C and holds zero here; user32 does not read it.
     */
    private static void prepareItemInfo(MemorySegment info, int mask) {
        info.fill((byte) 0);
        info.set(JAVA_INT, MII_CBSIZE_OFFSET, MENUITEMINFOW_SIZE);
        info.set(JAVA_INT, MII_FMASK_OFFSET, mask);
    }

    private static boolean isMenu(MemorySegment hMenu) {
        try {
            return (int) IS_MENU.invokeExact(hMenu) != 0;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static int itemCount(MemorySegment hMenu) {
        try {
            return (int) GET_MENU_ITEM_COUNT.invokeExact(hMenu);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static boolean getItemInfo(MemorySegment hMenu, int item, boolean byPosition,
                                       MemorySegment info) {
        try {
            return (int) GET_MENU_ITEM_INFO_W.invokeExact(hMenu, item, byPosition ? 1 : 0, info) != 0;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static boolean setItemInfo(MemorySegment hMenu, int item, boolean byPosition,
                                       MemorySegment info) {
        try {
            return (int) SET_MENU_ITEM_INFO_W.invokeExact(hMenu, item, byPosition ? 1 : 0, info) != 0;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static boolean insertItemInfo(MemorySegment hMenu, int item, boolean byPosition,
                                          MemorySegment info) {
        try {
            return (int) INSERT_MENU_ITEM_W.invokeExact(hMenu, item, byPosition ? 1 : 0, info) != 0;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code FindItemBySubmenu} ({@code GlassMenu.cpp:51-70}) transliterated, including the
     * {@code hSubmenu == NULL -> -1} shortcut and the fact that a {@code GetMenuItemCount} of
     * {@code -1} makes the loop empty.
     * <p>
     * The C compared raw handle values ({@code itemInfo.hSubMenu == hSubmenu}, {@code :63}). Read as
     * {@code ADDRESS} the slot is a {@link MemorySegment}, whose {@code equals} is <em>segment</em>
     * equality and is not the same test, so the comparison is written on {@link MemorySegment#address}.
     * For the same reason {@code hSubMenu} must stay a plain {@code ADDRESS} in
     * {@link #MENUITEMINFOW_LAYOUT}: giving it a target layout would bound the segment and change
     * what {@code equals} means without changing this code.
     *
     * @return the position of the item whose submenu is {@code hSubmenu}, or {@code -1}
     */
    private static int findItemBySubmenu(MemorySegment hMenu, long hSubmenu) {
        if (hSubmenu == 0) {
            return -1;
        }
        int count = itemCount(hMenu);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment info = arena.allocate(MENUITEMINFOW_LAYOUT);
            for (int pos = 0; pos < count; pos++) {
                prepareItemInfo(info, MIIM_SUBMENU);
                if (getItemInfo(hMenu, pos, true, info)
                        && info.get(ADDRESS, MII_HSUBMENU_OFFSET).address() == hSubmenu) {
                    return pos;
                }
            }
        }
        return -1;
    }

    /**
     * {@code _create} ({@code GlassMenu.cpp:116-120}): {@code (jlong)::CreateMenu()}.
     * <p>
     * The C called {@code CreateMenu} for submenus too ({@code WinMenuDelegate.createMenu} ->
     * {@code WinMenuImpl.create}), where the Win32 convention is {@code CreatePopupMenu}. That is not
     * corrected here: substituting {@code CreatePopupMenu} is a behaviour change, not a migration.
     *
     * @return the HMENU as an opaque handle value, or 0 on failure - which is exactly what
     *         {@code WinMenuImpl.create()} already tests
     */
    static long menuCreate() {
        try {
            return ((MemorySegment) CREATE_MENU.invokeExact()).address();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code _destroy} ({@code GlassMenu.cpp:127-152}): detach every child menu, then destroy this
     * one. The walk is backwards ({@code count-1 .. 0}) because {@code RemoveMenu} shifts the
     * positions after it, and it removes <em>only</em> entries that carry a submenu, so a child HMENU
     * outlives its parent and its own {@code WinMenuDelegate} can still destroy it. Plain items are
     * left for {@code DestroyMenu}.
     * <p>
     * The mask is {@code MIIM_SUBMENU | MIIM_DATA} as in C ({@code :138}), even though
     * {@code dwItemData} is read only inside the block comment at {@code :143-147}: this is a
     * migration, and an unused bit of a query mask is not the place to start editing.
     * <p>
     * Silent on a handle that is not a menu, and {@code void} either way - {@code WinMenuImpl.destroy()}
     * clears its {@code ptr} regardless, so a non-menu handle stays silently leaked exactly as before.
     */
    static void menuDestroy(long hMenu) {
        MemorySegment menu = MemorySegment.ofAddress(hMenu);
        if (!isMenu(menu)) {
            return;
        }
        int count = itemCount(menu);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment info = arena.allocate(MENUITEMINFOW_LAYOUT);
            for (int pos = count - 1; pos >= 0; pos--) {
                prepareItemInfo(info, MIIM_SUBMENU | MIIM_DATA);
                if (getItemInfo(menu, pos, true, info)
                        && info.get(ADDRESS, MII_HSUBMENU_OFFSET).address() != 0) {
                    removeMenu(menu, pos, MF_BYPOSITION);
                }
            }
        }
        try {
            int ignoredResult = (int) DESTROY_MENU.invokeExact(menu);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code _insertItem} ({@code GlassMenu.cpp:159-188}). The three arguments the C took and never
     * used - {@code Callback callback}, {@code int shortcut}, {@code int modifiers} - do not cross
     * this boundary; {@code WinMenuImpl.insertItem} still evaluates them, and says why.
     * <p>
     * {@code MFS_GRAYED} here is 3, not the {@code MF_GRAYED} of 1 that {@link #menuEnableItem} uses.
     * Both are what the C wrote.
     *
     * @return {@code false} if {@code hMenu} is not a menu or {@code InsertMenuItemW} fails; no
     *         exception, no log, no {@code GetLastError} - the C read none of those either
     */
    static boolean menuInsertItem(long hMenu, int pos, int cmdID, String title,
                                  boolean enabled, boolean checked) {
        MemorySegment menu = MemorySegment.ofAddress(hMenu);
        if (!isMenu(menu)) {
            return false;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment info = arena.allocate(MENUITEMINFOW_LAYOUT);
            MemorySegment text = wideString(arena, title);
            prepareItemInfo(info, MIIM_FTYPE | MIIM_STATE | MIIM_ID | MIIM_STRING);
            info.set(JAVA_INT, MII_FTYPE_OFFSET, MFT_STRING);
            info.set(JAVA_INT, MII_FSTATE_OFFSET, (enabled ? MFS_ENABLED : MFS_GRAYED)
                    | (checked ? MFS_CHECKED : MFS_UNCHECKED));
            info.set(JAVA_INT, MII_WID_OFFSET, cmdID);
            info.set(ADDRESS, MII_DWTYPEDATA_OFFSET, text);
            info.set(JAVA_INT, MII_CCH_OFFSET, title.length());
            return insertItemInfo(menu, pos, true, info);
        }
    }

    /**
     * {@code _insertSubmenu} ({@code GlassMenu.cpp:195-221}). This is the one body whose guard is a
     * <b>conjunction</b>: {@code ::IsMenu(hMenu) && ::IsMenu(hSubmenu)} ({@code :201}). Checking only
     * the parent would not merely change a return value - HMENU values are recycled, so a stale child
     * handle that Windows has since reissued would be accepted and a foreign menu attached.
     * <p>
     * The parent does not take ownership of the child: {@code menuDestroy} detaches children before
     * {@code DestroyMenu} precisely so each {@code WinMenuDelegate} can still destroy its own.
     */
    static boolean menuInsertSubmenu(long hMenu, int pos, long hSubmenu, String title,
                                     boolean enabled) {
        MemorySegment menu = MemorySegment.ofAddress(hMenu);
        MemorySegment submenu = MemorySegment.ofAddress(hSubmenu);
        if (!isMenu(menu) || !isMenu(submenu)) {
            return false;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment info = arena.allocate(MENUITEMINFOW_LAYOUT);
            MemorySegment text = wideString(arena, title);
            prepareItemInfo(info, MIIM_FTYPE | MIIM_STATE | MIIM_STRING | MIIM_SUBMENU);
            info.set(JAVA_INT, MII_FTYPE_OFFSET, MFT_STRING);
            info.set(JAVA_INT, MII_FSTATE_OFFSET, enabled ? MFS_ENABLED : MFS_GRAYED);
            info.set(ADDRESS, MII_HSUBMENU_OFFSET, submenu);
            info.set(ADDRESS, MII_DWTYPEDATA_OFFSET, text);
            info.set(JAVA_INT, MII_CCH_OFFSET, title.length());
            return insertItemInfo(menu, pos, true, info);
        }
    }

    /**
     * {@code _insertSeparator} ({@code GlassMenu.cpp:228-236}):
     * {@code ::InsertMenu(hMenu, pos, MF_SEPARATOR, NULL, NULL)}.
     * <p>
     * <b>{@code MF_BYPOSITION} is absent</b>, so {@code MF_BYCOMMAND} (0) applies and {@code pos} is
     * interpreted as a <em>command id</em>, not a position - unlike every other insert in this class,
     * which passes {@code fByPosition = TRUE}. That is what the C did and it is reproduced verbatim;
     * {@code WinMenuNativeTest} records what Windows actually does with it.
     */
    static boolean menuInsertSeparator(long hMenu, int pos) {
        MemorySegment menu = MemorySegment.ofAddress(hMenu);
        if (!isMenu(menu)) {
            return false;
        }
        try {
            return (int) INSERT_MENU_W.invokeExact(menu, pos, MF_SEPARATOR, 0L, MemorySegment.NULL) != 0;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code _removeAtPos} ({@code GlassMenu.cpp:243-260}); the dead {@code dwItemData} block goes. */
    static boolean menuRemoveAtPos(long hMenu, int pos) {
        MemorySegment menu = MemorySegment.ofAddress(hMenu);
        return isMenu(menu) && removeMenu(menu, pos, MF_BYPOSITION);
    }

    private static boolean removeMenu(MemorySegment hMenu, int position, int flags) {
        try {
            return (int) REMOVE_MENU.invokeExact(hMenu, position, flags) != 0;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code _setItemTitle} ({@code GlassMenu.cpp:267-284}), which addresses the item <b>by command
     * id</b> ({@code fByPosition = FALSE}, {@code :279}) - the opposite of
     * {@link #menuSetSubmenuTitle}. Keep them distinct.
     */
    static boolean menuSetItemTitle(long hMenu, int cmdID, String title) {
        MemorySegment menu = MemorySegment.ofAddress(hMenu);
        if (!isMenu(menu)) {
            return false;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment info = arena.allocate(MENUITEMINFOW_LAYOUT);
            MemorySegment text = wideString(arena, title);
            prepareItemInfo(info, MIIM_STRING);
            info.set(ADDRESS, MII_DWTYPEDATA_OFFSET, text);
            info.set(JAVA_INT, MII_CCH_OFFSET, title.length());
            return setItemInfo(menu, cmdID, false, info);
        }
    }

    /**
     * {@code _setSubmenuTitle} ({@code GlassMenu.cpp:291-312}), by <b>position</b>.
     * <p>
     * <b>Latent bug, carried verbatim.</b> The test is {@code pos > 0} ({@code :298}) while
     * {@link #findItemBySubmenu} returns 0 for the first item and -1 for none, so the <em>first</em>
     * submenu of a menu bar - typically "File" - can never be renamed. Correcting it to
     * {@code pos >= 0} would be a behaviour change inside a migration; it needs its own commit, its
     * own test and a deliberate update of the golden transcript.
     */
    static boolean menuSetSubmenuTitle(long hMenu, long hSubmenu, String title) {
        MemorySegment menu = MemorySegment.ofAddress(hMenu);
        if (!isMenu(menu)) {
            return false;
        }
        int pos = findItemBySubmenu(menu, hSubmenu);
        if (pos <= 0) {
            return false;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment info = arena.allocate(MENUITEMINFOW_LAYOUT);
            MemorySegment text = wideString(arena, title);
            prepareItemInfo(info, MIIM_STRING);
            info.set(ADDRESS, MII_DWTYPEDATA_OFFSET, text);
            info.set(JAVA_INT, MII_CCH_OFFSET, title.length());
            return setItemInfo(menu, pos, true, info);
        }
    }

    /**
     * {@code _enableItem} ({@code GlassMenu.cpp:319-330}): {@code 0 <= EnableMenuItem(...)}.
     * {@code EnableMenuItem} is declared {@code BOOL} (signed), so its {@code -1} "no such item"
     * really does come back as failure - which is <em>not</em> true of {@link #menuCheckItem}, and
     * the asymmetry is the C's, faithfully kept.
     */
    static boolean menuEnableItem(long hMenu, int cmdID, boolean enable) {
        MemorySegment menu = MemorySegment.ofAddress(hMenu);
        return isMenu(menu)
                && enableMenuItem(menu, cmdID, MF_BYCOMMAND | (enable ? MF_ENABLED : MF_GRAYED)) >= 0;
    }

    /**
     * {@code _enableSubmenu} ({@code GlassMenu.cpp:337-352}). Carries the same {@code pos > 0} bug as
     * {@link #menuSetSubmenuTitle}: the first submenu cannot be enabled or disabled.
     */
    static boolean menuEnableSubmenu(long hMenu, long hSubmenu, boolean enable) {
        MemorySegment menu = MemorySegment.ofAddress(hMenu);
        if (!isMenu(menu)) {
            return false;
        }
        int pos = findItemBySubmenu(menu, hSubmenu);
        return pos > 0
                && enableMenuItem(menu, pos, MF_BYPOSITION | (enable ? MF_ENABLED : MF_GRAYED)) >= 0;
    }

    private static int enableMenuItem(MemorySegment hMenu, int item, int flags) {
        try {
            return (int) ENABLE_MENU_ITEM.invokeExact(hMenu, item, flags);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code _checkItem} ({@code GlassMenu.cpp:359-370}).
     * <p>
     * <b>Latent bug, carried verbatim.</b> The C wrote {@code 0 <= ::CheckMenuItem(...)}, but
     * {@code CheckMenuItem} returns {@code DWORD}: the comparison is unsigned and therefore always
     * true, so the method reported success even for a command id no item has, where
     * {@code CheckMenuItem} returns {@code (DWORD)-1}. Reading the {@code JAVA_INT} result as
     * {@code >= 0} would turn that into {@code false} - a silent bug fix inside a migration. So the
     * result is called for, and discarded.
     *
     * @return {@code true} whenever {@code hMenu} is a menu, whatever {@code CheckMenuItem} answered
     */
    static boolean menuCheckItem(long hMenu, int cmdID, boolean check) {
        MemorySegment menu = MemorySegment.ofAddress(hMenu);
        if (!isMenu(menu)) {
            return false;
        }
        try {
            int unsignedResultTheCNeverChecked = (int) CHECK_MENU_ITEM.invokeExact(
                    menu, cmdID, MF_BYCOMMAND | (check ? MF_CHECKED : MF_UNCHECKED));
        } catch (Throwable t) {
            throw unexpected(t);
        }
        return true;
    }

    /* -- read-back, for WinMenuNativeTest's parity transcript; no product code calls these -------- */

    /** {@code IsMenu}; the test needs it to prove a child HMENU outlived its destroyed parent. */
    static boolean menuIsMenu(long hMenu) {
        return isMenu(MemorySegment.ofAddress(hMenu));
    }

    /** {@code GetMenuItemCount}; {@code -1} when the handle is not a menu. */
    static int menuItemCount(long hMenu) {
        return itemCount(MemorySegment.ofAddress(hMenu));
    }

    /** The item's {@code {fType, fState, wID}}; {@code null} when it cannot be read. */
    static int[] menuItemFlags(long hMenu, int pos) {
        MemorySegment menu = MemorySegment.ofAddress(hMenu);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment info = arena.allocate(MENUITEMINFOW_LAYOUT);
            prepareItemInfo(info, MIIM_FTYPE | MIIM_STATE | MIIM_ID);
            if (!getItemInfo(menu, pos, true, info)) {
                return null;
            }
            return new int[] {
                info.get(JAVA_INT, MII_FTYPE_OFFSET),
                info.get(JAVA_INT, MII_FSTATE_OFFSET),
                info.get(JAVA_INT, MII_WID_OFFSET)};
        }
    }

    /** The item's {@code hSubMenu} as a handle value; 0 when it has none or cannot be read. */
    static long menuItemSubmenu(long hMenu, int pos) {
        MemorySegment menu = MemorySegment.ofAddress(hMenu);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment info = arena.allocate(MENUITEMINFOW_LAYOUT);
            prepareItemInfo(info, MIIM_SUBMENU);
            if (!getItemInfo(menu, pos, true, info)) {
                return 0;
            }
            return info.get(ADDRESS, MII_HSUBMENU_OFFSET).address();
        }
    }

    /**
     * The item's title, read back as UTF-16 through the two-step {@code MIIM_STRING} protocol: ask
     * once with {@code dwTypeData == NULL} to learn {@code cch}, then again into a buffer of
     * {@code cch + 1} code units. {@code cch} comes back as the number of units actually copied.
     *
     * @return the title, or {@code null} when the item cannot be read (a separator answers {@code ""})
     */
    static String menuItemTitle(long hMenu, int pos) {
        MemorySegment menu = MemorySegment.ofAddress(hMenu);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment info = arena.allocate(MENUITEMINFOW_LAYOUT);
            prepareItemInfo(info, MIIM_STRING);
            if (!getItemInfo(menu, pos, true, info)) {
                return null;
            }
            int length = info.get(JAVA_INT, MII_CCH_OFFSET);
            if (length <= 0) {
                return "";
            }
            MemorySegment buffer = arena.allocate(JAVA_CHAR, length + 1L);
            prepareItemInfo(info, MIIM_STRING);
            info.set(ADDRESS, MII_DWTYPEDATA_OFFSET, buffer);
            info.set(JAVA_INT, MII_CCH_OFFSET, length + 1);
            if (!getItemInfo(menu, pos, true, info)) {
                return null;
            }
            int copied = Math.min(info.get(JAVA_INT, MII_CCH_OFFSET), length);
            char[] chars = new char[copied];
            MemorySegment.copy(buffer, JAVA_CHAR, 0, chars, 0, copied);
            return new String(chars);
        }
    }

    /* -- the one upcall: WM_COMMAND (was GlassMenu.cpp's CallStaticBooleanMethod, since deleted) ---------- */

    /** {@code int32_t (*notify_command)(void* user, int32_t cmd_id)}. */
    private static final FunctionDescriptor NOTIFY_COMMAND_FD =
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT);

    private static boolean menuCallbackInstalled;

    static int sizeOfMenuCallbacks() {
        try {
            return (int) GWIN_SIZEOF_MENU_CALLBACKS.invokeExact();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * Hands {@code glass.dll} the callback that replaces {@code WinMenuImpl._initIDs}. There is no JNI
     * path behind it any more: {@code HandleMenuCommand} ({@code GlassMenu.cpp}) dials
     * {@code g_menuCallbacks.notify_command} when a table is installed and answers "not handled"
     * when none is.
     * <p>
     * <b>Call this exactly once, from {@code WinMenuImpl}'s static initializer</b> - the line
     * {@code _initIDs()} occupied. That preserves the one behaviour the {@code _initIDs} scheme had
     * that a table does not automatically reproduce: until {@code WinMenuImpl} is touched at all,
     * there is no target, and every {@code WM_COMMAND} is silently "not handled" and goes to
     * {@code DefWindowProc}. Installing it earlier - from {@link WinGlassNative}'s initializer, say - would be a
     * behaviour change.
     * <p>
     * The table is copied by the library, so the confined arena that holds it is closed here; the stub
     * inside it is in {@link Arena#global()} for the reason the other two are, which the header states
     * outright: there is no point at which the library can promise no further {@code WM_COMMAND}, and
     * {@code gwin_menu_set_callbacks(NULL, NULL)} would only stop the library dialling the stub, not
     * make it unreachable.
     */
    static synchronized void installMenuCallback() {
        if (menuCallbackInstalled) {
            return;
        }
        MemorySegment callback = upcallStub(upcallTarget(WinMenuCalls.class, "onNotifyCommand", NOTIFY_COMMAND_FD),
                NOTIFY_COMMAND_FD, Arena.global());
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment table = arena.allocate(GWIN_MENU_CALLBACKS_LAYOUT);
            table.set(ADDRESS, NOTIFY_COMMAND_OFFSET, callback);
            int status = (int) GWIN_MENU_SET_CALLBACKS.invokeExact(table, MemorySegment.NULL);
            requireOk(status, "gwin_menu_set_callbacks");
        } catch (Throwable t) {
            throw unexpected(t);
        }
        menuCallbackInstalled = true;
    }

    static synchronized boolean menuCallbackInstalled() {
        return menuCallbackInstalled;
    }

    /**
     * The {@code GwinMenuCallbacks.notify_command} body: a menu command was chosen. {@code user} is
     * the pointer {@link #installMenuCallback} passed, which is {@code NULL} - the map from command id
     * to peer is {@code WinMenuItemDelegate.CommandIDManager}, and always was.
     * <p>
     * <b>Thread.</b> The Glass toolkit thread, always: {@code DispatchMessage} ->
     * {@code BaseWnd::StaticWindowProc} -> {@code GlassWindow::WindowProc}'s {@code WM_COMMAND} arm ->
     * {@code GlassWindow::HandleCommand} -> {@code HandleMenuCommand}. An inter-thread
     * {@code SendMessage} still runs the window procedure on the window's owning thread, and the only
     * {@code AttachCurrentThread} this library ever had was {@code Timer.cpp}'s, which is
     * deleted. Frequently nested inside the system's modal menu loop.
     * <p>
     * <b>Re-entrant.</b> {@code MenuItem.Callback.action()} is arbitrary application code: it may open
     * a dialog or nest a message loop. Nothing here holds a lock; the C held none either.
     * <p>
     * <b>The return value is load-bearing, and the contract is "non-zero means handled".</b>
     * {@code GlassWindow::WindowProc} answers {@code return 0} for a handled command and falls through
     * to {@code DefWindowProc} for an unhandled one ({@code GlassWindow.cpp:461-465}), so answering
     * wrongly hands a command the application consumed to the default window procedure, or hides one
     * it did not. The C tests {@code != 0}; nothing on either side may narrow that to {@code == 1}.
     * <p>
     * <b>A throwing target counts as unhandled.</b> {@code CallStaticBooleanMethod} had already
     * yielded {@code JNI_FALSE} by the time {@code CheckAndClearException} reported and swallowed the
     * throwable ({@code Utils.cpp:50-71}), so 0 here is parity and not a choice. Letting anything out
     * of an upcall stub would terminate the JVM.
     *
     * @param cmdId {@code LOWORD(wParam)} of {@code WM_COMMAND}, i.e. the {@code MENUITEMINFOW.wID}
     *              Java put there; {@code CommandIDManager} hands out 1..0xFFFF, so it is never
     *              negative and never truncated by the {@code WORD} it travelled in
     * @return 1 if a peer with a callback consumed it, 0 otherwise
     */
    static int onNotifyCommand(MemorySegment user, int cmdId) {
        try {
            return WinMenuImpl.notifyCommand(cmdId) ? 1 : 0;
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return 0;
        }
    }
}
