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

import com.sun.glass.events.KeyEvent;
import com.sun.glass.ui.GlassRobot;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.invoke.MethodHandle;
import static com.sun.glass.ui.win.WinCallSupport.GWIN_ERR_INVALID_ARG;
import static com.sun.glass.ui.win.WinCallSupport.POINT_LAYOUT;
import static com.sun.glass.ui.win.WinCallSupport.POINT_X_OFFSET;
import static com.sun.glass.ui.win.WinCallSupport.POINT_Y_OFFSET;
import static com.sun.glass.ui.win.WinGlassNative.bindGlass;
import static com.sun.glass.ui.win.WinGlassNative.bindUser32;
import static com.sun.glass.ui.win.WinGlassNative.unexpected;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * The {@link WinGlassNative} calls of {@link WinRobot} and of the key handling of {@code WinApplication}: the
 * key table, the Win32 input primitives, input synthesis and the screen capture.
 * <p>
 * <b>{@link WinRobot}</b> (ABI 1). Eight of its ten natives were {@code WRAPPER}s - their C bodies did
 * nothing but marshal one or two Win32 calls - so there is no C left for them at all: this class binds the
 * {@code user32} calls directly and fills the {@code INPUT} structure itself. The two that stay native are the
 * GDI screen capture ({@code gwin_robot_capture}; {@code gwin_robot_pixel_color}, its transforming twin, was
 * test-only under ABI 5 and has been deleted) and the key table ({@code gwin_key_java_to_windows},
 * {@code gwin_key_windows_to_java}), which {@code KeyTable.cpp} shares with the native {@code WndProc} input
 * path and must therefore exist in exactly one copy.
 * <p>
 * Every method here reproduces what the JNI body at commit {@code 8492cb03b0} did, step for step; the
 * javadoc of each names the {@code Robot.cpp} lines it comes from. The one intentional difference is
 * that the capture buffer is zero-filled where the JNI allocated an uninitialised {@code new BYTE[]},
 * which can only show on a GDI failure the JNI silently ignored (see {@link #robotCapture}).
 * <p>
 * <b>Threading.</b> {@link WinRobot} calls {@code Application.checkEventThread()} before every one of
 * these, so they all run on the JavaFX application thread, which on Windows is the Glass/Win32 UI
 * thread. That matters twice: {@code MapVirtualKeyW} and {@code gwin_key_java_to_windows} consult the
 * calling thread's keyboard layout, and {@code SendInput} is documented for the thread that owns the
 * input desktop.
 * <p>
 * <b>No {@link Linker.Option#critical critical} downcalls.</b> {@code gwin_robot_capture} is a
 * multi-step GDI sequence ({@code CreateDC}, {@code BitBlt}, {@code GetDIBits}) that can block on the
 * compositor for tens of milliseconds; pinning a Java {@code int[]} across it with
 * {@code MemorySegment.ofArray} would hold GC off for that whole time. It gets an off-heap buffer from
 * a per-call confined arena instead, and the result is copied into the caller's array afterwards -
 * which is also what the JNI did ({@code new BYTE[]} plus {@code SetIntArrayRegion}). The Win32 calls
 * are cold and pass no arrays at all.
 * <p>
 * Line numbers into the {@code native-glass/win} C++ sources ({@code Robot.cpp}, {@code KeyTable.cpp}, bare
 * {@code :N} and {@code LN} forms included) refer to those files at commit {@code 8492cb03b0}
 * ({@code git show 8492cb03b0:modules/javafx.graphics/src/main/native-glass/win/<file>}); a bare number belongs
 * to the C file named last before it.
 */
final class WinRobotCalls {

    /** {@code enum GwinRobotCaptureError}: the first GDI step of {@code gwin_robot_capture} that failed. */
    static final int GWIN_ROBOT_ERR_CREATE_DC = 1;
    static final int GWIN_ROBOT_ERR_CREATE_MEM_DC = 2;
    static final int GWIN_ROBOT_ERR_CREATE_BITMAP = 3;
    static final int GWIN_ROBOT_ERR_BITBLT = 4;
    static final int GWIN_ROBOT_ERR_GETDIBITS = 5;

    /* winuser.h: INPUT.type. */
    static final int INPUT_MOUSE = 0;
    static final int INPUT_KEYBOARD = 1;

    /* winuser.h: KEYBDINPUT.dwFlags. KEYEVENTF_SCANCODE and KEYEVENTF_UNICODE are deliberately absent. */
    static final int KEYEVENTF_EXTENDEDKEY = 0x0001;
    static final int KEYEVENTF_KEYUP = 0x0002;

    /* winuser.h: MapVirtualKeyW map types. The C used the bare 0. */
    static final int MAPVK_VK_TO_VSC = 0;

    /* winuser.h: MOUSEINPUT.dwFlags. MOUSEEVENTF_VIRTUALDESK is deliberately absent (see robotMouseMove). */
    static final int MOUSEEVENTF_MOVE = 0x0001;
    static final int MOUSEEVENTF_LEFTDOWN = 0x0002;
    static final int MOUSEEVENTF_LEFTUP = 0x0004;
    static final int MOUSEEVENTF_RIGHTDOWN = 0x0008;
    static final int MOUSEEVENTF_RIGHTUP = 0x0010;
    static final int MOUSEEVENTF_MIDDLEDOWN = 0x0020;
    static final int MOUSEEVENTF_MIDDLEUP = 0x0040;
    static final int MOUSEEVENTF_XDOWN = 0x0080;
    static final int MOUSEEVENTF_XUP = 0x0100;
    static final int MOUSEEVENTF_WHEEL = 0x0800;
    static final int MOUSEEVENTF_ABSOLUTE = 0x8000;

    /* winuser.h: MOUSEINPUT.mouseData for the X buttons, and one wheel notch. */
    static final int XBUTTON1 = 0x0001;
    static final int XBUTTON2 = 0x0002;
    static final int WHEEL_DELTA = 120;

    /* winuser.h: GetSystemMetrics indices. */
    static final int SM_CXSCREEN = 0;
    static final int SM_CYSCREEN = 1;
    static final int SM_SWAPBUTTON = 23;

    /* winuser.h: the two toggle keys KeyTable.cpp's _isKeyLocked asked GetKeyState about (L421-439). */
    static final int VK_CAPITAL = 0x14;

    /* winuser.h: the virtual-key codes of KeyTable.cpp's IsExtendedKey (L315-343). */
    static final int VK_PRIOR = 0x21;
    static final int VK_NEXT = 0x22;
    static final int VK_END = 0x23;
    static final int VK_HOME = 0x24;
    static final int VK_LEFT = 0x25;
    static final int VK_UP = 0x26;
    static final int VK_RIGHT = 0x27;
    static final int VK_DOWN = 0x28;
    static final int VK_SNAPSHOT = 0x2C;
    static final int VK_INSERT = 0x2D;
    static final int VK_DELETE = 0x2E;
    static final int VK_NUMPAD0 = 0x60;
    static final int VK_NUMPAD9 = 0x69;
    static final int VK_NUMLOCK = 0x90;

    /**
     * {@code MOUSEINPUT} (winuser.h) on x64: 32 bytes, four bytes of padding between {@code time} and
     * the pointer-sized {@code dwExtraInfo}. {@code glass_win_api.cpp} static_asserts these offsets.
     */
    static final StructLayout MOUSEINPUT_LAYOUT = MemoryLayout.structLayout(
            JAVA_INT.withName("dx"),
            JAVA_INT.withName("dy"),
            JAVA_INT.withName("mouseData"),
            JAVA_INT.withName("dwFlags"),
            JAVA_INT.withName("time"),
            MemoryLayout.paddingLayout(4),
            JAVA_LONG.withName("dwExtraInfo"));

    /** {@code KEYBDINPUT} (winuser.h) on x64: 24 bytes, the same padding before {@code dwExtraInfo}. */
    static final StructLayout KEYBDINPUT_LAYOUT = MemoryLayout.structLayout(
            JAVA_SHORT.withName("wVk"),
            JAVA_SHORT.withName("wScan"),
            JAVA_INT.withName("dwFlags"),
            JAVA_INT.withName("time"),
            MemoryLayout.paddingLayout(4),
            JAVA_LONG.withName("dwExtraInfo"));

    /**
     * {@code INPUT} (winuser.h) on x64: 40 bytes - {@code DWORD type} at 0, four bytes of padding, then
     * the union of {@code MOUSEINPUT} and {@code KEYBDINPUT} at 8. Every {@code SendInput} call below
     * passes {@code byteSize()} as {@code cbSize}, as the C passed {@code sizeof(INPUT)}.
     */
    static final StructLayout INPUT_LAYOUT = MemoryLayout.structLayout(
            JAVA_INT.withName("type"),
            MemoryLayout.paddingLayout(4),
            MemoryLayout.unionLayout(
                    MOUSEINPUT_LAYOUT.withName("mi"),
                    KEYBDINPUT_LAYOUT.withName("ki")).withName("u"));

    private static final long INPUT_TYPE_OFFSET = INPUT_LAYOUT.byteOffset(PathElement.groupElement("type"));
    private static final long MI_DX_OFFSET = inputOffset("mi", "dx");
    private static final long MI_DY_OFFSET = inputOffset("mi", "dy");
    private static final long MI_MOUSE_DATA_OFFSET = inputOffset("mi", "mouseData");
    private static final long MI_FLAGS_OFFSET = inputOffset("mi", "dwFlags");
    private static final long KI_WVK_OFFSET = inputOffset("ki", "wVk");
    private static final long KI_WSCAN_OFFSET = inputOffset("ki", "wScan");
    private static final long KI_FLAGS_OFFSET = inputOffset("ki", "dwFlags");

    private static final MethodHandle GWIN_ROBOT_CAPTURE = bindGlass("gwin_robot_capture",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, JAVA_LONG));
    private static final MethodHandle GWIN_KEY_JAVA_TO_WINDOWS = bindGlass("gwin_key_java_to_windows",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    private static final MethodHandle GWIN_KEY_WINDOWS_TO_JAVA = bindGlass("gwin_key_windows_to_java",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT));

    /** {@code int32_t gwin_key_code_for_char(uint16_t c, int32_t hint)}; see {@link #keyCodeForChar}. */
    private static final MethodHandle GWIN_KEY_CODE_FOR_CHAR = bindGlass("gwin_key_code_for_char",
            FunctionDescriptor.of(JAVA_INT, JAVA_SHORT, JAVA_INT));

    /** {@code UINT SendInput(UINT cInputs, LPINPUT pInputs, int cbSize)}. */
    private static final MethodHandle SEND_INPUT = bindUser32("SendInput",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT));

    /**
     * {@code void mouse_event(DWORD dwFlags, DWORD dx, DWORD dy, DWORD dwData, ULONG_PTR dwExtraInfo)} -
     * superseded by {@code SendInput}, but it is what {@code Robot.cpp} used for the wheel (L241-245)
     * and the two are not interchangeable in every driver stack, so the wheel keeps calling it.
     */
    private static final MethodHandle MOUSE_EVENT = bindUser32("mouse_event",
            FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG));

    /** {@code BOOL GetCursorPos(LPPOINT lpPoint)}. */
    static final MethodHandle GET_CURSOR_POS = bindUser32("GetCursorPos",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** {@code int GetSystemMetrics(int nIndex)}. */
    private static final MethodHandle GET_SYSTEM_METRICS = bindUser32("GetSystemMetrics",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT));

    /**
     * {@code UINT MapVirtualKeyW(UINT uCode, UINT uMapType)} - the {@code W} form is what
     * {@code ::MapVirtualKey} resolved to in the C, which compiles with {@code UNICODE} defined.
     */
    private static final MethodHandle MAP_VIRTUAL_KEY_W = bindUser32("MapVirtualKeyW",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT));

    /**
     * {@code SHORT GetKeyState(int nVirtKey)} - {@code KeyTable.cpp:427,431}. The return really is
     * 16 bits wide: bit 15 is "down" and bit 0 is the toggle state, which is the one
     * {@code _isKeyLocked} tested. Binding it as {@code JAVA_INT} would read three bytes of whatever
     * the ABI leaves in the register.
     */
    private static final MethodHandle GET_KEY_STATE = bindUser32("GetKeyState",
            FunctionDescriptor.of(JAVA_SHORT, JAVA_INT));

    private WinRobotCalls() {
    }

    private static long inputOffset(String member, String field) {
        return INPUT_LAYOUT.byteOffset(PathElement.groupElement("u"), PathElement.groupElement(member),
                PathElement.groupElement(field));
    }

    /* ---------------------------------------------------------------------------------------------
     * Key table (gwin_key_*, KeyTable.cpp)
     * ------------------------------------------------------------------------------------------- */

    /**
     * {@code KeyEvent.VK_*} to Windows virtual-key code, {@code KeyTable.cpp}'s
     * {@code JavaKeyToWindowsKey}: 0 when the code has no virtual key, which is the sentinel
     * {@link #robotKeyPress} and {@link #robotKeyRelease} treat as "send nothing". Layout-dependent for
     * the OEM keys, and therefore bound to the calling thread.
     */
    static int keyJavaToWindows(int javaKeyCode) {
        try {
            return (int) GWIN_KEY_JAVA_TO_WINDOWS.invokeExact(javaKeyCode);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * Windows virtual-key code to {@code KeyEvent.VK_*}, {@code KeyTable.cpp}'s
     * {@code WindowsKeyToJavaKey}: {@code KeyEvent.VK_UNDEFINED} (0) when the table has no entry.
     * Nothing in {@code WinRobot} calls this; it completes the key-table pair the ABI exports and is covered by
     * {@code WinGlassNativeTest}.
     */
    static int keyWindowsToJava(int virtualKey) {
        try {
            return (int) GWIN_KEY_WINDOWS_TO_JAVA.invokeExact(virtualKey);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code gwin_key_code_for_char}, which is {@code WinApplication._getKeyCodeForChar} -
     * {@code KeyTable.cpp}'s body, unchanged: {@code 0x7F} is {@code KeyEvent.VK_DELETE} before any OS
     * call; a numeric-keypad {@code hint} that maps back to {@code c} is returned unchanged; otherwise
     * {@code VkKeyScanEx(c, layout) & 0xFF}, then the OEM or the plain table lookup, with
     * {@code KeyEvent.VK_UNDEFINED} (0) for 0 and 0xFF.
     * <p>
     * <b>Why {@code short} and not {@code char}.</b> The C parameter is a {@code uint16_t}, an integer, and
     * the descriptor gives it the 16-bit integer layout that matches it, {@code JAVA_SHORT}, so the caller
     * narrows: {@code keyCodeForChar((short) c, hint)}. The 16 bits are unchanged by that
     * cast, so a code unit above {@code 0x7FFF} - which is
     * negative as a Java {@code short} - still arrives as the same unsigned value. Do not widen this
     * to {@code JAVA_INT}: the C would then read a sign-extended pattern in the low half of a register
     * whose upper half the ABI does not define.
     * <p>
     * <b>Threading.</b> Two keyboard layouts are read: {@code VkKeyScanEx} and {@code MapVirtualKeyEx}
     * use the Glass toolkit thread's layout ({@code GetKeyboardLayout(GetMainThreadId())}, 0 before
     * the toolkit window exists), while the numeric-keypad branch reaches
     * {@code JavaKeyToWindowsKey}, whose OEM search uses the <em>calling</em> thread's layout. The
     * result is therefore bound to the calling thread and must not be cached or moved;
     * {@code Application.getKeyCodeForChar} does not call {@code checkEventThread} either.
     */
    static int keyCodeForChar(short c, int hint) {
        try {
            return (int) GWIN_KEY_CODE_FOR_CHAR.invokeExact(c, hint);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code KeyTable.cpp}'s {@code IsExtendedKey} (L315-343). Note what is <em>not</em> in it, exactly as
     * in the C: no {@code VK_RCONTROL}, {@code VK_RMENU} or {@code VK_DIVIDE}, which are extended keys on a
     * real keyboard but were never marked here.
     */
    static boolean isExtendedKey(int virtualKey) {
        return switch (virtualKey) {
            case VK_PRIOR, VK_NEXT, VK_END, VK_HOME, VK_LEFT, VK_UP, VK_RIGHT, VK_DOWN,
                 VK_SNAPSHOT, VK_INSERT, VK_DELETE, VK_NUMLOCK -> true;
            default -> virtualKey >= VK_NUMPAD0 && virtualKey <= VK_NUMPAD9;
        };
    }

    /**
     * {@code WinApplication._isKeyLocked}, which was {@code KeyTable.cpp:421-439} - a two-case switch
     * over {@code user32!GetKeyState} and nothing else, so there is no C left for it.
     * <p>
     * Three details of the C survive here because they are observable. Every code other than the two
     * named ones returns {@code KEY_LOCK_UNKNOWN} <em>without asking the OS at all</em> (L434-436).
     * The bit tested is bit 0, the toggle state, not the high "is down" bit (L437-438). And the two
     * Glass key codes happen to have the same numeric values as the Windows virtual keys they map to
     * ({@code VK_CAPS_LOCK == VK_CAPITAL == 0x14}, {@code VK_NUM_LOCK == VK_NUMLOCK == 0x90}), which
     * is a coincidence the C did not rely on and neither does this: the switch is over
     * {@code KeyEvent} constants and what reaches {@code GetKeyState} is a winuser.h constant.
     * <p>
     * {@code GetKeyState} reports the calling thread's view of the keyboard, and this runs on the FX
     * thread exactly as the JNI did: {@code Application.isKeyLocked} calls {@code checkEventThread()}
     * first ({@code Application.java:754-756}).
     */
    static int keyLockState(int keyCode) {
        int virtualKey = switch (keyCode) {
            case KeyEvent.VK_CAPS_LOCK -> VK_CAPITAL;
            case KeyEvent.VK_NUM_LOCK -> VK_NUMLOCK;
            default -> 0;
        };
        if (virtualKey == 0) {
            return KeyEvent.KEY_LOCK_UNKNOWN;
        }
        return (getKeyState(virtualKey) & 0x1) != 0 ? KeyEvent.KEY_LOCK_ON : KeyEvent.KEY_LOCK_OFF;
    }

    /* ---------------------------------------------------------------------------------------------
     * Win32 primitives
     * ------------------------------------------------------------------------------------------- */

    static short getKeyState(int virtualKey) {
        try {
            return (short) GET_KEY_STATE.invokeExact(virtualKey);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    static int mapVirtualKey(int code, int mapType) {
        try {
            return (int) MAP_VIRTUAL_KEY_W.invokeExact(code, mapType);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    static int getSystemMetrics(int index) {
        try {
            return (int) GET_SYSTEM_METRICS.invokeExact(index);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code SendInput(1, &input, sizeof(INPUT))}, whose result the C ignored at every call site
     * ({@code Robot.cpp} L56, L113, L189, L233), so this ignores it too.
     */
    private static void sendOneInput(MemorySegment input) {
        try {
            int ignoredSentCount = (int) SEND_INPUT.invokeExact(1, input, (int) INPUT_LAYOUT.byteSize());
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /* ---------------------------------------------------------------------------------------------
     * Robot input synthesis (Robot.cpp L35-245, now Java over user32)
     * ------------------------------------------------------------------------------------------- */

    /** {@code Robot.cpp} {@code KeyEvent(env, code, true)} (L35-60, L79). */
    static void robotKeyPress(int javaKeyCode) {
        keyEvent(javaKeyCode, true);
    }

    /** {@code Robot.cpp} {@code KeyEvent(env, code, false)} (L35-60, L90). */
    static void robotKeyRelease(int javaKeyCode) {
        keyEvent(javaKeyCode, false);
    }

    /**
     * One {@code INPUT_KEYBOARD} event, exactly as {@code KeyEvent} built it: the scan code is truncated to
     * the {@code WORD} that {@code wScan} is, and {@code time} and {@code dwExtraInfo} stay 0, which the
     * zero-filled arena allocation gives us as the C's {@code INPUT keyInput = {0}} did.
     */
    private static void keyEvent(int javaKeyCode, boolean press) {
        int virtualKey = keyJavaToWindows(javaKeyCode);
        if (virtualKey == 0) {
            return;
        }
        int scanCode = mapVirtualKey(virtualKey, MAPVK_VK_TO_VSC);
        int flags = press ? 0 : KEYEVENTF_KEYUP;
        if (isExtendedKey(virtualKey)) {
            flags |= KEYEVENTF_EXTENDEDKEY;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment input = arena.allocate(INPUT_LAYOUT);
            input.set(JAVA_INT, INPUT_TYPE_OFFSET, INPUT_KEYBOARD);
            input.set(JAVA_SHORT, KI_WVK_OFFSET, (short) virtualKey);
            input.set(JAVA_SHORT, KI_WSCAN_OFFSET, (short) scanCode);
            input.set(JAVA_INT, KI_FLAGS_OFFSET, flags);
            sendOneInput(input);
        }
    }

    /**
     * {@code Robot.cpp} {@code _mouseMove} (L101-114) from {@code GlassScreen::FX2Win} onwards:
     * {@code MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_MOVE} with the coordinates normalised as
     * {@code (int) (w * 65536.0 / GetSystemMetrics(SM_CXSCREEN))} - the float widened to double,
     * divided in double by the metric, truncated toward zero. Without
     * {@code MOUSEEVENTF_VIRTUALDESK} that normalisation is against the <em>primary</em> monitor, so
     * points on another monitor legitimately fall outside 0..65535; that is the JNI behaviour and it is
     * kept.
     *
     * @param windowsX x in Windows pixels, already through {@link WinScreenTransform#fx2Win}
     * @param windowsY y in Windows pixels
     */
    static void robotMouseMove(float windowsX, float windowsY) {
        int dx = (int) (windowsX * 65536.0 / getSystemMetrics(SM_CXSCREEN));
        int dy = (int) (windowsY * 65536.0 / getSystemMetrics(SM_CYSCREEN));
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment input = arena.allocate(INPUT_LAYOUT);
            input.set(JAVA_INT, INPUT_TYPE_OFFSET, INPUT_MOUSE);
            input.set(JAVA_INT, MI_DX_OFFSET, dx);
            input.set(JAVA_INT, MI_DY_OFFSET, dy);
            input.set(JAVA_INT, MI_FLAGS_OFFSET, MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_MOVE);
            sendOneInput(input);
        }
    }

    /** {@code Robot.cpp} {@code _mousePress} (L153-190). */
    static void robotMousePress(int buttons) {
        mouseButtons(buttons, true);
    }

    /** {@code Robot.cpp} {@code _mouseRelease} (L197-234). */
    static void robotMouseRelease(int buttons) {
        mouseButtons(buttons, false);
    }

    /**
     * One {@code INPUT_MOUSE} event carrying every requested button flag, as both C bodies built it, with
     * {@code SM_SWAPBUTTON} swapping the primary and secondary buttons ("Software Driving Software"). The event
     * is sent even when no bit was set and {@code dwFlags} is 0 - the C sent it too, and {@code GlassRobot}
     * relies on nothing here.
     */
    private static void mouseButtons(int buttons, boolean press) {
        boolean swapped = getSystemMetrics(SM_SWAPBUTTON) != 0;
        int left = press ? MOUSEEVENTF_LEFTDOWN : MOUSEEVENTF_LEFTUP;
        int right = press ? MOUSEEVENTF_RIGHTDOWN : MOUSEEVENTF_RIGHTUP;
        int flags = 0;
        int mouseData = 0;
        if ((buttons & GlassRobot.MOUSE_LEFT_BTN) != 0) {
            flags |= swapped ? right : left;
        }
        if ((buttons & GlassRobot.MOUSE_RIGHT_BTN) != 0) {
            flags |= swapped ? left : right;
        }
        if ((buttons & GlassRobot.MOUSE_MIDDLE_BTN) != 0) {
            flags |= press ? MOUSEEVENTF_MIDDLEDOWN : MOUSEEVENTF_MIDDLEUP;
        }
        if ((buttons & GlassRobot.MOUSE_BACK_BTN) != 0) {
            flags |= press ? MOUSEEVENTF_XDOWN : MOUSEEVENTF_XUP;
            mouseData |= XBUTTON1;
        }
        if ((buttons & GlassRobot.MOUSE_FORWARD_BTN) != 0) {
            flags |= press ? MOUSEEVENTF_XDOWN : MOUSEEVENTF_XUP;
            mouseData |= XBUTTON2;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment input = arena.allocate(INPUT_LAYOUT);
            input.set(JAVA_INT, INPUT_TYPE_OFFSET, INPUT_MOUSE);
            input.set(JAVA_INT, MI_FLAGS_OFFSET, flags);
            input.set(JAVA_INT, MI_MOUSE_DATA_OFFSET, mouseData);
            sendOneInput(input);
        }
    }

    /**
     * {@code Robot.cpp} {@code _mouseWheel} (L241-245):
     * {@code mouse_event(MOUSEEVENTF_WHEEL, 0, 0, wheelAmt * -1 * WHEEL_DELTA, 0)} - the legacy call,
     * not {@code SendInput}, and a positive {@code wheelAmt} therefore scrolls down. The negative
     * product crosses as the {@code DWORD} of its two's complement, which is what the int argument of
     * the descriptor does.
     */
    static void robotMouseWheel(int wheelAmt) {
        try {
            MOUSE_EVENT.invokeExact(MOUSEEVENTF_WHEEL, 0, 0, wheelAmt * -1 * WHEEL_DELTA, 0L);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code GetCursorPos} into a fresh {@code POINT}, as {@code _getMouseX} and {@code _getMouseY}
     * each did for themselves ({@code Robot.cpp} L121-146). The result is ignored exactly as the C
     * ignored it; on failure the zero-filled allocation reads back as (0, 0) where the C read an
     * uninitialised stack {@code POINT}.
     *
     * @return the cursor position in Windows pixels, {@code [x, y]}
     */
    static int[] cursorPosition() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment point = arena.allocate(POINT_LAYOUT);
            try {
                int ignoredResult = (int) GET_CURSOR_POS.invokeExact(point);
            } catch (Throwable t) {
                throw unexpected(t);
            }
            return new int[] {point.get(JAVA_INT, POINT_X_OFFSET), point.get(JAVA_INT, POINT_Y_OFFSET)};
        }
    }

    /* ---------------------------------------------------------------------------------------------
     * Screen capture (gwin_robot_*, Robot.cpp L254-384)
     * ------------------------------------------------------------------------------------------- */

    /**
     * {@code Robot.cpp} {@code _getScreenCapture} (L275-307): fills the first {@code width * height}
     * elements of {@code data} with the screen rectangle, whose {@code x} and {@code y} are raw Windows
     * pixels - {@code GlassRobot.getScreenCapture} has already scaled them by the output scale, and the
     * JNI applied no transform here either.
     * <p>
     * The four argument checks are the JNI ones, in its order, and like the JNI they reject silently:
     * a null array, a non-positive width or height, {@code width >= (INT_MAX / 4) / height} and an
     * array shorter than the rectangle all leave {@code data} untouched. They are made here rather
     * than left to the C so that no buffer is allocated for a rectangle that will be refused.
     * {@code gwin_robot_capture} repeats them.
     * <p>
     * The pixels land in an off-heap buffer of a confined arena and are copied into {@code data}
     * afterwards, whatever the status - the JNI copied its {@code new BYTE[]} block unconditionally
     * too, and every GDI failure it ignored. The one difference is that this buffer starts zeroed, so
     * where a failed {@code GetDIBits} left the JNI's uninitialised block to be swizzled into arbitrary
     * opaque colours, it now yields {@code 0xFF000000}.
     *
     * @return {@link WinCallSupport#GWIN_OK}, {@link WinCallSupport#GWIN_ERR_INVALID_ARG}, or the {@code
     *         GWIN_ROBOT_ERR_*} code of the first GDI step that failed; {@link WinRobot} ignores it, as the JNI did
     */
    static int robotCapture(int x, int y, int width, int height, int[] data) {
        if (data == null) {
            return GWIN_ERR_INVALID_ARG;
        }
        if (width <= 0 || height <= 0) {
            return GWIN_ERR_INVALID_ARG;
        }
        final int maxPixels = Integer.MAX_VALUE / Integer.BYTES;
        if (width >= maxPixels / height) {
            return GWIN_ERR_INVALID_ARG;
        }
        final int numPixels = width * height;
        if (numPixels > data.length) {
            return GWIN_ERR_INVALID_ARG;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pixels = arena.allocate(JAVA_INT, numPixels);
            int status = capture(x, y, width, height, pixels, numPixels);
            MemorySegment.copy(pixels, JAVA_INT, 0, data, 0, numPixels);
            return status;
        }
    }

    /**
     * The colour of one Windows-pixel position, as {@code Robot.cpp} {@code _getPixelColor} (L254-268)
     * read it once its coordinates were through {@code FX2Win} and truncated: a 1x1 capture into a
     * value that starts at 0, with the status ignored, so a failed capture reads back as opaque black.
     * Deliberately not {@code GetPixel} on the screen DC, which does not see layered windows correctly.
     * <p>
     * The transform itself is {@link WinScreenTransform#fx2Win}, in Java, so the coordinates arriving
     * here are already Windows pixels. {@code gwin_robot_pixel_color}, the C-side equivalent that did its own
     * transform, was test-only under ABI 5, never bound here, and has been deleted.
     */
    static int robotPixelColorAt(int windowsX, int windowsY) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pixel = arena.allocate(JAVA_INT);
            int ignoredStatus = capture(windowsX, windowsY, 1, 1, pixel, 1);
            return pixel.get(JAVA_INT, 0);
        }
    }

    private static int capture(int x, int y, int width, int height, MemorySegment argb, long length) {
        try {
            return (int) GWIN_ROBOT_CAPTURE.invokeExact(x, y, width, height, argb, length);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * A capture into a caller-supplied segment, for the tests that drive {@code gwin_robot_capture}
     * directly - including the argument rejections, which {@link #robotCapture} makes in Java before
     * the C ever sees them.
     */
    static int captureInto(int x, int y, int width, int height, MemorySegment argb, long length) {
        return capture(x, y, width, height, argb, length);
    }
}
