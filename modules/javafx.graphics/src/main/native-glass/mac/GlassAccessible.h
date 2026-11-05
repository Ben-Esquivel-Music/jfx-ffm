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

#import <Cocoa/Cocoa.h>

#import "glass_mac_internal.h"

@interface GlassAccessible : NSObject {
@private
    int64_t peerId;
}
- (id)initWithPeerId:(int64_t)peerId;
- (int64_t)getPeerId;
@end

/*
 * The dials of the GmacAccessibleCallbacks slots that GlassAccessible and AccessibleBase share, on the main thread
 * with Java attached. Each answers what its JNI site answered once the call returned. With no table installed, which
 * no peer sees because MacAccessible installs it before it creates one, the sites answer as while Java is detached.
 */
GLASS_INTERNAL id GlassDialAttributeValue(const GmacAccessibleCallbacks *callbacks, int64_t peerId,
                                          NSString *attribute);
GLASS_INTERNAL id GlassDialAttributeValueForParameter(const GmacAccessibleCallbacks *callbacks, int64_t peerId,
                                                      NSString *attribute, id parameter);
GLASS_INTERNAL BOOL GlassDialIsAttributeSettable(const GmacAccessibleCallbacks *callbacks, int64_t peerId,
                                                 NSString *attribute);
GLASS_INTERNAL void GlassDialSetValue(const GmacAccessibleCallbacks *callbacks, int64_t peerId, id value,
                                      NSString *attribute);
GLASS_INTERNAL int32_t GlassDialPerformAction(const GmacAccessibleCallbacks *callbacks, int64_t peerId,
                                              NSString *action);

/* From dealloc, on whichever thread released the peer last. */
GLASS_INTERNAL void GlassDialPeerReleased(const GmacAccessibleCallbacks *callbacks, int64_t peerId);
