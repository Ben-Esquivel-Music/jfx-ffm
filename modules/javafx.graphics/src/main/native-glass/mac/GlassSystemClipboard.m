/*
 * Copyright (c) 2013, 2026, Oracle and/or its affiliates. All rights reserved.
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
#import "glass_mac_internal.h"

// The UTType calls of the former FormatEncoder natives, which Java now makes; the test hooks keep them as its oracle.
static NSString *UTIForMIME(NSString *mime)
{
    CFStringRef cfMIME = (CFStringRef)mime;
    NSString* nsUTI = (NSString *)UTTypeCreatePreferredIdentifierForTag(kUTTagClassMIMEType,
                                                                        cfMIME,
                                                                        (CFStringRef)@"public.mime-type");
    [nsUTI autorelease];
    return nsUTI;
}

static NSString *MIMEForUTI(NSString *uti)
{
    CFStringRef cfUTI = (CFStringRef)uti;
    NSString* nsMIME = [(NSString*)UTTypeCopyPreferredTagWithClass(cfUTI,
                                                                   kUTTagClassMIMEType) autorelease];
    return nsMIME;
}

int32_t gmac_test_clipboard_mime_to_uti(const uint16_t *mime, int32_t length, char **out_utf8, int32_t *out_length)
{
    int32_t status;

    GLASS_POOL_ENTER;
    {
        status = GlassCopyUtf8([UTIForMIME(GlassNSStringOrEmpty(mime, length)) UTF8String], out_utf8, out_length);
    }
    GLASS_POOL_EXIT;

    return status;
}

int32_t gmac_test_clipboard_uti_to_mime(const uint16_t *uti, int32_t length, char **out_utf8, int32_t *out_length)
{
    int32_t status;

    GLASS_POOL_ENTER;
    {
        status = GlassCopyUtf8([MIMEForUTI(GlassNSStringOrEmpty(uti, length)) UTF8String], out_utf8, out_length);
    }
    GLASS_POOL_EXIT;

    return status;
}

int32_t gmac_clipboard_file_reference_path(const uint16_t *path, int32_t length, char **out_utf8,
                                           int32_t *out_length)
{
    int32_t status;

    GLASS_POOL_ENTER;
    {
        NSString* string = GlassNSStringOrEmpty(path, length);
        NSURL *url = [NSURL URLWithString:string];
        if (url != nil) {
            url = [url filePathURL];
            if (url != nil) {
                string = [url path];
            }
        }
        status = GlassCopyUtf8([string UTF8String], out_utf8, out_length);
    }
    GLASS_POOL_EXIT;

    return status;
}
