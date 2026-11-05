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

package com.sun.glass.ui.mac;

import com.sun.glass.ui.mac.MacGlassNative.SystemLibrary;
import com.sun.javafx.font.JniStringCodec;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_OK;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_POOL_POP;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_POOL_PUSH;
import static com.sun.glass.ui.mac.MacGlassNative.bind;
import static com.sun.glass.ui.mac.MacGlassNative.free;
import static com.sun.glass.ui.mac.MacGlassNative.newStringUtf;
import static com.sun.glass.ui.mac.MacGlassNative.unexpected;
import static com.sun.glass.ui.mac.MacGlassNative.utf16;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BOOLEAN;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The natives of {@code MacSystemClipboard}: {@code _convertFileReferencePath}, through
 * {@code gmac_clipboard_file_reference_path}, which this class binds when it initializes, and the two
 * {@code FormatEncoder} conversions, which call the CoreServices UTType functions their JNI bodies called, through
 * the CoreFoundation string functions that the bodies' toll-free bridged {@code NSString}s ran (audit g3 §9).
 * <p>
 * None asserts a thread, as no JNI body did: {@code FormatEncoder}'s callers are synchronized and run on any thread,
 * and the UTType functions are thread-safe. Each conversion runs inside a pool, as each body ran between
 * {@code GLASS_POOL_ENTER} and {@code GLASS_POOL_EXIT}, for what the UTType functions autorelease.
 */
final class MacClipboardCalls {

    /** {@code kCFStringEncodingUTF8}. */
    static final int CF_STRING_ENCODING_UTF8 = 0x08000100;

    /** The JNI's conformance argument, which makes every answer of {@link #mimeToUti} dynamic (latent bug L19). */
    static final String MIME_TYPE_CONFORMANCE = "public.mime-type";

    private static final MethodHandle GMAC_CLIPBOARD_FILE_REFERENCE_PATH = bind(
            "gmac_clipboard_file_reference_path",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));

    /** A UTType function of the converted string: a +1 {@code CFStringRef}, or NULL. */
    @FunctionalInterface
    interface Conversion {

        MemorySegment convert(MemorySegment string) throws Throwable;
    }

    private MacClipboardCalls() {
    }

    /**
     * {@code _convertFileReferencePath}, on any thread, as the JNI asserted none: the path of the file URL that
     * {@code path}, parsed as a URL, refers to, such as a file reference URL, else {@code path} itself, decoded as
     * {@code NewStringUTF} decoded it. A null path is taken as {@code ""}.
     */
    static String fileReferencePath(String path) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment outUtf8 = arena.allocate(ADDRESS);
            MemorySegment outLength = arena.allocate(JAVA_INT);
            int status;
            try {
                status = (int) GMAC_CLIPBOARD_FILE_REFERENCE_PATH.invokeExact(utf16(arena, path),
                        path != null ? path.length() : 0, outUtf8, outLength);
            } catch (Throwable t) {
                throw unexpected(t);
            }
            MemorySegment utf8 = outUtf8.get(ADDRESS, 0);
            try {
                return status == GMAC_OK ? newStringUtf(utf8, outLength.get(JAVA_INT, 0)) : null;
            } finally {
                free(utf8);
            }
        }
    }

    /**
     * {@code FormatEncoder._convertMIMEtoUTI}: the identifier {@code UTTypeCreatePreferredIdentifierForTag} answers
     * for the MIME type {@code mime}, conforming to {@value #MIME_TYPE_CONFORMANCE}.
     */
    static String mimeToUti(String mime) {
        return convert(mime, string -> {
            MemorySegment conformance = createString(MIME_TYPE_CONFORMANCE);
            try {
                return (MemorySegment) CoreServices.UT_TYPE_CREATE_PREFERRED_IDENTIFIER_FOR_TAG.invokeExact(
                        (MemorySegment) CoreServices.K_UT_TAG_CLASS_MIME_TYPE.invokeExact(), string, conformance);
            } finally {
                release(conformance);
            }
        });
    }

    /**
     * {@code FormatEncoder._convertUTItoMIME}: the MIME type tag {@code UTTypeCopyPreferredTagWithClass} answers for
     * the UTI {@code uti}.
     */
    static String utiToMime(String uti) {
        return convert(uti, string -> (MemorySegment) CoreServices.UT_TYPE_COPY_PREFERRED_TAG_WITH_CLASS.invokeExact(
                string, (MemorySegment) CoreServices.K_UT_TAG_CLASS_MIME_TYPE.invokeExact()));
    }

    /**
     * The body both conversions shared: a null input is taken as {@code ""}, as {@code nsStringWithJavaString} took
     * it, and a NULL result, or one UTF-8 cannot hold, is null, as {@code NewStringUTF} of a NULL
     * {@code UTF8String} was. The pool is popped however the body ends, so that a throw cannot leave the thread's
     * pool depth raised.
     */
    static String convert(String input, Conversion conversion) {
        poolPush();
        try {
            MemorySegment string = createString(input != null ? input : "");
            try {
                MemorySegment converted;
                try {
                    converted = conversion.convert(string);
                } catch (Throwable t) {
                    throw unexpected(t);
                }
                try {
                    return utf8String(converted);
                } finally {
                    release(converted);
                }
            } finally {
                release(string);
            }
        } finally {
            poolPop();
        }
    }

    /** {@code [[NSString alloc] initWithCharacters:length:]}, +1, which keeps every code unit. */
    private static MemorySegment createString(String s) {
        try (Arena arena = Arena.ofConfined()) {
            return (MemorySegment) CoreFoundation.CF_STRING_CREATE_WITH_CHARACTERS.invokeExact(MemorySegment.NULL,
                    utf16(arena, s), (long) s.length());
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code -[NSString UTF8String]} as {@code NewStringUTF} read it: what {@code CFStringGetCString} writes, to its
     * first NUL, or null for NULL and for a string UTF-8 cannot hold. {@code MacAccessibilityCalls} reads the
     * strings AppKit answers with it too.
     */
    static String utf8String(MemorySegment string) {
        if (string.address() == 0) {
            return null;
        }
        long capacity = maximumSizeForEncoding(stringLength(string)) + 1;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(Math.max(1, capacity));
            if (!getCString(string, buffer, capacity)) {
                return null;
            }
            return JniStringCodec.fromNewStringUtf(buffer);
        }
    }

    private static long stringLength(MemorySegment string) {
        try {
            return (long) CoreFoundation.CF_STRING_GET_LENGTH.invokeExact(string);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static long maximumSizeForEncoding(long length) {
        try {
            return (long) CoreFoundation.CF_STRING_GET_MAXIMUM_SIZE_FOR_ENCODING.invokeExact(length,
                    CF_STRING_ENCODING_UTF8);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static boolean getCString(MemorySegment string, MemorySegment buffer, long capacity) {
        try {
            return (boolean) CoreFoundation.CF_STRING_GET_C_STRING.invokeExact(string, buffer, capacity,
                    CF_STRING_ENCODING_UTF8);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static void release(MemorySegment object) {
        if (object.address() != 0) {
            try {
                CoreFoundation.CF_RELEASE.invokeExact(object);
            } catch (Throwable t) {
                throw unexpected(t);
            }
        }
    }

    private static void poolPush() {
        try {
            GMAC_POOL_PUSH.invokeExact();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static void poolPop() {
        try {
            GMAC_POOL_POP.invokeExact();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** Bound on the first conversion, not with {@code gmac_clipboard_file_reference_path}. */
    private static final class CoreServices {

        private static final SystemLibrary LIBRARY = SystemLibrary.framework("CoreServices");

        static final MethodHandle UT_TYPE_CREATE_PREFERRED_IDENTIFIER_FOR_TAG = LIBRARY.bind(
                "UTTypeCreatePreferredIdentifierForTag", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        static final MethodHandle UT_TYPE_COPY_PREFERRED_TAG_WITH_CLASS = LIBRARY.bind(
                "UTTypeCopyPreferredTagWithClass", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
        static final MethodHandle K_UT_TAG_CLASS_MIME_TYPE = LIBRARY.bindDataValue("kUTTagClassMIMEType");
    }

    private static final class CoreFoundation {

        private static final SystemLibrary LIBRARY = SystemLibrary.framework("CoreFoundation");

        static final MethodHandle CF_STRING_CREATE_WITH_CHARACTERS = LIBRARY.bind("CFStringCreateWithCharacters",
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG));
        static final MethodHandle CF_STRING_GET_LENGTH = LIBRARY.bind("CFStringGetLength",
                FunctionDescriptor.of(JAVA_LONG, ADDRESS));
        /* CFStringEncoding is a UInt32. */
        static final MethodHandle CF_STRING_GET_MAXIMUM_SIZE_FOR_ENCODING = LIBRARY.bind(
                "CFStringGetMaximumSizeForEncoding", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_INT));
        /* Boolean is one byte. */
        static final MethodHandle CF_STRING_GET_C_STRING = LIBRARY.bind("CFStringGetCString",
                FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS, ADDRESS, JAVA_LONG, JAVA_INT));
        static final MethodHandle CF_RELEASE = LIBRARY.bind("CFRelease", FunctionDescriptor.ofVoid(ADDRESS));
    }
}
