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
#import "com_sun_glass_ui_Cursor.h"

#import <Cocoa/Cocoa.h>

#import "GlassMacros.h"
#import "GlassHelper.h"
#import "glass_mac_api.h"

//#define VERBOSE
#ifndef VERBOSE
    #define LOG(MSG, ...)
#else
    #define LOG(MSG, ...) GLASS_LOG(MSG, ## __VA_ARGS__);
#endif

#define CURSOR_BEST_SIZE 32

static NSArray *cursorCache;

#pragma mark --- Java NSCursor

@interface NSCursor (Java)

+ (NSCursor*)performJavaSelector:(SEL)aSelector;

@end

@implementation NSCursor (Java)

+ (NSCursor*)performJavaSelector:(SEL)aSelector
{
    NSCursor *cursor = nil;
    if ([GlassHelper InvokeSelectorIfAvailable:aSelector forClass:[NSCursor class] withArgument:NULL withReturnValue:(void**)&cursor] == NO)
    {
        cursor = [NSCursor arrowCursor];
    }
    return cursor;
}

@end

#pragma mark --- com_sun_glass_ui_mac_MacCursor

void gmac_cursor_init(void)
{
    NSString *base = @"/System/Library/Frameworks/ApplicationServices.framework/Versions/A/Frameworks/HIServices.framework/Versions/A/Resources/cursors";
    NSString *nwse = @"resizenorthwestsoutheast";
    NSString *nesw = @"resizenortheastsouthwest";
    NSString *nwsePath = [base stringByAppendingPathComponent:nwse];
    NSString *neswPath = [base stringByAppendingPathComponent:nesw];
    NSString *nwseFile = [nwsePath stringByAppendingPathComponent:@"cursor.pdf"];
    NSString *neswFile = [neswPath stringByAppendingPathComponent:@"cursor.pdf"];
    NSString *nwseInfo = [nwsePath stringByAppendingPathComponent:@"info.plist"];
    NSString *neswInfo = [neswPath stringByAppendingPathComponent:@"info.plist"];
    NSImage  *nwseImage = [[NSImage alloc] initByReferencingFile:nwseFile];
    NSImage  *neswImage = [[NSImage alloc] initByReferencingFile:neswFile];
    NSDictionary *nwseDict = [NSDictionary dictionaryWithContentsOfFile:nwseInfo];
    NSDictionary *neswDict = [NSDictionary dictionaryWithContentsOfFile:neswInfo];
    NSPoint   nwsePoint = NSMakePoint([[nwseDict valueForKey:@"hotx"] doubleValue],
                                      [[nwseDict valueForKey:@"hoty"] doubleValue]);
    NSPoint   neswPoint = NSMakePoint([[neswDict valueForKey:@"hotx"] doubleValue],
                                      [[neswDict valueForKey:@"hoty"] doubleValue]);


    cursorCache = [NSArray arrayWithObjects:
                      /* CURSOR_CUSTOM */           [NSCursor arrowCursor],     // not handed out in set
                      /* CURSOR_DEFAULT */          [NSCursor arrowCursor],
                      /* CURSOR_TEXT */             [NSCursor IBeamCursor],
                      /* CURSOR_CROSSHAIR */        [NSCursor crosshairCursor],
                      /* CURSOR_CLOSED_HAND */      [NSCursor closedHandCursor],
                      /* CURSOR_OPEN_HAND */        [NSCursor openHandCursor],
                      /* CURSOR_POINTING_HAND */    [NSCursor pointingHandCursor],
                      /* CURSOR_RESIZE_LEFT: */     [NSCursor resizeLeftCursor],
                      /* CURSOR_RESIZE_RIGHT */     [NSCursor resizeRightCursor],
                      /* CURSOR_RESIZE_UP */        [NSCursor resizeUpCursor],
                      /* CURSOR_RESIZE_DOWN */      [NSCursor resizeDownCursor],
                      /* CURSOR_RESIZE_LEFTRIGHT */ [NSCursor resizeLeftRightCursor],
                      /* CURSOR_RESIZE_UPDOWN */    [NSCursor resizeUpDownCursor],
                      /* CURSOR_DISAPPEAR */        [NSCursor disappearingItemCursor],
                      /* CURSOR_WAIT */             [NSCursor performJavaSelector:@selector(javaBusyButClickableCursor)],
                      /* CURSOR_RESIZE_SOUTHWEST */ [[NSCursor alloc] initWithImage:neswImage hotSpot:neswPoint],
                      /* CURSOR_RESIZE_SOUTHEAST */ [[NSCursor alloc] initWithImage:nwseImage hotSpot:nwsePoint],
                      /* CURSOR_RESIZE_NORTHWEST */ [[NSCursor alloc] initWithImage:nwseImage hotSpot:nwsePoint],
                      /* CURSOR_RESIZE_NORTHEAST */ [[NSCursor alloc] initWithImage:neswImage hotSpot:neswPoint],
                      /* CURSOR_MOVE */             [NSCursor performJavaSelector:@selector(javaMoveCursor)],
                      /* NS_REQUIRES_NIL_TERM */    nil
                   ];
    [cursorCache retain];

    assert([cursorCache count] == (com_sun_glass_ui_Cursor_CURSOR_MAX + 1));
}

void *gmac_cursor_create(void *image, int32_t x, int32_t y)
{
    NSCursor *cursor = nil;
    GLASS_POOL_ENTER;
    {
        if (image != NULL)
        {
            cursor = [[NSCursor alloc] initWithImage:(NSImage *)image hotSpot:NSMakePoint(x, y)];
            [(NSImage *)image release];
        }
    }
    GLASS_POOL_EXIT;
    return cursor;
}

void gmac_cursor_set(int32_t type)
{
    GLASS_POOL_ENTER;
    {
        assert(type > 0 && type <= com_sun_glass_ui_Cursor_CURSOR_MAX);

        NSCursor *cursor = [cursorCache objectAtIndex: type];

        [cursor set];
    }
    GLASS_POOL_EXIT;
}

void gmac_cursor_set_custom(void *cursor)
{
    GLASS_POOL_ENTER;
    {
        [(NSCursor*)cursor set];
    }
    GLASS_POOL_EXIT;
}

void gmac_cursor_set_visible(int32_t visible)
{
    GLASS_POOL_ENTER;
    {
        if (visible != 0)
        {
            [NSCursor unhide];
        }
        else
        {
            [NSCursor hide];
        }
    }
    GLASS_POOL_EXIT;
}

void gmac_cursor_best_size(int32_t width, int32_t height, int32_t *out_width, int32_t *out_height)
{
    GLASS_POOL_ENTER;
    {
        int32_t widthBest = width;
        int32_t heightBest = height;

        NSImage *image = [[[NSCursor arrowCursor] image] retain];

        if (widthBest <= 0)
        {
            if (image != nil)
            {
                widthBest = (int32_t)[image size].width;
            }
            else
            {
                widthBest = CURSOR_BEST_SIZE;
            }
        }

        if (heightBest <= 0)
        {
            if (image != nil)
            {
                heightBest = (int32_t)[image size].height;
            }
            else
            {
                heightBest = CURSOR_BEST_SIZE;
            }
        }

        [image release];

        *out_width = widthBest;
        *out_height = heightBest;
    }
    GLASS_POOL_EXIT;
}
