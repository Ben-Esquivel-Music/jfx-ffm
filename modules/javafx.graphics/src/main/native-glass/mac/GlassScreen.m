/*
 * Copyright (c) 2011, 2026, Oracle and/or its affiliates. All rights reserved.
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

#import "common.h"

#import "GlassMacros.h"
#import "GlassScreen.h"
#import "GlassTimer.h"
#import "glass_mac_api.h"
#import "glass_mac_internal.h"

#pragma clang diagnostic ignored "-Wdeprecated-declarations"

//#define VERBOSE
#ifndef VERBOSE
    #define LOG(MSG, ...)
#else
    #define LOG(MSG, ...) GLASS_LOG(MSG, ## __VA_ARGS__);
#endif

#define MAX_DISPLAY_COUNT 1024

NSSize maxScreenDimensions;

CGFloat GetScreenScaleFactor(NSScreen *screen)
{
    if ([screen respondsToSelector:@selector(backingScaleFactor)]) {
        return [screen backingScaleFactor];
    } else {
        return [screen userSpaceScaleFactor];
    }
}

void GlassFillScreenInfo(NSScreen *screen, GmacScreenInfo *info)
{
    // Note that NSDeviceResolution always reports 72 DPI, so we use Core Graphics API instead
    const CGDirectDisplayID displayID = [[[screen deviceDescription] objectForKey:@"NSScreenNumber"] intValue];
    CGSize size = CGDisplayScreenSize(displayID);
    CGRect rect = CGDisplayBounds(displayID);
    const CGFloat MM_PER_INCH = 25.4f; // 1 inch == 25.4 mm
    // Avoid division by zero, default to 72 DPI
    if (size.width == 0) size.width = rect.size.width * MM_PER_INCH / 72.f;
    if (size.height == 0) size.height = rect.size.height * MM_PER_INCH / 72.f;
    NSSize resolution = {rect.size.width / (size.width / MM_PER_INCH), rect.size.height / (size.height / MM_PER_INCH)};

    NSRect primaryFrame = [[[NSScreen screens] objectAtIndex:0] frame];

    if (floor(NSAppKitVersionNumber) > 1265) // NSAppKitVersionNumber10_9
    {
        // On MacOS X 10.10 beta the objects returned by [NSScreen screens] are released
        // without any notification. This means JavaFX must retain its own references to
        // avoid crashes when using them later on.
        // Note, this fix is deliberately allowing the objects to leak. This is the
        // safest and least intrusive fix appropriate for a maintenance release.
        // Screens are usually create and released when an external monitor is added
        // or removed, therefore the memory leaked should never grow too much.
        [screen retain];
    }

    float scale = (float)GetScreenScaleFactor(screen);
    info->screen = screen;

    info->depth = (int32_t)NSBitsPerPixelFromDepth([screen depth]);

    info->x = (int32_t)[screen frame].origin.x;
    info->y = (int32_t)(primaryFrame.size.height - [screen frame].size.height - [screen frame].origin.y);
    info->width = (int32_t)[screen frame].size.width;
    info->height = (int32_t)[screen frame].size.height;

    info->platform_x = (int32_t)[screen frame].origin.x;
    info->platform_y = (int32_t)(primaryFrame.size.height - [screen frame].size.height - [screen frame].origin.y);
    info->platform_width = (int32_t)[screen frame].size.width;
    info->platform_height = (int32_t)[screen frame].size.height;

    info->visible_x = (int32_t)[screen visibleFrame].origin.x;
    info->visible_y = (int32_t)(primaryFrame.size.height - [screen visibleFrame].size.height
                                - [screen visibleFrame].origin.y);
    info->visible_width = (int32_t)[screen visibleFrame].size.width;
    info->visible_height = (int32_t)[screen visibleFrame].size.height;

    info->resolution_x = (int32_t)resolution.width;
    info->resolution_y = (int32_t)resolution.height;
    info->platform_scale_x = 1.0f;
    info->platform_scale_y = 1.0f;
    info->output_scale_x = scale;
    info->output_scale_y = scale;
}

int32_t gmac_screens_query(GmacScreenInfo **out_screens, int32_t *out_count)
{
    int32_t status = GMAC_OK;
    *out_screens = NULL;
    *out_count = 0;

    GLASS_POOL_ENTER;
    {
        NSArray* screens = [NSScreen screens];
        NSUInteger count = [screens count];
        GmacScreenInfo *infos = count > 0 ? calloc(count, sizeof(GmacScreenInfo)) : NULL;
        if (count > 0 && infos == NULL) {
            status = GMAC_ERR_NO_DATA;
        } else {
            maxScreenDimensions = NSMakeSize(0.f,0.f);
            for (NSUInteger index = 0; index < count; index++) {
                NSRect screenRect = [[screens objectAtIndex:index] frame];

                if (screenRect.size.width > maxScreenDimensions.width) {
                    maxScreenDimensions.width = screenRect.size.width;
                }
                if (screenRect.size.height > maxScreenDimensions.height) {
                    maxScreenDimensions.height = screenRect.size.height;
                }

                GlassFillScreenInfo([screens objectAtIndex:index], &infos[index]);
            }
            *out_screens = infos;
            *out_count = (int32_t)count;
        }
    }
    GLASS_POOL_EXIT;

    return status;
}

void GlassScreenDidChangeScreenParameters(void)
{
    const GmacAppCallbacks *callbacks = GlassAppCallbacks();
    if (callbacks != NULL && callbacks->notify_screen_settings_changed != NULL)
    {
        callbacks->notify_screen_settings_changed();
    }
}

@implementation NSScreen (FullscreenAdditions)

- (CGDirectDisplayID)enterFullscreenAndHideCursor:(BOOL)hide
{
    CGDirectDisplayID displayID = 0;

    CGDisplayCount displayCount = 0;
    CGDirectDisplayID activeDisplays[MAX_DISPLAY_COUNT];
    CGDisplayErr err = CGGetActiveDisplayList(MAX_DISPLAY_COUNT, activeDisplays, &displayCount);
    if (err != kCGErrorSuccess)
    {
        NSLog(@"CGGetActiveDisplayList returned error: %d", err);
    }
    else
    {
        NSRect nsrect = [self frame];

        for (CGDisplayCount i=0; i<displayCount; i++)
        {
            CGRect cgrect = CGDisplayBounds(activeDisplays[i]);
            if ((nsrect.origin.x == cgrect.origin.x) && (nsrect.origin.y == cgrect.origin.y)
                && (nsrect.size.width == cgrect.size.width) && (nsrect.size.height == cgrect.size.height))
            {
                displayID = activeDisplays[i];
                break;
            }
        }

#if 0
        err = CGDisplayCapture(displayID);
#endif
        if (displayID == kCGDirectMainDisplay)
        {
            [NSMenu setMenuBarVisible:NO];
        }

        if (err != kCGErrorSuccess)
        {
            NSLog(@"CGDisplayCapture returned error: %d", err);
            displayID = 0;
        }
        else
        {
            if (hide == YES)
            {
                CGDisplayHideCursor(displayID);
            }
        }
    }

    return displayID;
}

- (void)exitFullscreen:(CGDirectDisplayID)displayID
{
    if (displayID != 0)
    {
        if (displayID == kCGDirectMainDisplay)
        {
            [NSMenu setMenuBarVisible:YES];
        }
#if 0
        CGDisplayRelease(displayID);
#endif
        CGDisplayShowCursor(displayID);
    }
}

@end

