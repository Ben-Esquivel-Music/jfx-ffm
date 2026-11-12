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

package test.com.sun.glass.utils;

import com.sun.glass.utils.NativeLibLoader;
import com.sun.glass.utils.NativeLibLoaderShim;
import java.util.ArrayList;
import java.util.List;

/**
 * One {@link NativeLibLoader} call in a JVM of its own, for {@code NativeLibLoaderTest}: what the JVM's native access
 * mode and the loader's process-wide state must not leak into, or out of, the test JVM. It prints the outcome as
 * {@code RESULT <class>: <message>}, or {@code RESULT loaded}, and the {@code NativeLibLoader} methods on the stack of
 * a failure, innermost first, as {@code FRAMES <method>,<method>,...}.
 * <p>
 * Arguments: {@code loadLibrary <name>}, {@code loadLibraryFromSystemPaths <name>}, or
 * {@code openAbsentFiles <true|false> <name>}, which loads {@code name} with the macOS rule for absent files switched
 * on or off.
 */
public final class NativeLibLoaderChild {

    private NativeLibLoaderChild() {
    }

    public static void main(String[] args) {
        try {
            switch (args[0]) {
                case "loadLibrary" -> NativeLibLoader.loadLibrary(args[1]);
                case "loadLibraryFromSystemPaths" -> NativeLibLoader.loadLibraryFromSystemPaths(args[1]);
                case "openAbsentFiles" -> {
                    NativeLibLoaderShim.setOpenAbsentFiles(Boolean.parseBoolean(args[1]));
                    NativeLibLoader.loadLibrary(args[2]);
                }
                default -> throw new IllegalArgumentException("unknown mode " + args[0]);
            }
            System.out.println("RESULT loaded");
        } catch (Throwable t) {
            System.out.println("RESULT " + t.getClass().getName() + ": " + t.getMessage());
            List<String> frames = new ArrayList<>();
            for (StackTraceElement frame : t.getStackTrace()) {
                if (frame.getClassName().equals(NativeLibLoader.class.getName())) {
                    frames.add(frame.getMethodName());
                }
            }
            System.out.println("FRAMES " + String.join(",", frames));
        }
    }
}
