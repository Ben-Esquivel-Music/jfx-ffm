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
#import "GlassDialogs.h"
#import "GlassApplication.h"
#import "glass_mac_api.h"
#import "glass_mac_internal.h"

#pragma mark --- Dispatcher

@interface DialogDispatcher : NSObject
{
    NSSavePanel *panel;
    NSWindow    *owner;
    NSInteger    button;
    int64_t      loopId;
    NSArray     *m_filters;
}

- initWithPanel:(NSSavePanel*)panel owner:(NSWindow*)owner loopId:(int64_t)loopId;
- (void)runModally;
- (NSInteger)getButton;

- (void)applyFilters:(const GmacFileFilter *)gmacFilters count:(int32_t)count withDefaultIndex:(int32_t)index;
- (void)extensionFilterChanged:(NSPopUpButton*)sender;
@end

// The popup's titles and, per title, the path extensions its filter allows, as the JNI's applyExtensions made them.
static void makeFilterArrays(const GmacFileFilter *gmacFilters, int32_t count, NSMutableArray **outNames,
                             NSMutableArray **outFilters)
{
    NSMutableArray *filters = [NSMutableArray arrayWithCapacity:(NSUInteger)count];
    NSMutableArray *filterNames = [NSMutableArray arrayWithCapacity:(NSUInteger)count];
    for (int32_t i = 0; i < count; i++)
    {
        const GmacFileFilter *filter = &gmacFilters[i];
        [filterNames addObject:GlassNSStringOrEmpty(filter->description, filter->description_length)];

        NSMutableArray* extensions = [NSMutableArray arrayWithCapacity:(NSUInteger)filter->extension_count];
        for (int32_t j = 0; j < filter->extension_count; j++)
        {
            NSString* extension = GlassNSStringOrEmpty(filter->extensions[j], filter->extension_lengths[j]);
            [extensions addObject:[extension pathExtension]];
        }
        [filters addObject:extensions];
    }
    *outNames = filterNames;
    *outFilters = filters;
}

@implementation DialogDispatcher

- (id)initWithPanel:(NSSavePanel*)p owner:(NSWindow*)o loopId:(int64_t)l
{
    self = [super init];

    self->panel = p;
    self->owner = o;
    self->loopId = l;

    return self;
}

- (void)exitModalWithResult:(NSInteger)result
{
    self->button = result;
    const GmacDialogCallbacks *callbacks = GlassDialogCallbacks();
    if (callbacks != NULL && callbacks->event_loop_leave != NULL && GlassIsMainJavaAttached())
    {
        callbacks->event_loop_leave(self->loopId);
    }
}

- (void)beginPanelWithCompletionHandler:(void (^)(NSInteger result))handler
{
    if (owner) {
        [panel beginSheetModalForWindow: owner completionHandler:handler];
    } else {
        [panel beginWithCompletionHandler:handler];
    }
}

- (void)runModally
{
    // Prints what the JNI printed on a detached main thread, before it crashed on the NULL JNI environment.
    GLASS_WARN_IF_MAIN_JAVA_DETACHED;
    DialogDispatcher *dd = self;

    // Without a table no nested loop could wait for the panel. Java made the EventLoop before the call, and passes loop
    // id 0 when that threw, where the JNI returned here without beginning the panel.
    const GmacDialogCallbacks *callbacks = GlassDialogCallbacks();
    if (callbacks == NULL || self->loopId == 0 || !GlassIsMainJavaAttached())
    {
        return;
    }
    [self beginPanelWithCompletionHandler:^(NSInteger result)
    {
        [dd exitModalWithResult:result];
    }];
    if (callbacks->event_loop_enter != NULL)
    {
        callbacks->event_loop_enter(self->loopId);
    }
}

- (NSInteger)getButton
{
    return self->button;
}

- (void)applyFilters:(const GmacFileFilter *)gmacFilters count:(int32_t)count withDefaultIndex:(int32_t)index
{
    if (count > 0)
    {
        NSMutableArray *filterNames;
        NSMutableArray *filters;
        makeFilterArrays(gmacFilters, count, &filterNames, &filters);

        self->m_filters = filters;

        NSPopUpButton* filterView = [[[NSPopUpButton alloc] initWithFrame:NSZeroRect pullsDown:NO] autorelease];
        [self->panel setAccessoryView:filterView];
        [filterView setTarget:self];
        [filterView setAction:@selector(extensionFilterChanged:)];
        [filterView addItemsWithTitles:filterNames];
        [filterView selectItemAtIndex:index];
        [self extensionFilterChanged:filterView];
        [filterView sizeToFit];
    }
}

-(void)extensionFilterChanged:(NSPopUpButton*)sender
{
    NSInteger index = [sender indexOfSelectedItem];
    if (index >= 0)
    {
        NSArray* extensions = [self->m_filters objectAtIndex:index];
        if ([extensions count] == 0 || [extensions containsObject:@"*"])
        {
            [self->panel setAllowedFileTypes:nil];
            //Clean up the added extensions when resetting to any file type.
            NSString* fileName = [[self->panel nameFieldStringValue] stringByDeletingPathExtension];
            [self->panel setNameFieldStringValue:fileName];
        }
        else
        {
            [self->panel setAllowedFileTypes:extensions];
        }
        [self->panel validateVisibleColumns];
    }
}

@end

// Retained because the caller converts them after this export's pool drained; the JNI converted them inside its pool.
static int32_t copyRetainedUrls(NSArray *urls, void ***out_urls, int32_t *out_url_count)
{
    NSUInteger count = [urls count];
    void **block = malloc(count * sizeof(void *));
    if (block == NULL)
    {
        return GMAC_ERR_NO_DATA;
    }
    for (NSUInteger i = 0; i < count; i++)
    {
        block[i] = [[urls objectAtIndex:i] retain];
    }
    *out_urls = block;
    *out_url_count = (int32_t)count;
    return GMAC_OK;
}

static int64_t selectedFilter(NSSavePanel *panel, int32_t filter_count)
{
    return filter_count > 0 ? (int64_t)[(NSPopUpButton*)[panel accessoryView] indexOfSelectedItem] : -1;
}

#pragma mark --- C ABI

int32_t gmac_url_start_accessing_security_scoped_resource(void *url)
{
    return [(NSURL *)url startAccessingSecurityScopedResource] ? 1 : 0;
}

void gmac_url_stop_accessing_security_scoped_resource(void *url)
{
    [(NSURL *)url stopAccessingSecurityScopedResource];
}

int32_t gmac_url_get_bookmark(void *url, void *base_url, uint8_t **out_bytes, int32_t *out_length)
{
    int32_t status = GMAC_ERR_NO_DATA;
    *out_bytes = NULL;
    *out_length = 0;

    GLASS_POOL_ENTER;
    {
        NSError *error = nil;

        NSData *nsData = [(NSURL *)url bookmarkDataWithOptions:NSURLBookmarkCreationWithSecurityScope
                       includingResourceValuesForKeys:nil relativeToURL:(NSURL *)base_url error:&error];

        if (error) {
            NSLog(@"ERROR in Glass calling bookmarkDataWithOptions: %@", error);
        } else {
            const int32_t len = (int32_t)[nsData length];
            if (len < 0) {
                *out_length = len;
            } else {
                uint8_t *bytes = malloc((size_t)len + 1);
                if (bytes != NULL) {
                    if (len > 0) {
                        memcpy(bytes, [nsData bytes], (size_t)len);
                    }
                    bytes[len] = 0;
                    *out_bytes = bytes;
                    *out_length = len;
                    status = GMAC_OK;
                }
            }
        }
    }
    GLASS_POOL_EXIT;

    return status;
}

int32_t gmac_url_create_from_bookmark(const uint8_t *data, int32_t length, void *base_url, void **out_url)
{
    int32_t status = GMAC_ERR_NO_DATA;
    *out_url = NULL;

    GLASS_POOL_ENTER;
    {
        NSData *nsData = data != NULL ? [NSData dataWithBytes:data length:(NSUInteger)length] : nil;

        if (nsData) {
            BOOL isStale = NO;
            NSError *error = nil;

            NSURL *url = [NSURL URLByResolvingBookmarkData:nsData
                options:(NSURLBookmarkResolutionWithoutUI | NSURLBookmarkResolutionWithSecurityScope)
                relativeToURL:(NSURL *)base_url bookmarkDataIsStale:&isStale error:&error];

            if (isStale) {
                NSLog(@"URLByResolvingBookmarkData isStale=%d", isStale);
            }

            if (error) {
                NSLog(@"ERROR in Glass calling URLByResolvingBookmarkData: %@", error);
            } else {
                *out_url = [url retain];
                status = GMAC_OK;
            }
        }
    }
    GLASS_POOL_EXIT;

    return status;
}

int32_t gmac_url_copy_path(void *url, uint16_t **out_chars, int32_t *out_length)
{
    int32_t status = GMAC_ERR_NO_DATA;
    *out_chars = NULL;
    *out_length = 0;

    GLASS_POOL_ENTER;
    {
        NSData *data = [[(NSURL *)url path] dataUsingEncoding:NSUTF16LittleEndianStringEncoding];
        const int32_t length = (int32_t)(data.length / 2);
        uint16_t *chars = length >= 0 ? malloc(((size_t)length + 1) * sizeof(uint16_t)) : NULL;
        if (chars != NULL)
        {
            if (length > 0)
            {
                memcpy(chars, [data bytes], (size_t)length * sizeof(uint16_t));
            }
            chars[length] = 0;
            *out_chars = chars;
            *out_length = length;
            status = GMAC_OK;
        }
    }
    GLASS_POOL_EXIT;

    return status;
}

int32_t gmac_dialog_open(void *owner, const uint16_t *folder, int32_t folder_length, const uint16_t *title,
                         int32_t title_length, int32_t multiple, const GmacFileFilter *filters, int32_t filter_count,
                         int32_t default_index, int64_t loop_id, void ***out_urls, int32_t *out_url_count,
                         int64_t *out_selected)
{
    int32_t status = GMAC_OK;
    *out_urls = NULL;
    *out_url_count = 0;
    *out_selected = -1;

    GLASS_POOL_ENTER;
    {
        NSOpenPanel *panel = [NSOpenPanel openPanel];
        [panel setAllowsMultipleSelection:(multiple != 0)];
        [panel setTitle:GlassNSStringOrEmpty(title, title_length)];
        NSString *folderString = GlassNSStringOrEmpty(folder, folder_length);
        if ([folderString length] > 0)
        {
            [panel setDirectoryURL:[NSURL fileURLWithPath:folderString isDirectory:YES]];
        }

        [panel setResolvesAliases:YES];
        [panel setCanChooseFiles:YES];
        [panel setCanChooseDirectories:NO];
        [panel setShowsHiddenFiles:YES];
        [panel setExtensionHidden:NO];
        [panel setCanSelectHiddenExtension:YES];
        [panel setAllowsOtherFileTypes:NO];
        [panel setCanCreateDirectories:NO];

        DialogDispatcher *dispatcher = [[DialogDispatcher alloc] initWithPanel:panel owner:(NSWindow*)owner
                                                                        loopId:loop_id];
        [dispatcher applyFilters:filters count:filter_count withDefaultIndex:default_index];
        {
            [dispatcher performSelectorOnMainThread:@selector(runModally) withObject:nil waitUntilDone:YES];
            NSArray *urls = [panel URLs];

            if (([dispatcher getButton] == NSModalResponseOK) && ([urls count] > 0))
            {
                status = copyRetainedUrls(urls, out_urls, out_url_count);
            }

            *out_selected = selectedFilter(panel, filter_count);
        }
        [dispatcher release];
    }
    GLASS_POOL_EXIT;

    return status;
}

void gmac_dialog_save(void *owner, const uint16_t *folder, int32_t folder_length, const uint16_t *filename,
                      int32_t filename_length, const uint16_t *title, int32_t title_length,
                      const GmacFileFilter *filters, int32_t filter_count, int32_t default_index, int64_t loop_id,
                      void **out_url, int64_t *out_selected)
{
    *out_url = NULL;
    *out_selected = -1;

    GLASS_POOL_ENTER;
    {
        NSSavePanel *panel = [NSSavePanel savePanel];
        [panel setTitle:GlassNSStringOrEmpty(title, title_length)];
        NSString *folderString = GlassNSStringOrEmpty(folder, folder_length);
        if ([folderString length] > 0)
        {
            [panel setDirectoryURL:[NSURL fileURLWithPath:folderString isDirectory:YES]];
        }

        NSString *filenameString = GlassNSStringOrEmpty(filename, filename_length);
        if ([filenameString length] > 0) {
            filenameString = [filenameString stringByDeletingPathExtension];
            [panel setNameFieldStringValue:filenameString];
        }

        [panel setShowsHiddenFiles:YES];
        [panel setExtensionHidden:NO];
        [panel setCanSelectHiddenExtension:YES];
        [panel setAllowsOtherFileTypes:NO];
        [panel setCanCreateDirectories:YES];

        DialogDispatcher *dispatcher = [[DialogDispatcher alloc] initWithPanel:panel owner:(NSWindow*)owner
                                                                        loopId:loop_id];
        [dispatcher applyFilters:filters count:filter_count withDefaultIndex:default_index];
        {
            [dispatcher performSelectorOnMainThread:@selector(runModally) withObject:nil waitUntilDone:YES];
            NSURL *url = [panel URL];

            if (([dispatcher getButton] == NSModalResponseOK) && (url != nil))
            {
                *out_url = [url retain];
            }

            *out_selected = selectedFilter(panel, filter_count);
        }
        [dispatcher release];
    }
    GLASS_POOL_EXIT;
}

void gmac_dialog_folder(void *owner, const uint16_t *folder, int32_t folder_length, const uint16_t *title,
                        int32_t title_length, int64_t loop_id, void **out_url)
{
    *out_url = NULL;

    GLASS_POOL_ENTER;
    {
        NSOpenPanel *panel = [NSOpenPanel openPanel];
        [panel setTitle:GlassNSStringOrEmpty(title, title_length)];
        NSString *folderString = GlassNSStringOrEmpty(folder, folder_length);
        if ([folderString length] > 0)
        {
            [panel setDirectoryURL:[NSURL fileURLWithPath:folderString isDirectory:YES]];
        }

        [panel setAllowsMultipleSelection:NO];
        [panel setResolvesAliases:YES];
        [panel setCanChooseFiles:NO];
        [panel setCanChooseDirectories:YES];
        [panel setShowsHiddenFiles:NO];
        [panel setExtensionHidden:YES];
        [panel setCanSelectHiddenExtension:NO];
        [panel setAllowsOtherFileTypes:NO];
        [panel setCanCreateDirectories:YES];

        DialogDispatcher *dispatcher = [[DialogDispatcher alloc] initWithPanel:panel owner:(NSWindow*)owner
                                                                        loopId:loop_id];
        {
            [dispatcher performSelectorOnMainThread:@selector(runModally) withObject:panel waitUntilDone:YES];
            NSArray *urls = [panel URLs];
            if (([dispatcher getButton] == NSModalResponseOK) && ([urls count] >= 1))
            {
                *out_url = [[urls objectAtIndex:0] retain];
            }
        }
        [dispatcher release];
    }
    GLASS_POOL_EXIT;
}

#pragma mark --- shim-only test hooks

static void recordUnits(NSMutableString *record, NSString *string)
{
    [record appendFormat:@" %lu:", (unsigned long)[string length]];
    for (NSUInteger i = 0; i < [string length]; i++)
    {
        [record appendFormat:@"%04x", (unsigned)[string characterAtIndex:i]];
    }
}

int32_t gmac_test_dialog_describe_filters(const GmacFileFilter *filters, int32_t filter_count, char **out_utf8,
                                          int32_t *out_length)
{
    int32_t status;

    GLASS_POOL_ENTER;
    {
        NSMutableString *record = [NSMutableString string];
        if (filter_count > 0)
        {
            NSMutableArray *filterNames;
            NSMutableArray *extensionLists;
            makeFilterArrays(filters, filter_count, &filterNames, &extensionLists);
            for (NSUInteger i = 0; i < [filterNames count]; i++)
            {
                [record appendString:@"filter"];
                recordUnits(record, [filterNames objectAtIndex:i]);
                [record appendString:@"\n"];
                for (NSString *extension in [extensionLists objectAtIndex:i])
                {
                    [record appendString:@"extension"];
                    recordUnits(record, extension);
                    [record appendString:@"\n"];
                }
            }
        }
        status = GlassCopyUtf8([record UTF8String], out_utf8, out_length);
    }
    GLASS_POOL_EXIT;

    return status;
}
