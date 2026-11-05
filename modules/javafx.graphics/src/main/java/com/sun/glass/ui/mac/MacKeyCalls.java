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

import com.sun.glass.events.KeyEvent;
import java.util.function.LongSupplier;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_CURRENT_MODIFIER_FLAGS;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_KEY_CODE_FOR_CHAR;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_KEY_JAVA_TO_MAC;
import static com.sun.glass.ui.mac.MacGlassNative.unexpected;

/** The key natives of {@code MacApplication}. */
final class MacKeyCalls {

    /** {@code NSEventModifierFlagCapsLock}. */
    static final int GMAC_MODIFIER_FLAG_CAPS_LOCK = 65536;

    private MacKeyCalls() {
    }

    /** The Mac key code of a {@code KeyEvent.VK_*} code; 0 when there is none, which is also {@code kVK_ANSI_A}. */
    static int javaToMacKey(int javaKeyCode) {
        try {
            return (int) GMAC_KEY_JAVA_TO_MAC.invokeExact(javaKeyCode);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** Any thread: the library reads the main thread's map without a lock, as the JNI did. */
    static int keyCodeForChar(char c) {
        try {
            return (int) GMAC_KEY_CODE_FOR_CHAR.invokeExact(c);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    static int isKeyLocked(int keyCode) {
        return isKeyLocked(keyCode, MacKeyCalls::currentModifierFlags);
    }

    /** The JNI's switch: Caps Lock is the only locking key of macOS, and only it reads the modifier flags. */
    static int isKeyLocked(int keyCode, LongSupplier modifierFlags) {
        if (keyCode != KeyEvent.VK_CAPS_LOCK) {
            return KeyEvent.KEY_LOCK_UNKNOWN;
        }
        return (modifierFlags.getAsLong() & GMAC_MODIFIER_FLAG_CAPS_LOCK) != 0 ? KeyEvent.KEY_LOCK_ON
                : KeyEvent.KEY_LOCK_OFF;
    }

    private static long currentModifierFlags() {
        try {
            return (long) GMAC_CURRENT_MODIFIER_FLAGS.invokeExact();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }
}
