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
 * A hand-kept, JNI-free copy of what javac -h wrote for com.sun.glass.ui.Window at commit 0fd9cb0ef5, used only by the
 * Windows glass sources: every constant macro it defined, in javac's order, with javac's file name, macro names and
 * literals (an int constant is <decimal>L, a long one <decimal>i64), so that the unchanged #include lines of those
 * sources resolve here and compile to the same code without jni.h. test.com.sun.glass.ui.win.WinConstantsHeaderTest
 * checks each of these macros against the Java class.
 */

#ifndef COM_SUN_GLASS_UI_WINDOW_H
#define COM_SUN_GLASS_UI_WINDOW_H

#define com_sun_glass_ui_Window_UNTITLED 0L
#define com_sun_glass_ui_Window_TITLED 1L
#define com_sun_glass_ui_Window_TRANSPARENT 2L
#define com_sun_glass_ui_Window_EXTENDED 4L
#define com_sun_glass_ui_Window_NORMAL 0L
#define com_sun_glass_ui_Window_UTILITY 8L
#define com_sun_glass_ui_Window_POPUP 16L
#define com_sun_glass_ui_Window_CLOSABLE 32L
#define com_sun_glass_ui_Window_MINIMIZABLE 64L
#define com_sun_glass_ui_Window_MAXIMIZABLE 128L
#define com_sun_glass_ui_Window_RIGHT_TO_LEFT 256L
#define com_sun_glass_ui_Window_UNIFIED 512L
#define com_sun_glass_ui_Window_MODAL 1024L
#define com_sun_glass_ui_Window_DARK_FRAME 2048L

#endif /* COM_SUN_GLASS_UI_WINDOW_H */
