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


int32_t GetJavaKeyModifiers(NSEvent *event);
int32_t GetJavaMouseModifiers(NSUInteger buttons);
int32_t GetJavaModifiers(NSEvent *event);
int32_t GetJavaKeyCodeFor(unsigned short keyCode);
int32_t GetJavaKeyCode(NSEvent *event);

// The UTF-16 units of [event characters], written to out, for a key that sends its characters to Java; answers their
// count, or -1 for a key that sends none. Callers pass 16 units of out. Latent bug: every unit is written, so more than
// 16 overflow it, as they overflowed the JNI's stack buffer.
__attribute__((visibility("hidden"))) int32_t GetJavaKeyCharsUtf16(NSEvent *event, uint16_t *out);

NSString* GetStringForJavaKey(uint16_t key);
NSString* GetStringForMacKey(unsigned short keyCode, bool shifted);

// for key event injection
BOOL GetMacKey(int32_t javaKeyCode, unsigned short *outMacKeyCode);
