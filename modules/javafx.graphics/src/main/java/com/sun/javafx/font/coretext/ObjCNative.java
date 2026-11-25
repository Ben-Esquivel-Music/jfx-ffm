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

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The autorelease pools of the Objective-C runtime. CoreText autoreleases objects into the calling thread's pool, and
 * a thread that has none keeps them until it exits. {@code libobjc} is not a framework, so it is opened by its own
 * install name; the linker and the failure handling are those of {@link CFNative}.
 */
public final class ObjCNative {

    static final String LIBOBJC = "/usr/lib/libobjc.A.dylib";

    private static final Map<String, FunctionDescriptor> FUNCTIONS = new LinkedHashMap<>();

    private static final SymbolLookup OBJC = library();

    // The token is a long, not an ADDRESS: a returned address is boxed into a segment after libobjc has pushed the
    // pool, and an OutOfMemoryError there would leave the pool pushed. Both pass alike on arm64 and x86_64.
    private static final MethodHandle OBJC_AUTORELEASE_POOL_PUSH = bind("objc_autoreleasePoolPush",
            FunctionDescriptor.of(JAVA_LONG));
    private static final MethodHandle OBJC_AUTORELEASE_POOL_POP = bind("objc_autoreleasePoolPop",
            FunctionDescriptor.ofVoid(JAVA_LONG));

    private ObjCNative() {
    }

    /** Binds the two pool functions if they are not bound yet: calling it initialises this class. */
    public static void link() {
    }

    static Map<String, FunctionDescriptor> boundFunctions() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(FUNCTIONS));
    }

    /** The library for the life of the process; an {@link UnsatisfiedLinkError} if it cannot be opened. */
    @SuppressWarnings("restricted")
    private static SymbolLookup library() {
        try {
            return SymbolLookup.libraryLookup(LIBOBJC, Arena.global());
        } catch (IllegalArgumentException e) {
            UnsatisfiedLinkError error = new UnsatisfiedLinkError("cannot load " + LIBOBJC + " (macOS font support): "
                    + e.getMessage());
            error.initCause(e);
            throw error;
        }
    }

    @SuppressWarnings("restricted")
    private static MethodHandle bind(String function, FunctionDescriptor descriptor) {
        MethodHandle handle = CFNative.LINKER.downcallHandle(find(function), descriptor);
        FUNCTIONS.put(function, descriptor);
        return handle;
    }

    static MemorySegment find(String symbol) {
        return OBJC.find(symbol).orElseThrow(() -> new UnsatisfiedLinkError(LIBOBJC + " does not export " + symbol));
    }

    /** Pushes a pool on this thread; the token answered may be popped only on this thread. */
    public static long objc_autoreleasePoolPush() {
        try {
            return (long) OBJC_AUTORELEASE_POOL_PUSH.invokeExact();
        } catch (Throwable t) {
            throw CFNative.unexpected(t);
        }
    }

    /** Pops the pool of {@code pool} and every pool this thread pushed after it, releasing what they hold. */
    public static void objc_autoreleasePoolPop(long pool) {
        try {
            OBJC_AUTORELEASE_POOL_POP.invokeExact(pool);
        } catch (Throwable t) {
            throw CFNative.unexpected(t);
        }
    }
}
