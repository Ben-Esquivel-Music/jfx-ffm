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

package com.sun.javafx.font;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;

/**
 * Delegate onto {@code DFontDecoder}: its five {@code private} methods by reflection, and the decode sequence
 * {@code PrismFontFactory.getFontResource} runs on a {@code .dfont} file, called directly.
 */
public final class DFontDecoderShim {

    private DFontDecoderShim() {
    }

    /** The decoded file's bytes, or the exception production would have caught. */
    public record Decoded(byte[] bytes, String failure) {
    }

    public static long createCTFont(String fontName) {
        return invokeStatic("createCTFont", new Class<?>[] {String.class}, fontName);
    }

    public static void releaseCTFont(long font) {
        invokeStatic("releaseCTFont", new Class<?>[] {long.class}, font);
    }

    public static int getCTFontFormat(long font) {
        return invokeStatic("getCTFontFormat", new Class<?>[] {long.class}, font);
    }

    public static int[] getCTFontTags(long font) {
        return invokeStatic("getCTFontTags", new Class<?>[] {long.class}, font);
    }

    public static byte[] getCTFontTable(long font, int tag) {
        return invokeStatic("getCTFontTable", new Class<?>[] {long.class, int.class}, font, tag);
    }

    /** Production deletes the file only on failure; nothing registers this one, so it is deleted after reading. */
    public static Decoded decode(String fontName) {
        DFontDecoder decoder = new DFontDecoder();
        try {
            decoder.openFile();
            decoder.decode(fontName);
            decoder.closeFile();
            byte[] bytes = Files.readAllBytes(decoder.getFile().toPath());
            decoder.deleteFile();
            return new Decoded(bytes, null);
        } catch (Exception e) {
            decoder.deleteFile();
            return new Decoded(null, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T invokeStatic(String name, Class<?>[] parameterTypes, Object... arguments) {
        try {
            Method method = DFontDecoder.class.getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            return (T) method.invoke(null, arguments);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("DFontDecoder." + name + " no longer exists; re-point"
                    + " DFontDecoderShim at its replacement", e);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        }
    }
}
