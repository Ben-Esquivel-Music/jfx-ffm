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

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.Buffer;
import static com.sun.glass.ui.win.WinCallSupport.wideString;
import static com.sun.glass.ui.win.WinGlassNative.LIBRARY_NAME;
import static com.sun.glass.ui.win.WinGlassNative.bind;
import static com.sun.glass.ui.win.WinGlassNative.bindKernel32;
import static com.sun.glass.ui.win.WinGlassNative.bindUser32;
import static com.sun.glass.ui.win.WinGlassNative.bound;
import static com.sun.glass.ui.win.WinGlassNative.systemLibrary;
import static com.sun.glass.ui.win.WinGlassNative.unexpected;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * The {@link WinGlassNative} calls of {@link WinCursor}, and the icon half of {@code Pixels::CreateIcon} that
 * {@code WinWindow} uses.
 * <p>
 * {@link WinCursor} and {@link WinPixels} add no C: the {@code ICONINFO} sequence that
 * {@code Pixels::CreateIcon} ran is five plain OS entry points and two SDK structures, which is the
 * {@code WRAPPER} case, not a reason for a new {@code gwin_*} export.
 * <p>
 * No {@link Linker.Option#critical critical} downcall: the pixels are copied into a GDI DIB section by
 * {@link MemorySegment#copy}, never handed to a downcall, so no call here takes a buffer at all. That the JNI
 * held {@code GetPrimitiveArrayCritical} across {@code CreateDIBSection} and {@code CreateIconIndirect}
 * ({@code Utils.h:438}, {@code Pixels.cpp:122-145}) is a defect this migration drops rather than reproduces.
 * <p>
 * Line numbers into the {@code native-glass/win} C++ sources ({@code Pixels.cpp}, {@code Pixels.h},
 * {@code GlassCursor.cpp}, {@code Utils.h}) refer to those files at commit {@code 8492cb03b0}
 * ({@code git show 8492cb03b0:modules/javafx.graphics/src/main/native-glass/win/<file>}).
 */
final class WinCursorCalls {

    /* winuser.h: the GetSystemMetrics indices of the cursor size (WinCursor's best size). */
    static final int SM_CXCURSOR = 13;
    static final int SM_CYCURSOR = 14;

    /* wingdi.h: what the cursor's colour bitmap is (Pixels.cpp:119,122). */
    static final int BI_RGB = 0;
    static final int DIB_RGB_COLORS = 0;

    /* ICONINFO.fIcon: FALSE makes CreateIconIndirect build a cursor, TRUE an icon. */
    private static final int ICONINFO_CURSOR = 0;
    private static final int ICONINFO_ICON = 1;

    /**
     * The three messages {@code Bitmap::Bitmap} printed when the cursor's monochrome mask could not
     * be built ({@code Pixels.cpp:43,49,61}), kept to the character. They go to {@code System.err} and
     * not to {@code PlatformLogger} because that is where the C put them, and the paths that reach
     * them are unreachable from a valid {@code Pixels} anyway.
     */
    private static final String MASK_FAILED = "Bitmap: Failed to create monochrome bitmap for icon.";

    /**
     * {@code BITMAPINFOHEADER} (wingdi.h; filled at {@code Pixels.cpp:113-121}): 40 bytes, two {@code WORD}s in
     * the middle and no padding - every member is naturally aligned where it falls.
     * {@code WinCursorPixelsNativeTest} pins the size and every offset; the C side of the same check
     * is a {@code static_assert} in {@code glass_win_api.cpp}.
     */
    static final StructLayout BITMAPINFOHEADER_LAYOUT = MemoryLayout.structLayout(
            JAVA_INT.withName("biSize"),
            JAVA_INT.withName("biWidth"),
            JAVA_INT.withName("biHeight"),
            JAVA_SHORT.withName("biPlanes"),
            JAVA_SHORT.withName("biBitCount"),
            JAVA_INT.withName("biCompression"),
            JAVA_INT.withName("biSizeImage"),
            JAVA_INT.withName("biXPelsPerMeter"),
            JAVA_INT.withName("biYPelsPerMeter"),
            JAVA_INT.withName("biClrUsed"),
            JAVA_INT.withName("biClrImportant"));

    /**
     * {@code ICONINFO} (winuser.h) on x64: 32 bytes - three {@code DWORD}s, four bytes of padding
     * before the first {@code HBITMAP}, then the two handles ({@code Pixels.cpp:138-144}). On a
     * 32-bit Windows it would be 20 bytes; the JDK's {@link Linker} supports x64 and aarch64 there,
     * both of which have 8-byte handles, and {@link WinRobotCalls#INPUT_LAYOUT} rests on the same fact.
     */
    static final StructLayout ICONINFO_LAYOUT = MemoryLayout.structLayout(
            JAVA_INT.withName("fIcon"),
            JAVA_INT.withName("xHotspot"),
            JAVA_INT.withName("yHotspot"),
            MemoryLayout.paddingLayout(4),
            ADDRESS.withName("hbmMask"),
            ADDRESS.withName("hbmColor"));

    private static final long BMI_SIZE_OFFSET = bitmapInfoOffset("biSize");
    private static final long BMI_WIDTH_OFFSET = bitmapInfoOffset("biWidth");
    private static final long BMI_HEIGHT_OFFSET = bitmapInfoOffset("biHeight");
    private static final long BMI_PLANES_OFFSET = bitmapInfoOffset("biPlanes");
    private static final long BMI_BIT_COUNT_OFFSET = bitmapInfoOffset("biBitCount");
    private static final long BMI_COMPRESSION_OFFSET = bitmapInfoOffset("biCompression");
    private static final long BMI_SIZE_IMAGE_OFFSET = bitmapInfoOffset("biSizeImage");
    private static final long ICONINFO_FICON_OFFSET = iconInfoOffset("fIcon");
    private static final long ICONINFO_XHOTSPOT_OFFSET = iconInfoOffset("xHotspot");
    private static final long ICONINFO_YHOTSPOT_OFFSET = iconInfoOffset("yHotspot");
    private static final long ICONINFO_HBMMASK_OFFSET = iconInfoOffset("hbmMask");
    private static final long ICONINFO_HBMCOLOR_OFFSET = iconInfoOffset("hbmColor");

    /**
     * {@code int ShowCursor(BOOL bShow)} - {@code GlassCursor.cpp:173}. Bound eagerly, unlike the GDI
     * block below: hiding and showing the pointer must keep working on a machine where a GDI symbol
     * could not be resolved, and it is one call with no structure behind it.
     */
    private static final MethodHandle SHOW_CURSOR = bindUser32("ShowCursor",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT));

    /*
     * HCURSORs and HMODULEs are handle values this process never dereferences: they cross as ADDRESS built
     * by MemorySegment.ofAddress and are never reinterpret()ed.
     */

    /**
     * {@code HCURSOR LoadCursorW(HINSTANCE hInstance, LPCWSTR lpCursorName)} - the {@code W} form
     * {@code ::LoadCursor} resolved to under {@code UNICODE}. {@code lpCursorName} is either a
     * {@code MAKEINTRESOURCE} ordinal (the integer in pointer clothing) or a real string.
     */
    private static final MethodHandle LOAD_CURSOR_W = bindUser32("LoadCursorW",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));

    /** {@code HMODULE GetModuleHandleW(LPCWSTR lpModuleName)}. */
    private static final MethodHandle GET_MODULE_HANDLE_W = bindKernel32("GetModuleHandleW",
            FunctionDescriptor.of(ADDRESS, ADDRESS));

    /**
     * The GDI object calls behind a custom cursor, and the display device context of the screen
     * enumeration, bound on the first {@link #cursorCreateCustom} or {@link WinScreenCalls#collectMonitors()} rather
     * than in this class's initializer.
     * <p>
     * The laziness is blast radius, as it is for {@link WinTimerCalls.Winmm}: a process that shows no custom cursor
     * and enumerates no screen never binds these, and a machine on which one of them could not be
     * resolved loses those two rather than {@link WinRobot}, {@link WinTimer} and
     * {@code Cursor.setVisible}, all of which are bound eagerly. It is not about load order -
     * {@code gdi32.lib} is linked normally by {@code glass} ({@code win.cmake:171}, no {@code /DELAYLOAD}),
     * so {@code gdi32.dll} is already in the process by the time any of this runs.
     * <p>
     * {@code user32!CreateIconIndirect} is bound here and not beside {@code ShowCursor} because it is
     * only ever reached with a pair of GDI bitmaps in hand: it shares this block's fate exactly. The three
     * screen calls come last, so the cursor block keeps its binding order.
     */
    static final class Gdi32 {

        static final String NAME = "gdi32";

        /** A system DLL the JDK does not load for us; the lookup lives for the life of the process. */
        static final SymbolLookup LOOKUP = systemLibrary(NAME + ".dll");

        /**
         * {@code HBITMAP CreateBitmap(int nWidth, int nHeight, UINT nPlanes, UINT nBitCount,
         * const void *lpBits)} - the cursor's monochrome AND mask ({@code Pixels.cpp:57}).
         */
        private static final MethodHandle CREATE_BITMAP = bindGdi32("CreateBitmap",
                FunctionDescriptor.of(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS));

        /**
         * {@code HBITMAP CreateDIBSection(HDC hdc, const BITMAPINFO *pbmi, UINT usage,
         * void **ppvBits, HANDLE hSection, DWORD offset)} - the 32-bit colour bitmap
         * ({@code Pixels.cpp:122}).
         */
        private static final MethodHandle CREATE_DIB_SECTION = bindGdi32("CreateDIBSection",
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));

        /** {@code BOOL DeleteObject(HGDIOBJ ho)} - {@code BaseBitmap}'s destructor, {@code Pixels.h:35}. */
        private static final MethodHandle DELETE_OBJECT = bindGdi32("DeleteObject",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));

        /** {@code BOOL GdiFlush(void)} - {@code Pixels.cpp:148}. */
        private static final MethodHandle GDI_FLUSH = bindGdi32("GdiFlush",
                FunctionDescriptor.of(JAVA_INT));

        /** {@code HICON CreateIconIndirect(PICONINFO piconinfo)} - {@code Pixels.cpp:145}. */
        private static final MethodHandle CREATE_ICON_INDIRECT = bindUser32("CreateIconIndirect",
                FunctionDescriptor.of(ADDRESS, ADDRESS));

        /**
         * {@code HDC CreateDCW(LPCWSTR pwszDriver, LPCWSTR pwszDevice, LPCWSTR pszPort, const DEVMODEW* pdm)} -
         * {@code GetMonitorSettings}' {@code CreateDC(TEXT("DISPLAY"), mix.szDevice, NULL, NULL)}.
         */
        static final MethodHandle CREATE_DC_W = bindGdi32("CreateDCW",
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));

        /** {@code int GetDeviceCaps(HDC hdc, int index)} - the colour depth and the {@code LOGPIXELS} fallback. */
        static final MethodHandle GET_DEVICE_CAPS = bindGdi32("GetDeviceCaps",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));

        /** {@code BOOL DeleteDC(HDC hdc)} - the last statement of {@code GetMonitorSettings}. */
        static final MethodHandle DELETE_DC = bindGdi32("DeleteDC",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));

        private Gdi32() {
        }

        private static MethodHandle bindGdi32(String name, FunctionDescriptor descriptor) {
            return bind(LOOKUP, NAME, name, descriptor);
        }
    }

    private WinCursorCalls() {
    }

    private static long bitmapInfoOffset(String field) {
        return BITMAPINFOHEADER_LAYOUT.byteOffset(PathElement.groupElement(field));
    }

    private static long iconInfoOffset(String field) {
        return ICONINFO_LAYOUT.byteOffset(PathElement.groupElement(field));
    }

    /**
     * The returned value is Windows' cursor <em>display counter</em>, not a success flag: every
     * {@code TRUE} raises it by one, every {@code FALSE} lowers it by one, and the pointer is drawn
     * while it is non-negative. {@link WinCursor} discards it, exactly as the C did - and gets away
     * with it because the latch in {@link WinCursor} only ever calls this on an actual change, so the
     * counter alternates between two adjacent values instead of drifting one step further down per
     * call until nothing can show the pointer again. It is returned here rather than swallowed so
     * that the tests can assert that discipline.
     *
     * @return the display counter after the call
     */
    static int showCursor(boolean show) {
        try {
            return (int) SHOW_CURSOR.invokeExact(show ? 1 : 0);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code Pixels::CreateCursor}, i.e. {@link #buildIcon} with {@code fIcon = FALSE} and the
     * unclamped hotspot - what {@code Java_com_sun_glass_ui_win_WinCursor__1createCursor} ran
     * ({@code GlassCursor.cpp:154-158}). The returned {@code HCURSOR} is owned by the caller and is
     * never destroyed by this class: {@code native-glass/win} contains no {@code DestroyCursor} call
     * at all, and {@code com.sun.glass.ui.Cursor} keeps the value in its {@code ptr} field for the
     * life of the object, where {@code WinCursor.nativeHandleFor} reads it back. Adding a
     * {@code Cleaner} here would be a leak fix, not a migration.
     *
     * @return the {@code HCURSOR} as a {@code long}, or 0
     */
    static long cursorCreateCustom(int width, int height, Buffer pixels, int hotspotX, int hotspotY) {
        return buildIcon(width, height, pixels, ICONINFO_CURSOR, hotspotX, hotspotY);
    }

    /**
     * {@code Pixels::CreateIcon(env, jPixels)} with its defaults - {@code fIcon = TRUE}, hotspot
     * (0, 0) - which is what {@code Java_com_sun_glass_ui_win_WinWindow__1setIcon} built for a
     * non-null {@code Pixels}: the same five GDI / user32 steps as the cursor, on the calling thread
     * as before. <b>The {@code HICON} is handed to {@link WinWindowCalls#windowSetIcon} and is the window's from
     * then on</b>; this class never destroys it and must not. The one behaviour delta, on record:
     * the JNI reached this only after {@code FromHandle} found the GlassWindow, so a stale window
     * built no icon, whereas {@code WinWindow} builds first and a stale window then leaks the one
     * handle the C declined - unreachable through {@code Window.setIcon}, which checks the window is
     * open. The {@code GetBits() == NULL} crash of the JNI (a {@code Pixels} whose buffer could not
     * be attached) is not reproduced: {@link MemorySegment#ofBuffer} answers for every buffer.
     *
     * @return the {@code HICON} as a {@code long}, or 0
     */
    static long iconCreate(int width, int height, Buffer pixels) {
        return buildIcon(width, height, pixels, ICONINFO_ICON, 0, 0);
    }

    /**
     * The sequence of {@code Pixels::CreateIcon} ({@code Pixels.cpp:131-151}). Both bitmaps are deleted
     * afterwards because {@code CreateIconIndirect} has copied them.
     * <p>
     * Every failure is silent and yields 0, as the JNI's were ({@code Pixels.cpp:146} asserts only in
     * a debug build): a mask or colour bitmap that could not be built is still handed to
     * {@code CreateIconIndirect}, which then fails. The one check the JNI did not need is the first:
     * {@code com.sun.glass.ui.Pixels}' constructor rejects those dimensions before a {@code Pixels}
     * can exist ({@code Pixels.java:97-105,122-130}), but this method is also reachable from the
     * tests with arbitrary arguments, and {@code width * height * 4} is computed in {@code int} here
     * as it was in the C ({@code Pixels.cpp:111}).
     * <p>
     * Ownership of the result is the caller's business: see {@link #cursorCreateCustom} and {@link #iconCreate}.
     *
     * @param pixels the premultiplied BGRA pixels, row-major and top-down; position and limit are
     *               ignored, as {@code GetDirectBufferAddress} and {@code array()} ignored them
     * @param kind {@link #ICONINFO_CURSOR} or {@link #ICONINFO_ICON}
     * @return the {@code HCURSOR} / {@code HICON} as a {@code long}, or 0
     */
    private static long buildIcon(int width, int height, Buffer pixels, int kind, int hotspotX, int hotspotY) {
        if (width <= 0 || height <= 0 || width > ((Integer.MAX_VALUE / 4) / height)) {
            return 0;
        }
        int imageSize = width * height * 4;
        Buffer whole = pixels.duplicate();
        whole.clear();
        MemorySegment bits = MemorySegment.ofBuffer(whole);
        if (bits.byteSize() < imageSize) {
            return 0;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment mask = MemorySegment.NULL;
            MemorySegment colour = MemorySegment.NULL;
            try {
                mask = monochromeMask(arena, width, height);
                colour = colourBitmap(arena, width, height, bits, imageSize);
                MemorySegment iconInfo = arena.allocate(ICONINFO_LAYOUT);
                iconInfo.set(JAVA_INT, ICONINFO_FICON_OFFSET, kind);
                iconInfo.set(JAVA_INT, ICONINFO_XHOTSPOT_OFFSET, hotspotX);
                iconInfo.set(JAVA_INT, ICONINFO_YHOTSPOT_OFFSET, hotspotY);
                iconInfo.set(ADDRESS, ICONINFO_HBMMASK_OFFSET, mask);
                iconInfo.set(ADDRESS, ICONINFO_HBMCOLOR_OFFSET, colour);
                long icon = createIconIndirect(iconInfo);
                gdiFlush();
                return icon;
            } finally {
                // The C++ locals were destructors (Pixels.h:35), so both bitmaps went whatever the
                // way out of CreateIcon was, in reverse construction order; DeleteObject(NULL) is a
                // no-op the C made too, through the same `if (hBitmap)`, which is what makes the NULL
                // initialisers safe when the first helper is the one that throws.
                deleteObject(colour);
                deleteObject(mask);
            }
        }
    }

    /**
     * {@code Bitmap::Bitmap(int, int)} ({@code Pixels.cpp:33-66}): a zero-filled, word-aligned
     * monochrome bitmap. The {@code width <= 0 || height <= 0} guard of {@code Pixels.cpp:35-37} is the
     * one made by {@link #buildIcon} before this is called.
     *
     * @return the {@code HBITMAP}, or {@link MemorySegment#NULL}
     */
    private static MemorySegment monochromeMask(Arena arena, int width, int height) {
        if (width > Integer.MAX_VALUE - 15) {
            System.err.println(MASK_FAILED);
            return MemorySegment.NULL;
        }
        // Each scan line of a monochrome bitmap must be word aligned; one plane, one bit per pixel.
        int scanLength = (((width + 15) >> 4) << 1);
        if (scanLength > Integer.MAX_VALUE / height) {
            System.err.println(MASK_FAILED);
            return MemorySegment.NULL;
        }
        MemorySegment maskBits = arena.allocate((long) scanLength * height);
        MemorySegment mask = createBitmap(width, height, 1, 1, maskBits);
        if (mask.address() == 0) {
            System.err.println(MASK_FAILED);
        }
        return mask;
    }

    /**
     * {@code DIBitmap::DIBitmap} ({@code Pixels.cpp:104-129}): a 32-bit top-down {@code BI_RGB} DIB
     * section, filled with the {@code Pixels} bytes. The negative {@code biHeight} is what makes the
     * rows top-down, and the copy is the {@code memcpy} of {@code Pixels.cpp:125}.
     *
     * @return the {@code HBITMAP}, or {@link MemorySegment#NULL}
     */
    private static MemorySegment colourBitmap(Arena arena, int width, int height, MemorySegment bits,
                                              int imageSize) {
        MemorySegment header = arena.allocate(BITMAPINFOHEADER_LAYOUT);
        header.set(JAVA_INT, BMI_SIZE_OFFSET, (int) BITMAPINFOHEADER_LAYOUT.byteSize());
        header.set(JAVA_INT, BMI_WIDTH_OFFSET, width);
        header.set(JAVA_INT, BMI_HEIGHT_OFFSET, -height);
        header.set(JAVA_SHORT, BMI_PLANES_OFFSET, (short) 1);
        header.set(JAVA_SHORT, BMI_BIT_COUNT_OFFSET, (short) 32);
        header.set(JAVA_INT, BMI_COMPRESSION_OFFSET, BI_RGB);
        header.set(JAVA_INT, BMI_SIZE_IMAGE_OFFSET, imageSize);

        MemorySegment bitmapBits = arena.allocate(ADDRESS);
        MemorySegment bitmap = createDibSection(header, bitmapBits);
        MemorySegment target = bitmapBits.get(ADDRESS, 0);
        if (target.address() == 0) {
            // The C dropped the HBITMAP here without deleting it (Pixels.cpp:124-127: Attach is
            // inside the `if`). Unreachable - CreateDIBSection either fails and returns NULL or sets
            // the pointer - and invisible to the caller, so the handle is released rather than lost.
            deleteObject(bitmap);
            return MemorySegment.NULL;
        }
        MemorySegment.copy(bits, 0, bound(target, imageSize), 0, imageSize);
        return bitmap;
    }

    private static MemorySegment createBitmap(int width, int height, int planes, int bitCount,
                                              MemorySegment bits) {
        try {
            return (MemorySegment) Gdi32.CREATE_BITMAP.invokeExact(width, height, planes, bitCount, bits);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static MemorySegment createDibSection(MemorySegment header, MemorySegment bitsOut) {
        try {
            return (MemorySegment) Gdi32.CREATE_DIB_SECTION.invokeExact(MemorySegment.NULL, header,
                    DIB_RGB_COLORS, bitsOut, MemorySegment.NULL, 0);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static long createIconIndirect(MemorySegment iconInfo) {
        try {
            MemorySegment icon = (MemorySegment) Gdi32.CREATE_ICON_INDIRECT.invokeExact(iconInfo);
            return icon.address();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static void deleteObject(MemorySegment object) {
        try {
            int ignoredResult = (int) Gdi32.DELETE_OBJECT.invokeExact(object);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static void gdiFlush() {
        try {
            int ignoredResult = (int) Gdi32.GDI_FLUSH.invokeExact();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * winuser.h {@code IDC_*}: the {@code MAKEINTRESOURCE} ordinals {@code GlassCursor.cpp}'s
     * {@code GetNativeCursor} hands {@code LoadCursorW}, pinned by {@code static_assert} in
     * {@code glass_win_api.cpp}. {@code WinCursor} owns the {@code Cursor} type to ordinal switch.
     */
    static final int IDC_ARROW = 32512;
    static final int IDC_IBEAM = 32513;
    static final int IDC_WAIT = 32514;
    static final int IDC_CROSS = 32515;
    static final int IDC_SIZENWSE = 32642;
    static final int IDC_SIZENESW = 32643;
    static final int IDC_SIZEWE = 32644;
    static final int IDC_SIZENS = 32645;
    static final int IDC_SIZEALL = 32646;
    static final int IDC_HAND = 32649;

    /** The two cursors {@code GlassResources.rc} carries, loaded by name from {@code glass.dll} itself. */
    static final String IDC_CLOSED_HAND = "IDC_CLOSED_HAND";
    static final String IDC_OPEN_HAND = "IDC_OPEN_HAND";

    static long loadSystemCursor(int ordinal) {
        return loadSystemCursor(MemorySegment.ofAddress(ordinal));
    }

    static long loadSystemCursor(String name) {
        try (Arena arena = Arena.ofConfined()) {
            return loadSystemCursor(wideString(arena, name));
        }
    }

    /**
     * The tail of the former {@code GetNativeCursor} ({@code GlassCursor.cpp}). Every resource, ordinal or
     * name, is also tried on {@code glass.dll}'s own module, the only place the two {@code GlassResources.rc}
     * cursors resolve. The C then only ASSERTed the result. Shared system cursors are never destroyed, and
     * the JNI never destroyed these either.
     *
     * @return the {@code HCURSOR} as a {@code long}; 0 only if even the arrow could not be loaded
     */
    private static long loadSystemCursor(MemorySegment resource) {
        long cursor = loadCursor(MemorySegment.NULL, resource);
        if (cursor == 0L) {
            cursor = loadCursor(MemorySegment.ofAddress(moduleHandle(LIBRARY_NAME + ".dll")), resource);
        }
        if (cursor == 0L) {
            cursor = loadCursor(MemorySegment.NULL, MemorySegment.ofAddress(IDC_ARROW));
        }
        return cursor;
    }

    /**
     * {@code GetModuleHandleW(fileName)}: the {@code HMODULE} of a DLL this process has loaded, by
     * base name, or 0. For {@code glass.dll} it is the handle the loader mapped the library at - the
     * {@code HINSTANCE} the C once recorded from {@code DllMain} for {@code GlassApplication::GetHInstance()},
     * a chain deleted once {@code GlassCursor.cpp} went and nothing read it. Java resolves the handle
     * itself, on every {@code NativeLibLoader} path that keeps the file name - {@code WinWindowNativeTest}
     * asserts it by loading a cursor through it.
     */
    static long moduleHandle(String fileName) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment module = (MemorySegment) GET_MODULE_HANDLE_W.invokeExact(wideString(arena, fileName));
            return module.address();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static long loadCursor(MemorySegment module, MemorySegment resource) {
        try {
            MemorySegment cursor = (MemorySegment) LOAD_CURSOR_W.invokeExact(module, resource);
            return cursor.address();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }
}
