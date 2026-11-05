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
#import "GlassWindow.h"
#import "GlassView3D.h"
#import "GlassHelper.h"
#import "glass_mac_internal.h"

//#define VERBOSE
#ifndef VERBOSE
    #define LOG(MSG, ...)
#else
    #define LOG(MSG, ...) GLASS_LOG(MSG, ## __VA_ARGS__);
#endif

static inline GlassView3D<GlassView>* getGlassView(void *ptr)
{
    assert(ptr != NULL);

    return (GlassView3D<GlassView>*)ptr;
}

static const GmacViewCaps DEFAULT_VIEW_CAPS = {NULL, NULL, NULL, 0, 0, 0, 1};

static GlassView3D *createView(int64_t viewId, const GmacViewCaps *caps)
{
    // embed ourselves into GlassHostView, so we can later swap our view between windows (ex. fullscreen mode)
    NSView *hostView = [[GlassHostView alloc] initWithFrame:NSMakeRect(0, 0, 0, 0)]; // alloc creates ref count of 1
    [hostView setAutoresizingMask:(NSViewWidthSizable|NSViewHeightSizable)];
    [hostView setAutoresizesSubviews:YES];

    GlassView3D *view = [[GlassView3D alloc] initWithFrame:[hostView bounds] viewId:viewId caps:caps];
    [view setAutoresizingMask:(NSViewWidthSizable|NSViewHeightSizable)];

    [hostView addSubview:view];
    return view;
}

#pragma mark --- C ABI

void *gmac_view_create(int64_t view_id, const GmacViewCaps *caps)
{
    void *view = NULL;

    GLASS_POOL_ENTER;
    {
        view = createView(view_id, caps != NULL ? caps : &DEFAULT_VIEW_CAPS);
    }
    GLASS_POOL_EXIT;

    return view;
}

int64_t gmac_view_multi_click_time_ms(void)
{
    // 10.6 API
    return (int64_t)([NSEvent doubleClickInterval]*1000.0f);
}

int64_t gmac_view_get_native_frame_buffer(void *ptr)
{
    if (!ptr) return 0;

    int64_t fb = 0;

    GLASS_POOL_ENTER;
    {
        GlassView3D<GlassView> *view = getGlassView(ptr);
        GlassLayer *layer = (GlassLayer*)[view getLayer];
        fb = [[layer getPainterOffscreen] fbo];
    }
    GLASS_POOL_EXIT;

    return fb;
}

int32_t gmac_view_get_x(void *ptr)
{
    if (!ptr) return 0;

    int32_t x = 0;

    GLASS_POOL_ENTER;
    {
        NSView<GlassView> *view = getGlassView(ptr);
        NSWindow *window = [view window];
        if (window != nil)
        {
            NSRect frame = [window frame];
            NSRect contentRect = [window contentRectForFrameRect:frame];
            x = (int32_t)(contentRect.origin.x - frame.origin.x);
        }
    }
    GLASS_POOL_EXIT;

    return x;
}

int32_t gmac_view_get_y(void *ptr)
{
    if (!ptr) return 0;

    int32_t y = 0;

    GLASS_POOL_ENTER;
    {
        NSView<GlassView> *view = getGlassView(ptr);
        NSWindow * window = [view window];
        if (window != nil)
        {
            NSRect frame = [window frame];
            NSRect contentRect = [window contentRectForFrameRect:frame];

            // Assume that the border in the bottom is zero-sized
            y = (int32_t)(frame.size.height - contentRect.size.height);
        }
    }
    GLASS_POOL_EXIT;

    return y;
}

int32_t gmac_view_close(void *ptr)
{
    if (!ptr) return 0;

    GLASS_POOL_ENTER;
    {
        NSView<GlassView> *view = getGlassView(ptr);
        NSView * host = [view superview];
        if (host != nil) {
            [view removeFromSuperview];
            [host release];
        }
        [view release];
    }
    GLASS_POOL_EXIT;

    return 1;
}

void gmac_view_begin(void *ptr)
{
    if (!ptr) return;

    NSView<GlassView> *view = getGlassView(ptr);
    GLASS_POOL_PUSH; // it will be popped by "gmac_view_end"
    {
        [view retain];
//        [view lockFocus];
        [view begin];
    }
}

void gmac_view_end(void *ptr)
{
    if (!ptr) return;

    NSView<GlassView> *view = getGlassView(ptr);
    {
        [view end];
//        [view unlockFocus];
        [view release];
    }
    GLASS_POOL_POP; // it was pushed by "gmac_view_begin"
}

void gmac_view_schedule_repaint(void *ptr)
{
    if (!ptr) return;

    GLASS_POOL_ENTER;
    {
        NSView<GlassView> *view = getGlassView(ptr);
        [view setNeedsDisplay:YES];
    }
    GLASS_POOL_EXIT;
}

int32_t gmac_view_enter_fullscreen(void *ptr, int32_t animate, int32_t keepRatio, int32_t hideCursor)
{
    if (!ptr) return 0;

    GLASS_POOL_ENTER;
    {
        NSView<GlassView> *view = getGlassView(ptr);
        [view enterFullscreenWithAnimate:(animate != 0) withKeepRatio:(keepRatio != 0)
                          withHideCursor:(hideCursor != 0)];
    }
    GLASS_POOL_EXIT;

    return 1; // gznote: remove this return value
}

void gmac_view_exit_fullscreen(void *ptr, int32_t animate)
{
    if (!ptr) return;

    GLASS_POOL_ENTER;
    {
        NSView<GlassView> *view = getGlassView(ptr);
        [view exitFullscreenWithAnimate:(animate != 0)];
    }
    GLASS_POOL_EXIT;
}

void gmac_view_upload_pixels_direct(void *ptr, const void *pixels, int32_t width, int32_t height, float scaleX,
                                    float scaleY)
{
    if (!ptr) return;

    NSView<GlassView> *view = getGlassView(ptr);

    // must be in the middle of begin/end
    if ((width > 0) && (height > 0))
    {
        [view pushPixels:(void *)pixels withWidth:(unsigned int)width withHeight:(unsigned int)height
              withScaleX:scaleX withScaleY:scaleY];
    }
}

void gmac_view_upload_pixels_bytes(void *ptr, const uint8_t *array, int32_t length, int32_t offset, int32_t width,
                                   int32_t height, float scaleX, float scaleY)
{
    if (!ptr) return;
    if (!array) return;
    if (offset < 0) return;
    if (width <= 0 || height <= 0) return;

    if (width > (((INT_MAX - offset) / 4) / height))
    {
        return;
    }

    if ((4 * width * height + offset) > length)
    {
        return;
    }

    NSView<GlassView> *view = getGlassView(ptr);

    void *pixels = (void *)(array + offset);

    // must be in the middle of begin/end
    [view pushPixels:pixels withWidth:(unsigned int)width withHeight:(unsigned int)height withScaleX:scaleX
          withScaleY:scaleY];
}

void gmac_view_upload_pixels_ints(void *ptr, const uint32_t *array, int32_t length, int32_t offset, int32_t width,
                                  int32_t height, float scaleX, float scaleY)
{
    if (!ptr) return;
    if (!array) return;
    if (offset < 0) return;
    if (width <= 0 || height <= 0) return;

    if (width > ((INT_MAX - offset) / height))
    {
        return;
    }

    if ((width * height + offset) > length)
    {
        return;
    }

    NSView<GlassView> *view = getGlassView(ptr);

    void *pixels = (void *)(array + offset);

    // must be in the middle of begin/end
    [view pushPixels:pixels withWidth:(unsigned int)width withHeight:(unsigned int)height withScaleX:scaleX
          withScaleY:scaleY];
}

/*
 * Input methods callback
 */

void gmac_view_enable_input_method_events(void *ptr, int32_t enable)
{
    if (!ptr) return;

    GLASS_POOL_ENTER;
    {
        NSView<GlassView> *view = getGlassView(ptr);
        [view setInputMethodEnabled:(enable != 0)];
    }
    GLASS_POOL_EXIT;
}

void gmac_view_finish_input_method_composition(void *ptr)
{
    if (!ptr) return;

    GLASS_POOL_ENTER;
    {
        NSView<GlassView> *view = getGlassView(ptr);
        [view finishInputMethodComposition];
    }
    GLASS_POOL_EXIT;
}

void gmac_test_default_view_caps(GmacViewCaps *out)
{
    *out = DEFAULT_VIEW_CAPS;
}
