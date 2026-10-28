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

package com.sun.javafx.font.directwrite;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import static com.sun.javafx.font.directwrite.DWNative.GUID_LAYOUT;
import static com.sun.javafx.font.directwrite.DWNative.HR_2A;
import static com.sun.javafx.font.directwrite.DWNative.HR_OUT;
import static com.sun.javafx.font.directwrite.DWNative.bindOptional;
import static com.sun.javafx.font.directwrite.DWNative.bounded;
import static com.sun.javafx.font.directwrite.DWNative.com;
import static com.sun.javafx.font.directwrite.DWNative.guid;
import static com.sun.javafx.font.directwrite.DWNative.slot;
import static com.sun.javafx.font.directwrite.DWNative.tryLoad;
import static com.sun.javafx.font.directwrite.DWNative.unexpected;
import static com.sun.javafx.font.directwrite.DWNative.virtual;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * The COM apartment, the two factory constructors and the WIC calls of the {@link DWNative} Direct2D + WIC
 * greyscale mask path; {@link DWD2DCalls} has its render-target calls.
 * <p>
 * Together they are the last sixteen natives of directwrite.cpp: CoInitializeEx/CoUninitialize (the COM
 * apartment), the two factory constructors (_WICCreateImagingFactory, _D2D1CreateFactory) and the twelve
 * vtable calls that draw one glyph into a WIC bitmap and read its bytes back.
 * PrismFontFile.getDefaultAAMode() is AA_GREYSCALE, so DWGlyph.getPixelData routes every non-LCD
 * strike through this path: it is the most commonly executed mask path in the product, and the
 * one directwrite-metrics-golden.txt deliberately does not pin.
 * <p>
 * Line numbers into {@code directwrite.cpp} (bare {@code :N} included) and the SDK headers follow
 * {@link DWNative}: commit {@code 8492cb03b0}, Windows SDK 10.0.26100.0.
 */
final class DWWicCalls {

    /** {@code winerror.h}: COM was already initialized on this thread with a different model. */
    static final int RPC_E_CHANGED_MODE = 0x80010106;

    /** {@code winerror.h}: a success HRESULT that is not {@code S_OK} - what a second CoInitializeEx gives. */
    static final int S_FALSE = 0x1;

    /** {@code wtypesbase.h}: {@code CLSCTX_INPROC_SERVER}, the only class context the C asks for. */
    private static final int CLSCTX_INPROC_SERVER = 0x1;

    /** {@code wincodec.h:385}. Factory1, not Factory2 - see the class comment on {@link #wicCreateImagingFactory}. */
    static final String CLSID_WIC_IMAGING_FACTORY = "cacaf262-9370-4615-a13b-9f5539da4c0a";

    /** {@code wincodec.h:387}: only ever used to prove, in a test, that it is NOT the one being passed. */
    static final String CLSID_WIC_IMAGING_FACTORY2 = "317d06e8-5f24-433d-bdf7-79ce68d8abc2";

    /** {@code wincodec.h:6927}: the uuid of the {@code MIDL_INTERFACE} declaring IWICImagingFactory. */
    static final String IID_IWIC_IMAGING_FACTORY = "ec5ec8a9-c395-4314-9c77-54d7a935ff70";

    /** {@code d2d1.h:3341}: the uuid of the {@code DX_DECLARE_INTERFACE} declaring ID2D1Factory. */
    static final String IID_ID2D1_FACTORY = "06152247-6f50-465a-9245-118bfd3b6007";

    /**
     * The eleven {@code GUID_WICPixelFormat*} values of the switch at {@code directwrite.cpp:2371-2384},
     * indexed by the {@code OS.GUID_WICPixelFormat*} constant minus one (wincodec.h:547-565). Only
     * {@code 32bppPBGRA} ({@code DWGlyph.java:50}) is reachable today; the table stays complete so that
     * the mapping - and its {@code default: return NULL} before any COM call - is reproduced exactly.
     */
    private static final String[] WIC_PIXEL_FORMAT_UUIDS = {
            "6fddc324-4e03-4bfe-b185-3d77768dc908",   //  1 GUID_WICPixelFormat8bppGray,      :547
            "e6cd0116-eeba-4161-aa85-27dd9fb3a895",   //  2 GUID_WICPixelFormat8bppAlpha,     :548
            "6fddc324-4e03-4bfe-b185-3d77768dc90b",   //  3 GUID_WICPixelFormat16bppGray,     :554
            "6fddc324-4e03-4bfe-b185-3d77768dc90d",   //  4 GUID_WICPixelFormat24bppRGB,      :556
            "6fddc324-4e03-4bfe-b185-3d77768dc90c",   //  5 GUID_WICPixelFormat24bppBGR,      :555
            "6fddc324-4e03-4bfe-b185-3d77768dc90e",   //  6 GUID_WICPixelFormat32bppBGR,      :557
            "6fddc324-4e03-4bfe-b185-3d77768dc90f",   //  7 GUID_WICPixelFormat32bppBGRA,     :558
            "6fddc324-4e03-4bfe-b185-3d77768dc910",   //  8 GUID_WICPixelFormat32bppPBGRA,    :559
            "6fddc324-4e03-4bfe-b185-3d77768dc911",   //  9 GUID_WICPixelFormat32bppGrayFloat,:560
            "f5c7ad2d-6a8d-43dd-a7a8-a29935261ae9",   // 10 GUID_WICPixelFormat32bppRGBA,     :564
            "3cc4a650-a527-4d37-a916-3142c7ebedba" }; // 11 GUID_WICPixelFormat32bppPRGBA,    :565

    /* IWICImagingFactory : IUnknown, wincodec.h:6927. base 3 + ordinal. */
    static final int WIC_FACTORY_CREATE_BITMAP = 17;                // ord 14, wincodec.h:6988

    /*
     * IWICBitmap : IWICBitmapSource, wincodec.h:2330. IWICBitmapSource (wincodec.h:1357) declares five
     * methods, so the base is 8; slots 3 and 4 are its GetSize and GetPixelFormat, reached through an
     * IWICBitmap pointer, which is what pins the base arithmetic behaviourally.
     */
    static final int WIC_BITMAP_GET_SIZE = 3;                       // IWICBitmapSource ord 0, :1361
    static final int WIC_BITMAP_GET_PIXEL_FORMAT = 4;               // IWICBitmapSource ord 1, :1365
    static final int WIC_BITMAP_LOCK = 8;                           // ord 0, wincodec.h:2334

    /* IWICBitmapLock : IUnknown, wincodec.h:2209. base 3 + ordinal. */
    static final int WIC_LOCK_GET_SIZE = 3;                         // ord 0, wincodec.h:2213
    static final int WIC_LOCK_GET_STRIDE = 4;                       // ord 1, wincodec.h:2217
    static final int WIC_LOCK_GET_DATA_POINTER = 5;                 // ord 2, wincodec.h:2220

    /** {@code WICRect} (wincodec.h:491): four {@code INT}, 16 bytes. The stack struct of {@code :2395}. */
    static final StructLayout WIC_RECT_LAYOUT = MemoryLayout.structLayout(
            JAVA_INT.withName("X"),                   // offset 0
            JAVA_INT.withName("Y"),                   // offset 4
            JAVA_INT.withName("Width"),               // offset 8
            JAVA_INT.withName("Height"));             // offset 12, byteSize 16

    /** {@code D2D1_FACTORY_OPTIONS} (d2d1.h:1009): one {@code D2D1_DEBUG_LEVEL}, 4 bytes. */
    static final StructLayout D2D1_FACTORY_OPTIONS_LAYOUT = MemoryLayout.structLayout(
            JAVA_INT.withName("debugLevel"));         // offset 0, byteSize 4

    /**
     * {@code HRESULT f(this, UINT w, UINT h, REFWICPixelFormatGUID, WICBitmapCreateCacheOption,
     * IWICBitmap** out)}: {@code IWICImagingFactory::CreateBitmap} (wincodec.h:6988). The pixel format
     * is a {@code const GUID&} (wincodec.h:534), so it travels as a pointer like any other reference.
     */
    private static final MethodHandle HR_CREATE_BITMAP = virtual(FunctionDescriptor.of(
            JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));

    /** {@code HRESULT f(this, CONST WICRect*, DWORD, IWICBitmapLock** out)}: {@code IWICBitmap::Lock}. */
    private static final MethodHandle HR_LOCK =
            virtual(FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS));

    /**
     * The eleven WIC pixel-format GUIDs in one 176-byte {@link Arena#global() global} block, allocated
     * once. Nothing on the Direct2D + WIC path ever builds a GUID on a call path.
     */
    private static final MemorySegment WIC_PIXEL_FORMAT_GUIDS = wicPixelFormatBlock();

    private static final MemorySegment CLSID_WIC_IMAGING_FACTORY_BYTES =
            guid(Arena.global(), CLSID_WIC_IMAGING_FACTORY);
    private static final MemorySegment IID_IWIC_IMAGING_FACTORY_BYTES =
            guid(Arena.global(), IID_IWIC_IMAGING_FACTORY);

    /** {@code __uuidof(ID2D1Factory)}, the {@code REFIID} of {@code directwrite.cpp:894}. */
    private static final MemorySegment IID_ID2D1_FACTORY_BYTES = guid(Arena.global(), IID_ID2D1_FACTORY);

    private static MemorySegment wicPixelFormatBlock() {
        long size = GUID_LAYOUT.byteSize();
        MemorySegment block = Arena.global()
                .allocate(size * WIC_PIXEL_FORMAT_UUIDS.length, GUID_LAYOUT.byteAlignment());
        try (Arena scratch = Arena.ofConfined()) {
            for (int i = 0; i < WIC_PIXEL_FORMAT_UUIDS.length; i++) {
                MemorySegment.copy(guid(scratch, WIC_PIXEL_FORMAT_UUIDS[i]), 0, block, i * size, size);
            }
        }
        return block;
    }

    /**
     * The 16 bytes of the {@code GUID_WICPixelFormat*} the {@code OS} constant {@code pixelFormat}
     * names, or {@code null} for a value the switch at {@code directwrite.cpp:2371-2384} does not
     * list - which returns {@code NULL} <em>before</em> {@code CreateBitmap} is ever called.
     */
    private static MemorySegment wicPixelFormatGuid(int pixelFormat) {
        if (pixelFormat < 1 || pixelFormat > WIC_PIXEL_FORMAT_UUIDS.length) {
            return null;
        }
        long size = GUID_LAYOUT.byteSize();
        return WIC_PIXEL_FORMAT_GUIDS.asSlice((pixelFormat - 1) * size, size);
    }

    private DWWicCalls() {
    }

    /**
     * {@code ole32.dll}, loaded on <b>first use</b>. The JNI build imported
     * {@code CoInitializeEx}/{@code CoUninitialize}/{@code CoCreateInstance} statically, so ole32 was
     * pulled in the moment {@code javafx_font.dll} loaded; but {@code DWFactory.getWICFactory} is the
     * only caller, and it runs on the D2D/render thread the first time a greyscale or fallback mask is
     * needed. A holder class keeps that timing and that thread, and keeps a JavaFX process that never
     * draws text free of the dependency. A missing library leaves every handle {@code null}, which the
     * facade methods below turn into the same "COM unavailable" answers the C produced.
     */
    private static final class Ole32 {

        private static final SymbolLookup LIBRARY = tryLoad("ole32.dll");

        /** {@code HRESULT CoInitializeEx(LPVOID pvReserved, DWORD dwCoInit)} (combaseapi.h). */
        static final MethodHandle CO_INITIALIZE_EX = bindOptional(LIBRARY, "ole32.dll",
                "CoInitializeEx", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));

        /** {@code void CoUninitialize(void)} (combaseapi.h). */
        static final MethodHandle CO_UNINITIALIZE = bindOptional(LIBRARY, "ole32.dll",
                "CoUninitialize", FunctionDescriptor.ofVoid());

        /**
         * {@code HRESULT CoCreateInstance(REFCLSID, LPUNKNOWN, DWORD, REFIID, LPVOID*)}
         * (combaseapi.h). Both GUIDs cross as {@code const GUID&}, i.e. as pointers.
         */
        static final MethodHandle CO_CREATE_INSTANCE = bindOptional(LIBRARY, "ole32.dll",
                "CoCreateInstance",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));

        private Ole32() {
        }
    }

    /**
     * {@code d2d1.dll}, loaded on <b>first use</b>, which is what {@code directwrite.cpp:885} does:
     * {@code LoadLibrary(TEXT("d2d1.dll"))} inside {@code _D2D1CreateFactory}, reached only from
     * {@code DWFactory.getD2DFactory()} behind {@code checkThread()}. Binding it from the
     * {@code DWNative} initializer would pull d2d1.dll and its {@code DllMain} into every JavaFX
     * process - including LCD-only ones and ones that never draw text - at font-factory creation time
     * and on the FX thread instead. {@link Arena#global()} matches the missing {@code FreeLibrary} at
     * {@code directwrite.cpp:885}.
     */
    private static final class D2D1 {

        private static final SymbolLookup LIBRARY = tryLoad("d2d1.dll");

        /**
         * {@code HRESULT WINAPI D2D1CreateFactory(D2D1_FACTORY_TYPE, REFIID,
         * CONST D2D1_FACTORY_OPTIONS*, void**)} (d2d1.h:3666-3672), or {@code null} when the library
         * or the export is missing - the {@code hr = E_FAIL} the C leaves behind at {@code directwrite.cpp:883}.
         */
        static final MethodHandle CREATE_FACTORY = bindOptional(LIBRARY, "d2d1.dll",
                "D2D1CreateFactory",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));

        private D2D1() {
        }
    }

    /* ---------------------------------------------------------------------------------------------
     * The COM apartment - directwrite.cpp:848-864
     * ------------------------------------------------------------------------------------------- */

    /**
     * {@code CoInitializeEx(NULL, dwCoInit)} ({@code directwrite.cpp:848-858}), returning what the C
     * returned: {@code false} for {@code RPC_E_CHANGED_MODE} and {@code true} for everything else -
     * including {@code S_FALSE}, which is what a second initialization of an already-initialized
     * thread answers, and including a genuine failure HRESULT, which the C also reported as success.
     * <p>
     * The call is per <em>thread</em>, and so is its {@link #coUninitialize} partner:
     * {@code DWFactory.getWICFactory()} makes it on the first thread that asks for a WIC or D2D
     * factory ({@code DWFactory.checkThread()} pins that thread for the life of the process) and the
     * {@code GraphicsPipeline} dispose hook makes the matching {@code CoUninitialize} on the same
     * thread. {@code RPC_E_CHANGED_MODE} does <b>not</b> increment the per-thread initialization count
     * COM keeps, and the C correspondingly does not call {@code CoUninitialize} on that path: it
     * returns {@code null} from {@code getWICFactory} before the dispose hook is ever registered.
     *
     * @return {@code false} only when COM is already initialized on this thread with a different
     *         concurrency model
     */
    static boolean coInitializeEx(int dwCoInit) {
        if (Ole32.CO_INITIALIZE_EX == null) {
            return false;
        }
        try {
            int hr = (int) Ole32.CO_INITIALIZE_EX.invokeExact(MemorySegment.NULL, dwCoInit);
            return hr != RPC_E_CHANGED_MODE;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code CoUninitialize()} ({@code directwrite.cpp:860-864}); a no-op when ole32 is unavailable. */
    static void coUninitialize() {
        if (Ole32.CO_UNINITIALIZE == null) {
            return;
        }
        try {
            Ole32.CO_UNINITIALIZE.invokeExact();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /* ---------------------------------------------------------------------------------------------
     * The two factories - directwrite.cpp:866-899
     * ------------------------------------------------------------------------------------------- */

    /**
     * {@code CoCreateInstance(CLSID_WICImagingFactory, NULL, CLSCTX_INPROC_SERVER,
     * IID_IWICImagingFactory, &result)} ({@code directwrite.cpp:866-878}); {@code 0} on any failure.
     * It does not initialize COM itself - {@code DWFactory.getWICFactory} does, and bails out when
     * that fails.
     * <p>
     * <b>Which CLSID.</b> wincodec.h:385 defines {@code CLSID_WICImagingFactory} as factory1 and
     * :388-390 redefines it to factory2 under
     * {@code #if(_WIN32_WINNT >= _WIN32_WINNT_WIN8) || defined(_WIN7_PLATFORM_UPDATE)}.
     * {@code win.cmake:257} compiles the font target with {@code /D_WIN32_WINNT=0x0601} and nothing in
     * {@code native-font} or in the SDK defines {@code _WIN7_PLATFORM_UPDATE}, so the shipped
     * {@code javafx_font.dll} carries factory1 - confirmed by searching the binary for both 16-byte
     * encodings: factory1 is present once, factory2 is absent.
     */
    static long wicCreateImagingFactory() {
        if (Ole32.CO_CREATE_INSTANCE == null) {
            return 0L;
        }
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment out = scratch.allocate(ADDRESS);
            int hr = (int) Ole32.CO_CREATE_INSTANCE.invokeExact(CLSID_WIC_IMAGING_FACTORY_BYTES,
                    MemorySegment.NULL, CLSCTX_INPROC_SERVER, IID_IWIC_IMAGING_FACTORY_BYTES, out);
            return hr >= 0 ? out.get(ADDRESS, 0).address() : 0L;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code D2D1CreateFactory(factoryType, __uuidof(ID2D1Factory), &options, &result)}
     * ({@code directwrite.cpp:880-899}); {@code 0} when d2d1.dll is absent, does not export
     * {@code D2D1CreateFactory}, or fails - the three paths the C collapses into a silent
     * {@code hr = E_FAIL}. {@code D2D1_FACTORY_OPTIONS} is four zero bytes
     * ({@code D2D1_DEBUG_LEVEL_NONE}, {@code :891-892}); the C never passes {@code NULL} there, so
     * neither does this.
     */
    static long d2d1CreateFactory(int factoryType) {
        if (D2D1.CREATE_FACTORY == null) {
            return 0L;
        }
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment options = scratch.allocate(D2D1_FACTORY_OPTIONS_LAYOUT);
            MemorySegment out = scratch.allocate(ADDRESS);
            int hr = (int) D2D1.CREATE_FACTORY.invokeExact(factoryType, IID_ID2D1_FACTORY_BYTES,
                    options, out);
            return hr >= 0 ? out.get(ADDRESS, 0).address() : 0L;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /* ---------------------------------------------------------------------------------------------
     * IWICImagingFactory / IWICBitmap / IWICBitmapLock - directwrite.cpp:2365-2423
     * ------------------------------------------------------------------------------------------- */

    /**
     * {@code IWICImagingFactory::CreateBitmap}, slot 17 (wincodec.h:6988)
     * ({@code directwrite.cpp:2365-2388}). The eleven-way switch over {@code OS.GUID_WICPixelFormat*}
     * becomes {@link #wicPixelFormatGuid}, and its {@code default: return NULL} keeps its position:
     * an unknown value returns {@code 0} <em>before</em> any COM call is made.
     */
    static long createBitmap(long self, int uiWidth, int uiHeight, int pixelFormat, int options) {
        MemorySegment format = wicPixelFormatGuid(pixelFormat);
        if (format == null) {
            return 0L;
        }
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment out = scratch.allocate(ADDRESS);
            MemorySegment obj = com(self);
            int hr = (int) HR_CREATE_BITMAP.invokeExact(slot(obj, WIC_FACTORY_CREATE_BITMAP), obj,
                    uiWidth, uiHeight, format, options, out);
            return hr >= 0 ? out.get(ADDRESS, 0).address() : 0L;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code IWICBitmap::Lock}, slot 8 (wincodec.h:2334) ({@code directwrite.cpp:2390-2398}). The
     * rectangle is always passed, never {@code NULL} - the C builds a stack {@code WICRect} at
     * {@code :2395} whatever the arguments are.
     */
    static long lock(long self, int x, int y, int width, int height, int flags) {
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment rect = scratch.allocate(WIC_RECT_LAYOUT);
            rect.setAtIndex(JAVA_INT, 0, x);
            rect.setAtIndex(JAVA_INT, 1, y);
            rect.setAtIndex(JAVA_INT, 2, width);
            rect.setAtIndex(JAVA_INT, 3, height);
            MemorySegment out = scratch.allocate(ADDRESS);
            MemorySegment obj = com(self);
            int hr = (int) HR_LOCK.invokeExact(slot(obj, WIC_BITMAP_LOCK), obj, rect, flags, out);
            return hr >= 0 ? out.get(ADDRESS, 0).address() : 0L;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code IWICBitmapLock::GetDataPointer}, slot 5 (wincodec.h:2220)
     * ({@code directwrite.cpp:2401-2415}): the whole locked buffer copied into a fresh
     * {@code byte[]} of exactly {@code cbBufferSize}, or {@code null} on a failing HRESULT.
     * {@code DWGlyph.getD2DMask} then walks it row by row using {@link #getStride}, so the copy is
     * reproduced verbatim here; handing the loop a bounded {@code MemorySegment} instead would give
     * identical output and skip up to 256 KB per glyph, but it changes an interface signature and
     * belongs in a separate performance commit, not in a migration.
     * <p>
     * The length is a {@code UINT32}. The C passed it to {@code NewByteArray} as a {@code jsize}, so a
     * value above 2^31 would have raised {@code NegativeArraySizeException}; here it raises
     * {@code IllegalStateException} from {@code toArray} instead. A 256x256 32-bit bitmap locks
     * 256 KB, so neither is reachable.
     */
    static byte[] getDataPointer(long self) {
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment size = scratch.allocate(JAVA_INT);
            MemorySegment data = scratch.allocate(ADDRESS);
            MemorySegment obj = com(self);
            int hr = (int) HR_2A.invokeExact(slot(obj, WIC_LOCK_GET_DATA_POINTER), obj, size, data);
            if (hr < 0) {
                return null;
            }
            long cbBufferSize = Integer.toUnsignedLong(size.get(JAVA_INT, 0));
            return bounded(data.get(ADDRESS, 0), cbBufferSize).toArray(JAVA_BYTE);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code IWICBitmapLock::GetStride}, slot 4 (wincodec.h:2217)
     * ({@code directwrite.cpp:2417-2423}). A failure is indistinguishable from a stride of zero, as
     * there ({@code SUCCEEDED(hr) ? result : NULL}); {@code DWGlyph.getD2DMask} would then walk row 0
     * repeatedly. Reproduced rather than improved: an exception here would be a behaviour change.
     */
    static int getStride(long self) {
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment out = scratch.allocate(JAVA_INT);
            MemorySegment obj = com(self);
            int hr = (int) HR_OUT.invokeExact(slot(obj, WIC_LOCK_GET_STRIDE), obj, out);
            return hr >= 0 ? out.get(JAVA_INT, 0) : 0;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code IWICBitmapSource::GetSize}, slot 3 (wincodec.h:1361), reached through an
     * {@code IWICBitmap} pointer: {@code {width, height}}, or {@code null} on a failing HRESULT. This
     * is the base-arithmetic pin - {@code IWICBitmapSource} contributes five methods, which is what
     * puts {@code Lock} at slot 8.
     */
    static int[] wicBitmapSize(long self) {
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment width = scratch.allocate(JAVA_INT);
            MemorySegment height = scratch.allocate(JAVA_INT);
            MemorySegment obj = com(self);
            int hr = (int) HR_2A.invokeExact(slot(obj, WIC_BITMAP_GET_SIZE), obj, width, height);
            return hr >= 0 ? new int[] { width.get(JAVA_INT, 0), height.get(JAVA_INT, 0) } : null;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code IWICBitmapSource::GetPixelFormat}, slot 4 (wincodec.h:1365), as the 16 raw GUID bytes -
     * the independent check that {@link #createBitmap} really handed WIC the GUID the
     * {@code OS.GUID_WICPixelFormat*} constant names.
     */
    static byte[] wicBitmapPixelFormat(long self) {
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment format = scratch.allocate(GUID_LAYOUT);
            MemorySegment obj = com(self);
            int hr = (int) HR_OUT.invokeExact(slot(obj, WIC_BITMAP_GET_PIXEL_FORMAT), obj, format);
            return hr >= 0 ? format.toArray(JAVA_BYTE) : null;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code IWICBitmapLock::GetSize}, slot 3 (wincodec.h:2213): {@code {width, height}} of the locked
     * rectangle, or {@code null} on a failing HRESULT. Adjacent to {@code GetStride} (4) and
     * {@code GetDataPointer} (5), and it answers something neither of them does, so no permutation of
     * the three passes.
     */
    static int[] wicBitmapLockSize(long self) {
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment width = scratch.allocate(JAVA_INT);
            MemorySegment height = scratch.allocate(JAVA_INT);
            MemorySegment obj = com(self);
            int hr = (int) HR_2A.invokeExact(slot(obj, WIC_LOCK_GET_SIZE), obj, width, height);
            return hr >= 0 ? new int[] { width.get(JAVA_INT, 0), height.get(JAVA_INT, 0) } : null;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /* ---------------------------------------------------------------------------------------------
     * Direct2D + WIC test and diagnostic support
     * ------------------------------------------------------------------------------------------- */

    /** Whether ole32.dll loaded and exports all three COM entry points; loads it if it has not been. */
    static boolean isOle32Available() {
        return Ole32.CO_INITIALIZE_EX != null && Ole32.CO_UNINITIALIZE != null
                && Ole32.CO_CREATE_INSTANCE != null;
    }

    /** Whether d2d1.dll loaded and exports {@code D2D1CreateFactory}; loads it if it has not been. */
    static boolean isD2D1Available() {
        return D2D1.CREATE_FACTORY != null;
    }

    /**
     * The 16 bytes of the {@code GUID_WICPixelFormat*} an {@code OS} constant names, or {@code null}
     * for a value the C switch does not list.
     */
    static byte[] wicPixelFormatGuidBytes(int pixelFormat) {
        MemorySegment guid = wicPixelFormatGuid(pixelFormat);
        return guid == null ? null : guid.toArray(JAVA_BYTE);
    }

    /** How many pixel formats the table carries; the C switch has exactly this many cases. */
    static int wicPixelFormatCount() {
        return WIC_PIXEL_FORMAT_UUIDS.length;
    }
}
