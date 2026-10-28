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

package com.sun.glass.ui.win;

import com.sun.glass.ui.Application;
import com.sun.glass.ui.win.WinAccessibilityCalls.UiaCore;
import com.sun.glass.ui.win.WinApplicationCalls.Shlwapi;
import com.sun.glass.ui.win.WinCursorCalls.Gdi32;
import com.sun.glass.ui.win.WinScreenCalls.Shcore;
import com.sun.glass.ui.win.WinTimerCalls.Winmm;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.util.List;
import static com.sun.glass.ui.win.WinGlassNative.GLASS;
import static com.sun.glass.ui.win.WinGlassNative.KERNEL32;
import static com.sun.glass.ui.win.WinGlassNative.KERNEL32_NAME;
import static com.sun.glass.ui.win.WinGlassNative.LIBRARY_NAME;
import static com.sun.glass.ui.win.WinGlassNative.OLE32;
import static com.sun.glass.ui.win.WinGlassNative.OLE32_NAME;
import static com.sun.glass.ui.win.WinGlassNative.USER32;
import static com.sun.glass.ui.win.WinGlassNative.USER32_NAME;
import static com.sun.glass.ui.win.WinGlassNative.bounded;
import static com.sun.glass.ui.win.WinGlassNative.upcallStub;
import static com.sun.glass.ui.win.WinGlassNative.upcallTarget;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_CHAR;
import static java.lang.foreign.ValueLayout.JAVA_CHAR_UNALIGNED;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * What several {@link WinGlassNative} aggregates share: the {@code GwinStatus} values, the two windef.h
 * structures more than one peer fills, the UTF-16 marshalling, the two upcall-failure sinks and the helpers
 * that build and write callback tables. It holds no mutable state, and its initializer computes two layouts and
 * reaches no other class, which is what lets {@link WinGlassNative}'s initializer use it.
 * <p>
 * Line numbers into the {@code native-glass/win} C++ sources ({@code GlassApplication.cpp}, {@code Utils.cpp},
 * {@code Utils.h}, bare {@code :N} forms included) refer to those files at commit {@code 8492cb03b0}
 * ({@code git show 8492cb03b0:modules/javafx.graphics/src/main/native-glass/win/<file>}); a bare number belongs
 * to the C file named last before it.
 */
final class WinCallSupport {

    /** {@code enum GwinStatus} of {@code glass_win_api.h}. */
    static final int GWIN_OK = 0;
    static final int GWIN_ERR_INVALID_ARG = -1;

    /**
     * No toolkit window - never created, or already destroyed. Nothing was scheduled and no callback
     * will arrive for the id that was passed, so the caller may drop its registry entry. The two JNI
     * entry points this replaces returned {@code void} and failed exactly this silently
     * ({@code GlassApplication.cpp:229-235}, {@code :238-245}). It is also the answer when
     * {@code gwin_invoke_and_wait}, called from another thread, cannot create its wait event, and when
     * {@code gwin_invoke_later} cannot allocate its action.
     */
    static final int GWIN_ERR_NO_TOOLKIT = -2;

    /**
     * A callback slot reported a Java {@code Throwable}; the C maps it to {@code E_JAVAEXCEPTION}
     * exactly as {@code OleUtils.h}'s {@code checkJavaException} did.
     */
    static final int GWIN_ERR_UPCALL = -3;

    /**
     * An OLE / COM step failed and the JNI body swallowed the {@code HRESULT} ({@code OLE_CATCH});
     * reported so Java can see it, ignored by every caller here so it stays neutral.
     */
    static final int GWIN_ERR_OLE = -4;

    /** {@code POINT} (windef.h), what {@code GetCursorPos} fills. */
    static final StructLayout POINT_LAYOUT = MemoryLayout.structLayout(
            JAVA_INT.withName("x"),
            JAVA_INT.withName("y"));

    /**
     * {@code RECT} (windef.h), what {@code GetWindowRect} fills for the {@code _getAnchor} WRAPPER
     * ({@link WinWindowCalls#windowAnchor}). Pinned by {@code static_assert} in {@code glass_win_api.cpp}; there
     * is no sizeof export for a Windows SDK structure.
     */
    static final StructLayout RECT_LAYOUT = MemoryLayout.structLayout(
            JAVA_INT.withName("left"),
            JAVA_INT.withName("top"),
            JAVA_INT.withName("right"),
            JAVA_INT.withName("bottom"));

    static final long POINT_X_OFFSET = POINT_LAYOUT.byteOffset(PathElement.groupElement("x"));
    static final long POINT_Y_OFFSET = POINT_LAYOUT.byteOffset(PathElement.groupElement("y"));
    static final long RECT_LEFT_OFFSET = RECT_LAYOUT.byteOffset(PathElement.groupElement("left"));
    static final long RECT_TOP_OFFSET = RECT_LAYOUT.byteOffset(PathElement.groupElement("top"));
    static final long RECT_RIGHT_OFFSET = RECT_LAYOUT.byteOffset(PathElement.groupElement("right"));
    static final long RECT_BOTTOM_OFFSET = RECT_LAYOUT.byteOffset(PathElement.groupElement("bottom"));

    private WinCallSupport() {
    }

    /**
     * The UTF-16 code units of {@code chars} up to its first {@code U+0000}, as a {@code String}: what
     * {@code env->NewString((jchar*)p, wcslen(p))} made of a {@code wchar_t*}, and so what the former
     * {@code CreateJString} and {@code PlatformSupport::putString} returned. Copied unit for unit, not
     * decoded: {@code MemorySegment.getString(0, UTF_16LE)} runs a charset decoder, which replaces an
     * unpaired surrogate with {@code U+FFFD}, while {@code String(char[])} keeps it as the {@code char}
     * it was. Windows does not promise well-formed UTF-16 - a name is a sequence of {@code WCHAR}s -
     * so the decoder would be a silent change of value. Unaligned, as {@code getString} was, because
     * the pointer can be Windows' own.
     * <p>
     * The walk stops at the end of {@code chars}: a buffer with no NUL inside it throws
     * {@link IndexOutOfBoundsException}, as {@code getString} did.
     */
    static String codeUnitsToNul(MemorySegment chars) {
        long length = 0;
        while (chars.getAtIndex(JAVA_CHAR_UNALIGNED, length) != '\0') {
            length++;
        }
        return new String(chars.asSlice(0, length * JAVA_CHAR_UNALIGNED.byteSize()).toArray(JAVA_CHAR_UNALIGNED));
    }

    /**
     * Every export checked here answers {@link #GWIN_OK}, or {@link #GWIN_ERR_INVALID_ARG} for a {@code NULL}
     * buffer, which the facade never passes; the preference queries report every tolerated failure through
     * their struct's flags instead. So any other status is a facade bug, not a platform condition.
     */
    static void requireOk(int status, String function) {
        if (status != GWIN_OK) {
            throw new AssertionError(function + " returned " + status);
        }
    }

    /**
     * {@code CheckAndClearException} (the former {@code Utils.cpp:50-71}): report on this thread, and swallow
     * anything the reporting itself raises. {@link com.sun.glass.ui.Application#reportException} routes
     * to the current thread's uncaught-exception handler ({@code Application.java:448-453}), which is
     * where the C's {@code CallStaticVoidMethod(reportExceptionMID, t)} ended up too - one failure mode
     * fewer, because the {@code FindClass} it needed cannot fail here.
     */
    static void reportUpcallFailure(Throwable t) {
        try {
            Application.reportException(t);
        } catch (Throwable ignored) {
            // Nothing left to try: this is an upcall, and letting anything out of it kills the JVM.
        }
    }

    /**
     * The {@code checkJavaException} sink ({@code OleUtils.h}): {@code ExceptionDescribe} then
     * {@code ExceptionClear} - a stack trace on {@code System.err}, and no Java code of the
     * application runs. Used by every slot whose status the C maps to {@code E_JAVAEXCEPTION},
     * because those fire on OLE frames where an application handler that opened a dialog would nest
     * a modal loop inside a COM callback. Cannot itself throw.
     */
    static void describeUpcallFailure(Throwable t) {
        try {
            t.printStackTrace();
        } catch (Throwable ignored) {
            // Nothing left to try: this is an upcall, and letting anything out of it kills the JVM.
        }
    }

    /**
     * {@code JString} ({@code Utils.h:108-114}) with no JNI: {@code GetStringLength} code units
     * copied into a {@code wchar_t[len + 1]} with a manual NUL, and <em>no charset conversion at
     * all</em>. {@code String.length()} counts UTF-16 code units exactly as {@code GetStringLength}
     * did, {@code JAVA_CHAR} is the platform's native 16-bit order, and an embedded {@code U+0000}
     * survives the copy the same way it did in C - Windows then truncates the visible title there
     * while {@code cch} still reports the full length.
     * <p>
     * Deliberately not {@code arena.allocateFrom(text, UTF_16LE)}: that runs a charset encoder, which
     * writes U+FFFD for every unpaired surrogate that {@code JString} copied as the unit it was. (It
     * does not reject an embedded {@code U+0000}; it copies it, as this does.)
     *
     * @throws NullPointerException if {@code text} is {@code null}. This is the menus' one behaviour
     *         divergence and it replaces undefined behaviour: {@code JString} did not null-check, so
     *         a {@code null} title reached {@code GetStringLength(NULL)}. {@code com.sun.glass.ui.MenuItem}
     *         and {@code Menu} do accept a {@code null} title, so the path was reachable through the
     *         Glass menu API - which nothing in this repository drives on Windows
     */
    static MemorySegment wideString(Arena arena, String text) {
        char[] chars = text.toCharArray();
        MemorySegment segment = arena.allocate(JAVA_CHAR, chars.length + 1L);
        MemorySegment.copy(chars, 0, segment, JAVA_CHAR, 0, chars.length);
        segment.setAtIndex(JAVA_CHAR, chars.length, '\0');
        return segment;
    }

    static MemorySegment[] stubsFor(Class<?> owner, String[] targets, FunctionDescriptor[] descriptors) {
        MemorySegment[] stubs = new MemorySegment[targets.length];
        for (int i = 0; i < targets.length; i++) {
            stubs[i] = upcallStub(upcallTarget(owner, targets[i], descriptors[i]), descriptors[i], Arena.global());
        }
        return stubs;
    }

    static MemorySegment[] stubsFor(Class<?> owner, List<String> targets,
                                            List<FunctionDescriptor> descriptors) {
        MemorySegment[] stubs = new MemorySegment[targets.size()];
        for (int i = 0; i < stubs.length; i++) {
            FunctionDescriptor descriptor = descriptors.get(i);
            stubs[i] = upcallStub(upcallTarget(owner, targets.get(i), descriptor), descriptor, Arena.global());
        }
        return stubs;
    }

    /** Shared with {@code WinGlassNativeShim}'s recording table. */
    static void fillCallbackTable(MemorySegment table, StructLayout layout, List<String> names,
                                  MemorySegment[] stubs) {
        for (int i = 0; i < stubs.length; i++) {
            table.set(ADDRESS, layout.byteOffset(PathElement.groupElement(names.get(i))), stubs[i]);
        }
    }

    /**
     * {@code count} UTF-16 code units at {@code pointer}, copied. A count of 0 - or a {@code NULL}
     * pointer, which the C only ever pairs with 0 - is an empty array, never {@code null}.
     */
    static char[] utf16(MemorySegment pointer, int count) {
        if (count <= 0 || pointer.equals(MemorySegment.NULL)) {
            return new char[0];
        }
        return bounded(pointer, count * JAVA_CHAR.byteSize()).toArray(JAVA_CHAR);
    }

    /** Whether {@code <library>!<name>} resolves; the library must be one the facade binds. */
    static boolean resolves(String qualifiedName) {
        int separator = qualifiedName.indexOf('!');
        if (separator < 0) {
            throw new IllegalArgumentException("not a <library>!<name> pair: " + qualifiedName);
        }
        String library = qualifiedName.substring(0, separator);
        String name = qualifiedName.substring(separator + 1);
        SymbolLookup lookup = switch (library) {
            case LIBRARY_NAME -> GLASS;
            case USER32_NAME -> USER32;
            case KERNEL32_NAME -> KERNEL32;
            case OLE32_NAME -> OLE32;
            // Naming a lazy library here initialises its holder, and so binds it: a test that
            // asks whether winmm!timeSetEvent or shlwapi!AssocQueryStringW resolves gets the true
            // answer rather than "not yet", at the price of loading the DLL it just asked about.
            // shcore is the one holder that binds nothing unless all three of its symbols resolve.
            case Winmm.NAME -> Winmm.LOOKUP;
            case Gdi32.NAME -> Gdi32.LOOKUP;
            case Shlwapi.NAME -> Shlwapi.LOOKUP;
            case Shcore.NAME -> Shcore.LOOKUP;
            case UiaCore.NAME -> UiaCore.LOOKUP;
            default -> throw new IllegalArgumentException("not a library this class binds: " + library);
        };
        return lookup.find(name).isPresent();
    }
}
