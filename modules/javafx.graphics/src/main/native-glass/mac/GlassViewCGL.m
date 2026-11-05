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
#import "GlassViewCGL.h"

//#define VERBOSE
#ifndef VERBOSE
    #define LOG(MSG, ...)
#else
    #define LOG(MSG, ...) GLASS_LOG(MSG, ## __VA_ARGS__);
#endif

// http://developer.apple.com/library/mac/#technotes/tn2085/_index.html
// #define ENABLE_MULTITHREADED_GL

@implementation GlassViewCGL

- (CGLPixelFormatObj)_createPixelFormatWithDepth:(CGLPixelFormatAttribute)depth
{
    CGLPixelFormatObj pix = NULL;
    {
        const CGLPixelFormatAttribute attributes[] =
        {
            kCGLPFAAccelerated,
            kCGLPFAColorSize, 32,
            kCGLPFAAlphaSize, 8,
            kCGLPFADepthSize, depth,
            kCGLPFAAllowOfflineRenderers, // lets OpenGL know this context is offline renderer aware
            (CGLPixelFormatAttribute)0
        };
        GLint npix = 0;
        CGLError err = CGLChoosePixelFormat(attributes, &pix, &npix);
        if (pix == NULL)
        {
            NSLog(@"CGLChoosePixelFormat: No matching pixel format exists for the requested attributes, trying again with limited capabilities");
            const CGLPixelFormatAttribute attributes2[] =
            {
                kCGLPFAAllowOfflineRenderers,
                (CGLPixelFormatAttribute)0
            };
            err = CGLChoosePixelFormat(attributes2, &pix, &npix);
        }
        if (err != kCGLNoError)
        {
            NSLog(@"CGLChoosePixelFormat error: %d", err);
        }
    }
    return pix;
}

- (CGLContextObj)_createContextWithShared:(CGLContextObj)share
                               withFormat:(CGLPixelFormatObj)format
{
    CGLContextObj ctx = NULL;
    {
        CGLError err = CGLCreateContext(format, share, &ctx);
        if (err != kCGLNoError)
        {
            NSLog(@"CGLCreateContext error: %d", err);
        }
    }
    return ctx;
}

- (void)_initialize3dWithCaps:(const GmacViewCaps *)caps
                  useMTLForSW:(BOOL)mtlForSW
{
    int depthBits = caps->depth_bits;

    CGLContextObj sharedCGL = NULL;
    if (caps->share_context != NULL)
    {
        NSOpenGLContext *sharedContextNS = (NSOpenGLContext*)caps->share_context;
        sharedCGL = [sharedContextNS CGLContextObj];
    }

    CGLContextObj clientCGL = NULL;
    BOOL isSwPipe = NO;

    if (caps->context != NULL)
    {
        NSOpenGLContext *clientContextNS = (NSOpenGLContext*)caps->context;
        clientCGL = [clientContextNS CGLContextObj];
    }
    if (clientCGL == NULL)
    {
        CGLPixelFormatObj clientPixelFormat = [self _createPixelFormatWithDepth:(CGLPixelFormatAttribute)depthBits];
        clientCGL = [self _createContextWithShared:sharedCGL
                                        withFormat:clientPixelFormat];
    }
    if (sharedCGL == NULL)
    {
        // this can happen in Rain or clients other than Prism (ie. device details do not have the shared context set)
        sharedCGL = clientCGL;
        isSwPipe = YES;
    }

    BOOL isHiDPIAware = caps->hidpi_aware != 0 ? YES : NO;

    self->layer = [[GlassLayer alloc] initGlassLayer:(NSObject*)sharedCGL
                                    andClientContext:(NSObject*)clientCGL
                                         mtlQueuePtr:0l
                                      withHiDPIAware:isHiDPIAware
                                      isVsyncEnabled:NO // Unused in es2, the flag is added for metal.
                                        withIsSwPipe:isSwPipe
                                         useMTLForSW:mtlForSW];
    // https://developer.apple.com/library/mac/documentation/Cocoa/Reference/ApplicationKit/Classes/nsview_Class/Reference/NSView.html#//apple_ref/occ/instm/NSView/setWantsLayer:
    // the order of the following 2 calls is important: here we indicate we want a layer-hosting view
    [self setLayer:self->layer];
    [self setWantsLayer:YES];
}

- (id)initWithFrame:(NSRect)frame
               caps:(const GmacViewCaps *)caps
        useMTLForSW:(BOOL)mtlForSW;
{
    LOG("GlassViewCGL initWithFrame:caps:useMTLForSW");

    NSOpenGLPixelFormatAttribute pixelFormatAttributes[] =
    {
        NSOpenGLPFAAllowOfflineRenderers, // Lets OpenGL know this context is offline renderer aware
        (NSOpenGLPixelFormatAttribute)0
    };
    NSOpenGLPixelFormat *pFormat = [[[NSOpenGLPixelFormat alloc] initWithAttributes:pixelFormatAttributes] autorelease];
    if (!pFormat)
    {
        pFormat = [NSOpenGLView defaultPixelFormat];
        LOG("GlassViewCGL initWithFrame: initWithAttributes failed! Set pixel format to default pixel format");
    }
    self = [super initWithFrame:frame pixelFormat:pFormat];
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
    if ([self window] != nil)
    {
        [[self->layer getPainterOffscreen] setBackgroundColor:[[[self window] backgroundColor] colorUsingColorSpace:NSColorSpace.sRGBColorSpace]];
    }
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
