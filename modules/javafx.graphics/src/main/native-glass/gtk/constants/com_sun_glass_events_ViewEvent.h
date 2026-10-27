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
 * A hand-kept, JNI-free copy of the constants javac -h emits for com.sun.glass.events.ViewEvent, used only by the
 * GTK glass sources. File name, macro names and literals are javac's (an int constant is <decimal>L), so the
 * unchanged #include lines of those sources resolve here and compile to the same code. Only the constants those
 * sources use are defined; add a line in javac's spelling when one needs another.
 * test.com.sun.glass.ui.gtk.GtkConstantsHeaderTest checks each of these macros against the Java class.
 */

#ifndef COM_SUN_GLASS_EVENTS_VIEWEVENT_H
#define COM_SUN_GLASS_EVENTS_VIEWEVENT_H

#define com_sun_glass_events_ViewEvent_ADD 411L
#define com_sun_glass_events_ViewEvent_REMOVE 412L
#define com_sun_glass_events_ViewEvent_MOVE 423L
#define com_sun_glass_events_ViewEvent_FULLSCREEN_ENTER 431L
#define com_sun_glass_events_ViewEvent_FULLSCREEN_EXIT 432L

#endif /* COM_SUN_GLASS_EVENTS_VIEWEVENT_H */
