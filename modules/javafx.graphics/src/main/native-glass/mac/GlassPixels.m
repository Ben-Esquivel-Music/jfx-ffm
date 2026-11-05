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
#import "glass_mac_api.h"

//#define VERBOSE
#ifndef VERBOSE
    #define LOG(MSG, ...)
#else
    #define LOG(MSG, ...) GLASS_LOG(MSG, ## __VA_ARGS__);
#endif

NSImage* getImage(u_int8_t* data, int jWidth, int jHeight, int jOffset) {
    NSImage* image = NULL;
    CGImageRef cgImage = NULL;
    if ((data != NULL) && (jWidth > 0) && (jHeight > 0)) {
        CGColorSpaceRef colorSpace = CGColorSpaceCreateWithName(kCGColorSpaceSRGB);
        {
            size_t width = (size_t) jWidth;
            size_t height = (size_t) jHeight;

            CFDataRef cfData = CFDataCreate(NULL, data + jOffset, width * height * 4);
            if (cfData != NULL) {
                CGDataProviderRef provider = CGDataProviderCreateWithCFData(cfData);
                if (provider != NULL) {
                    cgImage = CGImageCreate(width, height, 8, 32, 4 * width, colorSpace,
                            kCGImageAlphaPremultipliedFirst | kCGBitmapByteOrder32Little,
                            provider, NULL, 1, kCGRenderingIntentDefault);
                    CGDataProviderRelease(provider);
                }
                CFRelease(cfData);
            }
        }
        CGColorSpaceRelease(colorSpace);
        if (cgImage != NULL) {
            image = [[NSImage alloc] initWithCGImage : cgImage size : NSMakeSize(jWidth, jHeight)];
            CGImageRelease(cgImage);
        } else {
            image = nil;
        }
    }
    return image;
}

// The callers of the former attachCommon reached getImage through the attachData upcall inside their own Glass pool,
// so this pool only nests there. Once the caller is Java, it drains what the image's construction autoreleased.
void gmac_image_create(const uint8_t *pixels, int32_t width, int32_t height, void **out_image)
{
    GLASS_POOL_ENTER;
    {
        *out_image = getImage((u_int8_t *)pixels, width, height, 0);
    }
    GLASS_POOL_EXIT;
}
