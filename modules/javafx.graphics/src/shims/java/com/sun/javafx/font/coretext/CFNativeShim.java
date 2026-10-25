/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
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

package com.sun.javafx.font.coretext;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.StructLayout;
import java.util.List;
import java.util.Map;

/** Test access to the package-private members of {@link CFNative}. */
public final class CFNativeShim {

    private CFNativeShim() {
    }

    public static Map<String, FunctionDescriptor> boundFunctions() {
        return CFNative.boundFunctions();
    }

    public static List<String> boundData() {
        return CFNative.boundData();
    }

    public static StructLayout cfRange() {
        return CFNative.CFRANGE;
    }

    public static MemorySegment kCFAllocatorDefault() {
        return CFNative.kCFAllocatorDefault;
    }

    public static MemorySegment kCFTypeDictionaryKeyCallBacks() {
        return CFNative.kCFTypeDictionaryKeyCallBacks;
    }

    public static MemorySegment kCFTypeDictionaryValueCallBacks() {
        return CFNative.kCFTypeDictionaryValueCallBacks;
    }

    public static MemorySegment boldCFString() {
        return CFNative.boldCFString();
    }

    public static void openFramework(String name) {
        CFNative.framework(name);
    }

    public static void bindIn(String framework, String function) {
        CFNative.framework(framework).bind(function, FunctionDescriptor.ofVoid());
    }

    public static Error unexpected(Throwable t) {
        return CFNative.unexpected(t);
    }

    public static MemorySegment CFAttributedStringCreate(MemorySegment allocator, MemorySegment string,
                                                         MemorySegment attributes) {
        return CFNative.CFAttributedStringCreate(allocator, string, attributes);
    }

    public static void CFDictionaryAddValue(MemorySegment dictionary, MemorySegment key, MemorySegment value) {
        CFNative.CFDictionaryAddValue(dictionary, key, value);
    }

    public static MemorySegment CFDictionaryCreateMutable(MemorySegment allocator, long capacity,
                                                          MemorySegment keyCallBacks, MemorySegment valueCallBacks) {
        return CFNative.CFDictionaryCreateMutable(allocator, capacity, keyCallBacks, valueCallBacks);
    }

    public static MemorySegment CFDictionaryGetValue(MemorySegment dictionary, MemorySegment key) {
        return CFNative.CFDictionaryGetValue(dictionary, key);
    }

    public static MemorySegment CFRetain(MemorySegment object) {
        return CFNative.CFRetain(object);
    }

    public static MemorySegment CFStringCreateWithCharacters(MemorySegment allocator, MemorySegment chars,
                                                             long numChars) {
        return CFNative.CFStringCreateWithCharacters(allocator, chars, numChars);
    }

    public static MemorySegment CFStringFind(SegmentAllocator allocator, MemorySegment string, MemorySegment find,
                                             long options) {
        return CFNative.CFStringFind(allocator, string, find, options);
    }

    public static char CFStringGetCharacterAtIndex(MemorySegment string, long index) {
        return CFNative.CFStringGetCharacterAtIndex(string, index);
    }

    public static void CFStringGetCharacters(MemorySegment string, MemorySegment range, MemorySegment buffer) {
        CFNative.CFStringGetCharacters(string, range, buffer);
    }

    public static long CFStringGetLength(MemorySegment string) {
        return CFNative.CFStringGetLength(string);
    }

    public static MemorySegment CFURLCreateWithFileSystemPath(MemorySegment allocator, MemorySegment filePath,
                                                              long pathStyle, boolean isDirectory) {
        return CFNative.CFURLCreateWithFileSystemPath(allocator, filePath, pathStyle, isDirectory);
    }
}
