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
#import "com_sun_glass_ui_mac_MacPasteboard.h"
#import "com_sun_glass_ui_Clipboard.h"

#import "GlassMacros.h"
#import "GlassPasteboard.h"
#import "GlassDragSource.h"
#import "glass_mac_api.h"
#import "glass_mac_internal.h"

#pragma clang diagnostic ignored "-Wdeprecated-declarations"

// UTF-16 code points for surrogate pairs
#define HIGH_SURROGATE_START 0xD800
#define HIGH_SURROGATE_END 0xDBFF
#define LOW_SURROGATE_START 0xDC00
#define LOW_SURROGATE_END 0xDFFF

//#define VERBOSE
#ifndef VERBOSE
    #define LOG(MSG, ...)
#else
    #define LOG(MSG, ...) GLASS_LOG(MSG, ## __VA_ARGS__);
#endif

static NSInteger lastDragSesionNumber = 0;

// Validate the string. Returns false if the string is nil or if
// it contains invalid partial surrogate pairs: a high surrogate
// without an immediately following low surrogate, or conversely,
// a low surrogate without an immediately preceding high surrogate.
static BOOL validate(NSString *data)
{
    if (data == nil) {
        return NO;
    }

    BOOL prevHiSurrogate = NO;
    NSUInteger i;
    for (i = 0; i < [data length]; i++) {
        BOOL hiSurrogate = NO;
        BOOL loSurrogate = NO;
        NSUInteger c = [data characterAtIndex:i];
        if (c >= HIGH_SURROGATE_START && c <= HIGH_SURROGATE_END) {
            hiSurrogate = YES;
        } else if (c >= LOW_SURROGATE_START && c <= LOW_SURROGATE_END) {
            loSurrogate = YES;
        }

        if (loSurrogate && !prevHiSurrogate) {
            return NO;
        }
        if (prevHiSurrogate && !loSurrogate) {
            return NO;
        }

        prevHiSurrogate = hiSurrogate;
    }
    return !prevHiSurrogate;
}

// Dock puts the data to a custom pasteboard, so dragging from it does not work.
// Copy the contents of the sender PBoard to the DraggingPBoard
void copyToDragPasteboardIfNeeded(id<NSDraggingInfo> sender)
{
    NSPasteboard* sourcePasteboard = [sender draggingPasteboard];
    if (![[sourcePasteboard name] isEqualToString:NSDragPboard] &&
        [sender draggingSequenceNumber] != lastDragSesionNumber)
    {
        lastDragSesionNumber = [sender draggingSequenceNumber];

        NSPasteboard* dragPasteboard = [NSPasteboard pasteboardWithName:NSDragPboard];
        [dragPasteboard clearContents];
        for (NSString* type in [sourcePasteboard types])
        {
            [dragPasteboard setData:[sourcePasteboard dataForType:type] forType:type];
        }
    }
}

static inline void DumpPasteboard(NSPasteboard *pasteboard)
{
    NSLog(@"\n");
    NSLog(@"DumpPasteboard");

    NSArray *items = [pasteboard pasteboardItems];
    if ([items count] > 0)
    {
        NSLog(@"---- [items count]: %d", (int)[items count]);
        for (NSUInteger i=0; i<[items count]; i++)
        {
            NSPasteboardItem *item = [items objectAtIndex:i];
            NSArray *types = [item types];
            for (NSUInteger j=0; j<[types count]; j++)
            {
                NSString *type = [types objectAtIndex:j];
                NSLog(@"--------- type: %@", type);

                NSUInteger length = 128;

                NSData *data = [item dataForType:type];
                NSString *string = [item stringForType:type];
                id representation = nil;
                if (string != nil)
                {
                    length = MIN(length, [string length]);
                    representation = [string substringToIndex:length];
                }
                else
                {
                    length = MIN(length, [data length]);
                    representation = [data subdataWithRange:NSMakeRange(0, length)];
                }
                NSLog(@"------------- data: %p [length: %d bytes] [first %d bytes rep: %@]", data, (int)[data length], (int)length, representation);
            }
        }
    }

    NSLog(@"\n");
}

#pragma mark --- C ABI

// The UTI the natives made of a String: nil for an empty one, as they made none when GetStringLength was 0.
static NSString *utiOrNil(const uint16_t *chars, int32_t length)
{
    NSString *utf = nil;
    if (length > 0)
    {
        utf = [NSString stringWithCharacters:(const UniChar *)chars length:(NSUInteger)length];
    }
    return utf;
}

// A malloc'd copy of the length bytes at bytes and a NUL, so that an empty block is not NULL; NULL when it cannot be
// allocated.
static uint8_t *copyBlock(const void *bytes, size_t length)
{
    uint8_t *block = malloc(length + 1);
    if (block != NULL)
    {
        if (length > 0)
        {
            memcpy(block, bytes, length);
        }
        block[length] = 0;
    }
    return block;
}

// The bytes the JNI's createUTF gave new String(byte[], "UTF-8"). A length that is negative as a jsize made its
// NewByteArray throw and its SetByteArrayRegion then crash; it answers NULL.
static uint8_t *copyCreateUtfBytes(NSString *string, int32_t *out_length)
{
    int32_t length = (int32_t)[string lengthOfBytesUsingEncoding:NSUTF8StringEncoding];
    if (length < 0)
    {
        return NULL;
    }
    *out_length = length;
    return copyBlock([string UTF8String], (size_t)length);
}

static int32_t copyUtisBlock(NSArray *typeLists, uint8_t **out_block, int32_t *out_length)
{
    NSMutableData *data = [NSMutableData data];
    int32_t itemCount = (int32_t)[typeLists count];
    [data appendBytes:&itemCount length:sizeof(itemCount)];
    for (NSArray *types in typeLists)
    {
        int32_t typeCount = (int32_t)[types count];
        [data appendBytes:&typeCount length:sizeof(typeCount)];
        for (NSString *type in types)
        {
            int32_t length = (int32_t)[type lengthOfBytesUsingEncoding:NSUTF8StringEncoding];
            if (length < 0)
            {
                return GMAC_ERR_NO_DATA;
            }
            [data appendBytes:&length length:sizeof(length)];
            if (length > 0)
            {
                [data appendBytes:[type UTF8String] length:(NSUInteger)length];
            }
        }
    }
    uint8_t *block = copyBlock([data bytes], [data length]);
    if (block == NULL)
    {
        return GMAC_ERR_NO_DATA;
    }
    *out_block = block;
    *out_length = (int32_t)[data length];
    return GMAC_OK;
}

static BOOL rawImageFitsPixels(size_t width, size_t height)
{
    return width > 0 && height > 0 && width <= (INT_MAX / 4) / height;
}

static BOOL rawImageFitsBlock(size_t width, size_t height)
{
    return (width > 0) && (height > 0) && (width <= ((INT_MAX / 4) - 2) / height);
}

// The byte[] of ByteArrayFromPixels: bytes 3 to 0 of the size_t width and height, which is their big-endian low word
// on a little-endian host only (latent bug), then the pixels.
static int32_t copyRawImageBlock(void *data, size_t width, size_t height, uint8_t **out_block, int32_t *out_length)
{
    if ((data == NULL) || !rawImageFitsBlock(width, height))
    {
        return GMAC_ERR_NO_DATA;
    }
    int32_t length = 4*(int32_t)(width*height);
    uint8_t *block = malloc((size_t)length + 4*(1+1) + 1); // pixels + (width+height) + NUL
    if (block == NULL)
    {
        return GMAC_ERR_NO_DATA;
    }

    uint8_t *w = (uint8_t *)&width;
    block[0] = w[3];
    block[1] = w[2];
    block[2] = w[1];
    block[3] = w[0];

    uint8_t *h = (uint8_t *)&height;
    block[4] = h[3];
    block[5] = h[2];
    block[6] = h[1];
    block[7] = h[0];

    memcpy(block + 8, data, (size_t)length);
    block[8 + length] = 0;

    *out_block = block;
    *out_length = length + 8;
    return GMAC_OK;
}

static int32_t copyRawImageOfImage(NSImage *image, uint8_t **out_block, int32_t *out_length)
{
    int32_t status = GMAC_ERR_NO_DATA;

    if (image != nil && image.size.width > 0 && image.size.height > 0)
    {
        CGImageRef cgImage = [image CGImageForProposedRect:NULL context:nil hints:nil];

        size_t width = CGImageGetWidth(cgImage);
        size_t height = CGImageGetHeight(cgImage);
        uint32_t *pixels = NULL;
        if (rawImageFitsPixels(width, height))
        {
            pixels = malloc(4 * width * height);
        }

        if (pixels != NULL)
        {
            CGColorSpaceRef space = CGColorSpaceCreateWithName(kCGColorSpaceSRGB);
            CGContextRef ctx = CGBitmapContextCreate(pixels, width, height, 8, 4*width, space,
                                                     kCGImageAlphaPremultipliedFirst|kCGBitmapByteOrder32Little);
            CGContextSetBlendMode(ctx, kCGBlendModeCopy);
            CGContextDrawImage(ctx, CGRectMake(0, 0, width, height), cgImage);
            CGContextFlush(ctx);

            status = copyRawImageBlock(pixels, width, height, out_block, out_length);

            CGColorSpaceRelease(space);
            free(pixels);
        }
    }

    return status;
}

// An image Java made for a UTI that takes none, where the JNI made none: released unused.
static void releaseUnusedImage(const GmacPasteboardRep *rep)
{
    if (rep->kind == GMAC_PASTEBOARD_REP_IMAGE && rep->data != NULL)
    {
        [(NSImage *)rep->data release];
    }
}

// item is an NSPasteboardItem, or what a test hook records its setters with.
static void SetItemValueForUtf(id item, const GmacPasteboardRep *rep, NSString *utf)
{
    BOOL isString = NO;
    if ([utf isEqualToString:NSPasteboardTypeString] == YES)
    {
        isString = YES;
    }
    else if ([utf isEqualToString:NSPasteboardTypePDF] == YES)
    {
        isString = YES;
    }
    if ([utf isEqualToString:NSPasteboardTypeRTF] == YES)
    {
        isString = YES;
    }
    else if ([utf isEqualToString:NSPasteboardTypeRTFD] == YES)
    {
        isString = YES;
    }
    else if ([utf isEqualToString:NSPasteboardTypeHTML] == YES)
    {
        isString = YES;
    }
    else if ([utf isEqualToString:NSPasteboardTypeTabularText] == YES)
    {
        isString = YES;
    }
    else if ([utf isEqualToString:NSPasteboardTypeMultipleTextSelection] == YES)
    {
        isString = YES;
    }
    else if ([utf isEqualToString:NSPasteboardTypeFindPanelSearchOptions] == YES)
    {
        isString = YES;
    }
    else if ([utf isEqualToString:(NSString*)kUTTypeURL] == YES)
    {
        isString = YES;
    }
    else if ([utf isEqualToString:(NSString*)kUTTypeFileURL] == YES)
    {
        isString = YES;
    }

    if (isString == YES)
    {
        if (rep->kind == GMAC_PASTEBOARD_REP_STRING)
        {
            // 'string' must never be nil
            NSString *string = [NSString stringWithCharacters:(const UniChar *)rep->data
                                                       length:(NSUInteger)rep->data_length];

            //NSLog(@"                SetValue(string): %@, ForUtf: %@", string, utf);
            if (validate(string)) {
                [item setString:string forType:utf];
            }
        }
        releaseUnusedImage(rep);
    }
    else
    {
        if ([utf isEqualToString:RAW_IMAGE_MIME] == YES || [utf isEqualToString:DRAG_IMAGE_MIME])
        {
            NSImage *image = rep->kind == GMAC_PASTEBOARD_REP_IMAGE ? (NSImage *)rep->data : NULL;
            if (image != NULL)
            {
                NSData *data = [image TIFFRepresentation];
                [item setData:data
                      forType:[utf isEqualToString:RAW_IMAGE_MIME] ? NSPasteboardTypeTIFF : DRAG_IMAGE_MIME];
                //NSLog(@"                setData: %p, ForUtf: %@", data, utf);
                [image release];
            }
        }
        else if ([utf isEqualToString:DRAG_IMAGE_OFFSET] == YES)
        {
            if (rep->kind == GMAC_PASTEBOARD_REP_BYTES)
            {
                NSPoint offset = NSZeroPoint;
                const uint8_t *array = rep->data;
                if (array != NULL) {
                    // The JNI compared sizeof(array), a pointer's size, so it always read 8 bytes (latent bug).
                    int32_t bigEndian[2];
                    memcpy(bigEndian, array, sizeof(bigEndian));
                    int32_t x = CFSwapInt32BigToHost(bigEndian[0]);
                    int32_t y = CFSwapInt32BigToHost(bigEndian[1]);
                    offset = NSMakePoint((float)x, (float)y);
                }

                [item setString:NSStringFromPoint(offset) forType:DRAG_IMAGE_OFFSET];
            }
            releaseUnusedImage(rep);
        }
        else
        {
            if (rep->kind == GMAC_PASTEBOARD_REP_BYTES)
            {
                // 'data' must never be nil
                NSData *data = [NSData dataWithBytes:rep->data length:(NSUInteger)rep->data_length];
                [item setData:data forType:utf];
                //NSLog(@"                SetValue(data): %p, ForUtf: %@", data, utf);
            }
            releaseUnusedImage(rep);
        }
    }
}

static void FillItemFromReps(id item, const GmacPasteboardRep *reps, int32_t repsCount)
{
    //NSLog(@"        FillItemFromReps repsCount: %d", repsCount);
    for (int i=0; i<repsCount; i++)
    {
        const GmacPasteboardRep *rep = &reps[i];
        if (rep->kind != GMAC_PASTEBOARD_REP_BAD)
        {
            NSString *utf = utiOrNil(rep->uti, rep->uti_length);
            // Setting pasteboard can throw exception. It shouldn't
            // happen if we validate the data, but in case it does,
            // we will catch the exception and log a warning
            @try {
                SetItemValueForUtf(item, rep, utf);
            } @catch (NSException *exception) {
                NSLog(@"WARNING: %@: %@ ",exception.name, exception.reason);
            }
        }
        else
        {
            NSLog(@"Glass error: NSPasteboardItemFromArray found bad item with %d entries", (int)rep->data_length);
        }
    }
}

void *gmac_pasteboard_create_system(int32_t type)
{
    LOG("gmac_pasteboard_create_system: %d", type);

    void *ptr = NULL;

    GLASS_POOL_ENTER;
    {
        NSPasteboard *pasteboard = nil;

        switch (type)
        {
            case com_sun_glass_ui_mac_MacPasteboard_General:
                pasteboard = [NSPasteboard pasteboardWithName:NSGeneralPboard];
                break;
            case com_sun_glass_ui_mac_MacPasteboard_DragAndDrop:
                pasteboard = [NSPasteboard pasteboardWithName:NSDragPboard];
                break;
        }

        ptr = pasteboard;
    }
    GLASS_POOL_EXIT;

    return ptr;
}

void *gmac_pasteboard_create_user(const uint16_t *name, int32_t length)
{
    LOG("gmac_pasteboard_create_user");

    void *ptr = NULL;

    GLASS_POOL_ENTER;
    {
        NSPasteboard *pasteboard = [[NSPasteboard pasteboardWithName:utiOrNil(name, length)] retain];
        ptr = pasteboard;
    }
    GLASS_POOL_EXIT;

    return ptr;
}

int32_t gmac_pasteboard_get_name(void *ptr, char **out_utf8, int32_t *out_length)
{
    LOG("gmac_pasteboard_get_name");

    int32_t status;

    GLASS_POOL_ENTER;
    {
        NSPasteboard *pasteboard = (NSPasteboard*)ptr;
        NSString *string = [pasteboard name];
        status = GlassCopyUtf8([string UTF8String], out_utf8, out_length);
    }
    GLASS_POOL_EXIT;

    return status;
}

int32_t gmac_pasteboard_get_utis(void *ptr, uint8_t **out_block, int32_t *out_length)
{
    LOG("gmac_pasteboard_get_utis");

    int32_t status = GMAC_ERR_NO_DATA;
    *out_block = NULL;
    *out_length = 0;

    GLASS_POOL_ENTER;
    {
        NSPasteboard *pasteboard = (NSPasteboard*)ptr;
        //DumpPasteboard(pasteboard);

        NSArray *items = [pasteboard pasteboardItems];
        if ([items count] > 0)
        {
            NSMutableArray *typeLists = [NSMutableArray arrayWithCapacity:[items count]];
            for (NSUInteger i=0; i<[items count]; i++)
            {
                NSPasteboardItem *item = [items objectAtIndex:i];

                NSArray *types = [item types];
                [typeLists addObject:(types != nil ? types : [NSArray array])];
            }
            status = copyUtisBlock(typeLists, out_block, out_length);
        }
    }
    GLASS_POOL_EXIT;

    return status;
}

int32_t gmac_pasteboard_get_item_as_raw_image(void *ptr, int32_t index, uint8_t **out_block, int32_t *out_length)
{
    LOG("gmac_pasteboard_get_item_as_raw_image");

    int32_t status = GMAC_ERR_NO_DATA;
    *out_block = NULL;
    *out_length = 0;

    GLASS_POOL_ENTER;
    {
        if (index >= 0)
        {
            NSPasteboard *pasteboard = (NSPasteboard*)ptr;
            NSArray *items = [pasteboard pasteboardItems];

            NSPasteboardItem *item = [items objectAtIndex:(NSUInteger)index];
            if (item != nil)
            {
                // since this is a convenience method we'll try to do our best to return an image for this item
                // by trying all the following types if needed
                NSArray *utfs = [NSArray arrayWithObjects:
                                 NSPasteboardTypeTIFF,
                                 NSPasteboardTypePNG,
                                 NSPasteboardTypePDF,
                                 nil];

                NSData *data = nil;
                for (NSUInteger i=0; i<[utfs count]; i++)
                {
                    data = [item dataForType:[utfs objectAtIndex:i]];
                    if (data != nil)
                    {
                        break;
                    }
                }

                NSImage *image = [[[NSImage alloc] initWithData:data] autorelease];
                if (image == nil)
                {
                    // if no image yet, try by referencing file url of this item (if it exists)
                    NSString *file = [item stringForType:(NSString*)kUTTypeURL];
                    if (file != nil)
                    {
                        NSURL *url = [NSURL URLWithString:file];
                        image = [[[NSImage alloc] initByReferencingURL:url] autorelease];
                    }
                }
                if (image == nil)
                {
                    // if no image yet, try by referencing file url of this item (if it exists)
                    NSString *file = [item stringForType:(NSString*)kUTTypeFileURL];
                    if (file != nil)
                    {
                        NSURL *url = [NSURL URLWithString:file];
                        image = [[[NSImage alloc] initByReferencingURL:url] autorelease];
                    }
                }

#if 0
                // last try: if no image yet, try asking the pastebard for one (it's not per item though)
                if ((image == nil) && ([NSImage canInitWithPasteboard:pasteboard]))
                {
                    image = [[[NSImage alloc] initWithPasteboard:pasteboard] autorelease];
                }
#endif

                status = copyRawImageOfImage(image, out_block, out_length);
            }
        }
    }
    GLASS_POOL_EXIT;

    return status;
}

int32_t gmac_pasteboard_get_item_string(void *ptr, int32_t index, const uint16_t *uti, int32_t uti_length,
                                        char **out_utf8, int32_t *out_length)
{
    LOG("gmac_pasteboard_get_item_string");

    int32_t status = GMAC_ERR_NO_DATA;
    *out_utf8 = NULL;
    *out_length = 0;

    GLASS_POOL_ENTER;
    {
        if (index >= 0)
        {
            NSString *utf = utiOrNil(uti, uti_length);
            if (utf != nil)
            {
                NSPasteboard *pasteboard = (NSPasteboard*)ptr;
                NSArray *items = [pasteboard pasteboardItems];
                NSPasteboardItem *item = [items objectAtIndex:(NSUInteger)index];
                if (item != nil)
                {
                    NSString *str = [item stringForType:utf];
                    if (str != nil)
                    {
                        int32_t length = 0;
                        uint8_t *bytes = copyCreateUtfBytes(str, &length);
                        if (bytes != NULL)
                        {
                            *out_utf8 = (char *)bytes;
                            *out_length = length;
                            status = GMAC_OK;
                        }
                    }
                }
            }
        }
    }
    GLASS_POOL_EXIT;

    return status;
}

int32_t gmac_pasteboard_get_item_bytes(void *ptr, int32_t index, const uint16_t *uti, int32_t uti_length,
                                       uint8_t **out_bytes, int32_t *out_length)
{
    LOG("gmac_pasteboard_get_item_bytes");

    int32_t status = GMAC_ERR_NO_DATA;
    *out_bytes = NULL;
    *out_length = 0;

    GLASS_POOL_ENTER;
    {
        if (index >= 0)
        {
            NSString *utf = utiOrNil(uti, uti_length);
            if (utf != nil)
            {
                NSPasteboard *pasteboard = (NSPasteboard*)ptr;
                NSArray *items = [pasteboard pasteboardItems];
                NSPasteboardItem *item = [items objectAtIndex:(NSUInteger)index];

                NSData *data = [item dataForType:utf];
                if (data != nil)
                {
                    int32_t length = (int32_t)[data length];
                    if (length < 0)
                    {
                        *out_length = length;
                    }
                    else
                    {
                        *out_bytes = copyBlock([data bytes], (size_t)length);
                        if (*out_bytes != NULL)
                        {
                            *out_length = length;
                            status = GMAC_OK;
                        }
                    }
                }
            }
        }
    }
    GLASS_POOL_EXIT;

    return status;
}

int64_t gmac_pasteboard_put_items(void *ptr, const GmacPasteboardItem *items, int32_t item_count,
                                  int32_t supported_actions)
{
    LOG("gmac_pasteboard_put_items");

    int64_t seed = 0;

    GLASS_POOL_ENTER;
    {
        NSPasteboard *pasteboard = (NSPasteboard*)ptr;
        seed = [pasteboard clearContents];

        LOG("gmac_pasteboard_put_items itemCount: %d", item_count);
        if (item_count > 0)
        {
            NSMutableArray *objects = [NSMutableArray arrayWithCapacity:(NSUInteger)item_count];
            for (int i=0; i<item_count; i++)
            {
                if (items[i].rep_count != GMAC_PASTEBOARD_ITEM_NULL)
                {
                    NSPasteboardItem *item = [[[NSPasteboardItem alloc] init] autorelease];
                    FillItemFromReps(item, items[i].reps, items[i].rep_count);
                    [objects addObject:item];
                }
            }

            // We perform a Drag-n-Drop only when our pasteboard is a DnD pasteboard
            // and if drag delegate was set. The latter one is set when DnD operation
            // came from a NSView (ex. mouse drag).
            if ([[pasteboard name] isEqualToString:NSDragPboard] && [GlassDragSource isDelegateSet])
            {
                // DnD requires separate NSDragging* calls to work since macOS 10.7
                // convert NSPasteboardItem-s array to NSDraggingItem-s
                NSMutableArray<NSDraggingItem*> *dItems =
                        [NSMutableArray<NSDraggingItem*> arrayWithCapacity:(NSUInteger)item_count];
                for (NSPasteboardItem* i in objects)
                {
                    [dItems addObject:[[NSDraggingItem alloc] initWithPasteboardWriter:i]];
                }

                // New DnD API (macOS 10.7+) requires us to skip writing data to Pasteboard.
                // It handles managing the pasteboard separately on its own.
                [GlassDragSource flushWithMask:supported_actions withItems:dItems];

                for (NSDraggingItem* i in dItems)
                {
                    [i release];
                }
            }
            else
            {
                // previous write to pasteboard for compatibility
                // http://developer.apple.com/library/mac/#documentation/cocoa/Conceptual/PasteboardGuide106/Articles/pbCustom.html
                [pasteboard writeObjects:objects];
            }
        }
    }
    GLASS_POOL_EXIT;

    return seed;
}

int64_t gmac_pasteboard_clear(void *ptr)
{
    LOG("gmac_pasteboard_clear");

    int64_t seed = 0;

    GLASS_POOL_ENTER;
    {
        NSPasteboard *pasteboard = (NSPasteboard*)ptr;
        seed = [pasteboard clearContents];
    }
    GLASS_POOL_EXIT;

    return seed;
}

int64_t gmac_pasteboard_get_seed(void *ptr)
{
    LOG("gmac_pasteboard_get_seed");

    int64_t seed = 0;

    GLASS_POOL_ENTER;
    {
        NSPasteboard *pasteboard = (NSPasteboard*)ptr;
        seed = [pasteboard changeCount];
    }
    GLASS_POOL_EXIT;

    return seed;
}

void gmac_pasteboard_release(void *ptr)
{
    LOG("gmac_pasteboard_release");

    GLASS_POOL_ENTER;
    {
        NSPasteboard *pasteboard = (NSPasteboard*)ptr;
        [pasteboard releaseGlobally];
    }
    GLASS_POOL_EXIT;
}

int32_t gmac_pasteboard_get_allowed_operation(void *ptr)
{
    LOG("gmac_pasteboard_get_allowed_operation");

    int32_t mask = 0;

    GLASS_POOL_ENTER;
    {
        NSPasteboard *pasteboard = (NSPasteboard*)ptr;
        if (pasteboard == [NSPasteboard pasteboardWithName:NSDragPboard])
        {
            // retrieve the mask for DnD
            mask = [GlassDragSource getSupportedActions];
        }
        else
        {
            // we can always copy from a NSPasteboard
            mask = com_sun_glass_ui_Clipboard_ACTION_COPY;
        }
    }
    GLASS_POOL_EXIT;

    return mask;
}

#pragma mark --- shim-only test hooks

GLASS_INTERNAL @interface GlassPasteboardItemRecorder : NSObject
{
@public
    NSMutableString *record;
}
- (BOOL)setString:(NSString *)string forType:(NSPasteboardType)type;
- (BOOL)setData:(NSData *)data forType:(NSPasteboardType)type;
@end

static void recordUnits(NSMutableString *record, NSString *string)
{
    if (string == nil)
    {
        [record appendString:@" nil"];
        return;
    }
    [record appendFormat:@" %lu:", (unsigned long)[string length]];
    for (NSUInteger i = 0; i < [string length]; i++)
    {
        [record appendFormat:@"%04x", (unsigned)[string characterAtIndex:i]];
    }
}

@implementation GlassPasteboardItemRecorder

- (BOOL)setString:(NSString *)string forType:(NSPasteboardType)type
{
    [record appendString:@"string"];
    recordUnits(record, type);
    recordUnits(record, string);
    [record appendString:@"\n"];
    return YES;
}

- (BOOL)setData:(NSData *)data forType:(NSPasteboardType)type
{
    [record appendString:@"data"];
    recordUnits(record, type);
    [record appendFormat:@" %lu:", (unsigned long)[data length]];
    const uint8_t *bytes = [data bytes];
    for (NSUInteger i = 0; i < MIN([data length], (NSUInteger)64); i++)
    {
        [record appendFormat:@"%02x", (unsigned)bytes[i]];
    }
    [record appendString:@"\n"];
    return YES;
}

@end

int32_t gmac_test_pasteboard_utis_block(const uint16_t *units, const int32_t *lengths, const int32_t *type_counts,
                                        int32_t item_count, uint8_t **out_block, int32_t *out_length)
{
    int32_t status = GMAC_ERR_NO_DATA;
    *out_block = NULL;
    *out_length = 0;

    GLASS_POOL_ENTER;
    {
        if (item_count > 0)
        {
            NSMutableArray *typeLists = [NSMutableArray arrayWithCapacity:(NSUInteger)item_count];
            int32_t type = 0;
            for (int32_t i = 0; i < item_count; i++)
            {
                NSMutableArray *types = [NSMutableArray array];
                for (int32_t j = 0; j < type_counts[i]; j++, type++)
                {
                    [types addObject:[NSString stringWithCharacters:units length:(NSUInteger)lengths[type]]];
                    units += lengths[type];
                }
                [typeLists addObject:types];
            }
            status = copyUtisBlock(typeLists, out_block, out_length);
        }
    }
    GLASS_POOL_EXIT;

    return status;
}

int32_t gmac_test_pasteboard_raw_image(const uint8_t *data, int32_t length, uint8_t **out_block,
                                       int32_t *out_length)
{
    int32_t status;
    *out_block = NULL;
    *out_length = 0;

    GLASS_POOL_ENTER;
    {
        NSData *imageData = [NSData dataWithBytes:data length:(NSUInteger)length];
        NSImage *image = [[[NSImage alloc] initWithData:imageData] autorelease];
        status = copyRawImageOfImage(image, out_block, out_length);
    }
    GLASS_POOL_EXIT;

    return status;
}

int32_t gmac_test_pasteboard_raw_image_bounds(uint64_t width, uint64_t height)
{
    return (rawImageFitsPixels((size_t)width, (size_t)height) ? 1 : 0)
        | (rawImageFitsBlock((size_t)width, (size_t)height) ? 2 : 0);
}

int32_t gmac_test_pasteboard_describe_items(const GmacPasteboardItem *items, int32_t item_count, char **out_utf8,
                                            int32_t *out_length)
{
    int32_t status;

    GLASS_POOL_ENTER;
    {
        GlassPasteboardItemRecorder *recorder = [[[GlassPasteboardItemRecorder alloc] init] autorelease];
        recorder->record = [NSMutableString string];
        for (int32_t i = 0; i < item_count; i++)
        {
            if (items[i].rep_count != GMAC_PASTEBOARD_ITEM_NULL)
            {
                [recorder->record appendString:@"item\n"];
                FillItemFromReps(recorder, items[i].reps, items[i].rep_count);
            }
        }
        status = GlassCopyUtf8([recorder->record UTF8String], out_utf8, out_length);
    }
    GLASS_POOL_EXIT;

    return status;
}
