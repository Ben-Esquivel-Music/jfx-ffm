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

import com.sun.glass.events.KeyEvent;
import com.sun.glass.ui.gtk.screencast.XdgDesktopPortal;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.HashMap;
import java.util.Map;
import static com.sun.glass.ui.gtk.GtkGlassNative.bounded;
import static com.sun.glass.ui.gtk.GtkGlassNative.unexpected;
import static com.sun.glass.ui.gtk.GtkPixelsCalls.GDK_DISPLAY_GET_DEFAULT;
import static com.sun.glass.ui.gtk.GtkRobotCalls.GDK_KEYMAP_GET_ENTRIES_FOR_KEYVAL;
import static com.sun.glass.ui.gtk.GtkRobotCalls.GDK_KEYMAP_GET_FOR_DISPLAY;
import static com.sun.glass.ui.gtk.GtkRobotCalls.GDK_KEYMAP_TRANSLATE_KEYBOARD_STATE;
import static com.sun.glass.ui.gtk.GtkRobotCalls.GDK_KEYVAL_TO_LOWER;
import static com.sun.glass.ui.gtk.GtkRobotCalls.GDK_X11_DISPLAY_GET_XDISPLAY;
import static com.sun.glass.ui.gtk.GtkRobotCalls.G_DIRECT_EQUAL;
import static com.sun.glass.ui.gtk.GtkRobotCalls.G_DIRECT_HASH;
import static com.sun.glass.ui.gtk.GtkRobotCalls.G_FREE_CALL;
import static com.sun.glass.ui.gtk.GtkRobotCalls.G_HASH_TABLE_INSERT;
import static com.sun.glass.ui.gtk.GtkRobotCalls.G_HASH_TABLE_ITER_INIT;
import static com.sun.glass.ui.gtk.GtkRobotCalls.G_HASH_TABLE_ITER_NEXT;
import static com.sun.glass.ui.gtk.GtkRobotCalls.G_HASH_TABLE_NEW;
import static com.sun.glass.ui.gtk.GtkRobotCalls.XKB_GET_NAMED_INDICATOR;
import static com.sun.glass.ui.gtk.GtkRobotCalls.XKB_GET_STATE;
import static com.sun.glass.ui.gtk.GtkRobotCalls.XKB_QUERY_EXTENSION;
import static com.sun.glass.ui.gtk.GtkRobotCalls.X_INTERN_ATOM;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * The key half of {@code glass_key.cpp} at commit {@code 033187ad90} that the robot and
 * {@code GtkApplication._isKeyLocked} used: the keyval tables of {@code initialize_key}, the keymap and XKB lookups and
 * the lock query. Its GDK, GLib and X11 handles are {@link GtkRobotCalls}'s, and {@code gdk_display_get_default} is
 * {@link GtkPixelsCalls}', bound there in the binding's order; this class binds nothing.
 */
final class GtkKeyCalls {

    /** {@code GDK_MOD2_MASK} (Num Lock) of {@code gdktypes.h}. */
    static final int GDK_MOD2_MASK = 1 << 4;

    /** {@code XkbUseCoreKbd} of {@code XKB.h}. */
    static final int XKB_USE_CORE_KBD = 0x0100;

    /** {@code sizeof(XkbStateRec)} on LP64 Linux; {@code group} is its first byte. */
    static final long XKB_STATE_REC_SIZE = 18;

    /** {@code sizeof(GdkKeymapKey)}: {@code guint keycode; gint group; gint level;}. */
    static final long GDK_KEYMAP_KEY_SIZE = 12;

    /** {@code sizeof(GHashTableIter)} on LP64: three pointers, two ints, one pointer. */
    static final long G_HASH_TABLE_ITER_SIZE = 40;

    /**
     * The {@code keymap} inserts of {@code initialize_key} ({@code glass_key.cpp}), in the C's order, as pairs of GDK
     * keyval ({@code gdkkeysyms.h}, named in the comment) and glass key code. The order matters: the robot answers
     * the first keyval that {@code g_hash_table_iter_next} reaches for a key code, which is why the table is also
     * built as a {@code GHashTable} (see {@link #findGdkKeyvalForGlassKeycode}).
     */
    static final int[] KEYMAP = {
            0xff0d, KeyEvent.VK_ENTER, // Return
            0xff08, KeyEvent.VK_BACKSPACE, // BackSpace
            0xff09, KeyEvent.VK_TAB, // Tab
            0xff0b, KeyEvent.VK_CLEAR, // Clear
            0xff13, KeyEvent.VK_PAUSE, // Pause
            0xff1b, KeyEvent.VK_ESCAPE, // Escape
            0x0020, KeyEvent.VK_SPACE, // space
            0xffff, KeyEvent.VK_DELETE, // Delete
            0xff61, KeyEvent.VK_PRINTSCREEN, // Print
            0xff63, KeyEvent.VK_INSERT, // Insert
            0xff6a, KeyEvent.VK_HELP, // Help
            0xffe1, KeyEvent.VK_SHIFT, // Shift_L
            0xffe2, KeyEvent.VK_SHIFT, // Shift_R
            0xffe3, KeyEvent.VK_CONTROL, // Control_L
            0xffe4, KeyEvent.VK_CONTROL, // Control_R
            0xffe9, KeyEvent.VK_ALT, // Alt_L
            0xffea, KeyEvent.VK_ALT_GRAPH, // Alt_R
            0xffeb, KeyEvent.VK_WINDOWS, // Super_L
            0xffec, KeyEvent.VK_WINDOWS, // Super_R
            0xff67, KeyEvent.VK_CONTEXT_MENU, // Menu
            0xffe7, KeyEvent.VK_WINDOWS, // Meta_L
            0xffe8, KeyEvent.VK_CONTEXT_MENU, // Meta_R
            0xffe5, KeyEvent.VK_CAPS_LOCK, // Caps_Lock
            0xff7f, KeyEvent.VK_NUM_LOCK, // Num_Lock
            0xff14, KeyEvent.VK_SCROLL_LOCK, // Scroll_Lock
            0xff55, KeyEvent.VK_PAGE_UP, // Page_Up
            0xff55, KeyEvent.VK_PAGE_UP, // Prior
            0xff56, KeyEvent.VK_PAGE_DOWN, // Page_Down
            0xff56, KeyEvent.VK_PAGE_DOWN, // Next
            0xff57, KeyEvent.VK_END, // End
            0xff50, KeyEvent.VK_HOME, // Home
            0xff51, KeyEvent.VK_LEFT, // Left
            0xff53, KeyEvent.VK_RIGHT, // Right
            0xff52, KeyEvent.VK_UP, // Up
            0xff54, KeyEvent.VK_DOWN, // Down
            0x002c, KeyEvent.VK_COMMA, // comma
            0x002d, KeyEvent.VK_MINUS, // minus
            0x002e, KeyEvent.VK_PERIOD, // period
            0x002f, KeyEvent.VK_SLASH, // slash
            0x003b, KeyEvent.VK_SEMICOLON, // semicolon
            0x003d, KeyEvent.VK_EQUALS, // equal
            0x005b, KeyEvent.VK_OPEN_BRACKET, // bracketleft
            0x005d, KeyEvent.VK_CLOSE_BRACKET, // bracketright
            0x005c, KeyEvent.VK_BACK_SLASH, // backslash
            0x007c, KeyEvent.VK_BACK_SLASH, // bar
            0xffaa, KeyEvent.VK_MULTIPLY, // KP_Multiply
            0xffab, KeyEvent.VK_ADD, // KP_Add
            0xffac, KeyEvent.VK_SEPARATOR, // KP_Separator
            0xffad, KeyEvent.VK_SUBTRACT, // KP_Subtract
            0xffae, KeyEvent.VK_DECIMAL, // KP_Decimal
            0x0027, KeyEvent.VK_QUOTE, // apostrophe
            0x0060, KeyEvent.VK_BACK_QUOTE, // grave
            0x0026, KeyEvent.VK_AMPERSAND, // ampersand
            0x002a, KeyEvent.VK_ASTERISK, // asterisk
            0x0022, KeyEvent.VK_DOUBLE_QUOTE, // quotedbl
            0x003c, KeyEvent.VK_LESS, // less
            0x003e, KeyEvent.VK_GREATER, // greater
            0x007b, KeyEvent.VK_BRACELEFT, // braceleft
            0x007d, KeyEvent.VK_BRACERIGHT, // braceright
            0x0040, KeyEvent.VK_AT, // at
            0x003a, KeyEvent.VK_COLON, // colon
            0x005e, KeyEvent.VK_CIRCUMFLEX, // asciicircum
            0x0024, KeyEvent.VK_DOLLAR, // dollar
            0x20ac, KeyEvent.VK_EURO_SIGN, // EuroSign
            0x0021, KeyEvent.VK_EXCLAMATION, // exclam
            0x00a1, KeyEvent.VK_INV_EXCLAMATION, // exclamdown
            0x0028, KeyEvent.VK_LEFT_PARENTHESIS, // parenleft
            0x0023, KeyEvent.VK_NUMBER_SIGN, // numbersign
            0x002b, KeyEvent.VK_PLUS, // plus
            0x0029, KeyEvent.VK_RIGHT_PARENTHESIS, // parenright
            0x005f, KeyEvent.VK_UNDERSCORE, // underscore
            0x0030, KeyEvent.VK_0, // 0
            0x0031, KeyEvent.VK_1, // 1
            0x0032, KeyEvent.VK_2, // 2
            0x0033, KeyEvent.VK_3, // 3
            0x0034, KeyEvent.VK_4, // 4
            0x0035, KeyEvent.VK_5, // 5
            0x0036, KeyEvent.VK_6, // 6
            0x0037, KeyEvent.VK_7, // 7
            0x0038, KeyEvent.VK_8, // 8
            0x0039, KeyEvent.VK_9, // 9
            0x0061, KeyEvent.VK_A, // a
            0x0062, KeyEvent.VK_B, // b
            0x0063, KeyEvent.VK_C, // c
            0x0064, KeyEvent.VK_D, // d
            0x0065, KeyEvent.VK_E, // e
            0x0066, KeyEvent.VK_F, // f
            0x0067, KeyEvent.VK_G, // g
            0x0068, KeyEvent.VK_H, // h
            0x0069, KeyEvent.VK_I, // i
            0x006a, KeyEvent.VK_J, // j
            0x006b, KeyEvent.VK_K, // k
            0x006c, KeyEvent.VK_L, // l
            0x006d, KeyEvent.VK_M, // m
            0x006e, KeyEvent.VK_N, // n
            0x006f, KeyEvent.VK_O, // o
            0x0070, KeyEvent.VK_P, // p
            0x0071, KeyEvent.VK_Q, // q
            0x0072, KeyEvent.VK_R, // r
            0x0073, KeyEvent.VK_S, // s
            0x0074, KeyEvent.VK_T, // t
            0x0075, KeyEvent.VK_U, // u
            0x0076, KeyEvent.VK_V, // v
            0x0077, KeyEvent.VK_W, // w
            0x0078, KeyEvent.VK_X, // x
            0x0079, KeyEvent.VK_Y, // y
            0x007a, KeyEvent.VK_Z, // z
            0x0041, KeyEvent.VK_A, // A
            0x0042, KeyEvent.VK_B, // B
            0x0043, KeyEvent.VK_C, // C
            0x0044, KeyEvent.VK_D, // D
            0x0045, KeyEvent.VK_E, // E
            0x0046, KeyEvent.VK_F, // F
            0x0047, KeyEvent.VK_G, // G
            0x0048, KeyEvent.VK_H, // H
            0x0049, KeyEvent.VK_I, // I
            0x004a, KeyEvent.VK_J, // J
            0x004b, KeyEvent.VK_K, // K
            0x004c, KeyEvent.VK_L, // L
            0x004d, KeyEvent.VK_M, // M
            0x004e, KeyEvent.VK_N, // N
            0x004f, KeyEvent.VK_O, // O
            0x0050, KeyEvent.VK_P, // P
            0x0051, KeyEvent.VK_Q, // Q
            0x0052, KeyEvent.VK_R, // R
            0x0053, KeyEvent.VK_S, // S
            0x0054, KeyEvent.VK_T, // T
            0x0055, KeyEvent.VK_U, // U
            0x0056, KeyEvent.VK_V, // V
            0x0057, KeyEvent.VK_W, // W
            0x0058, KeyEvent.VK_X, // X
            0x0059, KeyEvent.VK_Y, // Y
            0x005a, KeyEvent.VK_Z, // Z
            0xffb0, KeyEvent.VK_NUMPAD0, // KP_0
            0xffb1, KeyEvent.VK_NUMPAD1, // KP_1
            0xffb2, KeyEvent.VK_NUMPAD2, // KP_2
            0xffb3, KeyEvent.VK_NUMPAD3, // KP_3
            0xffb4, KeyEvent.VK_NUMPAD4, // KP_4
            0xffb5, KeyEvent.VK_NUMPAD5, // KP_5
            0xffb6, KeyEvent.VK_NUMPAD6, // KP_6
            0xffb7, KeyEvent.VK_NUMPAD7, // KP_7
            0xffb8, KeyEvent.VK_NUMPAD8, // KP_8
            0xffb9, KeyEvent.VK_NUMPAD9, // KP_9
            0xff8d, KeyEvent.VK_ENTER, // KP_Enter
            0xff95, KeyEvent.VK_HOME, // KP_Home
            0xff96, KeyEvent.VK_LEFT, // KP_Left
            0xff97, KeyEvent.VK_UP, // KP_Up
            0xff98, KeyEvent.VK_RIGHT, // KP_Right
            0xff99, KeyEvent.VK_DOWN, // KP_Down
            0xff9a, KeyEvent.VK_PAGE_UP, // KP_Prior
            0xff9a, KeyEvent.VK_PAGE_UP, // KP_Page_Up
            0xff9b, KeyEvent.VK_PAGE_DOWN, // KP_Next
            0xff9b, KeyEvent.VK_PAGE_DOWN, // KP_Page_Down
            0xff9c, KeyEvent.VK_END, // KP_End
            0xff9e, KeyEvent.VK_INSERT, // KP_Insert
            0xff9f, KeyEvent.VK_DELETE, // KP_Delete
            0xffaf, KeyEvent.VK_DIVIDE, // KP_Divide
            0xff9d, KeyEvent.VK_CLEAR, // KP_Begin
            0xffbe, KeyEvent.VK_F1, // F1
            0xffbf, KeyEvent.VK_F2, // F2
            0xffc0, KeyEvent.VK_F3, // F3
            0xffc1, KeyEvent.VK_F4, // F4
            0xffc2, KeyEvent.VK_F5, // F5
            0xffc3, KeyEvent.VK_F6, // F6
            0xffc4, KeyEvent.VK_F7, // F7
            0xffc5, KeyEvent.VK_F8, // F8
            0xffc6, KeyEvent.VK_F9, // F9
            0xffc7, KeyEvent.VK_F10, // F10
            0xffc8, KeyEvent.VK_F11, // F11
            0xffc9, KeyEvent.VK_F12, // F12
            0xfe03, KeyEvent.VK_ALT_GRAPH  // ISO_Level3_Shift
    };

    /** The {@code robot_java_to_keyval} assignments of {@code initialize_key}, as (glass key code, keyval) pairs. */
    static final int[] ROBOT_JAVA_TO_KEYVAL = {
            KeyEvent.VK_ENTER, 0xff0d, // Return
            KeyEvent.VK_CLEAR, 0xff0b, // Clear
            KeyEvent.VK_PAGE_UP, 0xff55, // Page_Up
            KeyEvent.VK_END, 0xff57, // End
            KeyEvent.VK_HOME, 0xff50, // Home
            KeyEvent.VK_LEFT, 0xff51, // Left
            KeyEvent.VK_UP, 0xff52, // Up
            KeyEvent.VK_RIGHT, 0xff53, // Right
            KeyEvent.VK_DOWN, 0xff54, // Down
            KeyEvent.VK_DELETE, 0xffff, // Delete
            KeyEvent.VK_BACK_SLASH, 0x005c, // backslash
            KeyEvent.VK_ALT_GRAPH, 0xfe03  // ISO_Level3_Shift
    };

    /**
     * The {@code robot_java_to_keyval} assignments of {@code initialize_key_remote_desktop}, made once the
     * remote-desktop screenshot method is selected; the {@code keyval_to_scancode} half of that function serves only
     * the screencast C and has no counterpart here.
     */
    static final int[] REMOTE_ROBOT_JAVA_TO_KEYVAL = {
            KeyEvent.VK_NUMPAD0, 0xff9e, // KP_Insert
            KeyEvent.VK_NUMPAD1, 0xff9c, // KP_End
            KeyEvent.VK_NUMPAD2, 0xff99, // KP_Down
            KeyEvent.VK_NUMPAD3, 0xff9b, // KP_Page_Down
            KeyEvent.VK_NUMPAD4, 0xff96, // KP_Left
            KeyEvent.VK_NUMPAD5, 0xff9d, // KP_Begin
            KeyEvent.VK_NUMPAD6, 0xff98, // KP_Right
            KeyEvent.VK_NUMPAD7, 0xff95, // KP_Home
            KeyEvent.VK_NUMPAD8, 0xff97, // KP_Up
            KeyEvent.VK_NUMPAD9, 0xff9a, // KP_Page_Up
            KeyEvent.VK_DECIMAL, 0xff9f, // KP_Delete
            KeyEvent.VK_WINDOWS, 0xffeb, // Super_L
            KeyEvent.VK_CONTEXT_MENU, 0xff67, // Menu
            KeyEvent.VK_CLEAR, 0xff9d, // KP_Begin
            0xE0, 0xff97, // KP_Up
            0xE1, 0xff99, // KP_Down
            0xE2, 0xff96, // KP_Left
            0xE3, 0xff98  // KP_Right
    };

    /** The {@code GHashTable} of {@link #KEYMAP}; never freed. */
    private static MemorySegment keymap = MemorySegment.NULL;

    /** {@code robot_java_to_keyval}, a {@code std::map} in the C. */
    private static final Map<Integer, Integer> ROBOT_KEYVALS = new HashMap<>();

    private static boolean keyInitialized;
    private static boolean keyInitializedRemoteDesktop;

    private static boolean xkbInitialized;
    private static int xkbAvailable;

    /** {@code Java_com_sun_glass_ui_gtk_GtkApplication__1isKeyLocked} ({@code glass_key.cpp}). */
    static int isKeyLocked(int keyCode) {
        try (Arena call = Arena.ofConfined()) {
            MemorySegment gdkDisplay = (MemorySegment) GDK_DISPLAY_GET_DEFAULT.invokeExact();
            MemorySegment display = (MemorySegment) GDK_X11_DISPLAY_GET_XDISPLAY.invokeExact(gdkDisplay);
            if (!isXkbAvailable(display)) {
                return KeyEvent.KEY_LOCK_UNKNOWN;
            }
            long keyCodeAtom = 0;
            switch (keyCode) {
                case KeyEvent.VK_CAPS_LOCK:
                    keyCodeAtom = (long) X_INTERN_ATOM.invokeExact(display, call.allocateFrom("Caps Lock"), 1);
                    break;
                case KeyEvent.VK_NUM_LOCK:
                    keyCodeAtom = (long) X_INTERN_ATOM.invokeExact(display, call.allocateFrom("Num Lock"), 1);
                    break;
                default:
                    break;
            }
            if (keyCodeAtom == 0) {
                return KeyEvent.KEY_LOCK_UNKNOWN;
            }
            MemorySegment isLocked = call.allocate(JAVA_INT);
            int found = (int) XKB_GET_NAMED_INDICATOR.invokeExact(display, keyCodeAtom, MemorySegment.NULL, isLocked,
                    MemorySegment.NULL, MemorySegment.NULL);
            if (found != 0) {
                return isLocked.get(JAVA_INT, 0) != 0 ? KeyEvent.KEY_LOCK_ON : KeyEvent.KEY_LOCK_OFF;
            }
            return KeyEvent.KEY_LOCK_UNKNOWN;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code find_gdk_keyval_for_glass_keycode} ({@code glass_key.cpp}); -1 when there is none. */
    static int findGdkKeyvalForGlassKeycode(int code) {
        initKeymap();
        Integer robot = ROBOT_KEYVALS.get(code);
        if (robot != null) {
            return robot;
        }
        int result = -1;
        try (Arena call = Arena.ofConfined()) {
            MemorySegment iter = call.allocate(G_HASH_TABLE_ITER_SIZE);
            MemorySegment key = call.allocate(ADDRESS);
            MemorySegment value = call.allocate(ADDRESS);
            G_HASH_TABLE_ITER_INIT.invokeExact(iter, keymap);
            while ((int) G_HASH_TABLE_ITER_NEXT.invokeExact(iter, key, value) != 0) {
                if (code == (int) value.get(ADDRESS, 0).address()) {
                    result = (int) key.get(ADDRESS, 0).address();
                    break;
                }
            }
        } catch (Throwable t) {
            throw unexpected(t);
        }
        return result;
    }

    /** {@code find_gdk_keycode_for_keyval} ({@code glass_key.cpp}); -1 when there is none. */
    static int findGdkKeycodeForKeyval(int keyval) {
        try (Arena call = Arena.ofConfined()) {
            MemorySegment keysOut = call.allocate(ADDRESS);
            MemorySegment nKeysOut = call.allocate(JAVA_INT);
            MemorySegment gdkDisplay = (MemorySegment) GDK_DISPLAY_GET_DEFAULT.invokeExact();
            MemorySegment gdkKeymap = (MemorySegment) GDK_KEYMAP_GET_FOR_DISPLAY.invokeExact(gdkDisplay);
            keyval = (int) GDK_KEYVAL_TO_LOWER.invokeExact(keyval);
            boolean requiresNumLock = keyvalRequiresNumlock(keyval);
            if ((int) GDK_KEYMAP_GET_ENTRIES_FOR_KEYVAL.invokeExact(gdkKeymap, keyval, keysOut, nKeysOut) == 0) {
                return -1;
            }
            int nKeys = nKeysOut.get(JAVA_INT, 0);
            MemorySegment keys = bounded(keysOut.get(ADDRESS, 0), Math.max(0, nKeys) * GDK_KEYMAP_KEY_SIZE);
            int group = getCurrentKeyboardGroup();
            int result = searchKeys(call, gdkKeymap, keys, nKeys, keyval, group, requiresNumLock);
            if (result < 0 && group != 0) {
                if (keyval >= 'a' && keyval <= 'z') {
                    result = searchKeys(call, gdkKeymap, keys, nKeys, keyval, 0, requiresNumLock);
                }
            }
            G_FREE_CALL.invokeExact(keys);
            return result;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code init_keymap} ({@code glass_key.cpp}) for the robot: {@code initialize_key} once, and
     * {@code initialize_key_remote_desktop}'s robot overrides once the remote-desktop screenshot method is in use
     * (the C tested {@code isRemoteDesktop}, which the screencast C sets from that same method when
     * {@code ScreencastHelper} loads, and {@code GtkRobot} loads it before a key reaches here). The C also connected
     * {@code keys-changed} handlers that clear the character map only its key-event path reads; that connection
     * stays with the C.
     */
    private static void initKeymap() {
        if (!keyInitialized) {
            try {
                keymap = (MemorySegment) G_HASH_TABLE_NEW.invokeExact(G_DIRECT_HASH, G_DIRECT_EQUAL);
                for (int i = 0; i < KEYMAP.length; i += 2) {
                    int replaced = (int) G_HASH_TABLE_INSERT.invokeExact(keymap, MemorySegment.ofAddress(KEYMAP[i]),
                            MemorySegment.ofAddress(KEYMAP[i + 1]));
                }
            } catch (Throwable t) {
                throw unexpected(t);
            }
            for (int i = 0; i < ROBOT_JAVA_TO_KEYVAL.length; i += 2) {
                ROBOT_KEYVALS.put(ROBOT_JAVA_TO_KEYVAL[i], ROBOT_JAVA_TO_KEYVAL[i + 1]);
            }
            keyInitialized = true;
        }
        if (XdgDesktopPortal.isRemoteDesktop() && !keyInitializedRemoteDesktop) {
            for (int i = 0; i < REMOTE_ROBOT_JAVA_TO_KEYVAL.length; i += 2) {
                ROBOT_KEYVALS.put(REMOTE_ROBOT_JAVA_TO_KEYVAL[i], REMOTE_ROBOT_JAVA_TO_KEYVAL[i + 1]);
            }
            keyInitializedRemoteDesktop = true;
        }
    }

    /** {@code keyval_requires_numlock} ({@code glass_key.cpp}): the keypad digits, operators and {@code KP_Equal}. */
    private static boolean keyvalRequiresNumlock(int keyval) {
        return switch (keyval) {
            case 0xffbd, 0xffaa, 0xffab, 0xffad, 0xffae, 0xffac, 0xffaf,
                 0xffb0, 0xffb1, 0xffb2, 0xffb3, 0xffb4, 0xffb5, 0xffb6, 0xffb7, 0xffb8, 0xffb9 -> true;
            default -> false;
        };
    }

    /** {@code search_keys} ({@code glass_key.cpp}). */
    private static int searchKeys(Arena call, MemorySegment gdkKeymap, MemorySegment keys, int nKeys, int searchKeyval,
                                  int searchGroup, boolean requiresNumLock) throws Throwable {
        int result = -1;
        int state = requiresNumLock ? GDK_MOD2_MASK : 0;
        MemorySegment keyval = call.allocate(JAVA_INT);
        for (int i = 0; i < nKeys; ++i) {
            keyval.set(JAVA_INT, 0, 0);
            int keycode = keys.get(JAVA_INT, i * GDK_KEYMAP_KEY_SIZE);
            if ((int) GDK_KEYMAP_TRANSLATE_KEYBOARD_STATE.invokeExact(gdkKeymap, keycode, state, searchGroup, keyval,
                    MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL) != 0) {
                if (keyval.get(JAVA_INT, 0) == searchKeyval) {
                    result = keycode;
                    break;
                }
            }
        }
        return result;
    }

    /** {@code get_current_keyboard_group} ({@code glass_key.cpp}); -1 without XKB. */
    private static int getCurrentKeyboardGroup() throws Throwable {
        MemorySegment gdkDisplay = (MemorySegment) GDK_DISPLAY_GET_DEFAULT.invokeExact();
        MemorySegment display = (MemorySegment) GDK_X11_DISPLAY_GET_XDISPLAY.invokeExact(gdkDisplay);
        if (isXkbAvailable(display)) {
            try (Arena call = Arena.ofConfined()) {
                MemorySegment xkbState = call.allocate(XKB_STATE_REC_SIZE);
                int status = (int) XKB_GET_STATE.invokeExact(display, XKB_USE_CORE_KBD, xkbState);
                return xkbState.get(JAVA_BYTE, 0) & 0xFF;
            }
        }
        return -1;
    }

    /** {@code isXkbAvailable} ({@code glass_key.cpp}): {@code XkbQueryExtension} with version 1.0, memoised. */
    private static boolean isXkbAvailable(MemorySegment display) throws Throwable {
        if (!xkbInitialized) {
            try (Arena call = Arena.ofConfined()) {
                MemorySegment major = call.allocate(JAVA_INT);
                MemorySegment minor = call.allocate(JAVA_INT);
                major.set(JAVA_INT, 0, 1);
                minor.set(JAVA_INT, 0, 0);
                xkbAvailable = (int) XKB_QUERY_EXTENSION.invokeExact(display, MemorySegment.NULL, MemorySegment.NULL,
                        MemorySegment.NULL, major, minor);
            }
            xkbInitialized = true;
        }
        return xkbAvailable != 0;
    }

    private GtkKeyCalls() {
    }
}
