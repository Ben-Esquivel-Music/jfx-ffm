/*
 * Copyright (c) 2024, 2026, Oracle and/or its affiliates. All rights reserved.
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

#import "GlassMacros.h"
#import "GlassViewMTL.h"

//#define VERBOSE
#ifndef VERBOSE
    #define LOG(MSG, ...)
#else
    #define LOG(MSG, ...) GLASS_LOG(MSG, ## __VA_ARGS__);
#endif

@implementation GlassViewMTL

- (void)_initialize3dWithCaps:(const GmacViewCaps *)caps
                  useMTLForSW:(BOOL)mtlForSW
{
    long mtlCommandQueuePtr = (long)(intptr_t)caps->mtl_command_queue;
    BOOL isVsyncEnabled = caps->vsync_enabled != 0 ? YES : NO;

    BOOL isSwPipe = NO;

    if (mtlCommandQueuePtr == 0l) {
        LOG("GlassViewMTL _initialize3dWithCaps : using software pipeline");
        isSwPipe = YES;
    }

    self->layer = [[GlassLayer alloc] initGlassLayer:nil
                                    andClientContext:nil
                                         mtlQueuePtr:mtlCommandQueuePtr
                                      withHiDPIAware:YES
                                      isVsyncEnabled:isVsyncEnabled
                                        withIsSwPipe:isSwPipe
                                         useMTLForSW:mtlForSW];

    // https://developer.apple.com/library/mac/documentation/Cocoa/Reference/ApplicationKit/Classes/nsview_Class/Reference/NSView.html#//apple_ref/occ/instm/NSView/setWantsLayer:
    // the order of the following 2 calls is important: here we indicate we want a layer-hosting view
    {
        [self setLayerContentsRedrawPolicy: NSViewLayerContentsRedrawOnSetNeedsDisplay];
        [self setLayer:self->layer];
        [self setWantsLayer:YES];
        //[self setWantsUpdateLayer:YES];
    }
}

- (BOOL) wantsUpdateLayer {
    return TRUE;
}

- (id)initWithFrame:(NSRect)frame caps:(const GmacViewCaps *)caps useMTLForSW:(BOOL)mtlForSW
{
    LOG("GlassViewMTL initWithFrame:caps:useMTLForSW");

    self = [super initWithFrame: frame];
    if (self != nil)
    {
        [self _initialize3dWithCaps:caps
                        useMTLForSW:(BOOL)mtlForSW];
    }
    return self;
}

- (void)dealloc
{
    [self->layer release];

    [super dealloc];
}

// also called when closing window, when [self window] == nil
- (void)viewDidMoveToWindow
{
    //[self->_delegate viewDidMoveToWindow];
}

- (GlassLayer*)getLayer
{
    return self->layer;
}

- (BOOL)acceptsFirstMouse:(NSEvent *)theEvent
{
    return YES;
}

@end
