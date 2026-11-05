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

#import <pthread.h>
#import <stddef.h>
#import <stdlib.h>
#import <string.h>

#import <ApplicationServices/ApplicationServices.h>

#import "GlassMacros.h"
#import "GlassStatics.h"
#import "glass_mac_api.h"
#import "glass_mac_internal.h"

static const int32_t ABI_CONSTANTS[GMAC_ABI_CONSTANT_COUNT] = {
    [GMAC_ABI_OK]                      = GMAC_OK,
    [GMAC_ABI_ERR_NO_DATA]             = GMAC_ERR_NO_DATA,
    [GMAC_ABI_ERR_UNSUPPORTED_OS]      = GMAC_ERR_UNSUPPORTED_OS,
    [GMAC_ABI_ERR_NOT_ATTACHED]        = GMAC_ERR_NOT_ATTACHED,
    [GMAC_ABI_ERR_DETACHED_OFF_MAIN]   = GMAC_ERR_DETACHED_OFF_MAIN,
    [GMAC_ABI_UPCALL_OK]               = GMAC_UPCALL_OK,
    [GMAC_ABI_UPCALL_THREW]            = GMAC_UPCALL_THREW,
    [GMAC_ABI_PREF_COLOR]              = GMAC_PREF_COLOR,
    [GMAC_ABI_PREF_COLOR_ARRAY]        = GMAC_PREF_COLOR_ARRAY,
    [GMAC_ABI_PREF_STRING]             = GMAC_PREF_STRING,
    [GMAC_ABI_PREF_BOOLEAN]            = GMAC_PREF_BOOLEAN,
    [GMAC_ABI_MODIFIER_FLAG_CAPS_LOCK] = GMAC_MODIFIER_FLAG_CAPS_LOCK,
    [GMAC_ABI_PASTEBOARD_REP_BAD]      = GMAC_PASTEBOARD_REP_BAD,
    [GMAC_ABI_PASTEBOARD_REP_NONE]     = GMAC_PASTEBOARD_REP_NONE,
    [GMAC_ABI_PASTEBOARD_REP_STRING]   = GMAC_PASTEBOARD_REP_STRING,
    [GMAC_ABI_PASTEBOARD_REP_BYTES]    = GMAC_PASTEBOARD_REP_BYTES,
    [GMAC_ABI_PASTEBOARD_REP_IMAGE]    = GMAC_PASTEBOARD_REP_IMAGE,
    [GMAC_ABI_PASTEBOARD_ITEM_NULL]    = GMAC_PASTEBOARD_ITEM_NULL
};

_Static_assert(GMAC_MODIFIER_FLAG_CAPS_LOCK == NSEventModifierFlagCapsLock, "the SDK moved the Caps Lock flag");

_Static_assert(sizeof(GmacAppCallbacks) == 21 * sizeof(void *), "GmacAppCallbacks must be 21 pointers");
_Static_assert(sizeof(GmacTimerCallbacks) == sizeof(void *), "GmacTimerCallbacks must be 1 pointer");
_Static_assert(sizeof(GmacWindowCallbacks) == 10 * sizeof(void *), "GmacWindowCallbacks must be 10 pointers");
_Static_assert(sizeof(GmacViewCallbacks) == 15 * sizeof(void *), "GmacViewCallbacks must be 15 pointers");
_Static_assert(sizeof(GmacGestureCallbacks) == 5 * sizeof(void *), "GmacGestureCallbacks must be 5 pointers");
_Static_assert(sizeof(GmacScreenInfo) == 88, "GmacScreenInfo must be 88 bytes");
_Static_assert(sizeof(GmacColor) == 24, "GmacColor must be 24 bytes");
_Static_assert(sizeof(GmacPref) == 48, "GmacPref must be 48 bytes");
_Static_assert(sizeof(GmacViewCaps) == 40, "GmacViewCaps must be 40 bytes");
_Static_assert(sizeof(GmacPasteboardRep) == 32, "GmacPasteboardRep must be 32 bytes");
_Static_assert(sizeof(GmacPasteboardItem) == 16, "GmacPasteboardItem must be 16 bytes");
_Static_assert(sizeof(GmacFileFilter) == 32, "GmacFileFilter must be 32 bytes");
_Static_assert(sizeof(GmacDialogCallbacks) == 2 * sizeof(void *), "GmacDialogCallbacks must be 2 pointers");
_Static_assert(sizeof(GmacMenuImage) == 24, "GmacMenuImage must be 24 bytes");
_Static_assert(sizeof(GmacMenuCallbacks) == 5 * sizeof(void *), "GmacMenuCallbacks must be 5 pointers");
_Static_assert(sizeof(GmacVariant) == 120, "GmacVariant must be 120 bytes");
_Static_assert(sizeof(GmacAccessibleCallbacks) == 16 * sizeof(void *), "GmacAccessibleCallbacks must be 16 pointers");

static const int32_t SCREEN_INFO_OFFSETS[] = {
    offsetof(GmacScreenInfo, screen),
    offsetof(GmacScreenInfo, depth),
    offsetof(GmacScreenInfo, x),
    offsetof(GmacScreenInfo, y),
    offsetof(GmacScreenInfo, width),
    offsetof(GmacScreenInfo, height),
    offsetof(GmacScreenInfo, platform_x),
    offsetof(GmacScreenInfo, platform_y),
    offsetof(GmacScreenInfo, platform_width),
    offsetof(GmacScreenInfo, platform_height),
    offsetof(GmacScreenInfo, visible_x),
    offsetof(GmacScreenInfo, visible_y),
    offsetof(GmacScreenInfo, visible_width),
    offsetof(GmacScreenInfo, visible_height),
    offsetof(GmacScreenInfo, resolution_x),
    offsetof(GmacScreenInfo, resolution_y),
    offsetof(GmacScreenInfo, platform_scale_x),
    offsetof(GmacScreenInfo, platform_scale_y),
    offsetof(GmacScreenInfo, output_scale_x),
    offsetof(GmacScreenInfo, output_scale_y)
};

static const int32_t COLOR_OFFSETS[] = {
    offsetof(GmacColor, r),
    offsetof(GmacColor, g),
    offsetof(GmacColor, b),
    offsetof(GmacColor, reserved),
    offsetof(GmacColor, a)
};

static const int32_t PREF_OFFSETS[] = {
    offsetof(GmacPref, key),
    offsetof(GmacPref, kind),
    offsetof(GmacPref, count),
    offsetof(GmacPref, color),
    offsetof(GmacPref, value)
};

static const int32_t VIEW_CAPS_OFFSETS[] = {
    offsetof(GmacViewCaps, mtl_command_queue),
    offsetof(GmacViewCaps, share_context),
    offsetof(GmacViewCaps, context),
    offsetof(GmacViewCaps, depth_bits),
    offsetof(GmacViewCaps, hidpi_aware),
    offsetof(GmacViewCaps, mtl_for_sw),
    offsetof(GmacViewCaps, vsync_enabled)
};

static const int32_t PASTEBOARD_REP_OFFSETS[] = {
    offsetof(GmacPasteboardRep, uti),
    offsetof(GmacPasteboardRep, uti_length),
    offsetof(GmacPasteboardRep, kind),
    offsetof(GmacPasteboardRep, data),
    offsetof(GmacPasteboardRep, data_length),
    offsetof(GmacPasteboardRep, reserved)
};

static const int32_t PASTEBOARD_ITEM_OFFSETS[] = {
    offsetof(GmacPasteboardItem, reps),
    offsetof(GmacPasteboardItem, rep_count),
    offsetof(GmacPasteboardItem, reserved)
};

static const int32_t FILE_FILTER_OFFSETS[] = {
    offsetof(GmacFileFilter, description),
    offsetof(GmacFileFilter, description_length),
    offsetof(GmacFileFilter, extension_count),
    offsetof(GmacFileFilter, extensions),
    offsetof(GmacFileFilter, extension_lengths)
};

static const int32_t MENU_IMAGE_OFFSETS[] = {
    offsetof(GmacMenuImage, image),
    offsetof(GmacMenuImage, width),
    offsetof(GmacMenuImage, height),
    offsetof(GmacMenuImage, scale_x),
    offsetof(GmacMenuImage, scale_y)
};

static const int32_t VARIANT_OFFSETS[] = {
    offsetof(GmacVariant, type),
    offsetof(GmacVariant, int1),
    offsetof(GmacVariant, int2),
    offsetof(GmacVariant, location),
    offsetof(GmacVariant, length),
    offsetof(GmacVariant, float1),
    offsetof(GmacVariant, float2),
    offsetof(GmacVariant, float3),
    offsetof(GmacVariant, float4),
    offsetof(GmacVariant, long_array_count),
    offsetof(GmacVariant, long1),
    offsetof(GmacVariant, double1),
    offsetof(GmacVariant, key),
    offsetof(GmacVariant, long_array),
    offsetof(GmacVariant, int_array),
    offsetof(GmacVariant, int_array_count),
    offsetof(GmacVariant, chars_length),
    offsetof(GmacVariant, chars),
    offsetof(GmacVariant, utf8),
    offsetof(GmacVariant, variant_array),
    offsetof(GmacVariant, variant_array_count),
    offsetof(GmacVariant, utf8_length)
};

_Static_assert(offsetof(GmacScreenInfo, depth) == 8 && offsetof(GmacScreenInfo, resolution_y) == 64
               && offsetof(GmacScreenInfo, platform_scale_x) == 68 && offsetof(GmacScreenInfo, output_scale_y) == 80,
               "GmacScreenInfo moved a member");
_Static_assert(offsetof(GmacColor, a) == 16, "GmacColor moved a member");
_Static_assert(offsetof(GmacPref, kind) == 8 && offsetof(GmacPref, count) == 12 && offsetof(GmacPref, color) == 16
               && offsetof(GmacPref, value) == 40, "GmacPref moved a member");
_Static_assert(offsetof(GmacViewCaps, share_context) == 8 && offsetof(GmacViewCaps, context) == 16
               && offsetof(GmacViewCaps, depth_bits) == 24 && offsetof(GmacViewCaps, hidpi_aware) == 28
               && offsetof(GmacViewCaps, mtl_for_sw) == 32 && offsetof(GmacViewCaps, vsync_enabled) == 36,
               "GmacViewCaps moved a member");
_Static_assert(offsetof(GmacPasteboardRep, uti_length) == 8 && offsetof(GmacPasteboardRep, kind) == 12
               && offsetof(GmacPasteboardRep, data) == 16 && offsetof(GmacPasteboardRep, data_length) == 24
               && offsetof(GmacPasteboardRep, reserved) == 28, "GmacPasteboardRep moved a member");
_Static_assert(offsetof(GmacPasteboardItem, rep_count) == 8 && offsetof(GmacPasteboardItem, reserved) == 12,
               "GmacPasteboardItem moved a member");
_Static_assert(offsetof(GmacFileFilter, description_length) == 8 && offsetof(GmacFileFilter, extension_count) == 12
               && offsetof(GmacFileFilter, extensions) == 16 && offsetof(GmacFileFilter, extension_lengths) == 24,
               "GmacFileFilter moved a member");
_Static_assert(offsetof(GmacMenuImage, width) == 8 && offsetof(GmacMenuImage, height) == 12
               && offsetof(GmacMenuImage, scale_x) == 16 && offsetof(GmacMenuImage, scale_y) == 20,
               "GmacMenuImage moved a member");
_Static_assert(offsetof(GmacVariant, int1) == 4 && offsetof(GmacVariant, int2) == 8
               && offsetof(GmacVariant, location) == 12 && offsetof(GmacVariant, length) == 16
               && offsetof(GmacVariant, float1) == 20 && offsetof(GmacVariant, float2) == 24
               && offsetof(GmacVariant, float3) == 28 && offsetof(GmacVariant, float4) == 32
               && offsetof(GmacVariant, long_array_count) == 36 && offsetof(GmacVariant, long1) == 40
               && offsetof(GmacVariant, double1) == 48 && offsetof(GmacVariant, key) == 56
               && offsetof(GmacVariant, long_array) == 64 && offsetof(GmacVariant, int_array) == 72
               && offsetof(GmacVariant, int_array_count) == 80 && offsetof(GmacVariant, chars_length) == 84
               && offsetof(GmacVariant, chars) == 88 && offsetof(GmacVariant, utf8) == 96
               && offsetof(GmacVariant, variant_array) == 104 && offsetof(GmacVariant, variant_array_count) == 112
               && offsetof(GmacVariant, utf8_length) == 116, "GmacVariant moved a member");

static pthread_once_t threadDataKeyOnce = PTHREAD_ONCE_INIT;
static int threadDataKeyStatus = 0;

// Written on the main thread and read on any thread; relaxed atomics keep that free of a data race.
static int32_t mainJavaAttached = 0;

static GmacAppCallbacks appCallbacks;

// Installed on the launcher thread before the delegate exists, read on the main thread: release/acquire publishes
// the copy.
static const GmacAppCallbacks *installedAppCallbacks = NULL;

static GmacTimerCallbacks timerCallbacks;

// Installed from MacTimer's class initialization before any timer exists, read on the timer threads.
static const GmacTimerCallbacks *installedTimerCallbacks = NULL;

static GmacWindowCallbacks windowCallbacks;

// Installed from MacWindow's class initialization before any window exists, read on the main thread.
static const GmacWindowCallbacks *installedWindowCallbacks = NULL;

static GmacViewCallbacks viewCallbacks;

// Installed from MacView's class initialization before any view exists, read on the main thread.
static const GmacViewCallbacks *installedViewCallbacks = NULL;

static GmacGestureCallbacks gestureCallbacks;

static const GmacGestureCallbacks *installedGestureCallbacks = NULL;

static GmacDialogCallbacks dialogCallbacks;

// Installed from MacCommonDialogs' class initialization before any dialog runs, read on the main thread.
static const GmacDialogCallbacks *installedDialogCallbacks = NULL;

static GmacMenuCallbacks menuCallbacks;

// Installed from MacMenuDelegate's class initialization before any menu exists, read on the main thread.
static const GmacMenuCallbacks *installedMenuCallbacks = NULL;

static GmacAccessibleCallbacks accessibleCallbacks;

// Installed from MacAccessible's class initialization before any peer exists, read on the main thread and, by dealloc,
// on any other.
static const GmacAccessibleCallbacks *installedAccessibleCallbacks = NULL;

int32_t gmac_abi_version(void)
{
    return GLASS_MAC_ABI_VERSION;
}

int32_t gmac_abi_constant(int32_t index)
{
    if (index < 0 || index >= GMAC_ABI_CONSTANT_COUNT) {
        return INT32_MIN;
    }
    return ABI_CONSTANTS[index];
}

static void createThreadDataKey(void)
{
    threadDataKeyStatus = pthread_key_create(&GlassThreadDataKey, NULL);
}

int32_t gmac_init(void)
{
    pthread_once(&threadDataKeyOnce, createThreadDataKey);
    return threadDataKeyStatus;
}

void gmac_free(void *block)
{
    free(block);
}

void GlassSetMainJavaAttached(int32_t attached)
{
    __atomic_store_n(&mainJavaAttached, attached, __ATOMIC_RELAXED);
}

int32_t gmac_main_thread_detached(void)
{
    return pthread_main_np() == 0 && __atomic_load_n(&mainJavaAttached, __ATOMIC_RELAXED) == 0;
}

BOOL GlassIsMainJavaAttached(void)
{
    return __atomic_load_n(&mainJavaAttached, __ATOMIC_RELAXED) != 0;
}

int32_t gmac_sizeof_app_callbacks(void)
{
    return (int32_t)sizeof(GmacAppCallbacks);
}

int32_t gmac_sizeof_screen_info(void)
{
    return (int32_t)sizeof(GmacScreenInfo);
}

int32_t gmac_sizeof_color(void)
{
    return (int32_t)sizeof(GmacColor);
}

int32_t gmac_sizeof_pref(void)
{
    return (int32_t)sizeof(GmacPref);
}

int32_t gmac_sizeof_timer_callbacks(void)
{
    return (int32_t)sizeof(GmacTimerCallbacks);
}

int32_t gmac_sizeof_window_callbacks(void)
{
    return (int32_t)sizeof(GmacWindowCallbacks);
}

int32_t gmac_sizeof_view_caps(void)
{
    return (int32_t)sizeof(GmacViewCaps);
}

int32_t gmac_sizeof_view_callbacks(void)
{
    return (int32_t)sizeof(GmacViewCallbacks);
}

int32_t gmac_sizeof_gesture_callbacks(void)
{
    return (int32_t)sizeof(GmacGestureCallbacks);
}

int32_t gmac_sizeof_pasteboard_rep(void)
{
    return (int32_t)sizeof(GmacPasteboardRep);
}

int32_t gmac_sizeof_pasteboard_item(void)
{
    return (int32_t)sizeof(GmacPasteboardItem);
}

int32_t gmac_sizeof_file_filter(void)
{
    return (int32_t)sizeof(GmacFileFilter);
}

int32_t gmac_sizeof_dialog_callbacks(void)
{
    return (int32_t)sizeof(GmacDialogCallbacks);
}

int32_t gmac_sizeof_menu_image(void)
{
    return (int32_t)sizeof(GmacMenuImage);
}

int32_t gmac_sizeof_menu_callbacks(void)
{
    return (int32_t)sizeof(GmacMenuCallbacks);
}

int32_t gmac_sizeof_variant(void)
{
    return (int32_t)sizeof(GmacVariant);
}

int32_t gmac_sizeof_accessible_callbacks(void)
{
    return (int32_t)sizeof(GmacAccessibleCallbacks);
}

static int32_t memberOffset(const int32_t *offsets, int32_t count, int32_t member)
{
    return member < 0 || member >= count ? -1 : offsets[member];
}

int32_t gmac_offsetof_screen_info(int32_t member)
{
    return memberOffset(SCREEN_INFO_OFFSETS, (int32_t)(sizeof(SCREEN_INFO_OFFSETS) / sizeof(int32_t)), member);
}

int32_t gmac_offsetof_color(int32_t member)
{
    return memberOffset(COLOR_OFFSETS, (int32_t)(sizeof(COLOR_OFFSETS) / sizeof(int32_t)), member);
}

int32_t gmac_offsetof_pref(int32_t member)
{
    return memberOffset(PREF_OFFSETS, (int32_t)(sizeof(PREF_OFFSETS) / sizeof(int32_t)), member);
}

int32_t gmac_offsetof_view_caps(int32_t member)
{
    return memberOffset(VIEW_CAPS_OFFSETS, (int32_t)(sizeof(VIEW_CAPS_OFFSETS) / sizeof(int32_t)), member);
}

int32_t gmac_offsetof_pasteboard_rep(int32_t member)
{
    return memberOffset(PASTEBOARD_REP_OFFSETS, (int32_t)(sizeof(PASTEBOARD_REP_OFFSETS) / sizeof(int32_t)), member);
}

int32_t gmac_offsetof_pasteboard_item(int32_t member)
{
    return memberOffset(PASTEBOARD_ITEM_OFFSETS, (int32_t)(sizeof(PASTEBOARD_ITEM_OFFSETS) / sizeof(int32_t)), member);
}

int32_t gmac_offsetof_file_filter(int32_t member)
{
    return memberOffset(FILE_FILTER_OFFSETS, (int32_t)(sizeof(FILE_FILTER_OFFSETS) / sizeof(int32_t)), member);
}

int32_t gmac_offsetof_menu_image(int32_t member)
{
    return memberOffset(MENU_IMAGE_OFFSETS, (int32_t)(sizeof(MENU_IMAGE_OFFSETS) / sizeof(int32_t)), member);
}

int32_t gmac_offsetof_variant(int32_t member)
{
    return memberOffset(VARIANT_OFFSETS, (int32_t)(sizeof(VARIANT_OFFSETS) / sizeof(int32_t)), member);
}

int32_t gmac_app_install_callbacks(const GmacAppCallbacks *callbacks)
{
    if (callbacks == NULL) {
        __atomic_store_n(&installedAppCallbacks, NULL, __ATOMIC_RELEASE);
        return GMAC_OK;
    }
    appCallbacks = *callbacks;
    __atomic_store_n(&installedAppCallbacks, &appCallbacks, __ATOMIC_RELEASE);
    return GMAC_OK;
}

const GmacAppCallbacks *GlassAppCallbacks(void)
{
    return __atomic_load_n(&installedAppCallbacks, __ATOMIC_ACQUIRE);
}

int32_t gmac_timer_install_callbacks(const GmacTimerCallbacks *callbacks)
{
    if (callbacks == NULL) {
        __atomic_store_n(&installedTimerCallbacks, NULL, __ATOMIC_RELEASE);
        return GMAC_OK;
    }
    timerCallbacks = *callbacks;
    __atomic_store_n(&installedTimerCallbacks, &timerCallbacks, __ATOMIC_RELEASE);
    return GMAC_OK;
}

const GmacTimerCallbacks *GlassTimerCallbacks(void)
{
    return __atomic_load_n(&installedTimerCallbacks, __ATOMIC_ACQUIRE);
}

int32_t gmac_window_install_callbacks(const GmacWindowCallbacks *callbacks)
{
    if (callbacks == NULL) {
        __atomic_store_n(&installedWindowCallbacks, NULL, __ATOMIC_RELEASE);
        return GMAC_OK;
    }
    windowCallbacks = *callbacks;
    __atomic_store_n(&installedWindowCallbacks, &windowCallbacks, __ATOMIC_RELEASE);
    return GMAC_OK;
}

const GmacWindowCallbacks *GlassWindowCallbacks(void)
{
    return __atomic_load_n(&installedWindowCallbacks, __ATOMIC_ACQUIRE);
}

int32_t gmac_view_install_callbacks(const GmacViewCallbacks *callbacks)
{
    if (callbacks == NULL) {
        __atomic_store_n(&installedViewCallbacks, NULL, __ATOMIC_RELEASE);
        return GMAC_OK;
    }
    viewCallbacks = *callbacks;
    __atomic_store_n(&installedViewCallbacks, &viewCallbacks, __ATOMIC_RELEASE);
    return GMAC_OK;
}

const GmacViewCallbacks *GlassViewCallbacks(void)
{
    return __atomic_load_n(&installedViewCallbacks, __ATOMIC_ACQUIRE);
}

int32_t gmac_gesture_install_callbacks(const GmacGestureCallbacks *callbacks)
{
    if (callbacks == NULL) {
        __atomic_store_n(&installedGestureCallbacks, NULL, __ATOMIC_RELEASE);
        return GMAC_OK;
    }
    gestureCallbacks = *callbacks;
    __atomic_store_n(&installedGestureCallbacks, &gestureCallbacks, __ATOMIC_RELEASE);
    return GMAC_OK;
}

const GmacGestureCallbacks *GlassGestureCallbacks(void)
{
    return __atomic_load_n(&installedGestureCallbacks, __ATOMIC_ACQUIRE);
}

int32_t gmac_dialog_install_callbacks(const GmacDialogCallbacks *callbacks)
{
    if (callbacks == NULL) {
        __atomic_store_n(&installedDialogCallbacks, NULL, __ATOMIC_RELEASE);
        return GMAC_OK;
    }
    dialogCallbacks = *callbacks;
    __atomic_store_n(&installedDialogCallbacks, &dialogCallbacks, __ATOMIC_RELEASE);
    return GMAC_OK;
}

const GmacDialogCallbacks *GlassDialogCallbacks(void)
{
    return __atomic_load_n(&installedDialogCallbacks, __ATOMIC_ACQUIRE);
}

int32_t gmac_menu_install_callbacks(const GmacMenuCallbacks *callbacks)
{
    if (callbacks == NULL) {
        __atomic_store_n(&installedMenuCallbacks, NULL, __ATOMIC_RELEASE);
        return GMAC_OK;
    }
    menuCallbacks = *callbacks;
    __atomic_store_n(&installedMenuCallbacks, &menuCallbacks, __ATOMIC_RELEASE);
    return GMAC_OK;
}

const GmacMenuCallbacks *GlassMenuCallbacks(void)
{
    return __atomic_load_n(&installedMenuCallbacks, __ATOMIC_ACQUIRE);
}

int32_t gmac_a11y_install_callbacks(const GmacAccessibleCallbacks *callbacks)
{
    if (callbacks == NULL) {
        __atomic_store_n(&installedAccessibleCallbacks, NULL, __ATOMIC_RELEASE);
        return GMAC_OK;
    }
    accessibleCallbacks = *callbacks;
    __atomic_store_n(&installedAccessibleCallbacks, &accessibleCallbacks, __ATOMIC_RELEASE);
    return GMAC_OK;
}

const GmacAccessibleCallbacks *GlassAccessibleCallbacks(void)
{
    return __atomic_load_n(&installedAccessibleCallbacks, __ATOMIC_ACQUIRE);
}

int32_t GlassCopyUtf8(const char *utf8, char **out_utf8, int32_t *out_length)
{
    *out_utf8 = NULL;
    *out_length = 0;
    if (utf8 == NULL) {
        return GMAC_ERR_NO_DATA;
    }
    size_t length = strlen(utf8);
    char *copy = malloc(length + 1);
    if (copy == NULL) {
        return GMAC_ERR_NO_DATA;
    }
    memcpy(copy, utf8, length + 1);
    *out_utf8 = copy;
    *out_length = (int32_t)length;
    return GMAC_OK;
}

NSString *GlassNSStringOrEmpty(const uint16_t *chars, int32_t len)
{
    NSString *string = @"";
    if (len > 0)
    {
        string = [[[NSString alloc] initWithCharacters:chars length:(NSUInteger)len] autorelease];
    }
    return string;
}

NSString *GlassNSStringOrNil(const uint16_t *chars, int32_t len)
{
    if (chars == NULL) {
        return nil;
    }
    return [NSString stringWithCharacters:chars length:(NSUInteger)len];
}

void gmac_pool_push(void)
{
    GLASS_POOL_PUSH;
}

void gmac_pool_pop(void)
{
    GLASS_POOL_POP;
}

void gmac_mouse_location_flipped(double *out_x, double *out_y)
{
    CGPoint where = NSPointToCGPoint([NSEvent mouseLocation]);
    NSScreen * screen = [[NSScreen screens] objectAtIndex: 0];
    NSRect screenFrame = screen.frame;
    where.y = screenFrame.size.height - where.y;
    *out_x = where.x;
    *out_y = where.y;
}

int32_t gmac_test_pool_depth(void)
{
    GlassThreadData *data = (GlassThreadData *)pthread_getspecific(GlassThreadDataKey);
    return data == NULL ? -1 : data->counter;
}

int32_t gmac_test_set_main_java_attached(int32_t attached)
{
    return __atomic_exchange_n(&mainJavaAttached, attached, __ATOMIC_RELAXED);
}

static int32_t copyTestString(NSString *string, uint16_t *out, int32_t capacity)
{
    if (string == nil) {
        return -1;
    }
    NSUInteger length = [string length];
    NSUInteger count = capacity > 0 ? MIN(length, (NSUInteger)capacity) : 0;
    if (count > 0) {
        [string getCharacters:out range:NSMakeRange(0, count)];
    }
    return (int32_t)length;
}

int32_t gmac_test_string_or_empty(const uint16_t *chars, int32_t len, uint16_t *out, int32_t capacity)
{
    int32_t result;
    GLASS_POOL_ENTER;
    {
        result = copyTestString(GlassNSStringOrEmpty(chars, len), out, capacity);
    }
    GLASS_POOL_EXIT;
    return result;
}

int32_t gmac_test_string_or_nil(const uint16_t *chars, int32_t len, uint16_t *out, int32_t capacity)
{
    int32_t result;
    GLASS_POOL_ENTER;
    {
        result = copyTestString(GlassNSStringOrNil(chars, len), out, capacity);
    }
    GLASS_POOL_EXIT;
    return result;
}

static const char *const TEST_FILES[] = {"/a", NULL, "\xC3\xA9\xF0\x9F\x98\x80"};
static const int32_t TEST_FILE_LENGTHS[] = {2, 0, 6};

static const GmacColor TEST_COLORS[] = {{1, 2, 3, 0, 0.25}, {4, 5, 6, 0, 0.75}};
static const GmacColor TEST_BAD_COLORS[] = {{7, 8, 9, 0, 1.0}, {-1, 0, 0, 0, 1.0}};

static const GmacPref TEST_PREFERENCES[] = {
    {"test.color", GMAC_PREF_COLOR, 0, {255, 128, 0, 0, 0.5}, NULL},
    {"test.badColor", GMAC_PREF_COLOR, 0, {256, 0, 0, 0, 1.0}, NULL},
    {"test.colors", GMAC_PREF_COLOR_ARRAY, 2, {0, 0, 0, 0, 0.0}, TEST_COLORS},
    {"test.badColors", GMAC_PREF_COLOR_ARRAY, 2, {0, 0, 0, 0, 0.0}, TEST_BAD_COLORS},
    {"test.noColors", GMAC_PREF_COLOR_ARRAY, 0, {0, 0, 0, 0, 0.0}, NULL},
    {"test.string", GMAC_PREF_STRING, 6, {0, 0, 0, 0, 0.0}, "string"},
    {"test.nullString", GMAC_PREF_STRING, 0, {0, 0, 0, 0, 0.0}, NULL},
    {"test.true", GMAC_PREF_BOOLEAN, 1, {0, 0, 0, 0, 0.0}, NULL},
    {"test.false", GMAC_PREF_BOOLEAN, 0, {0, 0, 0, 0, 0.0}, NULL}
};

static int32_t fireNoArgumentSlot(int32_t (*slot)(void))
{
    return slot != NULL ? slot() : GMAC_ERR_NO_DATA;
}

int32_t gmac_test_fire_app_callback(int32_t slot)
{
    if (slot < 0 || slot >= (int32_t)(sizeof(GmacAppCallbacks) / sizeof(void *))) {
        return INT32_MIN;
    }
    const GmacAppCallbacks *callbacks = GlassAppCallbacks();
    if (callbacks == NULL) {
        return GMAC_ERR_NO_DATA;
    }
    switch (slot) {
        case 0:
            return callbacks->run_runnable != NULL
                ? callbacks->run_runnable(INT64_C(0x0102030405060708)) : GMAC_ERR_NO_DATA;
        case 1:
            return fireNoArgumentSlot(callbacks->run_launchable);
        case 2:
            return fireNoArgumentSlot(callbacks->set_event_thread);
        case 3:
            return callbacks->set_name != NULL ? callbacks->set_name("A\xF0\x9F\x98\x80" "B", 6) : GMAC_ERR_NO_DATA;
        case 4:
            return fireNoArgumentSlot(callbacks->notify_will_finish_launching);
        case 5:
            return fireNoArgumentSlot(callbacks->notify_did_finish_launching);
        case 6:
            return fireNoArgumentSlot(callbacks->notify_will_become_active);
        case 7:
            return fireNoArgumentSlot(callbacks->notify_did_become_active);
        case 8:
            return fireNoArgumentSlot(callbacks->notify_will_resign_active);
        case 9:
            return fireNoArgumentSlot(callbacks->notify_did_resign_active);
        case 10:
            return fireNoArgumentSlot(callbacks->notify_will_hide);
        case 11:
            return fireNoArgumentSlot(callbacks->notify_did_hide);
        case 12:
            return fireNoArgumentSlot(callbacks->notify_will_unhide);
        case 13:
            return fireNoArgumentSlot(callbacks->notify_did_unhide);
        case 14:
            return fireNoArgumentSlot(callbacks->notify_will_quit);
        case 15:
            return callbacks->notify_open_files != NULL
                ? callbacks->notify_open_files(TEST_FILES, TEST_FILE_LENGTHS, 3) : GMAC_ERR_NO_DATA;
        case 16:
            return fireNoArgumentSlot(callbacks->notify_did_terminate);
        case 17:
            return fireNoArgumentSlot(callbacks->enter_nested_event_loop);
        case 18:
            return fireNoArgumentSlot(callbacks->leave_nested_event_loop);
        case 19:
            return fireNoArgumentSlot(callbacks->notify_screen_settings_changed);
        default:
            return callbacks->notify_preferences_changed != NULL
                ? callbacks->notify_preferences_changed(TEST_PREFERENCES,
                        (int32_t)(sizeof(TEST_PREFERENCES) / sizeof(GmacPref)))
                : GMAC_ERR_NO_DATA;
    }
}

int32_t gmac_test_inspect_and_release_image(void *image, int32_t *out, uint8_t *pixels, int32_t capacity)
{
    if (image == NULL) {
        return -1;
    }
    int32_t length = -1;
    GLASS_POOL_ENTER;
    {
        NSImage *nsImage = (NSImage *)image;
        NSSize size = [nsImage size];
        out[0] = (int32_t)size.width;
        out[1] = (int32_t)size.height;
        memset(out + 2, 0, 7 * sizeof(int32_t));

        CGImageRef cgImage = [nsImage CGImageForProposedRect:NULL context:nil hints:nil];
        if (cgImage != NULL) {
            out[2] = (int32_t)CGImageGetWidth(cgImage);
            out[3] = (int32_t)CGImageGetHeight(cgImage);
            out[4] = (int32_t)CGImageGetBitsPerComponent(cgImage);
            out[5] = (int32_t)CGImageGetBitsPerPixel(cgImage);
            out[6] = (int32_t)CGImageGetBytesPerRow(cgImage);
            out[7] = (int32_t)CGImageGetBitmapInfo(cgImage);

            CFStringRef name = CGColorSpaceCopyName(CGImageGetColorSpace(cgImage));
            out[8] = name != NULL && CFEqual(name, kCGColorSpaceSRGB) ? 1 : 0;
            if (name != NULL) {
                CFRelease(name);
            }

            CFDataRef data = CGDataProviderCopyData(CGImageGetDataProvider(cgImage));
            if (data != NULL) {
                length = (int32_t)CFDataGetLength(data);
                int32_t count = MIN(length, MAX(capacity, 0));
                if (count > 0) {
                    memcpy(pixels, CFDataGetBytePtr(data), (size_t)count);
                }
                CFRelease(data);
            }
        }
        [nsImage release];
    }
    GLASS_POOL_EXIT;
    return length;
}

typedef struct TimerTicks {
    int64_t timerId;
    int32_t count;
    int32_t status;
} TimerTicks;

static void *fireTimerTicks(void *data)
{
    TimerTicks *ticks = (TimerTicks *)data;
    const GmacTimerCallbacks *callbacks = GlassTimerCallbacks();
    ticks->status = GMAC_ERR_NO_DATA;
    if (callbacks != NULL && callbacks->tick != NULL) {
        for (int32_t i = 0; i < ticks->count; i++) {
            ticks->status = callbacks->tick(ticks->timerId);
        }
    }
    return NULL;
}

int32_t gmac_test_fire_timer_callback(int64_t timer_id, int32_t count, int32_t on_new_thread)
{
    if (count < 1) {
        return INT32_MIN;
    }
    TimerTicks ticks = {timer_id, count, GMAC_ERR_NO_DATA};
    if (on_new_thread == 0) {
        fireTimerTicks(&ticks);
        return ticks.status;
    }
    pthread_t thread;
    if (pthread_create(&thread, NULL, fireTimerTicks, &ticks) != 0) {
        return INT32_MIN;
    }
    pthread_join(thread, NULL);
    return ticks.status;
}

static const int32_t SDK_CONSTANTS[GMAC_SDK_CONSTANT_COUNT] = {
    [GMAC_SDK_EVENT_LEFT_MOUSE_DOWN]                  = kCGEventLeftMouseDown,
    [GMAC_SDK_EVENT_LEFT_MOUSE_UP]                    = kCGEventLeftMouseUp,
    [GMAC_SDK_EVENT_RIGHT_MOUSE_DOWN]                 = kCGEventRightMouseDown,
    [GMAC_SDK_EVENT_RIGHT_MOUSE_UP]                   = kCGEventRightMouseUp,
    [GMAC_SDK_EVENT_MOUSE_MOVED]                      = kCGEventMouseMoved,
    [GMAC_SDK_EVENT_LEFT_MOUSE_DRAGGED]               = kCGEventLeftMouseDragged,
    [GMAC_SDK_EVENT_RIGHT_MOUSE_DRAGGED]              = kCGEventRightMouseDragged,
    [GMAC_SDK_EVENT_SCROLL_WHEEL]                     = kCGEventScrollWheel,
    [GMAC_SDK_EVENT_OTHER_MOUSE_DOWN]                 = kCGEventOtherMouseDown,
    [GMAC_SDK_EVENT_OTHER_MOUSE_UP]                   = kCGEventOtherMouseUp,
    [GMAC_SDK_EVENT_OTHER_MOUSE_DRAGGED]              = kCGEventOtherMouseDragged,
    [GMAC_SDK_HID_EVENT_TAP]                          = kCGHIDEventTap,
    [GMAC_SDK_SCROLL_EVENT_UNIT_LINE]                 = kCGScrollEventUnitLine,
    [GMAC_SDK_MOUSE_EVENT_BUTTON_NUMBER]              = kCGMouseEventButtonNumber,
    [GMAC_SDK_SCROLL_WHEEL_EVENT_DELTA_AXIS_1]        = kCGScrollWheelEventDeltaAxis1,
    [GMAC_SDK_WINDOW_LIST_OPTION_ON_SCREEN_ONLY]      = kCGWindowListOptionOnScreenOnly,
    [GMAC_SDK_NULL_WINDOW_ID]                         = kCGNullWindowID,
    [GMAC_SDK_WINDOW_IMAGE_DEFAULT]                   = kCGWindowImageDefault,
    [GMAC_SDK_RENDERING_INTENT_ABSOLUTE_COLORIMETRIC] = kCGRenderingIntentAbsoluteColorimetric,
    [GMAC_SDK_CAPTURE_BITMAP_INFO]                    = kCGBitmapByteOrder32Host | kCGImageAlphaPremultipliedFirst,
    [GMAC_SDK_SIZEOF_CGFLOAT]                         = sizeof(CGFloat),
    [GMAC_SDK_SIZEOF_CGPOINT]                         = sizeof(CGPoint),
    [GMAC_SDK_SIZEOF_CGRECT]                          = sizeof(CGRect),
    [GMAC_SDK_SIZEOF_CGEVENTTYPE]                     = sizeof(CGEventType),
    [GMAC_SDK_SIZEOF_CGMOUSEBUTTON]                   = sizeof(CGMouseButton),
    [GMAC_SDK_SIZEOF_CGEVENTTAPLOCATION]              = sizeof(CGEventTapLocation),
    [GMAC_SDK_SIZEOF_CGSCROLLEVENTUNIT]               = sizeof(CGScrollEventUnit),
    [GMAC_SDK_SIZEOF_CGEVENTFIELD]                    = sizeof(CGEventField),
    [GMAC_SDK_SIZEOF_CGCHARCODE]                      = sizeof(CGCharCode),
    [GMAC_SDK_SIZEOF_CGKEYCODE]                       = sizeof(CGKeyCode),
    [GMAC_SDK_SIZEOF_BOOLEAN]                         = sizeof(Boolean),
    [GMAC_SDK_SIZEOF_AXERROR]                         = sizeof(AXError),
    [GMAC_SDK_SIZEOF_CGERROR]                         = sizeof(CGError),
    [GMAC_SDK_SIZEOF_CGWINDOWLISTOPTION]              = sizeof(CGWindowListOption),
    [GMAC_SDK_SIZEOF_CGWINDOWID]                      = sizeof(CGWindowID),
    [GMAC_SDK_SIZEOF_CGWINDOWIMAGEOPTION]             = sizeof(CGWindowImageOption),
    [GMAC_SDK_SIZEOF_CGCOLORRENDERINGINTENT]          = sizeof(CGColorRenderingIntent),
    [GMAC_SDK_SIZEOF_CGBITMAPINFO]                    = sizeof(CGBitmapInfo),
    [GMAC_SDK_SIZEOF_SIZE_T]                          = sizeof(size_t)
};

int32_t gmac_test_sdk_constant(int32_t index)
{
    if (index < 0 || index >= GMAC_SDK_CONSTANT_COUNT) {
        return INT32_MIN;
    }
    return SDK_CONSTANTS[index];
}

const void *gmac_test_color_space_srgb_name(void)
{
    return kCGColorSpaceSRGB;
}

static const GmacScreenInfo TEST_SCREEN = {
    (void *)0x0102030405060708, 1001, 1002, 1003, 1004, 1005, 1006, 1007, 1008, 1009, 1010, 1011, 1012, 1013, 1014,
    1015, 1.5f, 2.5f, 3.5f, 4.5f
};

static int32_t fireWindowIdSlot(int32_t (*slot)(int64_t), int64_t window_id)
{
    return slot != NULL ? slot(window_id) : GMAC_ERR_NO_DATA;
}

int32_t gmac_test_fire_window_callback(int32_t slot, int64_t window_id)
{
    if (slot < 0 || slot >= (int32_t)(sizeof(GmacWindowCallbacks) / sizeof(void *))) {
        return INT32_MIN;
    }
    const GmacWindowCallbacks *callbacks = GlassWindowCallbacks();
    if (callbacks == NULL) {
        return GMAC_ERR_NO_DATA;
    }
    switch (slot) {
        case 0:
            return callbacks->notify_move != NULL
                ? callbacks->notify_move(window_id, 1001, 1002, 1) : GMAC_ERR_NO_DATA;
        case 1:
            return callbacks->notify_resize != NULL
                ? callbacks->notify_resize(window_id, 1001, 1002, 1003) : GMAC_ERR_NO_DATA;
        case 2:
            return callbacks->notify_move_to_another_screen != NULL
                ? callbacks->notify_move_to_another_screen(window_id, &TEST_SCREEN) : GMAC_ERR_NO_DATA;
        case 3:
            return callbacks->notify_scale_changed != NULL
                ? callbacks->notify_scale_changed(window_id, 1.5f, 2.5f, 3.5f, 4.5f) : GMAC_ERR_NO_DATA;
        case 4:
            return fireWindowIdSlot(callbacks->notify_close, window_id);
        case 5:
            return callbacks->notify_focus != NULL ? callbacks->notify_focus(window_id, 1001) : GMAC_ERR_NO_DATA;
        case 6:
            return fireWindowIdSlot(callbacks->notify_focus_ungrab, window_id);
        case 7:
            return fireWindowIdSlot(callbacks->notify_focus_disabled, window_id);
        case 8:
            return fireWindowIdSlot(callbacks->notify_destroy, window_id);
        default:
            return fireWindowIdSlot(callbacks->notify_dispose, window_id);
    }
}

int32_t gmac_test_nslocale_language_direction(void)
{
    int32_t direction;
    GLASS_POOL_ENTER;
    {
        NSString* preferredLanguage = [[NSLocale preferredLanguages] objectAtIndex:0];
        NSLocale* locale = [NSLocale localeWithLocaleIdentifier:preferredLanguage];
        NSString* languageCode = [locale objectForKey:NSLocaleLanguageCode];
        direction = (int32_t)[NSLocale characterDirectionForLanguage:languageCode];
    }
    GLASS_POOL_EXIT;
    return direction;
}

static const uint16_t TEST_KEY_CHARS[] = {0x0061, 0x00E9, 0xD83D, 0xDE00, 0xDC00};
static const uint16_t TEST_INPUT_METHOD_TEXT[] = {0x0062, 0x65E5, 0xD83D, 0xDE00};

static int32_t fireViewIdSlot(int32_t (*slot)(int64_t), int64_t view_id)
{
    return slot != NULL ? slot(view_id) : GMAC_ERR_NO_DATA;
}

static int32_t fireDragSlot(int32_t (*slot)(int64_t, int32_t, int32_t, int32_t, int32_t, int32_t, int32_t *),
                            int64_t view_id, void *out)
{
    return slot != NULL ? slot(view_id, 1001, 1002, 1003, 1004, 1005, (int32_t *)out) : GMAC_ERR_NO_DATA;
}

int32_t gmac_test_fire_view_callback(int32_t slot, int64_t view_id, void *out)
{
    if (slot < 0 || slot >= (int32_t)(sizeof(GmacViewCallbacks) / sizeof(void *))) {
        return INT32_MIN;
    }
    const GmacViewCallbacks *callbacks = GlassViewCallbacks();
    if (callbacks == NULL) {
        return GMAC_ERR_NO_DATA;
    }
    switch (slot) {
        case 0:
            return callbacks->notify_view != NULL ? callbacks->notify_view(view_id, 1001) : GMAC_ERR_NO_DATA;
        case 1:
            return callbacks->notify_resize != NULL
                ? callbacks->notify_resize(view_id, 1001, 1002) : GMAC_ERR_NO_DATA;
        case 2:
            return callbacks->notify_repaint != NULL
                ? callbacks->notify_repaint(view_id, 1001, 1002, 1003, 1004) : GMAC_ERR_NO_DATA;
        case 3:
            return callbacks->notify_menu != NULL
                ? callbacks->notify_menu(view_id, 1001, 1002, 1003, 1004, 1) : GMAC_ERR_NO_DATA;
        case 4:
            return callbacks->notify_mouse != NULL
                ? callbacks->notify_mouse(view_id, 1001, 1002, 1003, 1004, 1005, 1006, 1007, 1, 0)
                : GMAC_ERR_NO_DATA;
        case 5:
            return callbacks->notify_key != NULL
                ? callbacks->notify_key(view_id, 1001, 1002, TEST_KEY_CHARS,
                        (int32_t)(sizeof(TEST_KEY_CHARS) / sizeof(uint16_t)), 1003, 0, (int32_t *)out)
                : GMAC_ERR_NO_DATA;
        case 6:
            return callbacks->notify_input_method != NULL
                ? callbacks->notify_input_method(view_id, TEST_INPUT_METHOD_TEXT,
                        (int32_t)(sizeof(TEST_INPUT_METHOD_TEXT) / sizeof(uint16_t)), 1001, 1002, 1003, -1, 1004)
                : GMAC_ERR_NO_DATA;
        case 7:
            return callbacks->notify_input_method_candidate_pos_request != NULL
                ? callbacks->notify_input_method_candidate_pos_request(view_id, 1001, (double *)out,
                        (int32_t *)((char *)out + 16))
                : GMAC_ERR_NO_DATA;
        case 8:
            return fireDragSlot(callbacks->notify_drag_enter, view_id, out);
        case 9:
            return fireDragSlot(callbacks->notify_drag_over, view_id, out);
        case 10:
            return fireDragSlot(callbacks->notify_drag_drop, view_id, out);
        case 11:
            return fireViewIdSlot(callbacks->notify_drag_leave, view_id);
        case 12:
            return callbacks->notify_drag_end != NULL ? callbacks->notify_drag_end(view_id, 1001) : GMAC_ERR_NO_DATA;
        case 13:
            return callbacks->get_accessible != NULL
                ? callbacks->get_accessible(view_id, (void **)out) : GMAC_ERR_NO_DATA;
        default:
            return fireViewIdSlot(callbacks->notify_dispose, view_id);
    }
}

int32_t gmac_test_fire_gesture_callback(int32_t slot, int64_t view_id)
{
    if (slot < 0 || slot >= (int32_t)(sizeof(GmacGestureCallbacks) / sizeof(void *))) {
        return INT32_MIN;
    }
    const GmacGestureCallbacks *callbacks = GlassGestureCallbacks();
    if (callbacks == NULL) {
        return GMAC_ERR_NO_DATA;
    }
    switch (slot) {
        case 0:
            return callbacks->rotate_gesture_performed != NULL
                ? callbacks->rotate_gesture_performed(view_id, 1001, 1002, 1003, 1004, 1005, 1.5f)
                : GMAC_ERR_NO_DATA;
        case 1:
            return callbacks->scroll_gesture_performed != NULL
                ? callbacks->scroll_gesture_performed(view_id, 1001, 1002, 1003, 1004, 1005, 1006, 1.5f, 2.5f)
                : GMAC_ERR_NO_DATA;
        case 2:
            return callbacks->swipe_gesture_performed != NULL
                ? callbacks->swipe_gesture_performed(view_id, 1001, 1002, 1003, 1004, 1005, 1006)
                : GMAC_ERR_NO_DATA;
        case 3:
            return callbacks->magnify_gesture_performed != NULL
                ? callbacks->magnify_gesture_performed(view_id, 1001, 1002, 1003, 1004, 1005, 1.5f)
                : GMAC_ERR_NO_DATA;
        default:
            return callbacks->gesture_finished != NULL
                ? callbacks->gesture_finished(view_id, 1001, 1002, 1003, 1004, 1005)
                : GMAC_ERR_NO_DATA;
    }
}

static const uint16_t TEST_KEY_EVENT_CHARS[] = {0x0061, 0x00E9};

int32_t gmac_test_fire_key_event(int64_t view_id, int32_t dials, int32_t *out_consumed)
{
    *out_consumed = 0;
    if (dials < 1) {
        return INT32_MIN;
    }
    const GmacViewCallbacks *callbacks = GlassViewCallbacks();
    if (callbacks == NULL || callbacks->notify_key == NULL) {
        return GMAC_ERR_NO_DATA;
    }
    GlassKeySend send = {
        callbacks, view_id, TEST_KEY_EVENT_CHARS, (int32_t)(sizeof(TEST_KEY_EVENT_CHARS) / sizeof(uint16_t)), 3001, 0
    };
    int32_t status = GlassDialKeySlot(&send, 1001, 2001, out_consumed);
    for (int32_t i = 1; i < dials; i++) {
        status = GlassDialKeySlot(&send, 1001 + i, 2001 + i, NULL);
    }
    return status;
}

int32_t gmac_test_fire_input_method(int64_t view_id, const uint16_t *chars, int32_t len, int32_t attributed,
                                    int32_t attr, int32_t length, int32_t cursor, uint64_t sel_location,
                                    uint64_t sel_length)
{
    const GmacViewCallbacks *callbacks = GlassViewCallbacks();
    if (callbacks == NULL || callbacks->notify_input_method == NULL) {
        return GMAC_ERR_NO_DATA;
    }
    int32_t status;
    GLASS_POOL_ENTER;
    {
        NSString *string = GlassNSStringOrEmpty(chars, len);
        id text = attributed != 0 ? [[[NSAttributedString alloc] initWithString:string] autorelease] : string;
        status = GlassDialInputMethod(callbacks, view_id, text, attr, length, cursor,
                NSMakeRange((NSUInteger)sel_location, (NSUInteger)sel_length));
    }
    GLASS_POOL_EXIT;
    return status;
}

int32_t gmac_test_fire_dialog_callback(int32_t slot, int64_t loop_id)
{
    if (slot < 0 || slot >= (int32_t)(sizeof(GmacDialogCallbacks) / sizeof(void *))) {
        return INT32_MIN;
    }
    const GmacDialogCallbacks *callbacks = GlassDialogCallbacks();
    if (callbacks == NULL) {
        return GMAC_ERR_NO_DATA;
    }
    int32_t (*dial)(int64_t) = slot == 0 ? callbacks->event_loop_enter : callbacks->event_loop_leave;
    return dial != NULL ? dial(loop_id) : GMAC_ERR_NO_DATA;
}

int32_t gmac_test_fire_menu_callback(int32_t slot, int64_t menu_id)
{
    if (slot < 0 || slot >= (int32_t)(sizeof(GmacMenuCallbacks) / sizeof(void *))) {
        return INT32_MIN;
    }
    const GmacMenuCallbacks *callbacks = GlassMenuCallbacks();
    if (callbacks == NULL) {
        return GMAC_ERR_NO_DATA;
    }
    int32_t (*const dials[])(int64_t) = {
        callbacks->action, callbacks->validate, callbacks->menu_opening, callbacks->menu_closed,
        callbacks->menu_disposed
    };
    return dials[slot] != NULL ? dials[slot](menu_id) : GMAC_ERR_NO_DATA;
}

static void *const TEST_A11Y_ATTRIBUTE = (void *)(uintptr_t)UINT64_C(0x0102030405060701);
static void *const TEST_A11Y_VALUE = (void *)(uintptr_t)UINT64_C(0x0102030405060702);
static void *const TEST_A11Y_CHILD = (void *)(uintptr_t)UINT64_C(0x0102030405060703);
static void *const TEST_A11Y_PARAMETER = (void *)(uintptr_t)UINT64_C(0x0102030405060704);
static void *const TEST_A11Y_ACTION = (void *)(uintptr_t)UINT64_C(0x0102030405060705);

static int32_t fireIdsSlot(int32_t (*slot)(int64_t, void ***, int32_t *), int64_t peer_id, void *out)
{
    return slot != NULL ? slot(peer_id, (void ***)out, (int32_t *)((char *)out + 8)) : GMAC_ERR_NO_DATA;
}

int32_t gmac_test_fire_accessible_callback(int32_t slot, int64_t peer_id, void *out)
{
    if (slot < 0 || slot >= (int32_t)(sizeof(GmacAccessibleCallbacks) / sizeof(void *))) {
        return INT32_MIN;
    }
    const GmacAccessibleCallbacks *callbacks = GlassAccessibleCallbacks();
    if (callbacks == NULL) {
        return GMAC_ERR_NO_DATA;
    }
    switch (slot) {
        case 0:
            return fireIdsSlot(callbacks->attribute_names, peer_id, out);
        case 1:
            return callbacks->attribute_value != NULL
                ? callbacks->attribute_value(peer_id, TEST_A11Y_ATTRIBUTE, (GmacVariant *)out) : GMAC_ERR_NO_DATA;
        case 2:
            return callbacks->is_attribute_settable != NULL
                ? callbacks->is_attribute_settable(peer_id, TEST_A11Y_ATTRIBUTE, (int32_t *)out) : GMAC_ERR_NO_DATA;
        case 3:
            return callbacks->set_value != NULL
                ? callbacks->set_value(peer_id, TEST_A11Y_VALUE, TEST_A11Y_ATTRIBUTE) : GMAC_ERR_NO_DATA;
        case 4:
            return callbacks->index_of_child != NULL
                ? callbacks->index_of_child(peer_id, TEST_A11Y_CHILD, (int64_t *)out) : GMAC_ERR_NO_DATA;
        case 5:
            return callbacks->array_attribute_count != NULL
                ? callbacks->array_attribute_count(peer_id, TEST_A11Y_ATTRIBUTE, (int32_t *)out) : GMAC_ERR_NO_DATA;
        case 6:
            return callbacks->array_attribute_values != NULL
                ? callbacks->array_attribute_values(peer_id, TEST_A11Y_ATTRIBUTE, 1001, 1002, (void ***)out,
                        (int32_t *)((char *)out + 8))
                : GMAC_ERR_NO_DATA;
        case 7:
            return fireIdsSlot(callbacks->parameterized_attribute_names, peer_id, out);
        case 8:
            return callbacks->attribute_value_for_parameter != NULL
                ? callbacks->attribute_value_for_parameter(peer_id, TEST_A11Y_ATTRIBUTE, TEST_A11Y_PARAMETER,
                        (GmacVariant *)out)
                : GMAC_ERR_NO_DATA;
        case 9:
            return fireIdsSlot(callbacks->action_names, peer_id, out);
        case 10:
            return callbacks->action_description != NULL
                ? callbacks->action_description(peer_id, TEST_A11Y_ACTION, (uint16_t **)out,
                        (int32_t *)((char *)out + 8))
                : GMAC_ERR_NO_DATA;
        case 11:
            return callbacks->perform_action != NULL
                ? callbacks->perform_action(peer_id, TEST_A11Y_ACTION) : GMAC_ERR_NO_DATA;
        case 12:
            return callbacks->is_ignored != NULL ? callbacks->is_ignored(peer_id, (int32_t *)out) : GMAC_ERR_NO_DATA;
        case 13:
            return callbacks->hit_test != NULL
                ? callbacks->hit_test(peer_id, 1.5f, 2.5f, (void **)out) : GMAC_ERR_NO_DATA;
        case 14:
            return callbacks->focused_ui_element != NULL
                ? callbacks->focused_ui_element(peer_id, (void **)out) : GMAC_ERR_NO_DATA;
        default:
            return callbacks->peer_released != NULL ? callbacks->peer_released(peer_id) : GMAC_ERR_NO_DATA;
    }
}
