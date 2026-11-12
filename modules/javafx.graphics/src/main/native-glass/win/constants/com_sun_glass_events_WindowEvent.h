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
 * A hand-kept, JNI-free copy of what javac -h wrote for com.sun.glass.events.WindowEvent at commit 0fd9cb0ef5, used
 * only by the Windows glass sources: every constant macro it defined, in javac's order, with javac's file name, macro
 * names and literals (an int constant is <decimal>L, a long one <decimal>i64), so that the unchanged #include lines of
 * those sources resolve here and compile to the same code without jni.h.
 * test.com.sun.glass.ui.win.WinConstantsHeaderTest checks each of these macros against the Java class.
 */

#ifndef COM_SUN_GLASS_EVENTS_WINDOWEVENT_H
#define COM_SUN_GLASS_EVENTS_WINDOWEVENT_H

#define com_sun_glass_events_WindowEvent_RESIZE 511L
#define com_sun_glass_events_WindowEvent_MOVE 512L
#define com_sun_glass_events_WindowEvent_RESCALE 513L
#define com_sun_glass_events_WindowEvent_CLOSE 521L
#define com_sun_glass_events_WindowEvent_DESTROY 522L
#define com_sun_glass_events_WindowEvent_MINIMIZE 531L
#define com_sun_glass_events_WindowEvent_MAXIMIZE 532L
#define com_sun_glass_events_WindowEvent_RESTORE 533L
#define com_sun_glass_events_WindowEvent__FOCUS_MIN 541L
#define com_sun_glass_events_WindowEvent_FOCUS_LOST 541L
#define com_sun_glass_events_WindowEvent_FOCUS_GAINED 542L
#define com_sun_glass_events_WindowEvent_FOCUS_GAINED_FORWARD 543L
#define com_sun_glass_events_WindowEvent_FOCUS_GAINED_BACKWARD 544L
#define com_sun_glass_events_WindowEvent__FOCUS_MAX 544L
#define com_sun_glass_events_WindowEvent_FOCUS_DISABLED 545L
#define com_sun_glass_events_WindowEvent_FOCUS_UNGRAB 546L

#endif /* COM_SUN_GLASS_EVENTS_WINDOWEVENT_H */
