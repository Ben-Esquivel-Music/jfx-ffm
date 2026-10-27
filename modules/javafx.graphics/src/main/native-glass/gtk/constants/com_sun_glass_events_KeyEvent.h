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

/*
 * A hand-kept, JNI-free copy of the constants javac -h emits for com.sun.glass.events.KeyEvent, used only by the
 * GTK glass sources. File name, macro names and literals are javac's (an int constant is <decimal>L), so the
 * unchanged #include lines of those sources resolve here and compile to the same code. Only the constants those
 * sources use are defined; add a line in javac's spelling when one needs another.
 * test.com.sun.glass.ui.gtk.GtkConstantsHeaderTest checks each of these macros against the Java class.
 */

#ifndef COM_SUN_GLASS_EVENTS_KEYEVENT_H
#define COM_SUN_GLASS_EVENTS_KEYEVENT_H

#define com_sun_glass_events_KeyEvent_PRESS 111L
#define com_sun_glass_events_KeyEvent_RELEASE 112L
#define com_sun_glass_events_KeyEvent_TYPED 113L
#define com_sun_glass_events_KeyEvent_MODIFIER_SHIFT 1L
#define com_sun_glass_events_KeyEvent_MODIFIER_CONTROL 4L
#define com_sun_glass_events_KeyEvent_MODIFIER_ALT 8L
#define com_sun_glass_events_KeyEvent_MODIFIER_WINDOWS 16L
#define com_sun_glass_events_KeyEvent_MODIFIER_BUTTON_PRIMARY 32L
#define com_sun_glass_events_KeyEvent_MODIFIER_BUTTON_SECONDARY 64L
#define com_sun_glass_events_KeyEvent_MODIFIER_BUTTON_MIDDLE 128L
#define com_sun_glass_events_KeyEvent_MODIFIER_BUTTON_BACK 256L
#define com_sun_glass_events_KeyEvent_MODIFIER_BUTTON_FORWARD 512L
#define com_sun_glass_events_KeyEvent_VK_UNDEFINED 0L
#define com_sun_glass_events_KeyEvent_VK_ENTER 10L
#define com_sun_glass_events_KeyEvent_VK_BACKSPACE 8L
#define com_sun_glass_events_KeyEvent_VK_TAB 9L
#define com_sun_glass_events_KeyEvent_VK_CLEAR 12L
#define com_sun_glass_events_KeyEvent_VK_PAUSE 19L
#define com_sun_glass_events_KeyEvent_VK_ESCAPE 27L
#define com_sun_glass_events_KeyEvent_VK_SPACE 32L
#define com_sun_glass_events_KeyEvent_VK_DELETE 127L
#define com_sun_glass_events_KeyEvent_VK_PRINTSCREEN 154L
#define com_sun_glass_events_KeyEvent_VK_INSERT 155L
#define com_sun_glass_events_KeyEvent_VK_HELP 156L
#define com_sun_glass_events_KeyEvent_VK_SHIFT 16L
#define com_sun_glass_events_KeyEvent_VK_CONTROL 17L
#define com_sun_glass_events_KeyEvent_VK_ALT 18L
#define com_sun_glass_events_KeyEvent_VK_ALT_GRAPH 65406L
#define com_sun_glass_events_KeyEvent_VK_WINDOWS 524L
#define com_sun_glass_events_KeyEvent_VK_CONTEXT_MENU 525L
#define com_sun_glass_events_KeyEvent_VK_CAPS_LOCK 20L
#define com_sun_glass_events_KeyEvent_VK_NUM_LOCK 144L
#define com_sun_glass_events_KeyEvent_VK_SCROLL_LOCK 145L
#define com_sun_glass_events_KeyEvent_VK_PAGE_UP 33L
#define com_sun_glass_events_KeyEvent_VK_PAGE_DOWN 34L
#define com_sun_glass_events_KeyEvent_VK_END 35L
#define com_sun_glass_events_KeyEvent_VK_HOME 36L
#define com_sun_glass_events_KeyEvent_VK_LEFT 37L
#define com_sun_glass_events_KeyEvent_VK_UP 38L
#define com_sun_glass_events_KeyEvent_VK_RIGHT 39L
#define com_sun_glass_events_KeyEvent_VK_DOWN 40L
#define com_sun_glass_events_KeyEvent_VK_COMMA 44L
#define com_sun_glass_events_KeyEvent_VK_MINUS 45L
#define com_sun_glass_events_KeyEvent_VK_PERIOD 46L
#define com_sun_glass_events_KeyEvent_VK_SLASH 47L
#define com_sun_glass_events_KeyEvent_VK_SEMICOLON 59L
#define com_sun_glass_events_KeyEvent_VK_EQUALS 61L
#define com_sun_glass_events_KeyEvent_VK_OPEN_BRACKET 91L
#define com_sun_glass_events_KeyEvent_VK_BACK_SLASH 92L
#define com_sun_glass_events_KeyEvent_VK_CLOSE_BRACKET 93L
#define com_sun_glass_events_KeyEvent_VK_MULTIPLY 106L
#define com_sun_glass_events_KeyEvent_VK_ADD 107L
#define com_sun_glass_events_KeyEvent_VK_SEPARATOR 108L
#define com_sun_glass_events_KeyEvent_VK_SUBTRACT 109L
#define com_sun_glass_events_KeyEvent_VK_DECIMAL 110L
#define com_sun_glass_events_KeyEvent_VK_DIVIDE 111L
#define com_sun_glass_events_KeyEvent_VK_AMPERSAND 150L
#define com_sun_glass_events_KeyEvent_VK_ASTERISK 151L
#define com_sun_glass_events_KeyEvent_VK_DOUBLE_QUOTE 152L
#define com_sun_glass_events_KeyEvent_VK_LESS 153L
#define com_sun_glass_events_KeyEvent_VK_GREATER 160L
#define com_sun_glass_events_KeyEvent_VK_BRACELEFT 161L
#define com_sun_glass_events_KeyEvent_VK_BRACERIGHT 162L
#define com_sun_glass_events_KeyEvent_VK_BACK_QUOTE 192L
#define com_sun_glass_events_KeyEvent_VK_QUOTE 222L
#define com_sun_glass_events_KeyEvent_VK_AT 512L
#define com_sun_glass_events_KeyEvent_VK_COLON 513L
#define com_sun_glass_events_KeyEvent_VK_CIRCUMFLEX 514L
#define com_sun_glass_events_KeyEvent_VK_DOLLAR 515L
#define com_sun_glass_events_KeyEvent_VK_EURO_SIGN 516L
#define com_sun_glass_events_KeyEvent_VK_EXCLAMATION 517L
#define com_sun_glass_events_KeyEvent_VK_INV_EXCLAMATION 518L
#define com_sun_glass_events_KeyEvent_VK_LEFT_PARENTHESIS 519L
#define com_sun_glass_events_KeyEvent_VK_NUMBER_SIGN 520L
#define com_sun_glass_events_KeyEvent_VK_PLUS 521L
#define com_sun_glass_events_KeyEvent_VK_RIGHT_PARENTHESIS 522L
#define com_sun_glass_events_KeyEvent_VK_UNDERSCORE 523L
#define com_sun_glass_events_KeyEvent_VK_0 48L
#define com_sun_glass_events_KeyEvent_VK_1 49L
#define com_sun_glass_events_KeyEvent_VK_2 50L
#define com_sun_glass_events_KeyEvent_VK_3 51L
#define com_sun_glass_events_KeyEvent_VK_4 52L
#define com_sun_glass_events_KeyEvent_VK_5 53L
#define com_sun_glass_events_KeyEvent_VK_6 54L
#define com_sun_glass_events_KeyEvent_VK_7 55L
#define com_sun_glass_events_KeyEvent_VK_8 56L
#define com_sun_glass_events_KeyEvent_VK_9 57L
#define com_sun_glass_events_KeyEvent_VK_A 65L
#define com_sun_glass_events_KeyEvent_VK_B 66L
#define com_sun_glass_events_KeyEvent_VK_C 67L
#define com_sun_glass_events_KeyEvent_VK_D 68L
#define com_sun_glass_events_KeyEvent_VK_E 69L
#define com_sun_glass_events_KeyEvent_VK_F 70L
#define com_sun_glass_events_KeyEvent_VK_G 71L
#define com_sun_glass_events_KeyEvent_VK_H 72L
#define com_sun_glass_events_KeyEvent_VK_I 73L
#define com_sun_glass_events_KeyEvent_VK_J 74L
#define com_sun_glass_events_KeyEvent_VK_K 75L
#define com_sun_glass_events_KeyEvent_VK_L 76L
#define com_sun_glass_events_KeyEvent_VK_M 77L
#define com_sun_glass_events_KeyEvent_VK_N 78L
#define com_sun_glass_events_KeyEvent_VK_O 79L
#define com_sun_glass_events_KeyEvent_VK_P 80L
#define com_sun_glass_events_KeyEvent_VK_Q 81L
#define com_sun_glass_events_KeyEvent_VK_R 82L
#define com_sun_glass_events_KeyEvent_VK_S 83L
#define com_sun_glass_events_KeyEvent_VK_T 84L
#define com_sun_glass_events_KeyEvent_VK_U 85L
#define com_sun_glass_events_KeyEvent_VK_V 86L
#define com_sun_glass_events_KeyEvent_VK_W 87L
#define com_sun_glass_events_KeyEvent_VK_X 88L
#define com_sun_glass_events_KeyEvent_VK_Y 89L
#define com_sun_glass_events_KeyEvent_VK_Z 90L
#define com_sun_glass_events_KeyEvent_VK_NUMPAD0 96L
#define com_sun_glass_events_KeyEvent_VK_NUMPAD1 97L
#define com_sun_glass_events_KeyEvent_VK_NUMPAD2 98L
#define com_sun_glass_events_KeyEvent_VK_NUMPAD3 99L
#define com_sun_glass_events_KeyEvent_VK_NUMPAD4 100L
#define com_sun_glass_events_KeyEvent_VK_NUMPAD5 101L
#define com_sun_glass_events_KeyEvent_VK_NUMPAD6 102L
#define com_sun_glass_events_KeyEvent_VK_NUMPAD7 103L
#define com_sun_glass_events_KeyEvent_VK_NUMPAD8 104L
#define com_sun_glass_events_KeyEvent_VK_NUMPAD9 105L
#define com_sun_glass_events_KeyEvent_VK_F1 112L
#define com_sun_glass_events_KeyEvent_VK_F2 113L
#define com_sun_glass_events_KeyEvent_VK_F3 114L
#define com_sun_glass_events_KeyEvent_VK_F4 115L
#define com_sun_glass_events_KeyEvent_VK_F5 116L
#define com_sun_glass_events_KeyEvent_VK_F6 117L
#define com_sun_glass_events_KeyEvent_VK_F7 118L
#define com_sun_glass_events_KeyEvent_VK_F8 119L
#define com_sun_glass_events_KeyEvent_VK_F9 120L
#define com_sun_glass_events_KeyEvent_VK_F10 121L
#define com_sun_glass_events_KeyEvent_VK_F11 122L
#define com_sun_glass_events_KeyEvent_VK_F12 123L

#endif /* COM_SUN_GLASS_EVENTS_KEYEVENT_H */
