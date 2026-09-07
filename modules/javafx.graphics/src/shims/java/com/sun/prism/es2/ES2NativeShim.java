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

package com.sun.prism.es2;

import java.util.List;

/**
 * Test access to the {@code prism_es2} binding layer of {@code com.sun.prism.es2}.
 * <p>
 * Compiled into the module by the {@code compile-shims} execution of the javafx.graphics pom, which
 * compiles {@code src/main/java} and {@code src/shims/java} together, so it may call the package-private
 * {@link ES2Native} and read its package-private constants and layout; the tests in
 * {@code test.com.sun.prism.es2} reach it through the
 * {@code --add-exports javafx.graphics/com.sun.prism.es2=ALL-UNNAMED} line of {@code src/test/addExports}.
 * The restricted {@code java.lang.foreign} calls stay inside {@link ES2Native}, the only class the module
 * grants native access to; this shim just forwards the binding facts the tests assert - the symbols the
 * facade bound, the ABI version and the one struct layout - without opening a rendering path.
 */
public final class ES2NativeShim {

    private ES2NativeShim() {
    }

    /**
     * Loads the {@code prism_es2} library, binds every {@code es2_*} symbol and checks the ABI version,
     * without going through {@code ES2Pipeline}.
     *
     * @throws UnsatisfiedLinkError if the library cannot be loaded, lacks a symbol the facade binds, was
     *         denied native access, or reports another ABI version
     */
    public static void loadLibrary() {
        ES2Native.loadLibrary();
    }

    public static List<String> boundSymbols() {
        return ES2Native.boundSymbols();
    }

    public static List<String> missingSymbols() {
        return ES2Native.missingSymbols();
    }

    public static int expectedAbiVersion() {
        return ES2Native.ABI_VERSION;
    }

    public static int abiVersion() {
        return ES2Native.abiVersion();
    }

    public static long sizeofPixelFormatAttrs() {
        return ES2Native.sizeofPixelFormatAttrs();
    }

    public static long pixelFormatAttrsLayoutByteSize() {
        return ES2Native.PIXEL_FORMAT_ATTRS.byteSize();
    }
}
