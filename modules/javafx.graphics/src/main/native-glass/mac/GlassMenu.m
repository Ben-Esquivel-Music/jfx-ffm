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
#import "com_sun_glass_events_KeyEvent.h"

#import "GlassMacros.h"
#import "GlassMenu.h"
#import "GlassKey.h"
#import "GlassWindow+Java.h"
#import "glass_mac_api.h"
#import "glass_mac_internal.h"

#pragma clang diagnostic ignored "-Wdeprecated-declarations"

//#define VERBOSE
#ifndef VERBOSE
    #define LOG(MSG, ...)
#else
    #define LOG(MSG, ...) GLASS_LOG(MSG, ## __VA_ARGS__);
#endif

@interface NSMenuItem (SPI)

// Apple's SPI
- setAppleMenu:(NSMenuItem*)item;

@end

static NSString *menuKeyEquivalent(uint16_t shortcut, int32_t modifiers, NSUInteger *outModifierMask)
{
    NSString *keyEquivalent = GetStringForJavaKey(shortcut);
    LOG("_setShortcut %c -> %s", shortcut, [keyEquivalent UTF8String]);

    NSUInteger modifierMask = 0;
    if ((modifiers & com_sun_glass_events_KeyEvent_MODIFIER_COMMAND) != 0)
    {
        modifierMask = modifierMask | NSCommandKeyMask;
    }
    if ((modifiers & com_sun_glass_events_KeyEvent_MODIFIER_SHIFT) != 0)
    {
        modifierMask = modifierMask | NSShiftKeyMask;
    }
    if ((modifiers & com_sun_glass_events_KeyEvent_MODIFIER_CONTROL) != 0)
    {
        modifierMask = modifierMask | NSControlKeyMask;
    }
    if ((modifiers & com_sun_glass_events_KeyEvent_MODIFIER_OPTION) != 0)
    {
        modifierMask = modifierMask | NSAlternateKeyMask;
    }
    if ((modifiers & com_sun_glass_events_KeyEvent_MODIFIER_FUNCTION) != 0)
    {
        if (shortcut >= com_sun_glass_events_KeyEvent_VK_F1 &&
            shortcut <= com_sun_glass_events_KeyEvent_VK_F12) {
            int delta = shortcut - com_sun_glass_events_KeyEvent_VK_F1;
            keyEquivalent = [NSString stringWithFormat:@"%C", (unsigned short)(NSF1FunctionKey + delta)];
        } else if (shortcut >= com_sun_glass_events_KeyEvent_VK_F13 &&
                   shortcut <= com_sun_glass_events_KeyEvent_VK_F24) {
            int delta = shortcut - com_sun_glass_events_KeyEvent_VK_F13;
            keyEquivalent = [NSString stringWithFormat:@"%C", (unsigned short)(NSF13FunctionKey + delta)];
        }
    }
    *outModifierMask = modifierMask;
    return keyEquivalent;
}

@implementation GlassMenubar

- (id)init
{
    self = [super init];
    if (self != nil)
    {
        self->menu = [[NSMenu allocWithZone:NSDefaultMallocZone()] initWithTitle:@"Menubar"];
    }
    return self;
}

- (void)dealloc
{
    LOG("GlassMenubar dealloc: %p", self);
    [super dealloc];
}

@end

@implementation GlassMenu

- (id)initMenuWithId:(int64_t)identifier title:(NSString *)title enabled:(BOOL)enabled
{
    self = [super init];
    if (self != nil)
    {
        // Prints what the JNI printed on a detached main thread, before it crashed on the NULL JNI environment.
        GLASS_WARN_IF_MAIN_JAVA_DETACHED;
        self->menuId = identifier;
        LOG("initMenuWithId: %lld title %s", identifier, [title UTF8String]);

        self->item = [[NSMenuItem alloc] initWithTitle:title
                                                action:NULL
                                         keyEquivalent:@""];
        [self->item setEnabled:enabled];
        [self->item setTarget:self];

        self->menu = [[NSMenu alloc] initWithTitle:[self->item title]];
        [self->menu setDelegate: self];
    }
    return self;
}

- (id)initItemWithId:(int64_t)identifier title:(NSString *)title shortcut:(uint16_t)shortcut
           modifiers:(int32_t)modifiers icon:(const GmacMenuImage *)icon enabled:(BOOL)enabled
             checked:(BOOL)checked
{
    self = [super init];
    if (self != nil)
    {
        // Prints what the JNI printed on a detached main thread, before it crashed on the NULL JNI environment.
        GLASS_WARN_IF_MAIN_JAVA_DETACHED;
        self->menuId = identifier;

        NSString *keyEquivalent = @"";
        LOG("initItemWithId: %lld title %s", identifier, [title UTF8String]);

        self->item = [[NSMenuItem alloc] initWithTitle:title
                                                action:@selector(action:)
                                         keyEquivalent:keyEquivalent];
        if (shortcut != '\0')
        {
            [self _setShortcut:shortcut modifiers:modifiers];
        }
        [self->item setEnabled:enabled];
        [self _setChecked:checked];

        if (icon != NULL) {
            [self _setImage:icon];
        }
        [self->item setTarget:self];
    }
    return self;
}

- (void)dealloc
{
    assert(pthread_main_np() == 1);

    const GmacMenuCallbacks *callbacks = GlassMenuCallbacks();
    if (callbacks != NULL && callbacks->menu_disposed != NULL && GlassIsMainJavaAttached())
    {
        callbacks->menu_disposed(self->menuId);
    }

    [self->item release];

    [super dealloc];
}

- (void)action:(id)sender
{
    const GmacMenuCallbacks *callbacks = GlassMenuCallbacks();
    if (callbacks != NULL)
    {
        // The JNI printed this only for an item with a callback, which only Java knows of now.
        GLASS_WARN_IF_MAIN_JAVA_DETACHED;
        if (callbacks->action != NULL && GlassIsMainJavaAttached())
        {
            callbacks->action(self->menuId);
        }
    }
}

// JDK-8096139: do not use menuNeedsUpdate here, even though Cocoa prohibits
// changing the menu structure during menuWillOpen...
- (void)menuWillOpen: (NSMenu *)_menu
{
    if ([GlassWindow _hasGrab]) {
        // If an auto-hide popup window is showing, it has an active grab.
        // In this case, we close the popup instead of opening the menu:
        [_menu cancelTrackingWithoutAnimation];

        // _resetGrab fires FOCUS_UNGRAB on the popup's owner window, which
        // will trigger doAutoHide() -> hide() on the popup.
        [GlassWindow _resetGrab];
        return;
    }

    GLASS_WARN_IF_MAIN_JAVA_DETACHED;
    const GmacMenuCallbacks *callbacks = GlassMenuCallbacks();
    if (callbacks != NULL && callbacks->menu_opening != NULL && GlassIsMainJavaAttached())
    {
        callbacks->menu_opening(self->menuId);
    }
}

- (void)menuDidClose: (NSMenu *)menu
{
    GLASS_WARN_IF_MAIN_JAVA_DETACHED;
    const GmacMenuCallbacks *callbacks = GlassMenuCallbacks();
    if (callbacks != NULL && callbacks->menu_closed != NULL && GlassIsMainJavaAttached())
    {
        callbacks->menu_closed(self->menuId);
    }
}

#pragma mark NSMenuValidation

- (BOOL)validateMenuItem:(NSMenuItem *)menuItem
{
    LOG("validateMenuItem: %s action: %p", [[menuItem title] UTF8String], [menuItem action]);
    GLASS_WARN_IF_MAIN_JAVA_DETACHED;
    const GmacMenuCallbacks *callbacks = GlassMenuCallbacks();
    if (callbacks == NULL || !GlassIsMainJavaAttached())
    {
        return YES;
    }
    GlassMenu *glassTargetItem = (GlassMenu *)[menuItem target];
    if (callbacks->validate != NULL)
    {
        callbacks->validate(self->menuId);
    }
    return ([glassTargetItem->item isEnabled]);
}

- (void)_setShortcut:(uint16_t)shortcut modifiers:(int32_t)modifiers
{
    NSUInteger modifierMask;
    NSString *keyEquivalent = menuKeyEquivalent(shortcut, modifiers, &modifierMask);
    [self->item setKeyEquivalent:keyEquivalent];
    [self->item setKeyEquivalentModifierMask:modifierMask];
}

- (void)_setChecked:(BOOL)checked
{
    [self->item setState:(checked ? NSOnState : NSOffState)];
}

- (void)_setImage:(const GmacMenuImage *)icon
{
    // Prints what the JNI printed on a detached main thread, before it crashed on the NULL JNI environment for an icon.
    GLASS_WARN_IF_MAIN_JAVA_DETACHED;
    if (icon != NULL)
    {
        NSImage *image = (NSImage *)icon->image;
        if (image != NULL)
        {
            if ((icon->scale_x > 1) && (icon->scale_y > 1) && (icon->width > 0) && (icon->height > 0)) {
                NSSize imgSize = {icon->width / icon->scale_x, icon->height / icon->scale_y};
                [image setSize: imgSize];
            }
            [self->item setImage: image];
            [image release];
        }
    }
    else
    {
        [self->item setImage: nil];
    }
}

@end

#pragma mark --- C ABI

void *gmac_menubar_create(void)
{
    void *menubar = NULL;

    GLASS_POOL_ENTER;
    {
        menubar = [[GlassMenubar alloc] init];
    }
    GLASS_POOL_EXIT;

    return menubar;
}

void gmac_menubar_insert(void *menubarPtr, void *menuPtr, int32_t pos)
{
    LOG("gmac_menubar_insert");

    GLASS_POOL_ENTER;
    {
        GlassMenubar *menubar = (GlassMenubar *)menubarPtr;
        GlassMenu *glassmenu = (GlassMenu *)menuPtr;

        [menubar->menu insertItem:glassmenu->item atIndex:pos];
        [menubar->menu setSubmenu:glassmenu->menu forItem:glassmenu->item];

        [glassmenu->menu setAutoenablesItems:YES];

        if ([[glassmenu->item title] compare:@"Apple"] == NSOrderedSame)
        {
            LOG("calling setAppleMenu");
            [NSApp performSelector:@selector(setAppleMenu:) withObject:glassmenu->item];
        }

        [[NSApp mainMenu] update];
    }
    GLASS_POOL_EXIT;
}

void gmac_menubar_remove(void *menubarPtr, void *menuPtr, int32_t pos)
{
    LOG("gmac_menubar_remove mb %p mp %p pos %d", menubarPtr, menuPtr, pos);

    GLASS_POOL_ENTER;
    {
        GlassMenubar *menubar = (GlassMenubar *)menubarPtr;
        GlassMenu *glassmenu = (GlassMenu *)menuPtr;
        if ([menubar->menu indexOfItem: glassmenu->item] != -1) {
            [menubar->menu removeItem:glassmenu->item];
            [glassmenu release];
        }
        [[NSApp mainMenu] update];
    }
    GLASS_POOL_EXIT;
}

void *gmac_menu_create(int64_t menu_id, const uint16_t *title, int32_t title_length, int32_t enabled)
{
    void *menu = NULL;

    GLASS_POOL_ENTER;
    {
        menu = [[GlassMenu alloc] initMenuWithId:menu_id
                                           title:GlassNSStringOrEmpty(title, title_length)
                                         enabled:(enabled != 0)];
    }
    GLASS_POOL_EXIT;

    LOG("gmac_menu_create id %lld --> %p", menu_id, menu);
    return menu;
}

void *gmac_menu_item_create(int64_t menu_id, const uint16_t *title, int32_t title_length, uint16_t shortcut,
                            int32_t modifiers, const GmacMenuImage *icon, int32_t enabled, int32_t checked)
{
    void *menuItem = NULL;

    GLASS_POOL_ENTER;
    {
        menuItem = [[GlassMenu alloc] initItemWithId:menu_id
                                               title:GlassNSStringOrEmpty(title, title_length)
                                            shortcut:shortcut
                                           modifiers:modifiers
                                                icon:icon
                                             enabled:(enabled != 0)
                                             checked:(checked != 0)];
    }
    GLASS_POOL_EXIT;

    LOG("gmac_menu_item_create id %lld --> %p", menu_id, menuItem);
    return menuItem;
}

void gmac_menu_insert(void *menuPtr, void *submenuPtr, int32_t pos)
{
    LOG("gmac_menu_insert mp %p smp %p pos %d", menuPtr, submenuPtr, pos);

    GLASS_POOL_ENTER;
    {
        GlassMenu *menu = (GlassMenu *)menuPtr;

        if (submenuPtr != NULL)
        {
            GlassMenu *submenu = (GlassMenu *)submenuPtr;
            [menu->menu insertItem:submenu->item atIndex:pos];
            [menu->menu setSubmenu:submenu->menu forItem:submenu->item];

            [submenu->menu setAutoenablesItems:YES];
        }
        else
        {
            [menu->menu addItem:[NSMenuItem separatorItem]];
        }
    }
    GLASS_POOL_EXIT;
}

void gmac_menu_remove(void *menuPtr, void *submenuPtr, int32_t pos)
{
    LOG("gmac_menu_remove mp %p smp %p pos %d", menuPtr, submenuPtr, pos);

    GLASS_POOL_ENTER;
    {
        GlassMenu *menu = (GlassMenu *)menuPtr;
        if (submenuPtr != NULL)
        {
            GlassMenu *submenu = (GlassMenu *)submenuPtr;
            LOG("gmac_menu_remove: submenu %p subitem %p subindex %d",
                submenu, submenu->item, [menu->menu indexOfItem: submenu->item]);

            if ([menu->menu indexOfItem: submenu->item] != -1) {
                [menu->menu removeItem:submenu->item];
                [submenu release];
            }
        }
        else
        {
            LOG("gmac_menu_remove: at index %d", pos);
            [menu->menu removeItemAtIndex:pos];
        }
        [[NSApp mainMenu] update];
    }
    GLASS_POOL_EXIT;
}

void gmac_menu_set_title(void *menuPtr, const uint16_t *title, int32_t title_length)
{
    LOG("gmac_menu_set_title");

    GLASS_POOL_ENTER;
    {
        GlassMenu *menu = (GlassMenu *)menuPtr;
        NSInteger index = [[NSApp mainMenu] indexOfItem: menu->item];
        NSString *string = GlassNSStringOrEmpty(title, title_length);
        if (index != -1) {
            [[[[NSApp mainMenu] itemAtIndex:index] submenu] setTitle: string];
        } else {
            [menu->item setTitle:string];
        }
    }
    GLASS_POOL_EXIT;
}

void gmac_menu_set_shortcut(void *menuPtr, uint16_t shortcut, int32_t modifiers)
{
    LOG("gmac_menu_set_shortcut");

    GLASS_POOL_ENTER;
    {
        GlassMenu *menu = (GlassMenu *)menuPtr;
        [menu _setShortcut:shortcut modifiers:modifiers];
    }
    GLASS_POOL_EXIT;
}

void gmac_menu_set_image(void *menuPtr, const GmacMenuImage *icon)
{
    LOG("gmac_menu_set_image");

    GLASS_POOL_ENTER;
    {
        GlassMenu *menu = (GlassMenu *)menuPtr;
        if (menu != nil)
        {
            [menu _setImage:icon];
        }
        else if (icon != NULL)
        {
            // Java makes the image before the call, where the JNI made none for a NULL menu.
            [(NSImage *)icon->image release];
        }
    }
    GLASS_POOL_EXIT;
}

void gmac_menu_set_enabled(void *menuPtr, int32_t enabled)
{
    LOG("gmac_menu_set_enabled");

    GLASS_POOL_ENTER;
    {
        GlassMenu *menu = (GlassMenu *)menuPtr;
        [menu->item setEnabled:(enabled != 0)];
    }
    GLASS_POOL_EXIT;
}

void gmac_menu_set_checked(void *menuPtr, int32_t checked)
{
    LOG("gmac_menu_set_checked");

    GLASS_POOL_ENTER;
    {
        GlassMenu *menu = (GlassMenu *)menuPtr;
        [menu _setChecked:(checked != 0)];
    }
    GLASS_POOL_EXIT;
}

int32_t gmac_test_menu_key_equivalent(uint16_t shortcut, int32_t modifiers, uint16_t *out, int32_t capacity,
                                      uint64_t *out_mask)
{
    int32_t length;

    GLASS_POOL_ENTER;
    {
        NSUInteger modifierMask;
        NSString *keyEquivalent = menuKeyEquivalent(shortcut, modifiers, &modifierMask);
        NSUInteger count = capacity > 0 ? MIN([keyEquivalent length], (NSUInteger)capacity) : 0;
        if (count > 0)
        {
            [keyEquivalent getCharacters:out range:NSMakeRange(0, count)];
        }
        length = (int32_t)[keyEquivalent length];
        *out_mask = (uint64_t)modifierMask;
    }
    GLASS_POOL_EXIT;

    return length;
}
