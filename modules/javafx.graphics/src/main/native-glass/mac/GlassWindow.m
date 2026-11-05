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
#import "com_sun_glass_events_ViewEvent.h"
#import "com_sun_glass_events_WindowEvent.h"
#import "com_sun_glass_ui_Window.h"
#import "com_sun_glass_ui_Window_Level.h"

#import "GlassMacros.h"
#import "GlassWindow.h"
#import "GlassWindow+Java.h"
#import "GlassWindow+Overrides.h"
#import "GlassView.h"
#import "GlassScreen.h"
#import "GlassApplication.h"
#import "glass_mac_api.h"
#import "glass_mac_internal.h"

#pragma clang diagnostic ignored "-Wdeprecated-declarations"

//#include <stdio.h>
//#include <stdarg.h>
//
//void glass_logf(char *fmt, ...) {
//    va_list argp;
//    va_start(argp, fmt);
//    FILE *f = fopen("/tmp/glass.log", "a");
//    vfprintf(f, fmt, argp);
//    fclose(f);
//}

//#define VERBOSE
#ifndef VERBOSE
    #define LOG(MSG, ...)
#else
    #define LOG(MSG, ...) GLASS_LOG(MSG, ## __VA_ARGS__);
#endif

#pragma mark --- Internal utilities

static inline GlassWindow *getGlassWindow(void *ptr)
{
    assert(ptr != NULL);

    NSWindow * nsWindow = (NSWindow*)ptr;
    return (GlassWindow*)[nsWindow delegate];
}

// --------------------------------------------------------------------------------------
// NSWindow/NSPanel descendants implementation
#define GLASS_NS_WINDOW_IMPLEMENTATION                                                  \
- (id)initWithDelegate:(GlassWindow*)delegate                                           \
             frameRect:(NSRect)rect                                                     \
             styleMask:(NSUInteger)styleMask                                            \
                screen:(NSScreen*)screen                                                \
{                                                                                       \
    self->gWindow = delegate; /* must be done before calling [super init...] */         \
    self = [super initWithContentRect:rect                                              \
                            styleMask:styleMask                                         \
                              backing:NSBackingStoreBuffered                            \
                                defer:NO                                                \
                               screen:screen];                                          \
                                                                                        \
    if (self == nil) {                                                                  \
        return nil;                                                                     \
    }                                                                                   \
                                                                                        \
    [self setDelegate:delegate];                                                        \
    [self setAcceptsMouseMovedEvents:NO];                                               \
    [self setShowsResizeIndicator:NO];                                                  \
    [self setAllowsConcurrentViewDrawing:YES];                                          \
                                                                                        \
    [self setReleasedWhenClosed:YES];                                                   \
                                                                                        \
    return self;                                                                        \
}                                                                                       \
                                                                                        \
- (void)dealloc                                                                         \
{                                                                                       \
    id window = self->gWindow;                                                          \
    LOG("dealloc window: %p", window);                                                  \
    [super dealloc];                                                                    \
    [window release];                                                                   \
}                                                                                       \
                                                                                        \
- (void)close                                                                           \
{                                                                                       \
    self->gWindow->isClosed = YES;                                                      \
    [self->gWindow close];                                                              \
    LOG("gWindow close: %p", self->gWindow);                                            \
    [super close];                                                                      \
    LOG("super close");                                                                 \
}                                                                                       \
/* super calls NSWindow on the next run-loop pass when NSWindow could be released */    \
- (BOOL)performKeyEquivalent:(NSEvent *)theEvent                                        \
{                                                                                       \
    [self retain];                                                                      \
    BOOL result = [super performKeyEquivalent:theEvent];                                \
    result = result || self->gWindow->isClosed;                                         \
    [self release];                                                                     \
    return result;                                                                      \
}                                                                                       \
- (BOOL)canBecomeMainWindow                                                             \
{                                                                                       \
    return [self->gWindow canBecomeMainWindow];                                         \
}                                                                                       \
- (BOOL)canBecomeKeyWindow                                                              \
{                                                                                       \
    return [self->gWindow canBecomeKeyWindow];                                          \
}                                                                                       \
- (void)setHidesOnDeactivate:(BOOL)hideOnDeactivate                                     \
{                                                                                       \
    [super setHidesOnDeactivate:NO];                                                    \
}                                                                                       \
- (BOOL)hidesOnDeactivate                                                               \
{                                                                                       \
    return [self->gWindow hidesOnDeactivate];                                           \
}                                                                                       \
- (BOOL)worksWhenModal                                                                  \
{                                                                                       \
    return [self->gWindow worksWhenModal];                                              \
}                                                                                       \
- (void)setBackgroundColor:(NSColor *)color                                             \
{                                                                                       \
    [super setBackgroundColor:[self->gWindow setBackgroundColor:color]];                \
}                                                                                       \
- (NSButton *)standardWindowButton:(NSWindowButton)type                                 \
{                                                                                       \
    NSButton* button = [super standardWindowButton:type];                               \
    switch (type)                                                                       \
    {                                                                                   \
        case NSWindowDocumentIconButton:                                                \
            [button setAcceptsTouchEvents:NO];                                          \
            [button setAction:nil];                                                     \
            [button setEnabled:NO];                                                     \
            break;                                                                      \
        default:                                                                        \
            break;                                                                      \
    }                                                                                   \
    return button;                                                                      \
}                                                                                       \
- (void)sendEvent:(NSEvent *)event                                                      \
{                                                                                       \
    /* Local copy of the id keeps the retain/release calls balanced. */                 \
    id view = [self->gWindow->view retain];                                             \
    [self->gWindow sendEvent:event];                                                    \
    [super sendEvent:event];                                                            \
    [view release];                                                                     \
}

@implementation GlassWindow_Normal
GLASS_NS_WINDOW_IMPLEMENTATION

-(NSRect)windowWillUseStandardFrame:(NSWindow*)window defaultFrame:(NSRect)newFrame
{
    // For windows without titlebars the OS will suggest a frame that's the
    // same size as the existing window, not the screen.
    if (window.screen != nil && (window.styleMask & NSWindowStyleMaskTitled) == 0) {
        return window.screen.visibleFrame;
    }
    return newFrame;
}

-(BOOL)isZoomed
{
    // Ensure the window is not reported as maximized during initialization
    return self.screen != nil && [super isZoomed];
}
@end

@implementation GlassWindow_Panel
GLASS_NS_WINDOW_IMPLEMENTATION

- (void)setWorksWhenModal:(BOOL)worksWhenModal
{
    [super setWorksWhenModal:NO];
}

- (BOOL)accessibilityIsIgnored
{
    /* In JavaFX NSPanels are used to implement PopupWindows,
     * which are used by ContextMenu.  In Accessibility, for a
     * menu to work as expected, the window has to be ignored.
     * Note that asking the children of the window is
     * very important in this context. It ensures that all
     * descendants created.  Without it, the menu  will
     * not be seen by the assistive technology.
     */
    __block BOOL ignored = [super accessibilityIsIgnored];
    NSArray* children = [self accessibilityAttributeValue: NSAccessibilityChildrenAttribute];
    if (children) {
        [children enumerateObjectsUsingBlock: ^(id child, NSUInteger index, BOOL *stop) {
            NSString* role = [child accessibilityAttributeValue: NSAccessibilityRoleAttribute];
            if ([NSAccessibilityMenuRole isEqualToString: role]) {
                ignored = YES;
                *stop = YES;
            }
            /* Tooltips are exposed by AXHelp attribute and there is no API in Mac
             * to represent the tooltip window.
             * Nonetheless, the window must be ignored to prevent interfering with
             * VoiceOver focus.
             */
            if ([@"AXJFXTOOLTIP" isEqualToString: role]) {
                ignored = YES;
                *stop = YES;
            }
        }];
    }
    return ignored;
}

- (id)accessibilityAttributeValue:(NSString *)attribute
{
    /*
    * The default value of AXRoleDescription for a NSPanel is 'system dialog'.
    * While this is correct for an average cocoa application it is not appropriate
    * for JFX, where all NSPanels are decoration-less windows used to implement
    * tooltip, context menus, combo boxes, etc.
    */
    if ([NSAccessibilityRoleDescriptionAttribute isEqualToString: attribute]) {
        return @"";
    }
    return [super accessibilityAttributeValue: attribute];
}

@end
// --------------------------------------------------------------------------------------


@implementation GlassWindow

- (void)close
{
    [self _ungrabFocus];
}

- (void)sendEvent:(NSEvent *)event
{
    if ([event type] == NSEventTypeLeftMouseDown || [event type] == NSEventTypeRightMouseDown || [event type] == NSEventTypeOtherMouseDown)
    {
        NSPoint p = [NSEvent mouseLocation];
        NSRect frame = [self->nsWindow frame];
        NSRect contentRect = [self->nsWindow contentRectForFrameRect:frame];

        if (p.y >= (frame.origin.y + contentRect.size.height))
        {
            // Click to the titlebar
            [self _ungrabFocus];
        }

        [self _checkUngrab];
        [self reorderChildWindows];
    }
}

// Window vs Panel API
- (BOOL)canBecomeMainWindow
{
    if (!self->isEnabled)
    {
        // We'll send FOCUS_DISABLED
        return YES;
    }
    return self->isFocusable;
}

// Window vs Panel API
- (BOOL)hidesOnDeactivate
{
    return NO;
}

// Window vs Panel API
- (BOOL)worksWhenModal
{
    return NO;
}

- (BOOL)canBecomeKeyWindow
{
    if (!self->isEnabled)
    {
        // We'll send FOCUS_DISABLED
        return YES;
    }
    return self->isFocusable;
}

- (void) addChildWindow:(GlassWindow*)childWindow
{
    LOG("addChildWindow: %p  child: %p", self, childWindow);
    if (self->childWindows == nil) {
        self->childWindows = [[NSMutableArray alloc] init];
    }
    [self->childWindows addObject:childWindow];
}

- (void) removeChildWindow:(GlassWindow*)childWindow
{
    LOG("removeChildWindow: %p  child: %p", self, childWindow);
    if (self->childWindows != nil) {
        [self->childWindows removeObject:childWindow];
    }
}

//
// Recursively orders all child windows so that they are above their owner.
//
- (void) reorderChildWindows
{
    LOG("reorderChildWindows: %p", self);
    if ((self->childWindows != nil) && (!nsWindow.isMiniaturized)) {
        for (GlassWindow *child in self->childWindows)
        {
            // Owned windows must set their level to at least the level of their owner
            NSWindowLevel level = MAX(child->prefLevel, [self->nsWindow level]);
            [child->nsWindow setLevel:level];
            // Order child above the owner window
            if (child->nsWindow.isVisible) {
                [child->nsWindow orderWindow:NSWindowAbove relativeTo:[self->nsWindow windowNumber]];
            }
            [child reorderChildWindows];
        }
    }
}

//
// Recursively minimize or unminimize all child windows.
//
- (void) minimizeChildWindows:(BOOL)minimize
{
    LOG("minimizeChildWindows: %p  minimize:%d", self, minimize);
    if (self->childWindows != nil) {
        for (GlassWindow *child in self->childWindows)
        {
            if (minimize) {
                [child->nsWindow orderOut:child];
            } else {
                [child->nsWindow orderFront:child];
            }

            [child minimizeChildWindows:minimize];
        }
    }
}

//
// Recursively set the collection behavior for the child windows to enable or
// disable the "move to active space" behavior. This is used when the owner
// enters full-screen, so that the children will follow the owner to the
// full-screen space. It is called with "true" when first entering full screen
// and "false" once the transition to full-screen is complete.
//
- (void) setMoveToActiveSpaceChildWindows:(BOOL)moveToActiveSpace
{
    LOG("setMoveToActiveSpaceChildWindows: %p  moveToActiveSpace:%d", self, moveToActiveSpace);
    if (self->childWindows != nil) {
        for (GlassWindow *child in self->childWindows)
        {
            NSWindowCollectionBehavior behavior = [child->nsWindow collectionBehavior];
            if (moveToActiveSpace) {
                behavior |= NSWindowCollectionBehaviorMoveToActiveSpace;
            } else {
                behavior &= ~NSWindowCollectionBehaviorMoveToActiveSpace;
            }
            [child->nsWindow setCollectionBehavior: behavior];

            [child setMoveToActiveSpaceChildWindows:moveToActiveSpace];
        }
    }
}

- (NSColor*)setBackgroundColor:(NSColor *)color
{
    if (self->isTransparent == NO)
    {
        // allow color if we're opaque
        return color;
    }
    else
    {
        // for transparent window, ignore and set to clear color
        // FIXME: do we want to store the background color in case we switch to non-transparent mode?
        return [NSColor clearColor];
    }
}


@end

#pragma mark --- Dispatcher

// TODO: re-implement using Obj-C blocks ?
static void *createWindow(int64_t windowId, void *ownerPtr, void *screenPtr, int32_t windowMask)
{
    GlassWindow *window = nil;

    NSAutoreleasePool *pool = [[NSAutoreleasePool alloc] init];
    {
        bool isTitled = (windowMask & com_sun_glass_ui_Window_TITLED) != 0;
        bool isClosable = (windowMask & com_sun_glass_ui_Window_CLOSABLE) != 0;
        bool isMinimizable = (windowMask & com_sun_glass_ui_Window_MINIMIZABLE) != 0;
        bool isMaximizable = (windowMask & com_sun_glass_ui_Window_MAXIMIZABLE) != 0;
        bool isTransparent = (windowMask & com_sun_glass_ui_Window_TRANSPARENT) != 0;
        bool isUtility = (windowMask & com_sun_glass_ui_Window_UTILITY) != 0;
        bool isPopup = (windowMask & com_sun_glass_ui_Window_POPUP) != 0;
        bool isUnified = (windowMask & com_sun_glass_ui_Window_UNIFIED) != 0;
        bool isExtended = (windowMask & com_sun_glass_ui_Window_EXTENDED) != 0;
        bool isDarkFrame = (windowMask & com_sun_glass_ui_Window_DARK_FRAME) != 0;

        NSUInteger styleMask = NSWindowStyleMaskBorderless;
        // only titled windows get title
        if (isTitled)
        {
            styleMask = styleMask|NSWindowStyleMaskTitled;
        }

        // only nontransparent windows get decorations
        if (!isTransparent)
        {
            if (isClosable)
            {
                styleMask = styleMask|NSWindowStyleMaskClosable;
            }

            if (isMinimizable || isMaximizable)
            {
                // on Mac OS X there is one set for min/max buttons,
                // so if clients requests either one, we turn them both on
                styleMask = styleMask|NSWindowStyleMaskMiniaturizable;
            }

            if (isExtended) {
                styleMask = styleMask | NSWindowStyleMaskTitled | NSWindowStyleMaskFullSizeContentView;
            }

            if (isUnified) {
                styleMask = styleMask|NSWindowStyleMaskTexturedBackground;
            }

            if (isUtility)
            {
                styleMask = styleMask | NSWindowStyleMaskNonactivatingPanel;

                // The NSWindowStyleMaskUtilityWindow style makes the close button appear very small (because the
                // title bar is thinner than normal). This doesn't work well with client-side title bars in extended
                // windows: the point of a client-side title bar is its ability to host custom controls, so it can't
                // be very thin. We therefore only add this style for non-extended windows.
                if (!isExtended) {
                    styleMask |= NSWindowStyleMaskUtilityWindow;
                }
            }
        }

        if (isPopup)
        {
            // can receive keyboard input without activating the owning application
            styleMask = styleMask|NSWindowStyleMaskNonactivatingPanel;
        }

        // initial size must be 0x0 otherwise we don't get resize update if the initial size happens to be the exact same size as the later programatical one!
        CGFloat x = 0.0f;
        CGFloat y = 0.0f;
        CGFloat w = 0.0f;
        CGFloat h = 0.0f;

        NSScreen *screen = (NSScreen*)screenPtr;
        window = [[GlassWindow alloc] _initWithContentRect:NSMakeRect(x, y, w, h) styleMask:styleMask screen:screen
                                                  windowId:windowId];
        window->isStandardButtonsVisible = YES;

        if (isExtended) {
            [window->nsWindow setTitleVisibility:NSWindowTitleHidden];
            [window->nsWindow setTitlebarAppearsTransparent:YES];
            [window->nsWindow setToolbar:[NSToolbar new]];
        }

        if (isUnified) {
            //Prevent the textured effect from disappearing on border thickness recalculation
            [window->nsWindow setAutorecalculatesContentBorderThickness:NO forEdge:NSMaxYEdge];
        }

        if (isUtility) {
            // When we hide the standard window buttons, they are still part of the button group that activates
            // the hover appearance (the icons inside the buttons) when the cursor is over any of the buttons.
            // This leads to the close button receiving the hover appearance when the mouse cursor is over one
            // of the hidden buttons. Setting the hidden buttons' frame to an empty rectangle fixes this.
            [[window->nsWindow standardWindowButton:NSWindowMiniaturizeButton] setFrame:CGRectMake(0, 0, 0, 0)];
            [[window->nsWindow standardWindowButton:NSWindowZoomButton] setFrame:CGRectMake(0, 0, 0, 0)];

            [window->nsWindow setLevel:NSNormalWindowLevel];
        }

        window->prefLevel = [window->nsWindow level];
        if (ownerPtr != NULL)
        {
            // Get owner glass window and add this window as a child window
            window->owner = getGlassWindow(ownerPtr);
            [window->owner addChildWindow:window];

            // Owned windows must set their level to at least the level of their owner
            NSWindowLevel level = MAX(window->prefLevel, [window->owner->nsWindow level]);
            [window->nsWindow setLevel:level];
        }

        /* 10.7 full screen window support */
        if ([NSWindow instancesRespondToSelector:@selector(toggleFullScreen:)]) {
            NSWindowCollectionBehavior behavior = [window->nsWindow collectionBehavior];
            if (!isPopup && !isUtility && !window->owner)
            {
                // Only ownerless windows should have the Full Screen Toggle control
                behavior |= (1 << 7) /* NSWindowCollectionBehaviorFullScreenPrimary */;
            }
            else
            {
                // Other windows are only allowed to be shown together with a primary
                // full screen window
                behavior |= (1 << 8) /* NSWindowCollectionBehaviorFullScreenAuxiliary */;
            }
            [window->nsWindow setCollectionBehavior: behavior];
        }

        if (isTransparent)
        {
            [window->nsWindow setBackgroundColor:[NSColor clearColor]];
            [window->nsWindow setHasShadow:NO];
            [window->nsWindow setOpaque:NO];
        }
        else
        {
            [window->nsWindow setHasShadow:YES];
            [window->nsWindow setOpaque:YES];
        }

        window->isDecorated = isTitled || isExtended;
        window->isExtended = isExtended;
        window->isTransparent = isTransparent;
        window->isSizeAssigned = NO;
        window->isLocationAssigned = NO;
        window->isResizable = NO;
        [window _setResizable:NO]; // actual value will be set later with a separate downcall
        [window->nsWindow setAppearance:isDarkFrame
            ? [NSAppearance appearanceNamed:NSAppearanceNameDarkAqua]
            : [NSAppearance appearanceNamed:NSAppearanceNameAqua]];
    }
    [pool drain];

    return window->nsWindow;
}

void *gmac_window_create(void *owner, void *screen, int32_t style_mask, int64_t window_id)
{
    void *window = NULL;

    GLASS_POOL_ENTER;
    {
        window = createWindow(window_id, owner, screen, style_mask);
    }
    GLASS_POOL_EXIT;

    return window;
}

void gmac_window_set_level(void *ptr, int32_t javaLevel)
{
    if (!ptr) return;

    GLASS_POOL_ENTER;
    {
        GlassWindow *window = getGlassWindow(ptr);
        NSInteger level = NSNormalWindowLevel;
        switch (javaLevel)
        {
            case com_sun_glass_ui_Window_Level_FLOATING:
                level = NSFloatingWindowLevel;
                break;
            case com_sun_glass_ui_Window_Level_TOPMOST:
                level = NSScreenSaverWindowLevel;
                break;
        }
        window->prefLevel = level; // Save preferred level
        if (window->owner != nil) {
            // Owned windows must set their level to at least the level of their owner
            level = MAX(level, [window->owner->nsWindow level]);
        }
        [window->nsWindow setLevel:level];
        [window reorderChildWindows];
    }
    GLASS_POOL_EXIT;
}

void gmac_window_set_focusable(void *ptr, int32_t isFocusable)
{
    if (!ptr) return;

    GLASS_POOL_ENTER;
    {
        GlassWindow *window = getGlassWindow(ptr);
        window->isFocusable = (isFocusable != 0);
    }
    GLASS_POOL_EXIT;
}

void gmac_window_set_enabled(void *ptr, int32_t isEnabled)
{
    if (!ptr) return;

    GLASS_POOL_ENTER;
    {
        GlassWindow *window = getGlassWindow(ptr);
        window->isEnabled = (isEnabled != 0);
        NSButton *zoomButton = [window->nsWindow standardWindowButton:NSWindowZoomButton];
        if ((window->isEnabled) && (window->isResizable)){
            [window->nsWindow setStyleMask: window->enabledStyleMask];
            if (window->enabledStyleMask & NSWindowStyleMaskResizable) {
                [zoomButton setEnabled:YES];
            }
        }
        else if((window->isEnabled) && (!window->isResizable)){
            [window->nsWindow setStyleMask:
                (window->enabledStyleMask & ~(NSUInteger) NSWindowStyleMaskResizable)];
            [zoomButton setEnabled:NO];
        }
        else{
            window->enabledStyleMask = [window->nsWindow styleMask];
            [window->nsWindow setStyleMask:
                (window->enabledStyleMask & ~(NSUInteger)(NSWindowStyleMaskMiniaturizable | NSWindowStyleMaskResizable))];
            [zoomButton setEnabled:NO];
        }
    }
    GLASS_POOL_EXIT;
}

void gmac_window_set_alpha(void *ptr, float alpha)
{
    if (!ptr) return;

    GLASS_POOL_ENTER;
    {
        GlassWindow *window = getGlassWindow(ptr);
        [window->nsWindow setAlphaValue:alpha];
    }
    GLASS_POOL_EXIT;
}

int32_t gmac_window_set_background(void *ptr, float r, float g, float b)
{
    if (!ptr) return 0;

    GLASS_POOL_ENTER;
    {
        GlassWindow *window = getGlassWindow(ptr);
        [window->nsWindow setBackgroundColor:[NSColor colorWithSRGBRed:r green:g blue:b alpha:1.0f]];
    }
    GLASS_POOL_EXIT;

    return 1; // gznote: remove this return value if unused
}

void gmac_window_leave_native_full_screen(void *ptr)
{
    if (!ptr) return;

    GLASS_POOL_ENTER;
    {
        GlassWindow *window = getGlassWindow(ptr);

        // We don't support changing views in the FS mode because
        // by Glass design the FS functionality belongs to the View.
        // Also, this leads to a crash on the Mac
        if ([window->nsWindow styleMask] & (1 << 14)/*NSFullScreenWindowMask*/) {
            [window->nsWindow performSelector:@selector(toggleFullScreen:) withObject:nil];

            // Wait until the FS mode has really exited
            [GlassApplication enterFullScreenExitingLoop];
        }
    }
    GLASS_POOL_EXIT;
}

int32_t gmac_window_set_view(void *ptr, void *view)
{
    if (!ptr) return 0;

    GLASS_POOL_ENTER;
    {
        GlassWindow *window = getGlassWindow(ptr);

        NSView<GlassView> *oldView = window->view;
        window->view = (GlassView3D<GlassView>*)view;
        //NSLog(@"        window: %@", window);
        //NSLog(@"                frame: %.2f,%.2f %.2fx%.2f", [window frame].origin.x, [window frame].origin.y, [window frame].size.width, [window frame].size.height);
        //NSLog(@"        view: %@", window->view);
        //NSLog(@"                frame: %.2f,%.2f %.2fx%.2f", [window->view frame].origin.x, [window->view frame].origin.y, [window->view frame].size.width, [window->view frame].size.height);

        // Make sure we synchronize scale factors to the new view as any
        // dynamic updates might have happened when we had the old view
        // and/or we may have been set visible before we had a view and
        // missed the initial notification.
        if ([window->nsWindow screen]) {
            [window->view notifyScaleFactorChanged:GetScreenScaleFactor([window->nsWindow screen])];
        }

        if (oldView && oldView != window->view) {
            [[oldView delegate] resetMouseTracking];
        }

        if (window->view != nil)
        {
            CALayer *layer = [window->view getLayer];
            LOG("   layer: %p", layer);
            // TODO : Move below logic to CGL specific View/Layer class
            if (([layer.sublayers[0] isKindOfClass:[CAOpenGLLayer class]] == YES) &&
                (([window->nsWindow styleMask] & NSWindowStyleMaskTexturedBackground) == NO))
            {
                [((CAOpenGLLayer*)layer) setOpaque:[window->nsWindow isOpaque]];
            }

            window->suppressWindowMoveEvent = YES; // JDK-8111165
            {
                NSRect viewFrame = [window->view frame];
                if ((viewFrame.size.width != 0.0f) && (viewFrame.size.height != 0.0f))
                {
                    NSRect windowFrame = [window->nsWindow frameRectForContentRect:viewFrame];
                    windowFrame.origin.x = [window->nsWindow frame].origin.x;
                    windowFrame.origin.y = [window->nsWindow frame].origin.y;
                    [window _setWindowFrameWithRect:NSMakeRect(windowFrame.origin.x, windowFrame.origin.y,
                                                               windowFrame.size.width, windowFrame.size.height)
                                        withDisplay:YES withAnimate:NO];
                }

                [window->nsWindow setContentView:[window->view superview]]; // use our superview not ourselves!
                [window->nsWindow setInitialFirstResponder:window->view];
                [window->nsWindow makeFirstResponder:window->view];
            }
            window->suppressWindowMoveEvent = NO;
        }
        else
        {
            // If the contentView is set to nil within performKeyEquivalent: the OS will crash.
            NSView* dummy = [[NSView alloc] initWithFrame: NSMakeRect(0, 0, 10, 10)];
            [window->nsWindow performSelectorOnMainThread:@selector(setContentView:) withObject:dummy waitUntilDone:YES];
            [dummy release];
        }
    }
    GLASS_POOL_EXIT;

    return 1; // gznote: remove this return value if unused
}

int32_t gmac_window_set_menubar(void *ptr, void *menubar)
{
    if (!ptr) return 0;

    GLASS_POOL_ENTER;
    {
        GlassWindow *window = getGlassWindow(ptr);
        window->menubar = (GlassMenubar*)menubar;

        BOOL isEmbedded = [GlassApplication isEmbedded];
        BOOL isKeyWindow = [window->nsWindow isKeyWindow];

        if (!isEmbedded || isKeyWindow) {
            [NSApp setMainMenu:window->menubar->menu];
            [[NSApp mainMenu] update];
        }
    }
    GLASS_POOL_EXIT;

    return 1; // gznote: remove this return value if unused
}

int32_t gmac_window_close(void *ptr)
{
    if (!ptr) return 0;

    GLASS_POOL_ENTER;
    {
        GlassWindow *window = getGlassWindow(ptr);
        // this call will always close the window
        // without calling the windowShouldClose

        // If this window is closed from within performKeyEquivalent the OS might
        // try to send the same event to the new key window. To prevent this we
        // ensure that performKeyEquivalent returns YES.
        window->isClosed = YES;

        // JDK-8095359 When closing a window as the result of a global right-click
        //          mouse event outside the bounds of the window, using an immediate
        //          [window->nsWindow close] crashes the JDK as the AppKit at this
        //          point still has another [NSWindow _resignKeyFocus] from the
        //          right-click handling in [NSApplication sendEvent].  This defers
        //          the close until the [NSWindow _resignKeyFocus] can be performed.

        [window->nsWindow performSelectorOnMainThread:@selector(close) withObject:nil waitUntilDone:NO];

        // The NSWindow will be automatically released after closing
        // The GlassWindow is released in the [NSWindow dealloc] override
    }
    GLASS_POOL_EXIT;

    return 1; // gznote: remove this return value if unused
}

int32_t gmac_window_request_focus(void *ptr)
{
    if (!ptr) return 0;

    int32_t focused = 0;

    GLASS_POOL_ENTER;
    {
        GlassWindow *window = getGlassWindow(ptr);

        if ([window->nsWindow isVisible])
        {
            [window->nsWindow makeMainWindow];
            [window->nsWindow makeKeyAndOrderFront:window->nsWindow];
            [window->nsWindow orderFrontRegardless];
        }

        focused = [window->nsWindow isKeyWindow] ? 1 : 0;
    }
    GLASS_POOL_EXIT;

    return focused;
}

int32_t gmac_window_grab_focus(void *ptr)
{
    if (!ptr) return 0;

    int32_t ret = 0;

    GLASS_POOL_ENTER;
    {
        GlassWindow * window = getGlassWindow(ptr);
        //TODO: full screen
        [window _grabFocus];
        ret = 1;
    }
    GLASS_POOL_EXIT;

    return ret;
}

void gmac_window_ungrab_focus(void *ptr)
{
    if (!ptr) return;

    GLASS_POOL_ENTER;
    {
        GlassWindow * window = getGlassWindow(ptr);
        //TODO; full screen
        [window _ungrabFocus];
    }
    GLASS_POOL_EXIT;
}

int32_t gmac_window_maximize(void *ptr, int32_t maximize, int32_t isZoomed)
{
    if (!ptr) return 0;

    GLASS_POOL_ENTER;
    {
        GlassWindow *window = getGlassWindow(ptr);

        NSRect oldFrame = window->nsWindow.frame;
        int eventType = com_sun_glass_events_WindowEvent_RESTORE;

        window->suppressWindowResizeEvent = YES;
        window->suppressWindowMoveEvent = YES;

        if ((maximize != 0) && (isZoomed == 0))
        {
            window->preZoomedRect = oldFrame;
            eventType = com_sun_glass_events_WindowEvent_MAXIMIZE;

            if ([window->nsWindow styleMask] != NSWindowStyleMaskBorderless)
            {
                [window->nsWindow zoom:nil];
            }
            else
            {
                NSRect visibleRect = [[window _getScreen] visibleFrame];
                [window->nsWindow setFrame:visibleRect display:YES animate:YES];
            }
        }
        else if ((maximize == 0) && (isZoomed != 0))
        {
            // Platform unzooming only works reliably for titled windows. For
            // untitled windows the unzoom location can be wildly off. We want
            // to use platform unzoom when we can since it uses the platform's
            // user state which may be more up-to-date than the preZoomedRect.
            if (window->nsWindow.styleMask & NSWindowStyleMaskTitled) {
                [window->nsWindow zoom:nil];
            } else {
                [window->nsWindow setFrame:window->preZoomedRect display:YES animate:YES];
            }
        }

        window->suppressWindowResizeEvent = NO;
        window->suppressWindowMoveEvent = NO;

        NSRect newFrame = window->nsWindow.frame;
        if (!NSEqualRects(newFrame, oldFrame)) {
            NSRect flipFrame = [window _flipFrame];
            [window _sendJavaWindowMoveEventForFrame:flipFrame];
            [window _sendJavaWindowResizeEvent:eventType forFrame:flipFrame];
        }
    }
    GLASS_POOL_EXIT;

    return 1; // gznote: remove this return value if unused
}

void gmac_window_set_bounds(void *ptr,
                            int32_t x, int32_t y, int32_t xSet, int32_t ySet,
                            int32_t w, int32_t h, int32_t cw, int32_t ch, float xGravity, float yGravity)
{
    if (!ptr) return;

    GLASS_POOL_ENTER;
    {
        GlassWindow *window = getGlassWindow(ptr);
        if (xSet || ySet) window->isLocationAssigned = YES;
        if (w > 0 || h > 0 || cw > 0 || ch > 0) window->isSizeAssigned = YES;
        [window _setBounds:x y:y xSet:(xSet != 0) ySet:(ySet != 0) w:w h:h cw:cw ch:ch];
    }
    GLASS_POOL_EXIT;
}

int32_t gmac_window_set_minimum_size(void *ptr, int32_t width, int32_t height)
{
    if (!ptr) return 0;

    GLASS_POOL_ENTER;
    {
        GlassWindow *window = getGlassWindow(ptr);
        NSSize minSize = NSMakeSize(width, height);

        if (window->isExtended) {
            window->nsWindow.contentMinSize = minSize;
        } else {
            [window->nsWindow setMinSize:minSize];
        }
    }
    GLASS_POOL_EXIT;

    return 1; // gznote: remove this return value if unused
}

int32_t gmac_window_set_maximum_size(void *ptr, int32_t width, int32_t height)
{
    if (!ptr) return 0;

    GLASS_POOL_ENTER;
    {
        GlassWindow *window = getGlassWindow(ptr);
        NSSize maxSize = NSMakeSize(
            width == -1 ? FLT_MAX : (CGFloat)width,
            height == -1 ? FLT_MAX : (CGFloat)height);

        if (window->isExtended) {
            window->nsWindow.contentMaxSize = maxSize;
        } else {
            [window->nsWindow setMaxSize:maxSize];
        }
    }
    GLASS_POOL_EXIT;

    return 1; // gznote: remove this return value if unused
}

int32_t gmac_window_set_resizable(void *ptr, int32_t resizable)
{
    if (!ptr) return 0;

    GLASS_POOL_ENTER;
    {
        GlassWindow *window = getGlassWindow(ptr);
        [window _setResizable:(resizable != 0)];
    }
    GLASS_POOL_EXIT;

    return 1;
}

int32_t gmac_window_set_visible(void *ptr, int32_t visible)
{
    if (!ptr) return 0;

    int32_t now = 0;

    GLASS_POOL_ENTER;
    {
        GlassWindow *window = getGlassWindow(ptr);
        if (visible != 0)
        {
            if (!window->isLocationAssigned) {
                [window _setBounds:0 y:0 xSet:YES ySet:YES w:-1 h:-1 cw:-1 ch:-1];
            }
            if (!window->isSizeAssigned) {
                [window _setBounds:0 y:0 xSet:NO ySet:NO w:320 h:200 cw:-1 ch:-1];
            }
            [window _setVisible];
        }
        else
        {
            [window _ungrabFocus];
            [window->nsWindow orderOut:window->nsWindow];
        }
        now = [window->nsWindow isVisible] ? 1 : 0;

        // JDK-8088691 temp workaround: bring plugin window in front of a browser
        if (now == YES)
        {
            static BOOL isBackgroundOnlyAppChecked = NO;
            static BOOL isBackgroundOnlyApp = NO;
            if (isBackgroundOnlyAppChecked == NO)
            {
                isBackgroundOnlyAppChecked = YES;

                ProcessSerialNumber psn;
                if (GetCurrentProcess(&psn) == noErr)
                {
                    ProcessInfoRec info;
                    memset(&info, 0x00, sizeof(ProcessInfoRec));
                    GetProcessInformation(&psn, &info);
                    isBackgroundOnlyApp = ((modeOnlyBackground&info.processMode) == modeOnlyBackground);
                }
            }
            if (isBackgroundOnlyApp == YES)
            {
                [window->nsWindow performSelectorOnMainThread:@selector(orderFrontRegardless) withObject:nil waitUntilDone:YES];
            }
        }
    }
    GLASS_POOL_EXIT;

    return now;
}

int32_t gmac_window_set_title(void *ptr, const uint16_t *chars, int32_t length)
{
    if (!ptr) return 0;

    GLASS_POOL_ENTER;
    {
        GlassWindow *window = getGlassWindow(ptr);

        NSString *title = GlassNSStringOrEmpty(chars, length);
        LOG("   title: %s", [title UTF8String]);
        [window->nsWindow setTitle:title];
    }
    GLASS_POOL_EXIT;

    return 1; // gnote: remove this return value if unused
}

int32_t gmac_window_minimize(void *ptr, int32_t miniaturize)
{
    if (!ptr) return 0;

    GLASS_POOL_ENTER;
    {
        GlassWindow *window = getGlassWindow(ptr);

        NSUInteger styleMask = [window->nsWindow styleMask];
        BOOL isMiniaturizable = (styleMask & NSWindowStyleMaskMiniaturizable) != 0;

        // if the window does not have NSMiniaturizableWindowMask set
        // we need to temporarily set it to allow the window to
        // be programmatically minimized or restored.
        if (!isMiniaturizable) {
            [window->nsWindow setStyleMask: styleMask | NSWindowStyleMaskMiniaturizable];
        }

        if (miniaturize != 0)
        {
            [window->nsWindow miniaturize:nil];
        }
        else
        {
            [window->nsWindow deminiaturize:nil];
        }

        // Restore the state of NSMiniaturizableWindowMask
        if (!isMiniaturizable) {
            [window->nsWindow setStyleMask: styleMask];
        }

    }
    GLASS_POOL_EXIT;

    return 1; // gnote: remove this return value if unused
}

void gmac_window_set_icon(void *ptr, const uint8_t *pixels, int32_t width, int32_t height)
{
    if (!ptr) return;

    GLASS_POOL_ENTER;
    {
        GlassWindow *window = getGlassWindow(ptr);

        if (pixels)
        {
            NSImage *image = nil;

            image = getImage(
                        (u_int8_t *)pixels,
                        height, width, 0);

            if (image != nil) {
                // need an explicit window title for the rest of the code to work
                if ([window->nsWindow title] == nil)
                {
                    [window->nsWindow setTitle:@"Untitled"];
                }

                // http://www.cocoabuilder.com/archive/cocoa/199554-nswindow-title-bar-icon-without-representedurl.html
                [window->nsWindow setRepresentedURL:[NSURL fileURLWithPath:[window->nsWindow title]]];
                [[window->nsWindow standardWindowButton:NSWindowDocumentIconButton] setImage:image];
                [image release];
            } else {
                [[window->nsWindow standardWindowButton:NSWindowDocumentIconButton] setImage:nil];
            }
        } else {
            [[window->nsWindow standardWindowButton:NSWindowDocumentIconButton] setImage:nil];
        }
    }
    GLASS_POOL_EXIT;
}

void gmac_window_set_dark_frame(void *ptr, int32_t dark)
{
    if (!ptr) return;

    GLASS_POOL_ENTER;
    {
        GlassWindow *window = getGlassWindow(ptr);
        [window->nsWindow setAppearance:dark
            ? [NSAppearance appearanceNamed:NSAppearanceNameDarkAqua]
            : [NSAppearance appearanceNamed:NSAppearanceNameAqua]];
    }
    GLASS_POOL_EXIT;
}

void gmac_window_to_front(void *ptr)
{
    if (!ptr) return;

    GLASS_POOL_ENTER;
    {
        GlassWindow *window = getGlassWindow(ptr);
        [window->nsWindow orderFrontRegardless];
    }
    GLASS_POOL_EXIT;
}

void gmac_window_to_back(void *ptr)
{
    if (!ptr) return;

    GLASS_POOL_ENTER;
    {
        GlassWindow *window = getGlassWindow(ptr);
        [window->nsWindow orderBack:nil];
    }
    GLASS_POOL_EXIT;
}

void gmac_window_perform_drag(void *ptr)
{
    if (!ptr) return;

    GLASS_POOL_ENTER;
    {
        GlassWindow *window = getGlassWindow(ptr);
        [[window->view delegate] performWindowDrag];
    }
    GLASS_POOL_EXIT;
}

void gmac_window_perform_title_bar_double_click_action(void *ptr)
{
    if (!ptr) return;

    GLASS_POOL_ENTER;
    {
        GlassWindow *window = getGlassWindow(ptr);
        NSString* action = [NSUserDefaults.standardUserDefaults stringForKey:@"AppleActionOnDoubleClick"];

        if ([action isEqualToString:@"Minimize"]) {
            [window->nsWindow performMiniaturize:nil];
        } else if ([action isEqualToString:@"Maximize"]) {
            [window->nsWindow performZoom:nil];
        }
    }
    GLASS_POOL_EXIT;
}

void gmac_window_set_button_style(void *ptr, int32_t toolbarStyle, int32_t buttonsVisible)
{
    if (!ptr) return;

    GLASS_POOL_ENTER;
    {
        GlassWindow *window = getGlassWindow(ptr);
        if (window) {
            window->isStandardButtonsVisible = (buttonsVisible != 0);

            if (window->nsWindow) {
                [window->nsWindow setToolbarStyle:toolbarStyle];
                [[window->nsWindow standardWindowButton:NSWindowCloseButton] setHidden:!buttonsVisible];
                [[window->nsWindow standardWindowButton:NSWindowMiniaturizeButton] setHidden:!buttonsVisible];
                [[window->nsWindow standardWindowButton:NSWindowZoomButton] setHidden:!buttonsVisible];
            }
        }
    }
    GLASS_POOL_EXIT;
}

void gmac_window_set_button_appearance(void *ptr, int32_t darkAppearance)
{
    if (!ptr) return;

    GLASS_POOL_ENTER;
    {
        GlassWindow *window = getGlassWindow(ptr);
        if (window && window->nsWindow) {
            NSAppearance* appearance = darkAppearance
                ? [NSAppearance appearanceNamed:NSAppearanceNameDarkAqua]
                : [NSAppearance appearanceNamed:NSAppearanceNameAqua];

            [[window->nsWindow standardWindowButton:NSWindowCloseButton] setAppearance:appearance];
            [[window->nsWindow standardWindowButton:NSWindowMiniaturizeButton] setAppearance:appearance];
            [[window->nsWindow standardWindowButton:NSWindowZoomButton] setAppearance:appearance];
        }
    }
    GLASS_POOL_EXIT;
}
