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

#import "GlassMacros.h"
#import "GlassHelper.h"

@implementation GlassHelper

#pragma mark --- Invocation

+ (BOOL)InvokeSelectorIfAvailable:(SEL)aSelector forClass:(Class)aClass withArgument:(void *)anArgument withReturnValue:(void **)aReturnValue
{
    if ([aClass respondsToSelector:aSelector] == YES)
    {
        NSInvocation *invocation = [NSInvocation invocationWithMethodSignature:[aClass methodSignatureForSelector:aSelector]];
        [invocation setSelector:aSelector];
        [invocation setTarget:aClass];

        if (anArgument != NULL)
        {
            [invocation setArgument:anArgument atIndex:2]; // arguments 0 and 1 are self and _cmd respectively, which are set automatically by NSInvocation
        }

        [invocation invoke];

        if (aReturnValue != NULL)
        {
            [invocation getReturnValue:aReturnValue];
        }

        return YES;
    }
    else
    {
        return NO;
    }
}

@end
