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
 * What glass_mac_api.m shares with the rest of libglass and not with Java. Hidden, so that nothing outside the
 * library can bind these Objective-C-typed helpers.
 */

#ifndef GLASS_MAC_INTERNAL_H
#define GLASS_MAC_INTERNAL_H

#import <Foundation/Foundation.h>
#include <stdint.h>

#import "glass_mac_api.h"

#define GLASS_INTERNAL __attribute__((visibility("hidden")))

@class NSEvent;
@class NSScreen;

/* @"" when len <= 0, else an autoreleased copy of the len code units. chars may be NULL only when len is 0. */
GLASS_INTERNAL NSString *GlassNSStringOrEmpty(const uint16_t *chars, int32_t len);

/* nil when chars is NULL, else an autoreleased copy of the len code units (@"" when len is 0). */
GLASS_INTERNAL NSString *GlassNSStringOrNil(const uint16_t *chars, int32_t len);

/*
 * Written on the main thread by runLoop:: 1 just before its first upcall attaches the thread to Java, and 0 once
 * [NSApp run] has returned. These are the points where the JNI attached the thread and cleared its environment.
 */
GLASS_INTERNAL void GlassSetMainJavaAttached(int32_t attached);

/* The flag GlassSetMainJavaAttached wrote, readable on any thread. */
GLASS_INTERNAL BOOL GlassIsMainJavaAttached(void);

/* The table gmac_app_install_callbacks installed, or NULL. */
GLASS_INTERNAL const GmacAppCallbacks *GlassAppCallbacks(void);

/* The table gmac_timer_install_callbacks installed, or NULL. */
GLASS_INTERNAL const GmacTimerCallbacks *GlassTimerCallbacks(void);

/* The table gmac_window_install_callbacks installed, or NULL. */
GLASS_INTERNAL const GmacWindowCallbacks *GlassWindowCallbacks(void);

/* The tables gmac_view_install_callbacks and gmac_gesture_install_callbacks installed, or NULL. */
GLASS_INTERNAL const GmacViewCallbacks *GlassViewCallbacks(void);
GLASS_INTERNAL const GmacGestureCallbacks *GlassGestureCallbacks(void);

/* The table gmac_dialog_install_callbacks installed, or NULL. */
GLASS_INTERNAL const GmacDialogCallbacks *GlassDialogCallbacks(void);

/* The table gmac_menu_install_callbacks installed, or NULL. */
GLASS_INTERNAL const GmacMenuCallbacks *GlassMenuCallbacks(void);

/* The table gmac_a11y_install_callbacks installed, or NULL. Any thread. */
GLASS_INTERNAL const GmacAccessibleCallbacks *GlassAccessibleCallbacks(void);

/* One key event's dials of notify_key: what they share, and the dial count after this event's last dial, 0 before
 * its first. */
typedef struct GlassKeySend {
    const GmacViewCallbacks *callbacks;
    int64_t view_id;
    const uint16_t *chars;
    int32_t char_count;
    int32_t modifiers;
    uint64_t last_dial;
} GlassKeySend;

/*
 * Main thread. Dials notify_key for send with type and key_code, and answers its status, GMAC_ERR_NO_DATA for a NULL
 * slot. Writes to *out_consumed, unless out_consumed is NULL, what the slot wrote, 0 when it wrote nothing.
 * same_chars_as_previous is 1 when this event made the last dial of the slot, so that Java passes the char[] of that
 * dial again, as the JNI passed every dial of one event the same jcharArray.
 */
GLASS_INTERNAL int32_t GlassDialKeySlot(GlassKeySend *send, int32_t type, int32_t key_code, int32_t *out_consumed);

/*
 * The units one key event passes notify_key: text_char alone when it is not 0, else every unit GetJavaKeyCharsUtf16
 * answers for event, none for a key that sends none. units is inline_units, or a heap block for more units than
 * inline_units holds; if that block cannot be allocated, units keeps the first 16 and count is 16. Holds a pointer into
 * itself, so it is never copied. Every GlassKeyCharsFill is followed by one GlassKeyCharsRelease.
 */
typedef struct GlassKeyChars {
    uint16_t inline_units[16];
    uint16_t *units;
    int32_t count;
} GlassKeyChars;

GLASS_INTERNAL void GlassKeyCharsFill(GlassKeyChars *key_chars, NSEvent *event, unichar text_char);
GLASS_INTERNAL void GlassKeyCharsRelease(GlassKeyChars *key_chars);

/*
 * Main thread. Dials notify_input_method for text, an NSString or NSAttributedString, with the units the JNI gave
 * NewString, and answers its status: GMAC_ERR_NO_DATA for a NULL slot, INT32_MIN where the JNI made no String and so
 * made no call.
 */
GLASS_INTERNAL int32_t GlassDialInputMethod(const GmacViewCallbacks *callbacks, int64_t view_id, id text, int attr,
                                            int length, int cursor, NSRange selection);

/* What the JNI passed Screen's constructor for screen, which it retains and never releases (the deliberate leak). */
GLASS_INTERNAL void GlassFillScreenInfo(NSScreen *screen, GmacScreenInfo *info);

/*
 * The strings-out convention of glass_mac_api.h: a malloc'd copy of utf8 in *out_utf8 and its length, and GMAC_OK;
 * GMAC_ERR_NO_DATA with NULL and 0 when utf8 is NULL or the copy cannot be allocated.
 */
GLASS_INTERNAL int32_t GlassCopyUtf8(const char *utf8, char **out_utf8, int32_t *out_length);

#endif /* GLASS_MAC_INTERNAL_H */
