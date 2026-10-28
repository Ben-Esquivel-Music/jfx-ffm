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
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import static com.sun.glass.ui.win.WinCallSupport.POINT_LAYOUT;
import static com.sun.glass.ui.win.WinCallSupport.POINT_X_OFFSET;
import static com.sun.glass.ui.win.WinCallSupport.POINT_Y_OFFSET;
import static com.sun.glass.ui.win.WinCallSupport.RECT_LAYOUT;
import static com.sun.glass.ui.win.WinCallSupport.RECT_LEFT_OFFSET;
import static com.sun.glass.ui.win.WinCallSupport.RECT_TOP_OFFSET;
import static com.sun.glass.ui.win.WinCallSupport.wideString;
import static com.sun.glass.ui.win.WinGlassNative.bindGlass;
import static com.sun.glass.ui.win.WinGlassNative.bindUser32;
import static com.sun.glass.ui.win.WinGlassNative.unexpected;
import static com.sun.glass.ui.win.WinRobotCalls.GET_CURSOR_POS;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The {@link WinGlassNative} downcalls of {@link WinWindow}: the {@code gwin_window_*} exports of
 * {@code GlassWindow.cpp} and the {@code _getAnchor} {@code WRAPPER}.
 * <p>
 * The {@code gwin_window_*} exports (ABI 4) were {@code OS-CALL} natives - ten of them only a
 * {@code SendMessage(WM_DO_ACTION)} marshal around one {@code SetWindowPos} or {@code ShowWindow}, and the
 * marshal is observable, so they stay - or {@code PURE} state the {@code WndProc} reads synchronously (the
 * grab, the minimum and maximum size). {@code _getAnchor} has no C behind it.
 * <b>The handle stays the HWND</b>: {@code gwin_window_create} returns what {@code _createWindow} returned, and
 * {@code Window.getNativeWindow()} still hands it to Prism and to embedders.
 */
final class WinWindowCalls {

    /*
     * The window section of glass_win_api.h, in the header's order. critical(true) is
     * FORBIDDEN on every one of them: twenty-five block in SendMessage(WM_DO_ACTION), most of those
     * can upcall, show_system_menu runs a nested modal message loop, and the two that neither block
     * nor upcall (get_insets, set_icon) take no Java array.
     */

    private static final MethodHandle GWIN_WINDOW_CREATE = bindGlass("gwin_window_create",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_LONG));

    private static final MethodHandle GWIN_WINDOW_CLOSE = bindGlass("gwin_window_close",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    private static final MethodHandle GWIN_WINDOW_SET_VIEW = bindGlass("gwin_window_set_view",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    private static final MethodHandle GWIN_WINDOW_UPDATE_VIEW_SIZE = bindGlass("gwin_window_update_view_size",
            FunctionDescriptor.ofVoid(ADDRESS));

    private static final MethodHandle GWIN_WINDOW_SET_MENUBAR = bindGlass("gwin_window_set_menubar",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    private static final MethodHandle GWIN_WINDOW_SET_LEVEL = bindGlass("gwin_window_set_level",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));

    private static final MethodHandle GWIN_WINDOW_SET_FOCUSABLE = bindGlass("gwin_window_set_focusable",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));

    private static final MethodHandle GWIN_WINDOW_SET_ENABLED = bindGlass("gwin_window_set_enabled",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));

    private static final MethodHandle GWIN_WINDOW_SET_ALPHA = bindGlass("gwin_window_set_alpha",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_FLOAT));

    private static final MethodHandle GWIN_WINDOW_SET_DARK_FRAME = bindGlass("gwin_window_set_dark_frame",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));

    private static final MethodHandle GWIN_WINDOW_GET_INSETS = bindGlass("gwin_window_get_insets",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS));

    private static final MethodHandle GWIN_WINDOW_SET_BOUNDS = bindGlass("gwin_window_set_bounds",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT,
                    JAVA_INT, JAVA_INT, JAVA_FLOAT, JAVA_FLOAT));

    private static final MethodHandle GWIN_WINDOW_SET_TITLE = bindGlass("gwin_window_set_title",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    private static final MethodHandle GWIN_WINDOW_SET_RESIZABLE = bindGlass("gwin_window_set_resizable",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));

    private static final MethodHandle GWIN_WINDOW_SET_VISIBLE = bindGlass("gwin_window_set_visible",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));

    private static final MethodHandle GWIN_WINDOW_REQUEST_FOCUS = bindGlass("gwin_window_request_focus",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));

    private static final MethodHandle GWIN_WINDOW_GRAB_FOCUS = bindGlass("gwin_window_grab_focus",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    private static final MethodHandle GWIN_WINDOW_UNGRAB_FOCUS = bindGlass("gwin_window_ungrab_focus",
            FunctionDescriptor.ofVoid(ADDRESS));

    private static final MethodHandle GWIN_WINDOW_MINIMIZE = bindGlass("gwin_window_minimize",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));

    private static final MethodHandle GWIN_WINDOW_MAXIMIZE = bindGlass("gwin_window_maximize",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));

    private static final MethodHandle GWIN_WINDOW_SET_MIN_SIZE = bindGlass("gwin_window_set_min_size",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));

    private static final MethodHandle GWIN_WINDOW_SET_MAX_SIZE = bindGlass("gwin_window_set_max_size",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));

    private static final MethodHandle GWIN_WINDOW_SET_ICON = bindGlass("gwin_window_set_icon",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));

    private static final MethodHandle GWIN_WINDOW_TO_FRONT = bindGlass("gwin_window_to_front",
            FunctionDescriptor.ofVoid(ADDRESS));

    private static final MethodHandle GWIN_WINDOW_TO_BACK = bindGlass("gwin_window_to_back",
            FunctionDescriptor.ofVoid(ADDRESS));

    private static final MethodHandle GWIN_WINDOW_SET_CURSOR = bindGlass("gwin_window_set_cursor",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));

    private static final MethodHandle GWIN_WINDOW_SHOW_SYSTEM_MENU = bindGlass("gwin_window_show_system_menu",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT));

    /*
     * HWNDs are handle values this process never dereferences: they cross as ADDRESS built by
     * MemorySegment.ofAddress and are never reinterpret()ed.
     */

    /** {@code BOOL IsWindow(HWND hWnd)}. */
    private static final MethodHandle IS_WINDOW = bindUser32("IsWindow",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** {@code HWND GetCapture(void)}: {@code NULL} when no window of this thread holds it. */
    private static final MethodHandle GET_CAPTURE = bindUser32("GetCapture",
            FunctionDescriptor.of(ADDRESS));

    /** {@code BOOL GetWindowRect(HWND hWnd, LPRECT lpRect)}. */
    private static final MethodHandle GET_WINDOW_RECT = bindUser32("GetWindowRect",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    private WinWindowCalls() {
    }

    /**
     * {@code gwin_window_create}: {@code new GlassWindow} + {@code BaseWnd::Create} on the toolkit
     * thread, as {@code ENTER_MAIN_THREAD_AND_RETURN(jlong)} did. {@code ownerPtr} is the owner's
     * HWND or 0; {@code monitor} is the HMONITOR {@code Screen.getNativeScreen()} holds, stored and
     * never read again; {@code mask} is the {@code Window} style bit set, and the whole style
     * computation stays in C. {@code windowId} is what every slot will carry back, so
     * {@code WinWindow} registers it BEFORE this call: on the {@code CreateWindowEx}-failed path the
     * GlassWindow is deleted, and {@code notify_dispose(windowId)} fires, before this returns.
     * <b>Blocks and upcalls.</b>
     *
     * @return the HWND - {@code Window.ptr}, the value {@code getNativeWindow()} hands to Prism and
     *         to embedders - or 0 when {@code CreateWindowEx} failed or a C++ exception was caught.
     *         With no toolkit the return is INDETERMINATE stack contents, exactly as the JNI's
     *         was; it is not normalised here, and {@code Window}'s constructor rejects only 0
     */
    static long windowCreate(long ownerPtr, long monitor, int mask, long windowId) {
        try {
            MemorySegment hwnd = (MemorySegment) GWIN_WINDOW_CREATE.invokeExact(MemorySegment.ofAddress(ownerPtr),
                    MemorySegment.ofAddress(monitor), mask, windowId);
            return hwnd.address();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code gwin_window_close}: {@code GlassWindow::Close} then {@code ::DestroyWindow}, synchronous
     * - {@code notify_destroy} is certain, {@code notify_dispose} likely, before this returns; the
     * registry entry goes with the latter and never here. Does not null-check the GlassWindow.
     * <b>Blocks and upcalls.</b>
     *
     * @return {@code DestroyWindow}'s result; indeterminate with no toolkit
     */
    static boolean windowClose(long hwnd) {
        try {
            return (int) GWIN_WINDOW_CLOSE.invokeExact(MemorySegment.ofAddress(hwnd)) != 0;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code gwin_window_set_view}: {@code ViewContainer::SetGlassView(view)} on the toolkit thread,
     * after the active-touch-window clear and {@code ResetMouseTracking}. {@code view} is the
     * {@code GlassView*} the {@code WinView} holds ({@code View.ptr}), or 0. Always true, as the JNI returned
     * {@code JNI_TRUE} from outside its action. Does not null-check the GlassWindow. <b>Blocks.</b>
     */
    static boolean windowSetView(long hwnd, long view) {
        try {
            return (int) GWIN_WINDOW_SET_VIEW.invokeExact(MemorySegment.ofAddress(hwnd),
                    MemorySegment.ofAddress(view)) != 0;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code gwin_window_update_view_size}: {@code IsWindowVisible ? NotifyViewSize : nothing}.
     * <b>Blocks and upcalls</b> (the view table).
     */
    static void windowUpdateViewSize(long hwnd) {
        try {
            GWIN_WINDOW_UPDATE_VIEW_SIZE.invokeExact(MemorySegment.ofAddress(hwnd));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code gwin_window_set_menubar}: {@code ::SetMenu(hwnd, hmenu)} and the insets bookkeeping. <b>Blocks.</b> */
    static boolean windowSetMenubar(long hwnd, long hmenu) {
        try {
            return (int) GWIN_WINDOW_SET_MENUBAR.invokeExact(MemorySegment.ofAddress(hwnd),
                    MemorySegment.ofAddress(hmenu)) != 0;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code gwin_window_set_level}: {@code SetWindowPos(HWND_TOPMOST / HWND_NOTOPMOST)}. The
     * {@code Window.Level} mapping stays in C so that {@code (HWND)-1} is not a Java constant:
     * FLOATING and TOPMOST are topmost, every other value is not. <b>Blocks.</b>
     */
    static void windowSetLevel(long hwnd, int level) {
        try {
            GWIN_WINDOW_SET_LEVEL.invokeExact(MemorySegment.ofAddress(hwnd), level);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code gwin_window_set_focusable}: only 1 is true, as the JNI compared against
     * {@code JNI_TRUE}. <b>Blocks.</b>
     */
    static void windowSetFocusable(long hwnd, boolean focusable) {
        try {
            GWIN_WINDOW_SET_FOCUSABLE.invokeExact(MemorySegment.ofAddress(hwnd), focusable ? 1 : 0);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code gwin_window_set_enabled}: same "only 1 is true" rule. <b>Blocks.</b> */
    static void windowSetEnabled(long hwnd, boolean enabled) {
        try {
            GWIN_WINDOW_SET_ENABLED.invokeExact(MemorySegment.ofAddress(hwnd), enabled ? 1 : 0);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code gwin_window_set_alpha}: the {@code float} crosses as is and the C computes
     * {@code F2B(value) = BYTE(255.f * value)} on this side of its marshal, truncating toward zero
     * and wrapping outside [0, 1] - reproduced, not clamped. <b>Blocks.</b>
     */
    static void windowSetAlpha(long hwnd, float alpha) {
        try {
            GWIN_WINDOW_SET_ALPHA.invokeExact(MemorySegment.ofAddress(hwnd), alpha);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code gwin_window_set_dark_frame}: {@code DwmSetWindowAttribute(DWMWA_USE_IMMERSIVE_DARK_MODE)}.
     * <b>Blocks.</b>
     */
    static void windowSetDarkFrame(long hwnd, boolean dark) {
        try {
            GWIN_WINDOW_SET_DARK_FRAME.invokeExact(MemorySegment.ofAddress(hwnd), dark ? 1 : 0);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code gwin_window_get_insets}: {@code UpdateInsets} then {@code (left << 48) | (top << 32) |
     * (right << 16) | bottom}, aliasing above 65535 or below 0 exactly as the JNI's did and as
     * {@code WinWindow} unpacks. 0 when {@code ::IsWindow} is false - tested before the GlassWindow
     * is looked up, so a stale or fake handle is safe here. Not marshalled: it runs on the calling
     * thread and mutates {@code m_insets}, racing off the toolkit thread as the JNI did.
     */
    static long windowGetInsets(long hwnd) {
        try {
            return (long) GWIN_WINDOW_GET_INSETS.invokeExact(MemorySegment.ofAddress(hwnd));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code gwin_window_set_bounds}: the JNI body unchanged, {@code SWP_NOSENDCHANGING} included,
     * so this path never produces {@code notify_moving}. {@code xSet} / {@code ySet} cross as 0 / 1;
     * {@code xGravity} / {@code yGravity} are accepted and ignored, as the JNI declared and never
     * read them. Silent when {@code ::IsWindow} is false. <b>Blocks.</b>
     */
    static void windowSetBounds(long hwnd, int x, int y, boolean xSet, boolean ySet, int w, int h, int cw, int ch,
                                float xGravity, float yGravity) {
        try {
            GWIN_WINDOW_SET_BOUNDS.invokeExact(MemorySegment.ofAddress(hwnd), x, y, xSet ? 1 : 0, ySet ? 1 : 0,
                    w, h, cw, ch, xGravity, yGravity);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code gwin_window_set_title}: {@code ::SetWindowTextW} with the title as {@link WinCallSupport#wideString}
     * builds it - UTF-16 code units and a NUL, never UTF-8, an embedded {@code U+0000} truncating
     * the visible title there as it did. {@code Window.setTitle} substitutes {@code ""} for
     * {@code null} before it gets here; a {@code null} here is a {@link NullPointerException} where
     * {@code JString} dereferenced NULL. <b>Blocks.</b>
     */
    static boolean windowSetTitle(long hwnd, String title) {
        try (Arena arena = Arena.ofConfined()) {
            return (int) GWIN_WINDOW_SET_TITLE.invokeExact(MemorySegment.ofAddress(hwnd),
                    wideString(arena, title)) != 0;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code gwin_window_set_resizable}: only 1 is true ({@code jbool_to_bool}); false for a child
     * window. <b>Blocks.</b>
     */
    static boolean windowSetResizable(long hwnd, boolean resizable) {
        try {
            return (int) GWIN_WINDOW_SET_RESIZABLE.invokeExact(MemorySegment.ofAddress(hwnd),
                    resizable ? 1 : 0) != 0;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code gwin_window_set_visible}: {@code ShowWindow} and the focus / touch bookkeeping around
     * it. <b>Returns its own argument</b>, not a status - the JNI's {@code return visible;} sat
     * outside the action, and {@code Window.setVisible} only tests it for truth. <b>Blocks and
     * upcalls</b> ({@code notify_focus_ungrab}; the touch teardown reaches the view tables).
     */
    static boolean windowSetVisible(long hwnd, boolean visible) {
        try {
            return (int) GWIN_WINDOW_SET_VISIBLE.invokeExact(MemorySegment.ofAddress(hwnd), visible ? 1 : 0) != 0;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code gwin_window_request_focus}: {@code ::SetForegroundWindow}; {@code event} is only
     * ASSERTed. <b>Blocks.</b>
     */
    static boolean windowRequestFocus(long hwnd, int event) {
        try {
            return (int) GWIN_WINDOW_REQUEST_FOCUS.invokeExact(MemorySegment.ofAddress(hwnd), event) != 0;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code gwin_window_grab_focus}: the process-wide grab, which ungrabs - and fires
     * {@code notify_focus_ungrab} on - whichever window held it before. <b>Blocks and upcalls.</b>
     */
    static boolean windowGrabFocus(long hwnd) {
        try {
            return (int) GWIN_WINDOW_GRAB_FOCUS.invokeExact(MemorySegment.ofAddress(hwnd)) != 0;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code gwin_window_ungrab_focus}: nothing unless this window holds the grab. <b>Blocks and upcalls.</b> */
    static void windowUngrabFocus(long hwnd) {
        try {
            GWIN_WINDOW_UNGRAB_FOCUS.invokeExact(MemorySegment.ofAddress(hwnd));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code gwin_window_minimize}: {@code ShowWindow(SW_MINIMIZE / SW_RESTORE)}; always true, as the
     * JNI was. <b>Blocks and upcalls.</b>
     */
    static boolean windowMinimize(long hwnd, boolean minimize) {
        try {
            return (int) GWIN_WINDOW_MINIMIZE.invokeExact(MemorySegment.ofAddress(hwnd), minimize ? 1 : 0) != 0;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code gwin_window_maximize}: {@code ShowWindow(SW_MAXIMIZE / SW_RESTORE)}; always true.
     * {@code wasMaximized} crosses and is never read, as in the JNI. <b>Blocks and upcalls.</b>
     */
    static boolean windowMaximize(long hwnd, boolean maximize, boolean wasMaximized) {
        try {
            return (int) GWIN_WINDOW_MAXIMIZE.invokeExact(MemorySegment.ofAddress(hwnd), maximize ? 1 : 0,
                    wasMaximized ? 1 : 0) != 0;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code gwin_window_set_min_size}: the state {@code WM_GETMINMAXINFO} answers. The C maps a 0
     * argument to -1 ("not set") on its side of the marshal; {@link #windowSetMaxSize} does NOT, so
     * a maximum of 0 really pins the window to zero - the shipped asymmetry, kept where it was.
     * <b>Blocks.</b>
     */
    static boolean windowSetMinSize(long hwnd, int width, int height) {
        try {
            return (int) GWIN_WINDOW_SET_MIN_SIZE.invokeExact(MemorySegment.ofAddress(hwnd), width, height) != 0;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code gwin_window_set_max_size}: see {@link #windowSetMinSize} for the asymmetry. <b>Blocks.</b> */
    static boolean windowSetMaxSize(long hwnd, int width, int height) {
        try {
            return (int) GWIN_WINDOW_SET_MAX_SIZE.invokeExact(MemorySegment.ofAddress(hwnd), width, height) != 0;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code gwin_window_set_icon}: {@code WM_SETICON} for ICON_SMALL and ICON_BIG, then the window
     * <b>takes ownership</b> of {@code hicon} - it destroys the previous icon now and this one on the
     * next call or in {@code ~GlassWindow}. Nothing in this class ever calls {@code DestroyIcon},
     * and nothing may: the handle {@link WinCursorCalls#iconCreate} built is the C's from here on. 0 means "no
     * icon". Not marshalled and does not upcall. A NULL GlassWindow - a stale or foreign HWND - is
     * checked, and the icon is then neither installed nor destroyed: the one case in which the
     * caller still owns it, which {@code Window.setIcon} cannot reach because it checks the window is
     * open first.
     */
    static void windowSetIcon(long hwnd, long hicon) {
        try {
            GWIN_WINDOW_SET_ICON.invokeExact(MemorySegment.ofAddress(hwnd), MemorySegment.ofAddress(hicon));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code gwin_window_to_front}: {@code SetWindowPos(HWND_TOP)}, after a TOPMOST step for an
     * unfocusable window. <b>Blocks.</b>
     */
    static void windowToFront(long hwnd) {
        try {
            GWIN_WINDOW_TO_FRONT.invokeExact(MemorySegment.ofAddress(hwnd));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code gwin_window_to_back}: {@code SetWindowPos(HWND_BOTTOM)}. <b>Blocks.</b> */
    static void windowToBack(long hwnd) {
        try {
            GWIN_WINDOW_TO_BACK.invokeExact(MemorySegment.ofAddress(hwnd));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code gwin_window_set_cursor}: {@code BaseWnd::SetCursor} on this window and on its delegate
     * - stores the HCURSOR for {@code WM_SETCURSOR} to replay and calls {@code ::SetCursor} at once.
     * {@code hcursor} is what {@code WinCursor.nativeHandleFor} resolved, 0 for no cursor; the
     * resolution the JNI made inside its action now runs before the call, on the same thread on
     * Windows. <b>Blocks.</b>
     */
    static void windowSetCursor(long hwnd, long hcursor) {
        try {
            GWIN_WINDOW_SET_CURSOR.invokeExact(MemorySegment.ofAddress(hwnd), MemorySegment.ofAddress(hcursor));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code gwin_window_show_system_menu}: {@code TrackPopupMenu(TPM_RETURNCMD)}, which <b>runs a
     * nested modal message loop</b> on the toolkit thread for as long as the menu is open, and every
     * message dispatched inside it can upcall. {@code x} / {@code y} are client coordinates in
     * physical pixels. Never call it from an upcall target.
     */
    static void windowShowSystemMenu(long hwnd, int x, int y) {
        try {
            GWIN_WINDOW_SHOW_SYSTEM_MENU.invokeExact(MemorySegment.ofAddress(hwnd), x, y);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code Java_com_sun_glass_ui_win_WinWindow__1getAnchor} ({@code GlassWindow.cpp}), step for
     * step and on the calling thread, as it had no {@code ENTER_MAIN_THREAD}:
     * <ol>
     * <li>{@code !IsWindow(hwnd)} answers <b>0</b> - not {@code ANCHOR_NO_CAPTURE}. The two are not
     * interchangeable: 0 sends {@code WinWindow.setBounds} down {@code RESIZE_AROUND_ANCHOR} with a
     * (0, 0) anchor, {@code ANCHOR_NO_CAPTURE} down {@code RESIZE_TO_FX_ORIGIN};</li>
     * <li>{@code GetCapture() != hwnd} answers {@code ANCHOR_NO_CAPTURE}. {@code GetCapture} is
     * per-thread, so off the toolkit thread this is the answer, as before;</li>
     * <li>{@code GetCursorPos && GetWindowRect}, short-circuit - a failed {@code GetCursorPos} skips
     * {@code GetWindowRect} - packs cursor minus window origin with {@link #packAnchor};</li>
     * <li>either call failing falls through to {@code ANCHOR_NO_CAPTURE}, not 0.</li>
     * </ol>
     */
    static long windowAnchor(long hwnd) {
        MemorySegment window = MemorySegment.ofAddress(hwnd);
        if (isWindow(window) == 0) {
            return 0L;
        }
        if (captureWindow() != hwnd) {
            return WinWindow.ANCHOR_NO_CAPTURE;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment point = arena.allocate(POINT_LAYOUT);
            MemorySegment rect = arena.allocate(RECT_LAYOUT);
            if (cursorPos(point) != 0 && windowRect(window, rect) != 0) {
                return packAnchor(point.get(JAVA_INT, POINT_X_OFFSET) - rect.get(JAVA_INT, RECT_LEFT_OFFSET),
                        point.get(JAVA_INT, POINT_Y_OFFSET) - rect.get(JAVA_INT, RECT_TOP_OFFSET));
            }
        }
        return WinWindow.ANCHOR_NO_CAPTURE;
    }

    /**
     * {@code ((jlong) anchor.x << 32) | ((jlong) anchor.y & 0xffffffffL)} with the two
     * {@code LONG} differences already taken in 32 bits: the x half is a signed shift, the y half is
     * masked, so a negative y comes back as a large unsigned low word that {@code WinWindow.setBounds}
     * recovers with {@code (int) anchor}.
     */
    static long packAnchor(int deltaX, int deltaY) {
        return ((long) deltaX << 32) | ((long) deltaY & 0xffffffffL);
    }

    private static int isWindow(MemorySegment hwnd) {
        try {
            return (int) IS_WINDOW.invokeExact(hwnd);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static long captureWindow() {
        try {
            MemorySegment hwnd = (MemorySegment) GET_CAPTURE.invokeExact();
            return hwnd.address();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static int cursorPos(MemorySegment point) {
        try {
            return (int) GET_CURSOR_POS.invokeExact(point);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static int windowRect(MemorySegment hwnd, MemorySegment rect) {
        try {
            return (int) GET_WINDOW_RECT.invokeExact(hwnd, rect);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }
}
