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

#import "GlassWindow.h"
#import "glass_mac_api.h"

@interface GlassWindow (Java)

- (void)_sendJavaWindowMoveToAnotherScreenEventIfNeeded;
- (void)_sendJavaWindowMoveEventForFrame:(NSRect)frame;
- (void)_sendJavaWindowResizeEvent:(int)type forFrame:(NSRect)frame;
- (void)_sendJavaWindowNotifyScaleChanged:(CGFloat)newScale;

- (id)_initWithContentRect:(NSRect)contentRect styleMask:(NSUInteger)windowStyle screen:(NSScreen *)screen
                  windowId:(int64_t)identifier;
- (const GmacWindowCallbacks *)_windowCallbacks;
- (void)_ungrabFocus;
+ (void)_resetGrab;
+ (BOOL)_hasGrab;
- (void)_checkUngrab;
- (void)_grabFocus;
- (void)_setResizable:(bool)resizable;
- (NSRect)_constrainFrame:(NSRect)frame;
- (void)_setVisible;
- (void)_setBounds:(int32_t)x y:(int32_t)y xSet:(uint8_t)xSet ySet:(uint8_t)ySet w:(int32_t)w h:(int32_t)h
                cw:(int32_t)cw ch:(int32_t)ch;
- (void)_setWindowFrameWithRect:(NSRect)rect withDisplay:(uint8_t)display withAnimate:(uint8_t)animate;
- (NSScreen*)_getScreen;

- (void)_setFlipFrame:(NSRect)frameRect display:(BOOL)displayFlag animate:(BOOL)animateFlag;
- (NSRect)_flipFrame;

@end
