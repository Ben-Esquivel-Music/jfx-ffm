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

#import <Cocoa/Cocoa.h>

#import "glass_mac_api.h"

@interface GlassMenubar : NSObject
{
@public
        NSMenu        *menu;
}

- (id)init;

@end

@interface GlassMenu : NSObject<NSMenuDelegate>
{
@public

    // Java's id of the menu or item, which the GmacMenuCallbacks slots take.
    int64_t     menuId;

    NSMenuItem  *item;
    NSMenu      *menu;
}

// Menu
- (id)initMenuWithId:(int64_t)identifier title:(NSString *)title enabled:(BOOL)enabled;

// MenuItem
- (id)initItemWithId:(int64_t)identifier title:(NSString *)title shortcut:(uint16_t)shortcut
           modifiers:(int32_t)modifiers icon:(const GmacMenuImage *)icon enabled:(BOOL)enabled
             checked:(BOOL)checked;

- (void)action:(id)sender;
- (void)_setShortcut:(uint16_t)shortcut modifiers:(int32_t)modifiers;
- (void)_setChecked:(BOOL)checked;
- (void)_setImage:(const GmacMenuImage *)icon;

@end
