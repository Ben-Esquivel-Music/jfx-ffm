/*
 * Copyright (c) 2010, 2026, Oracle and/or its affiliates. All rights reserved.
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

import com.sun.glass.ui.Menu;
import com.sun.glass.ui.MenuItem.Callback;
import com.sun.glass.ui.Pixels;
import com.sun.glass.ui.delegate.MenuDelegate;
import com.sun.glass.ui.delegate.MenuItemDelegate;

class MacMenuDelegate implements MenuDelegate, MenuItemDelegate {

    static {
        MacMenuCallbacks.install();
    }

    // GlassMenu *
    long ptr;

    // The MacMenuCallbacks id of the GlassMenu at ptr
    private long menuId;

    // GlassMenu <-> Menu
    private Menu menu;
    public MacMenuDelegate(final Menu menu) {
        this.menu = menu;
    }

    public MacMenuDelegate() {
    }

    Menu menu() {
        return menu;
    }

    @Override public boolean createMenu(String title, boolean enabled) {
        menuId = MacMenuCallbacks.register(this, null);
        ptr = MacMenuCalls.createMenu(menuId, title, enabled);
        return ptr != 0;
    }

    @Override public boolean createMenuItem(String title, Callback callback,
                                            int shortcutKey, int shortcutModifiers, Pixels pixels,
                                            boolean enabled, boolean checked) {
        menuId = MacMenuCallbacks.register(this, callback);
        ptr = MacMenuCalls.createMenuItem(menuId, title, (char)shortcutKey, shortcutModifiers,
                                          pixels, enabled, checked);
        return ptr != 0;
    }

    @Override public boolean insert(MenuDelegate menu, int pos) {
        if (ptr == 0) return false;
        MacMenuDelegate macMenu = (MacMenuDelegate)menu;
        MacMenuCalls.insert(ptr, macMenu.ptr, pos);
        return true;
    }

    @Override public boolean insert(MenuItemDelegate item, int pos) {
        MacMenuDelegate macMenu = (MacMenuDelegate)item;
        if (ptr == 0) return false;
        MacMenuCalls.insert(ptr, macMenu != null ? macMenu.ptr : 0, pos);
        return true;
    }

    @Override public boolean remove(MenuDelegate menu, int pos) {
        MacMenuDelegate macMenu = (MacMenuDelegate)menu;
        if (ptr == 0) return false;
        MacMenuCalls.remove(ptr, macMenu.ptr, pos);
        macMenu.ptr = 0;
        return true;
    }

    @Override public boolean remove(MenuItemDelegate item, int pos) {
        MacMenuDelegate macMenu = (MacMenuDelegate)item;
        if (ptr == 0) return false;
        MacMenuCalls.remove(ptr, macMenu == null ? 0L : macMenu.ptr, pos);
        if (macMenu != null) {
            macMenu.ptr = 0L;
        }
        return true;
    }

    @Override public boolean setTitle(String title) {
        if (ptr == 0) return false;
        MacMenuCalls.setTitle(ptr, title);
        return true;
    }

    @Override public boolean setShortcut(int shortcutKey, int shortcutModifiers) {
        if (ptr == 0) return false;
        MacMenuCalls.setShortcut(ptr, (char)shortcutKey, shortcutModifiers);
        return true;
    }

    @Override public boolean setPixels(Pixels pixels) {
        if (ptr == 0) return false;
        MacMenuCalls.setPixels(ptr, pixels);
        return true;
    }

    @Override public boolean setEnabled(boolean enabled) {
        if (ptr == 0) return false;
        MacMenuCalls.setEnabled(ptr, enabled);
        return true;
    }

    @Override public boolean setChecked(boolean checked) {
        MacMenuCalls.setChecked(ptr, checked);
        return true;
    }

    @Override public boolean setCallback(Callback callback) {
        MacMenuCalls.setCallback(menuId, ptr, callback);
        return true;
    }

}

