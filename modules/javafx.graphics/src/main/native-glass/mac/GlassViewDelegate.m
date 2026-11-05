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
#import "com_sun_glass_events_MouseEvent.h"
#import "com_sun_glass_events_KeyEvent.h"
#import "com_sun_glass_events_DndEvent.h"
#import "com_sun_glass_events_SwipeGesture.h"
#import "com_sun_glass_ui_Clipboard.h"
#import "com_sun_glass_ui_mac_MacGestureSupport.h"

#import "GlassMacros.h"
#import "GlassViewDelegate.h"
#import "GlassKey.h"
#import "GlassWindow.h"
#import "GlassApplication.h"
#import "GlassHelper.h"
#import "GlassPasteboard.h"
#import "glass_mac_internal.h"

#pragma clang diagnostic ignored "-Wdeprecated-declarations"

//#define VERBOSE
#ifndef VERBOSE
    #define LOG(MSG, ...)
#else
    #define LOG(MSG, ...) GLASS_LOG(MSG, ## __VA_ARGS__);
#endif

//#define DNDVERBOSE
#ifndef DNDVERBOSE
    #define DNDLOG(MSG, ...)
#else
    #define DNDLOG(MSG, ...) GLASS_LOG(MSG, ## __VA_ARGS__);
#endif

// used Safari as a reference while dragging large images
#define MAX_DRAG_SIZE 400

// explicitly set image size
#define DEFAULT_DRAG_SIZE 64

// Tracks pressed modifier keys
static NSUInteger s_modifierFlags = 0;

@interface GlassViewDelegate (hidden)
- (void)maybeBeginGestureWithEvent:(NSEvent *)theEvent withMask:(GestureMaskType)theMask;
- (void)maybeEndGestureWithEvent:(NSEvent *)theEvent withMask:(GestureMaskType)theMask;
@end

// Extracted from class-dump utility output for NSEvent class
@interface NSEvent (hidden)

- (long long)_scrollPhase;
- (unsigned long long)momentumPhase;
@end


static uint8_t isInertialScroll(NSEvent *theEvent)
{
    enum
    {
        SelectorNotSet,
        MomentumPhaseSelector,
        ScrollPhaseSelector,
        SelectorNotAvailable
    };

    static int selector = SelectorNotSet;

    switch (selector)
    {
        case SelectorNotSet:
            if ([theEvent respondsToSelector:@selector(momentumPhase)])
            {   // Available from OS X 10.7
                selector = MomentumPhaseSelector;
            }
            else if ([theEvent respondsToSelector:@selector(_scrollPhase)])
            {   // Available in OS X 10.6 and earlier. Deprecated in OS X 10.7
                selector = ScrollPhaseSelector;
            }
            else
            {
                selector = SelectorNotAvailable;
            }
            return isInertialScroll(theEvent);

        case MomentumPhaseSelector:
            return ([theEvent momentumPhase] != 0);

        case ScrollPhaseSelector:
            return ([theEvent _scrollPhase] != 0);
    }

    return 0;
}


static int32_t getSwipeDirFromEvent(NSEvent *theEvent)
{
    if ([theEvent deltaX] < 0) {
        return com_sun_glass_events_SwipeGesture_DIR_RIGHT;
    }
    if ([theEvent deltaX] > 0) {
        return com_sun_glass_events_SwipeGesture_DIR_LEFT;
    }
    if ([theEvent deltaY] > 0) {
        return com_sun_glass_events_SwipeGesture_DIR_UP;
    }
    if ([theEvent deltaY] < 0) {
        return com_sun_glass_events_SwipeGesture_DIR_DOWN;
    }
    return 0;
}

// Main thread. The notify_key dials made so far, so that an event can tell whether another event dialled the slot
// between two of its own dials.
static uint64_t keySlotDials = 0;

int32_t GlassDialKeySlot(GlassKeySend *send, int32_t type, int32_t key_code, int32_t *out_consumed)
{
    int32_t consumed = 0;
    int32_t status = GMAC_ERR_NO_DATA;
    if (send->callbacks->notify_key != NULL)
    {
        int32_t sameChars = (send->last_dial != 0 && send->last_dial == keySlotDials) ? 1 : 0;
        send->last_dial = ++keySlotDials;
        status = send->callbacks->notify_key(send->view_id, type, key_code, send->chars, send->char_count,
                                             send->modifiers, sameChars, &consumed);
    }
    if (out_consumed != NULL)
    {
        *out_consumed = consumed;
    }
    return status;
}

static void dialModifierKeySlot(GlassKeySend *send, NSUInteger changedFlags, NSUInteger currentFlags, NSUInteger mask,
                                int32_t vkCode)
{
    if (changedFlags & mask)
    {
        GlassDialKeySlot(send, (currentFlags & mask) ? com_sun_glass_events_KeyEvent_PRESS
                                                     : com_sun_glass_events_KeyEvent_RELEASE, vkCode, NULL);
    }
}

// The UTF-16 units of text as the JNI passed them, including its latent over-read: the attributed branch copies
// length units from data that may hold fewer, and the uninitialized rest goes to Java.
int32_t GlassDialInputMethod(const GmacViewCallbacks *callbacks, int64_t view_id, id text, int attr, int length,
                             int cursor, NSRange selection)
{
    if (callbacks->notify_input_method == NULL)
    {
        return GMAC_ERR_NO_DATA;
    }
    int32_t status = INT32_MIN;
    if ([text isKindOfClass:[NSAttributedString class]]) {
        if (length <= 0 || length > SIZE_MAX / sizeof(uint16_t)) {
            return INT32_MIN;
        }

        NSData *data = [[text string] dataUsingEncoding:NSUTF16LittleEndianStringEncoding];
        uint16_t *dataBytes = (uint16_t *)malloc(sizeof(uint16_t) * (size_t)length);
        if (dataBytes != NULL) {
            [data getBytes:dataBytes length:length * 2];
            status = callbacks->notify_input_method(view_id, dataBytes, length, attr, length, cursor,
                                                    (int32_t)selection.location, (int32_t)selection.length);
            free(dataBytes);
        }
    } else {
        NSData *data = [text dataUsingEncoding:NSUTF16LittleEndianStringEncoding];
        status = callbacks->notify_input_method(view_id, (const uint16_t *)[data bytes], (int32_t)(data.length / 2),
                                                attr, length, cursor, (int32_t)selection.location,
                                                (int32_t)selection.length);
    }
    return status;
}

static int32_t dialDragSlot(int32_t (*slot)(int64_t, int32_t, int32_t, int32_t, int32_t, int32_t, int32_t *),
                            int64_t viewId, int32_t x, int32_t y, int32_t xAbs, int32_t yAbs, int32_t recommendedAction)
{
    int32_t action = com_sun_glass_ui_Clipboard_ACTION_NONE;
    if (slot != NULL && GlassIsMainJavaAttached())
    {
        slot(viewId, x, y, xAbs, yAbs, recommendedAction, &action);
    }
    return action;
}


@implementation GlassViewDelegate

- (id)initWithView:(NSView*)view viewId:(int64_t)identifier
{
    self = [super init];
    if (self != nil)
    {
        GLASS_WARN_IF_MAIN_JAVA_DETACHED;

        self->nsView = view;
        self->viewId = identifier;
        self->mouseIsOver = NO;
        self->mouseDownMask = 0;

        self->gestureInProgress = NO;
        self->gesturesBeganMask = 0;

        self->nativeFullScreenModeWindow = nil;

        // optimization
        [self->nsView allocateGState];

                // register for drag and drop
                [self->nsView registerForDraggedTypes:[NSArray arrayWithObjects:        NSPasteboardTypeString,
                                                                                NSPasteboardTypeTIFF,
                                                                                   NSPasteboardTypeRTF,
                                                                                   NSPasteboardTypeTabularText,
                                                                                   NSPasteboardTypeFont,
                                                                                   NSPasteboardTypeRuler,
                                                                                   NSPasteboardTypeColor,
                                                                                   NSPasteboardTypeRTFD,
                                                                                   NSPasteboardTypeHTML,
                                                                                   NSPasteboardTypePDF,
                                                                                   NSPasteboardTypeMultipleTextSelection,
                                                                                   (NSString*)kUTTypeURL,
                                                                                   (NSString*)kUTTypeFileURL,
                                                                                   (NSString*)@"public.mime-type",
                                                                            nil]];
    }
    return self;
}

- (void)dealloc
{
    [self->lastEvent release];
    self->lastEvent = nil;

    [self->parentHost release];
    self->parentHost = nil;

    [self->parentWindow release];
    self->parentWindow = nil;

    [self->nativeFullScreenModeWindow release];
    self->nativeFullScreenModeWindow = nil;

    assert(pthread_main_np() == 1);

    const GmacViewCallbacks *callbacks = GlassViewCallbacks();
    if (callbacks != NULL && callbacks->notify_dispose != NULL && GlassIsMainJavaAttached())
    {
        callbacks->notify_dispose(self->viewId);
    }

    [super dealloc];
}

- (void)viewDidMoveToWindow
{
    //        NSLog(@"viewDidMoveToWindow");
    //        NSLog(@"        self: %@", self);
    //        NSLog(@"        [self superview]: %@", [self superview]);
    GLASS_WARN_IF_MAIN_JAVA_DETACHED;
    const GmacViewCallbacks *callbacks = GlassViewCallbacks();
    if ([self->nsView window] != nil)
    {
        if (self->parentHost == nil)
        {
            self->parentHost = (GlassHostView*)[[self->nsView superview] retain];
        }
        if (self->parentWindow == nil)
        {
            self->parentWindow = [[self->nsView window] retain];
        }

        [[self->nsView window] setAcceptsMouseMovedEvents:YES];
        if (callbacks != NULL)
        {
            [self _notifyView:com_sun_glass_events_ViewEvent_ADD callbacks:callbacks];
        }
    }
    else
    {
        if (self->parentWindow != nil)
        {
            [self->parentWindow release];
            self->parentWindow = nil;
        }
        if (callbacks != NULL)
        {
            [self _notifyView:com_sun_glass_events_ViewEvent_REMOVE callbacks:callbacks];
        }
    }
}

- (void)_notifyView:(int32_t)type callbacks:(const GmacViewCallbacks *)callbacks
{
    if (callbacks->notify_view != NULL && GlassIsMainJavaAttached())
    {
        callbacks->notify_view(self->viewId, type);
    }
}

- (void)_notifyResizeWidth:(int32_t)width height:(int32_t)height callbacks:(const GmacViewCallbacks *)callbacks
{
    if (callbacks->notify_resize != NULL && GlassIsMainJavaAttached())
    {
        callbacks->notify_resize(self->viewId, width, height);
    }
}

- (void)setFrameOrigin:(NSPoint)newOrigin
{

}

- (void)setFrameSize:(NSSize)newSize
{
    LOG("GlassViewDelegate setFrameSize %fx%f", newSize.width, newSize.height);

    //NSLog(@"GlassViewDelegate setFrameSize: %dx%d", (int)newSize.width, (int)newSize.height);
    // TODO: listen for resize view's notifications
    GLASS_WARN_IF_MAIN_JAVA_DETACHED;
    const GmacViewCallbacks *callbacks = GlassViewCallbacks();
    if (callbacks != NULL)
    {
        [self _notifyResizeWidth:(int)newSize.width height:(int)newSize.height callbacks:callbacks];
    }

    if (self->trackingRect) {
        [self->nsView removeTrackingRect:self->trackingRect];
    }
    self->trackingRect = [self->nsView addTrackingRect:[self->nsView bounds] owner:self->nsView userData:nil assumeInside:NO];
}

- (void)setFrame:(NSRect)frameRect
{
    LOG("GlassViewDelegate setFrame %fx%f", frameRect.size.width, frameRect.size.height);

    //NSLog(@"GlassViewDelegate setFrame: %d,%d %dx%d", (int)frameRect.origin.x, (int)frameRect.origin.y, (int)frameRect.size.width, (int)frameRect.size.height);
    // TODO: listen for resize view's notifications
    GLASS_WARN_IF_MAIN_JAVA_DETACHED;
    const GmacViewCallbacks *callbacks = GlassViewCallbacks();
    if (callbacks != NULL)
    {
        [self _notifyResizeWidth:(int)frameRect.size.width height:(int)frameRect.size.height callbacks:callbacks];
    }

    if (self->trackingRect) {
        [self->nsView removeTrackingRect:self->trackingRect];
    }
    self->trackingRect = [self->nsView addTrackingRect:[self->nsView bounds] owner:self->nsView userData:nil assumeInside:NO];
}

- (void)updateTrackingAreas
{
    if (self->trackingRect) {
        [self->nsView removeTrackingRect:self->trackingRect];
    }
    self->trackingRect = [self->nsView addTrackingRect:[self->nsView bounds] owner:self->nsView userData:nil assumeInside:NO];
}

- (void)drawRect:(NSRect)dirtyRect
{
    //NSLog(@"BEGIN View:drawRect %@: ", self);
    //NSLog(@"        [self frame]: %f,%f %fx%f", [self->nsView frame].origin.x, [self->nsView frame].origin.y, [self->nsView frame].size.width, [self->nsView frame].size.height);
    GLASS_WARN_IF_MAIN_JAVA_DETACHED;
    int32_t x = (int32_t)[self->nsView frame].origin.x;
    int32_t y = (int32_t)[self->nsView frame].origin.y;
    int32_t w = (int32_t)[self->nsView frame].size.width;
    int32_t h = (int32_t)[self->nsView frame].size.height;
    const GmacViewCallbacks *callbacks = GlassViewCallbacks();
    if (callbacks != NULL && callbacks->notify_repaint != NULL && GlassIsMainJavaAttached())
    {
        callbacks->notify_repaint(self->viewId, x, y, w, h);
    }
    //NSLog(@"END drawRect");
}

- (void)sendJavaMenuEvent:(NSEvent *)theEvent
{
//    NSLog(@"sendJavaMenuEvent");
    NSWindow * nswindow = [nsView window];
    if (nswindow && [[nswindow delegate] isKindOfClass: [GlassWindow class]]) {
        GlassWindow *window = (GlassWindow*)[nswindow delegate];
        if (!window->isEnabled) {
            return;
        }
    }
    NSPoint viewPoint = [nsView convertPoint:[theEvent locationInWindow] fromView:nil]; // convert from window coordinates to view coordinates
    CGPoint basePoint = CGEventGetLocation([theEvent CGEvent]);

    GLASS_WARN_IF_MAIN_JAVA_DETACHED;
    uint8_t isKeyboardTrigger = 0;
    const GmacViewCallbacks *callbacks = GlassViewCallbacks();
    if (callbacks != NULL && callbacks->notify_menu != NULL && GlassIsMainJavaAttached())
    {
        callbacks->notify_menu(self->viewId, (int32_t)viewPoint.x, (int32_t)viewPoint.y, (int32_t)basePoint.x,
                               (int32_t)basePoint.y, isKeyboardTrigger);
    }
}

- (void)sendJavaMouseEvent:(NSEvent *)theEvent
{
    NSWindow * nswindow = [nsView window];
    if (nswindow && [[nswindow delegate] isKindOfClass: [GlassWindow class]]) {
        GlassWindow *window = (GlassWindow*)[nswindow delegate];
        if (!window->isEnabled) {
            return;
        }
    }

    int type = 0;
    int button = com_sun_glass_events_MouseEvent_BUTTON_NONE;
    switch ([theEvent type])
    {
        case NSLeftMouseDown:
            type = com_sun_glass_events_MouseEvent_DOWN;
            button = com_sun_glass_events_MouseEvent_BUTTON_LEFT;
            break;
        case NSRightMouseDown:
            type = com_sun_glass_events_MouseEvent_DOWN;
            button = com_sun_glass_events_MouseEvent_BUTTON_RIGHT;
            break;
        case NSOtherMouseDown:
            type = com_sun_glass_events_MouseEvent_DOWN;
            switch ([theEvent buttonNumber]) {
                case 2:
                    button = com_sun_glass_events_MouseEvent_BUTTON_OTHER;
                    break;
                case 3:
                    button = com_sun_glass_events_MouseEvent_BUTTON_BACK;
                    break;
                case 4:
                    button = com_sun_glass_events_MouseEvent_BUTTON_FORWARD;
                    break;
            }
            break;

        case NSLeftMouseUp:
            type = com_sun_glass_events_MouseEvent_UP;
            button = com_sun_glass_events_MouseEvent_BUTTON_LEFT;
            break;
        case NSRightMouseUp:
            type = com_sun_glass_events_MouseEvent_UP;
            button = com_sun_glass_events_MouseEvent_BUTTON_RIGHT;
            break;
        case NSOtherMouseUp:
            type = com_sun_glass_events_MouseEvent_UP;
            switch ([theEvent buttonNumber]) {
                case 2:
                    button = com_sun_glass_events_MouseEvent_BUTTON_OTHER;
                    break;
                case 3:
                    button = com_sun_glass_events_MouseEvent_BUTTON_BACK;
                    break;
                case 4:
                    button = com_sun_glass_events_MouseEvent_BUTTON_FORWARD;
                    break;
            }
            break;

        case NSLeftMouseDragged:
            type = com_sun_glass_events_MouseEvent_DRAG;
            button = com_sun_glass_events_MouseEvent_BUTTON_LEFT;
            break;
        case NSRightMouseDragged:
            type = com_sun_glass_events_MouseEvent_DRAG;
            button = com_sun_glass_events_MouseEvent_BUTTON_RIGHT;
            break;
        case NSOtherMouseDragged:
            type = com_sun_glass_events_MouseEvent_DRAG;
            switch ([theEvent buttonNumber]) {
                case 2:
                    button = com_sun_glass_events_MouseEvent_BUTTON_OTHER;
                    break;
                case 3:
                    button = com_sun_glass_events_MouseEvent_BUTTON_BACK;
                    break;
                case 4:
                    button = com_sun_glass_events_MouseEvent_BUTTON_FORWARD;
                    break;
            }
            break;

        case NSMouseMoved:
            type = com_sun_glass_events_MouseEvent_MOVE;
            break;

        case NSMouseEntered:
            type = com_sun_glass_events_MouseEvent_ENTER;
            self->lastTrackingNumber = [theEvent trackingNumber];
            break;

        case NSMouseExited:
            type = com_sun_glass_events_MouseEvent_EXIT;
            self->lastTrackingNumber = [theEvent trackingNumber];
            break;

        case NSScrollWheel:
            type = com_sun_glass_events_MouseEvent_WHEEL;
            break;

        default:
            break;
    }

    NSPoint viewPoint = [nsView convertPoint:[theEvent locationInWindow] fromView:nil]; // convert from window coordinates to view coordinates
    CGPoint basePoint = CGEventGetLocation([theEvent CGEvent]);

    if (type == com_sun_glass_events_MouseEvent_MOVE)
    {
        NSRect frame = [nsView frame];

        if (viewPoint.x < 0 || viewPoint.y < 0 ||
                viewPoint.x >= frame.size.width ||
                viewPoint.y >= frame.size.height)
        {
            // The MOVE events happening outside of the view must be ignored
            return;
        }

        // Check if the event is a duplicate
        if (self->lastEvent)
        {
            CGPoint oldBasePoint = CGEventGetLocation([self->lastEvent CGEvent]);

            if (basePoint.x == oldBasePoint.x && basePoint.y == oldBasePoint.y)
            {
                return;
            }
        }
    }

        //    NSLog(@"Event location: in window %@, in view %@, in base coordinates %d,%d",
        //          NSStringFromPoint([theEvent locationInWindow]),
        //          NSStringFromPoint(viewPoint),
        //          (int32_t)basePoint.x, (int32_t)basePoint.y);

    double rotationX = 0.0;
    double rotationY = 0.0;
    if (type == com_sun_glass_events_MouseEvent_WHEEL)
    {
        if ([theEvent hasPreciseScrollingDeltas])
        {
            rotationX = (double)[theEvent scrollingDeltaX] * 0.1;
            rotationY = (double)[theEvent scrollingDeltaY] * 0.1;
        }
        else
        {
            rotationX = (double)[theEvent deltaX];
            rotationY = (double)[theEvent deltaY];
        }

        //XXX: check for equality for doubles???
        if (rotationX == 0.0 && rotationY == 0.0)
        {
            return;
        }
    }

    BOOL block = NO;
    {
        // JDK-8107811
        if ((type == com_sun_glass_events_MouseEvent_ENTER) || (type == com_sun_glass_events_MouseEvent_EXIT))
        {
            // userData indicates if this is a synthesized EXIT event that MUST pass through
            // Note: userData is only valid for ENTER/EXIT events!
            if (self->mouseIsDown == YES && [theEvent userData] != self)
            {
                block = [self suppressMouseEnterExitOnMouseDown];
            }
        }
        else
        {
            // for the mouse supression we can not look at the mouse down state during ENTER/EXIT events
            // as they always report mouse up regardless of the actual state, so we need to store it
            // based on the events other than ENTER/EXIT
            self->mouseIsDown = (button != com_sun_glass_events_MouseEvent_BUTTON_NONE);
        }
    }
    if (block == NO)
    {
        if (!self->mouseIsOver &&
                type != com_sun_glass_events_MouseEvent_ENTER &&
                type != com_sun_glass_events_MouseEvent_EXIT)
        {
            // OS X didn't send mouseEnter. Synthesize it here.
            NSEvent *eeEvent = [NSEvent enterExitEventWithType:NSMouseEntered
                                                      location:[theEvent locationInWindow]
                                                 modifierFlags:[theEvent modifierFlags]
                                                     timestamp:[theEvent timestamp]
                                                  windowNumber:[theEvent windowNumber]
                                                       context:[theEvent context]
                                                   eventNumber:0
                                                trackingNumber:self->lastTrackingNumber
                                                      userData:self];
            [self sendJavaMouseEvent:eeEvent];
        }

        int32_t modifiers = GetJavaKeyModifiers(theEvent);
        if (type != com_sun_glass_events_MouseEvent_ENTER &&
            type != com_sun_glass_events_MouseEvent_EXIT) {
            modifiers |= GetJavaMouseModifiers([NSEvent pressedMouseButtons]);
            if (type != com_sun_glass_events_MouseEvent_UP)
            {
                switch (button)
                {
                    case com_sun_glass_events_MouseEvent_BUTTON_LEFT:
                        modifiers |= com_sun_glass_events_KeyEvent_MODIFIER_BUTTON_PRIMARY;
                        break;
                    case com_sun_glass_events_MouseEvent_BUTTON_RIGHT:
                        modifiers |= com_sun_glass_events_KeyEvent_MODIFIER_BUTTON_SECONDARY;
                        break;
                    case com_sun_glass_events_MouseEvent_BUTTON_OTHER:
                        modifiers |= com_sun_glass_events_KeyEvent_MODIFIER_BUTTON_MIDDLE;
                        break;
                    case com_sun_glass_events_MouseEvent_BUTTON_BACK:
                        modifiers |= com_sun_glass_events_KeyEvent_MODIFIER_BUTTON_BACK;
                        break;
                    case com_sun_glass_events_MouseEvent_BUTTON_FORWARD:
                        modifiers |= com_sun_glass_events_KeyEvent_MODIFIER_BUTTON_FORWARD;
                        break;
                }
            }
        }

        uint8_t isSynthesized = 0;

        uint8_t isPopupTrigger = 0;
        if (type == com_sun_glass_events_MouseEvent_DOWN) {
            if (button == com_sun_glass_events_MouseEvent_BUTTON_RIGHT) {
                isPopupTrigger = 1;
            }
            if (button == com_sun_glass_events_MouseEvent_BUTTON_LEFT &&
                (modifiers & com_sun_glass_events_KeyEvent_MODIFIER_CONTROL))
            {
                isPopupTrigger = 1;
            }
        }

        [self->lastEvent release];
        self->lastEvent = nil;
        switch (type) {
            // prepare GlassDragSource for possible drag,
            case com_sun_glass_events_MouseEvent_DOWN:
                switch (button) {
                    case com_sun_glass_events_MouseEvent_BUTTON_LEFT:    self->mouseDownMask |= 1 << 0; break;
                    case com_sun_glass_events_MouseEvent_BUTTON_RIGHT:   self->mouseDownMask |= 1 << 1; break;
                    case com_sun_glass_events_MouseEvent_BUTTON_OTHER:   self->mouseDownMask |= 1 << 2; break;
                    case com_sun_glass_events_MouseEvent_BUTTON_BACK:    self->mouseDownMask |= 1 << 3; break;
                    case com_sun_glass_events_MouseEvent_BUTTON_FORWARD: self->mouseDownMask |= 1 << 4; break;
                }
                //fall through
            case com_sun_glass_events_MouseEvent_DRAG:
                [GlassDragSource setDelegate:self];
                // fall through to save the lastEvent
            // or for filtering out duplicate MOVE events
            case com_sun_glass_events_MouseEvent_MOVE:
                self->lastEvent = [theEvent retain];
                break;
            case com_sun_glass_events_MouseEvent_UP:
                switch (button) {
                    case com_sun_glass_events_MouseEvent_BUTTON_LEFT:    self->mouseDownMask &= ~(1 << 0); break;
                    case com_sun_glass_events_MouseEvent_BUTTON_RIGHT:   self->mouseDownMask &= ~(1 << 1); break;
                    case com_sun_glass_events_MouseEvent_BUTTON_OTHER:   self->mouseDownMask &= ~(1 << 2); break;
                    case com_sun_glass_events_MouseEvent_BUTTON_BACK:    self->mouseDownMask &= ~(1 << 3); break;
                    case com_sun_glass_events_MouseEvent_BUTTON_FORWARD: self->mouseDownMask &= ~(1 << 4); break;
                }
                break;



            // Track whether the mouse is over the view
            case com_sun_glass_events_MouseEvent_ENTER:
                self->mouseIsOver = YES;
                break;
            case com_sun_glass_events_MouseEvent_EXIT:
                self->mouseIsOver = NO;
                break;
        }

        GLASS_WARN_IF_MAIN_JAVA_DETACHED;
        if (type == com_sun_glass_events_MouseEvent_WHEEL) {
            // Detect mouse wheel event sender.
            // Can be inertia from scroll gesture,
            // scroll gesture or mouse wheel itself
            //
            // JDK-8126339, JDK-8115029
            int32_t sender = com_sun_glass_ui_mac_MacGestureSupport_SCROLL_SRC_WHEEL;
            if (isInertialScroll(theEvent))
            {
                sender = com_sun_glass_ui_mac_MacGestureSupport_SCROLL_SRC_INERTIA;
            }
            else if (self->gestureInProgress == YES)
            {
                sender = com_sun_glass_ui_mac_MacGestureSupport_SCROLL_SRC_GESTURE;
            }

            const GmacGestureCallbacks *gestures = GlassGestureCallbacks();
            if (gestures != NULL && gestures->scroll_gesture_performed != NULL && GlassIsMainJavaAttached())
            {
                // The JNI passed the doubles through varargs to float parameters, which narrowed them.
                gestures->scroll_gesture_performed(self->viewId, modifiers, sender,
                                                   (int32_t)viewPoint.x, (int32_t)viewPoint.y,
                                                   (int32_t)basePoint.x, (int32_t)basePoint.y,
                                                   (float)rotationX, (float)rotationY);
            }
        } else {
            const GmacViewCallbacks *callbacks = GlassViewCallbacks();
            if (callbacks != NULL && callbacks->notify_mouse != NULL && GlassIsMainJavaAttached())
            {
                callbacks->notify_mouse(self->viewId, type, button, (int32_t)viewPoint.x, (int32_t)viewPoint.y,
                                        (int32_t)basePoint.x, (int32_t)basePoint.y, modifiers, isPopupTrigger,
                                        isSynthesized);
            }
        }

        if (isPopupTrigger) {
            [self sendJavaMenuEvent:theEvent];
        }
    }
}

- (void)resetMouseTracking
{
    if (self->mouseIsOver) {
        // Nothing of the parameters really matters for the EXIT event, except userData
        NSEvent* theEvent = [NSEvent
            enterExitEventWithType:NSMouseExited
                          location:[NSEvent mouseLocation]
                     modifierFlags:0
                         timestamp:[NSDate timeIntervalSinceReferenceDate]
                      windowNumber:[[self->nsView window] windowNumber]
                           context:[NSGraphicsContext currentContext]
                       eventNumber:0
                    trackingNumber:self->lastTrackingNumber
                          userData:self]; // indicates that this is a synthesized event

        [self sendJavaMouseEvent:theEvent];
    }
}

- (BOOL)sendJavaKeyEvent:(NSEvent *)theEvent isDown:(BOOL)isDown character:(unichar)textChar
{
    GLASS_WARN_IF_MAIN_JAVA_DETACHED;

    int32_t jKeyCode = GetJavaKeyCode(theEvent);
    int32_t jModifiers = GetJavaModifiers(theEvent);
    const GmacViewCallbacks *callbacks = GlassViewCallbacks();
    if (callbacks == NULL)
    {
        return NO;
    }
    return [self _dialKeyEvent:theEvent isDown:isDown character:textChar keyCode:jKeyCode modifiers:jModifiers
                     callbacks:callbacks];
}

- (BOOL)_dialKeyEvent:(NSEvent *)theEvent isDown:(BOOL)isDown character:(unichar)textChar keyCode:(int32_t)keyCode
            modifiers:(int32_t)modifiers callbacks:(const GmacViewCallbacks *)callbacks
{
    // The JNI's 16-unit key-chars buffer, so that its latent overflow stays what it was.
    uint16_t keyChars[16];
    int32_t charCount;
    if (textChar != 0) {
        keyChars[0] = textChar;
        charCount = 1;
    } else {
        charCount = MAX(GetJavaKeyCharsUtf16(theEvent, keyChars), 0);
    }
    if (!GlassIsMainJavaAttached())
    {
        return NO;
    }

    GlassKeySend send = {callbacks, self->viewId, keyChars, charCount, modifiers, 0};
    // This routine returns YES if the PRESS event was consumed. This is
    // used to ensure that performKeyEquivalent doesn't allow an event
    // to be processed by both JavaFX and the system menus.
    int32_t wasConsumed = 0;
    if (!isDown)
    {
        GlassDialKeySlot(&send, com_sun_glass_events_KeyEvent_RELEASE, keyCode, NULL);
    }
    else
    {
        // We only return YES if the PRESSED event was consumed. The TextInput
        // control consumes any TYPED event that looks like a shortcut.
        GlassDialKeySlot(&send, com_sun_glass_events_KeyEvent_PRESS, keyCode, &wasConsumed);

        // TYPED events should only be sent for printable characters. Thus we avoid
        // sending them for navigation keys. Perhaps this logic could be enhanced.
        // JDK-8112726: zero out the keycode for TYPED events
        if (keyCode < com_sun_glass_events_KeyEvent_VK_PAGE_UP ||
            keyCode > com_sun_glass_events_KeyEvent_VK_DOWN)
        {
            GlassDialKeySlot(&send, com_sun_glass_events_KeyEvent_TYPED, 0, NULL);
        }

        // Mac doesn't send keyUp for Cmd+<> key combinations (including Shift+Cmd+<>, etc.)
        // So we synthesize the event
        if (modifiers & com_sun_glass_events_KeyEvent_MODIFIER_COMMAND)
        {
            GlassDialKeySlot(&send, com_sun_glass_events_KeyEvent_RELEASE, keyCode, NULL);
        }
    }

    return wasConsumed != 0 ? YES : NO;
}

- (void)sendJavaModifierKeyEvent:(NSEvent *)theEvent
{
    NSUInteger currentFlags = [theEvent modifierFlags] & NSDeviceIndependentModifierFlagsMask;
    NSUInteger changedFlags = currentFlags ^ s_modifierFlags;

    int32_t jModifiers = GetJavaModifiers(theEvent);

    GLASS_WARN_IF_MAIN_JAVA_DETACHED;
    const GmacViewCallbacks *callbacks = GlassViewCallbacks();
    if (callbacks != NULL && GlassIsMainJavaAttached())
    {
        uint16_t noChars = 0;
        GlassKeySend send = {callbacks, self->viewId, &noChars, 0, jModifiers, 0};

        dialModifierKeySlot(&send, changedFlags, currentFlags, NSShiftKeyMask,
                            com_sun_glass_events_KeyEvent_VK_SHIFT);
        dialModifierKeySlot(&send, changedFlags, currentFlags, NSControlKeyMask,
                            com_sun_glass_events_KeyEvent_VK_CONTROL);
        dialModifierKeySlot(&send, changedFlags, currentFlags, NSAlternateKeyMask,
                            com_sun_glass_events_KeyEvent_VK_ALT);
        dialModifierKeySlot(&send, changedFlags, currentFlags, NSCommandKeyMask,
                            com_sun_glass_events_KeyEvent_VK_COMMAND);

        // For CapsLock both PRESS and RELEASE should be synthesized each time
        if (changedFlags & NSAlphaShiftKeyMask) {
            GlassDialKeySlot(&send, com_sun_glass_events_KeyEvent_PRESS,
                             com_sun_glass_events_KeyEvent_VK_CAPS_LOCK, NULL);
            GlassDialKeySlot(&send, com_sun_glass_events_KeyEvent_RELEASE,
                             com_sun_glass_events_KeyEvent_VK_CAPS_LOCK, NULL);
        }
    }

    s_modifierFlags = currentFlags;
}

- (void)sendJavaGestureEvent:(NSEvent *)theEvent type:(int)type
{
    NSPoint viewPoint = [nsView convertPoint:[theEvent locationInWindow] fromView:nil]; // convert from window coordinates to view coordinates
    CGPoint basePoint = CGEventGetLocation([theEvent CGEvent]);

    int32_t modifiers = GetJavaModifiers(theEvent);

    GLASS_WARN_IF_MAIN_JAVA_DETACHED;
    const GmacGestureCallbacks *gestures = GlassGestureCallbacks();
    if (gestures != NULL && GlassIsMainJavaAttached())
    {
        [self _dialGesture:type event:theEvent viewPoint:viewPoint basePoint:basePoint modifiers:modifiers
                 callbacks:gestures];
    }
}

- (void)_dialGesture:(int)type event:(NSEvent *)theEvent viewPoint:(NSPoint)viewPoint basePoint:(CGPoint)basePoint
           modifiers:(int32_t)modifiers callbacks:(const GmacGestureCallbacks *)gestures
{
    switch (type)
    {
        case com_sun_glass_ui_mac_MacGestureSupport_GESTURE_ROTATE:
            if (gestures->rotate_gesture_performed != NULL)
            {
                gestures->rotate_gesture_performed(self->viewId, modifiers,
                                                   (int32_t)viewPoint.x, (int32_t)viewPoint.y,
                                                   (int32_t)basePoint.x, (int32_t)basePoint.y,
                                                   (float)[theEvent rotation]);
            }
            break;
        case com_sun_glass_ui_mac_MacGestureSupport_GESTURE_SWIPE:
            if (gestures->swipe_gesture_performed != NULL)
            {
                gestures->swipe_gesture_performed(self->viewId, modifiers,
                                                  getSwipeDirFromEvent(theEvent),
                                                  (int32_t)viewPoint.x, (int32_t)viewPoint.y,
                                                  (int32_t)basePoint.x, (int32_t)basePoint.y);
            }
            break;
        case com_sun_glass_ui_mac_MacGestureSupport_GESTURE_MAGNIFY:
            if (gestures->magnify_gesture_performed != NULL)
            {
                gestures->magnify_gesture_performed(self->viewId, modifiers,
                                                    (int32_t)viewPoint.x, (int32_t)viewPoint.y,
                                                    (int32_t)basePoint.x, (int32_t)basePoint.y,
                                                    (float)[theEvent magnification]);
            }
            break;
    }
}

- (void)sendJavaGestureBeginEvent:(NSEvent *)theEvent
{
    self->gestureInProgress = YES;
}

- (void)sendJavaGestureEndEvent:(NSEvent *)theEvent
{
    self->gestureInProgress = NO;

    NSPoint viewPoint = [nsView convertPoint:[theEvent locationInWindow] fromView:nil]; // convert from window coordinates to view coordinates
    CGPoint basePoint = CGEventGetLocation([theEvent CGEvent]);

    int32_t modifiers = GetJavaModifiers(theEvent);

    GLASS_WARN_IF_MAIN_JAVA_DETACHED;
    const GmacGestureCallbacks *gestures = GlassGestureCallbacks();
    if (gestures != NULL && gestures->gesture_finished != NULL && GlassIsMainJavaAttached())
    {
        gestures->gesture_finished(self->viewId, modifiers, (int32_t)viewPoint.x, (int32_t)viewPoint.y,
                                   (int32_t)basePoint.x, (int32_t)basePoint.y);
    }
}

/*
 * This method is a replacement for the deprecated beginGestureWithEvent
 * method, which is no longer delivered to a View by macOS. This
 * is called for each gesture event to track the beginning of a
 * gesture using the phase of the event. We call sendJavaGestureBeginEvent
 * if there are no other gestures active.
 */
- (void)maybeBeginGestureWithEvent:(NSEvent *)theEvent withMask:(GestureMaskType)theMask
{
    NSEventPhase phase = [theEvent phase];
    if (phase == NSEventPhaseBegan) {
        if (gesturesBeganMask == 0) {
            [self sendJavaGestureBeginEvent:theEvent];
        }
        gesturesBeganMask |= theMask;
    }
}

/*
 * This method is a replacement for the deprecated endGestureWithEvent
 * method, which is no longer delivered to a View by macOS. This
 * is called for each gesture event to track the end of a
 * gesture using the phase of the event. We call sendJavaGestureEndEvent
 * if there are no other gestures active.
 */
- (void)maybeEndGestureWithEvent:(NSEvent *)theEvent withMask:(GestureMaskType)theMask
{
    NSEventPhase phase = [theEvent phase];
    if (phase == NSEventPhaseEnded || phase == NSEventPhaseCancelled) {
        if ((gesturesBeganMask & theMask) != 0) {
            gesturesBeganMask &= ~theMask;
            if (gesturesBeganMask == 0) {
                [self sendJavaGestureEndEvent:theEvent];
            }
        }
    }
}

- (void)doRotateWithEvent:(NSEvent *)theEvent
{
    [self maybeBeginGestureWithEvent:theEvent withMask:GESTURE_MASK_ROTATE];
    [self sendJavaGestureEvent:theEvent type:com_sun_glass_ui_mac_MacGestureSupport_GESTURE_ROTATE];
    [self maybeEndGestureWithEvent:theEvent withMask:GESTURE_MASK_ROTATE];
}

- (void)doSwipeWithEvent:(NSEvent *)theEvent
{
    [self maybeBeginGestureWithEvent:theEvent withMask:GESTURE_MASK_SWIPE];
    [self sendJavaGestureEvent:theEvent type:com_sun_glass_ui_mac_MacGestureSupport_GESTURE_SWIPE];
    [self maybeEndGestureWithEvent:theEvent withMask:GESTURE_MASK_SWIPE];
}

- (void)doMagnifyWithEvent:(NSEvent *)theEvent
{
    [self maybeBeginGestureWithEvent:theEvent withMask:GESTURE_MASK_MAGNIFY];
    [self sendJavaGestureEvent:theEvent type:com_sun_glass_ui_mac_MacGestureSupport_GESTURE_MAGNIFY];
    [self maybeEndGestureWithEvent:theEvent withMask:GESTURE_MASK_MAGNIFY];
}

- (void)doScrollWheel:(NSEvent *)theEvent
{
    [self maybeBeginGestureWithEvent:theEvent withMask:GESTURE_MASK_SCROLL];
    [self sendJavaMouseEvent:theEvent];
    [self maybeEndGestureWithEvent:theEvent withMask:GESTURE_MASK_SCROLL];
}

- (NSDragOperation)sendJavaDndEvent:(id <NSDraggingInfo>)info type:(int32_t)type
{
    GLASS_WARN_IF_MAIN_JAVA_DETACHED;

    NSPoint draggingLocation = [nsView convertPoint:[info draggingLocation] fromView:nil];
    int x = (int)draggingLocation.x;
    int y = (int)draggingLocation.y;

    int xAbs = (int)([info draggingLocation].x + [self->nsView window].frame.origin.x);
    int yAbs = (int)([[NSScreen screens] objectAtIndex: 0].frame.size.height - [self->nsView window].frame.origin.y
                     - [info draggingLocation].y);

    int mask;
    NSDragOperation operation = [info draggingSourceOperationMask];
    int32_t recommendedAction = com_sun_glass_ui_Clipboard_ACTION_NONE;

    if (info.draggingSource != nil) {
        [GlassDragSource setSupportedActions:[GlassDragSource mapNsOperationToJavaMaskInternal:operation]];
        recommendedAction = [GlassDragSource getRecommendedActionForMaskInternal:operation];
    }
    else {
        [GlassDragSource setSupportedActions:[GlassDragSource mapNsOperationToJavaMaskExternal:operation]];
        recommendedAction = [GlassDragSource getRecommendedActionForMaskExternal:operation];
    }

    const GmacViewCallbacks *callbacks = GlassViewCallbacks();
    switch (type)
    {
        case com_sun_glass_events_DndEvent_ENTER:
            DNDLOG("com_sun_glass_events_DndEvent_ENTER");
            copyToDragPasteboardIfNeeded(info);
            mask = callbacks != NULL
                ? dialDragSlot(callbacks->notify_drag_enter, self->viewId, x, y, xAbs, yAbs, recommendedAction)
                : com_sun_glass_ui_Clipboard_ACTION_NONE;
            break;
        case com_sun_glass_events_DndEvent_UPDATE:
            DNDLOG("com_sun_glass_events_DndEvent_UPDATE");
            mask = callbacks != NULL
                ? dialDragSlot(callbacks->notify_drag_over, self->viewId, x, y, xAbs, yAbs, recommendedAction)
                : com_sun_glass_ui_Clipboard_ACTION_NONE;
            break;
        case com_sun_glass_events_DndEvent_PERFORM:
            DNDLOG("com_sun_glass_events_DndEvent_PERFORM");
            mask = callbacks != NULL
                ? dialDragSlot(callbacks->notify_drag_drop, self->viewId, x, y, xAbs, yAbs, recommendedAction)
                : com_sun_glass_ui_Clipboard_ACTION_NONE;
            break;
        case com_sun_glass_events_DndEvent_EXIT:
            DNDLOG("com_sun_glass_events_DndEvent_EXIT");
            if (callbacks != NULL && callbacks->notify_drag_leave != NULL && GlassIsMainJavaAttached())
            {
                callbacks->notify_drag_leave(self->viewId);
            }
            mask = com_sun_glass_ui_Clipboard_ACTION_NONE;
            break;
        default:
            mask = com_sun_glass_ui_Clipboard_ACTION_NONE;
            break;
    }
    [GlassDragSource setMask:mask];

    return [GlassDragSource mapJavaMaskToNsOperation:[GlassDragSource getMask]];
}

// called from Java layer drag handler, triggered by DnD Pasteboard flush
- (void)startDrag:(NSDragOperation)operation withItems:(NSArray<NSDraggingItem*>*)items
{
    DNDLOG("startDrag");

    // Set up frames for dragging items
    for (NSDraggingItem* item in items)
    {
        NSPoint dragPoint = [self->nsView convertPoint:[self->lastEvent locationInWindow] fromView:nil];
        NSImage *image = nil;
        NSRect rect = [item draggingFrame];

        NSPasteboardItem *pbItem = (NSPasteboardItem*)[item item];
        NSArray<NSPasteboardType> *pbItemTypes = [pbItem types];

        if ([pbItemTypes containsObject:DRAG_IMAGE_MIME]) {
            //Try to init with drag image specified by the user
            image = [[NSImage alloc] initWithData:[pbItem dataForType:DRAG_IMAGE_MIME]];
        }

        if (image == nil && [pbItemTypes containsObject:NSPasteboardTypeFileURL])
        {
            // create an image with contents of URL
            NSString *fileURL = [[[NSString alloc] initWithData:[pbItem dataForType:NSPasteboardTypeFileURL] encoding:NSUTF8StringEncoding] autorelease];
            image = [[NSImage alloc] initByReferencingFile:fileURL];

            // this only works if we reference image files though, so make sure the image is valid
            if ([image isValid] == NO)
            {
                [image release];
                image = nil;
            }
        }

        if (image == nil && [pbItemTypes containsObject:NSPasteboardTypeURL])
        {
            // create an image with contents of URL
            NSString *url = [[[NSString alloc] initWithData:[pbItem dataForType:NSPasteboardTypeURL] encoding:NSUTF8StringEncoding] autorelease];
            image = [[NSImage alloc] initByReferencingURL:[NSURL URLWithString:url]];

            // same as with File URL, regular URL can also be invalid
            if ([image isValid] == NO)
            {
                [image release];
                image = nil;
            }
        }

        if (image == nil)
        {
            if ([items count] == 1)
            {
                // with no custom MIME image default to generic document icon
                image = [[NSImage alloc] initWithContentsOfFile:@"/System/Library/CoreServices/CoreTypes.bundle/Contents/Resources/GenericDocumentIcon.icns"];
            }
            else
            {
                // create multiple document image for more than one item in the clipboard
                image = [[NSImage imageNamed:NSImageNameMultipleDocuments] retain];
            }

            [image setSize:NSMakeSize(DEFAULT_DRAG_SIZE, DEFAULT_DRAG_SIZE)];
        }

        if (image != nil)
        {
            DNDLOG("created an image with dim %fx%f", [image size].width, [image size].height);
            // check the drag image size and scale it down as needed using Safari behavior (sizes) as reference
            rect.size.width = [image size].width;
            rect.size.height = [image size].height;
            if ((rect.size.width > MAX_DRAG_SIZE) || (rect.size.height > MAX_DRAG_SIZE))
            {
                if (rect.size.width >= rect.size.height)
                {
                    CGFloat ratio = rect.size.height/rect.size.width;
                    rect.size.width = MIN(rect.size.width, MAX_DRAG_SIZE);
                    rect.size.height = ratio * rect.size.width;
                    [image setSize:NSMakeSize(rect.size.width, rect.size.height)];
                }
                else
                {
                    CGFloat ratio = rect.size.width/rect.size.height;
                    rect.size.height = MIN(rect.size.height, MAX_DRAG_SIZE);
                    rect.size.width = ratio * rect.size.height;
                    [image setSize:NSMakeSize(rect.size.width, rect.size.height)];
                }
            }

            NSString *offsetString = [pbItem stringForType:DRAG_IMAGE_OFFSET];
            if (offsetString != nil) {
                NSPoint offset = NSPointFromString(offsetString);
                //Adjust offset to the image size
                float imageW = [image size].width;
                float imageH = [image size].height;

                if (offset.x < 0.0f) {
                    offset.x = 0.0f;
                } else if (offset.x > imageW) {
                    offset.x = imageW;
                }
                if (offset.y < 0.0f) {
                    offset.y = 0.0f;
                } else if (offset.y > imageH) {
                    offset.y = imageH;
                }

                dragPoint.x -= offset.x;
                dragPoint.y -= offset.y;
            }
        }
        else
        {
            // last resource: "empty" image
            rect.size.width = 1.0f;
            rect.size.height = 1.0f;
            image = [[NSImage alloc] initWithSize:NSMakeSize(rect.size.width, rect.size.height)];
        }

        rect.origin = NSPointToCGPoint(dragPoint);
        [item setDraggingFrame:rect contents:image];
        [image release];
    }

    self->draggingSource = [[GlassDraggingSource alloc] initWithOperation:operation delegate:self];

    NSDraggingSession *session = [self->nsView beginDraggingSessionWithItems:items event:self->lastEvent source:self->draggingSource];
}

- (void)synthesizeMouseUp:(NSEventType)type
{
    NSEvent* theEvent = [NSEvent
        mouseEventWithType:type
                  location:[NSEvent mouseLocation]
             modifierFlags:0
                 timestamp:[NSDate timeIntervalSinceReferenceDate]
              windowNumber:[[self->nsView window] windowNumber]
                   context:[NSGraphicsContext currentContext]
               eventNumber:0
                clickCount:0
                  pressure:0.0];

    [self sendJavaMouseEvent:theEvent];
}

- (void)draggingEnded:(NSDragOperation)operation
{
    DNDLOG("draggingEnded");

    if (self->draggingSource)
    {
        [self->draggingSource release];
        self->draggingSource = nil;
    }

    [GlassDragSource setDelegate:nil];

    // At the end of dragging we're handed a single definitive
    // drag operation (no Generic or Any).
    int32_t mask = com_sun_glass_ui_Clipboard_ACTION_NONE;
    if (operation == NSDragOperationNone) {
        mask = com_sun_glass_ui_Clipboard_ACTION_NONE;
    } else if (operation == NSDragOperationCopy) {
        mask = com_sun_glass_ui_Clipboard_ACTION_COPY;
    } else if (operation == NSDragOperationMove) {
        mask = com_sun_glass_ui_Clipboard_ACTION_MOVE;
    } else if (operation == NSDragOperationLink) {
        mask = com_sun_glass_ui_Clipboard_ACTION_REFERENCE;
    } else {
        DNDLOG("Invalid drag operation");
    }

    GLASS_WARN_IF_MAIN_JAVA_DETACHED;
    const GmacViewCallbacks *callbacks = GlassViewCallbacks();
    if (callbacks != NULL && callbacks->notify_drag_end != NULL && GlassIsMainJavaAttached())
    {
        callbacks->notify_drag_end(self->viewId, mask);
    }

    // JDK-8095312: OS X won't send mouseUp after DnD is complete, so we synthesize them
    if (self->mouseDownMask & 1 << 0) [self synthesizeMouseUp:NSLeftMouseUp];
    if (self->mouseDownMask & 1 << 1) [self synthesizeMouseUp:NSRightMouseUp];
    if (self->mouseDownMask & 1 << 2) [self synthesizeMouseUp:NSOtherMouseUp];
}

- (BOOL)suppressMouseEnterExitOnMouseDown
{
    return YES;
}

- (void)performWindowDrag
{
    [[nsView window] performWindowDragWithEvent:[NSApp currentEvent]];
}

- (void)notifyInputMethod:(id) aString attr:(int)attr length:(int)length cursor:(int)cursor
            selectedRange:(NSRange)selectionRange
{
    if ([NSThread isMainThread] == YES)
    {
        GLASS_WARN_IF_MAIN_JAVA_DETACHED;

        const GmacViewCallbacks *callbacks = GlassViewCallbacks();
        if (callbacks != NULL && GlassIsMainJavaAttached())
        {
            GlassDialInputMethod(callbacks, self->viewId, aString, attr, length, cursor, selectionRange);
        }
    }
}

- (NSRect)getInputMethodCandidatePosRequest:(int)pos
{
    NSRect retVal = NSMakeRect(0.0, 0.0, 0.0, 0.0);
    if ([NSThread isMainThread] == YES)
    {
        // TODO: For some reason result is not always converted to the screen coordinates,
        // and when we call this method before we set text to updated we get the
        // IndexOutOfBoundsException
        // In this case we return an empty rectangle so suggestion window is shown at the
        // bottom left corner of the main screen.
        GLASS_WARN_IF_MAIN_JAVA_DETACHED;
        const GmacViewCallbacks *callbacks = GlassViewCallbacks();
        if (callbacks != NULL)
        {
            double xy[2] = {0.0, 0.0};
            int32_t valid = 0;
            if (callbacks->notify_input_method_candidate_pos_request != NULL && GlassIsMainJavaAttached())
            {
                callbacks->notify_input_method_candidate_pos_request(self->viewId, pos, xy, &valid);
            }
            if (valid != 0)
            {
                retVal = NSMakeRect((CGFloat)xy[0], (CGFloat)xy[1], 0, 0);
            }
        }
    }
    return retVal;
}

- (void)sendJavaFullScreenEvent:(BOOL)entered withNativeWidget:(BOOL)isNative
{
    if (isNative) {
        // Must be done before sending the event to Java since the event handler
        // may re-request the operation.
        if (entered) {
            self->nativeFullScreenModeWindow = [[self->nsView window] retain];
        } else {
            [self->nativeFullScreenModeWindow release];
            self->nativeFullScreenModeWindow = nil;
        }
    }

    GLASS_WARN_IF_MAIN_JAVA_DETACHED;
    const GmacViewCallbacks *callbacks = GlassViewCallbacks();
    if (callbacks != NULL)
    {
        [self _notifyView:entered ? com_sun_glass_events_ViewEvent_FULLSCREEN_ENTER
                                  : com_sun_glass_events_ViewEvent_FULLSCREEN_EXIT
                callbacks:callbacks];
    }
}

- (void)setResizableForFullscreen:(BOOL)resizable
{
    NSWindow* window =  [self->nsView window];
    NSUInteger mask = [window styleMask];
    if (resizable) {
        mask |= NSWindowStyleMaskResizable;
    } else {
        mask &= ~(NSUInteger)NSWindowStyleMaskResizable;
    }
    [window setStyleMask: mask];
}

/*
 The hierarchy for our view is view -> superview (host) -> window

 1. create superview (new host) for our view
 2. create fullscreen window with the new superview
 3. create the background window (for fading out the desktop)
 4. remove our view from the window superview and insert it into the fullscreen window superview
 5. show our fullscreen window (and hide the original window)
 6. attach to it our background window (which will show it as well)
 7. zoom out our fullscreen window and at the same time animate the background window transparency
 8. enter fullscreen
 */
- (void)enterFullscreenWithAnimate:(BOOL)animate withKeepRatio:(BOOL)keepRatio withHideCursor:(BOOL)hideCursor
{
    LOG("GlassViewDelegate enterFullscreenWithAnimate:%d withKeepRatio:%d withHideCursor:%d", animate, keepRatio, hideCursor);

    [[self->nsView window] toggleFullScreen:self];
    // wait until the operation is complete
    [GlassApplication enterFullScreenExitingLoop];
}

- (void)exitFullscreenWithAnimate:(BOOL)animate
{
    LOG("GlassViewDelegate exitFullscreenWithAnimate");

    @try
    {
        if (self->nativeFullScreenModeWindow)
        {
            [self->nativeFullScreenModeWindow performSelector:@selector(toggleFullScreen:) withObject:nil];
            // wait until the operation is complete
            [GlassApplication enterFullScreenExitingLoop];
            return;
        }
    }
    @catch (NSException *e)
    {
        NSLog(@"exitFullscreenWithAnimate caught exception: %@", e);
    }

    [self sendJavaFullScreenEvent:NO withNativeWidget:NO];
}

- (GlassAccessible*)getAccessible
{
    GLASS_WARN_IF_MAIN_JAVA_DETACHED;
    const GmacViewCallbacks *callbacks = GlassViewCallbacks();
    void *accessible = NULL;
    if (callbacks != NULL && callbacks->get_accessible != NULL && GlassIsMainJavaAttached())
    {
        callbacks->get_accessible(self->viewId, &accessible);
    }
    return (GlassAccessible*)accessible;
}

@end
