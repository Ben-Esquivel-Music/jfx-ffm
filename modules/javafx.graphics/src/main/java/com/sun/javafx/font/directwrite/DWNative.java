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

import com.sun.javafx.geom.Path2D;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_CHAR;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * The DirectWrite calls behind {@code DWFactory}, {@code DWFontFile} and {@code DWGlyphLayout}, bound
 * directly from Java: what the {@code Java_com_sun_javafx_font_directwrite_OS_*} bodies of
 * {@code directwrite.cpp} wrap in JNI. It owns the {@link Linker}, the {@code dwrite.dll}
 * {@link SymbolLookup}, the cache every downcall handle comes from, the {@code GUID} layout and the COM
 * vtable dispatch helper, and it is the only class in this package that uses a restricted
 * {@code java.lang.foreign} method.
 *
 * <h2>Aggregates</h2>
 * Every peer calls this class. A call whose body is not here is one statement that hands over to a
 * package-private aggregate, so that no file of the binding exceeds 900 lines: {@link DWFontCalls},
 * {@link DWFontFaceCalls}, {@link DWGeometrySinkObject}, {@link DWShapingCalls},
 * {@link DWAnalysisSinkObject}, {@link DWTextRendererObject}, {@link DWWicCalls} and {@link DWD2DCalls},
 * with {@link DWLayouts}, {@link DWComObjectSupport} and {@link DWInboundTestHooks} beside them. The
 * aggregates link their vtable shapes through {@link #virtual} and their upcall stubs through
 * {@link #upcall}, so every restricted call stays here and {@code HANDLES} stays the one cache. Their
 * initializers reach only this class and {@link DWLayouts}, whose own initializers reach no class of the
 * package, so no two initializers can wait for each other.
 *
 * <h2>How a COM method is called</h2>
 * {@code dwrite.dll} exports exactly one of the functions this class needs by name
 * ({@code DWriteCreateFactory}, dwrite.h:5123); everything else is reached through a COM vtable, which
 * <em>is</em> the ABI and is a plain C ABI - {@code this} in RCX, the one Win64 calling convention. A
 * COM interface pointer {@code p} points at a struct whose word 0 is the vtable pointer
 * (unknwnbase.h:222), and slot {@code n} of that vtable holds the function pointer of the {@code n}-th
 * method (unknwnbase.h:198-218 fixes {@code QueryInterface}, {@code AddRef} and {@code Release} as
 * slots 0, 1 and 2 of <em>every</em> interface; an interface's own methods follow its bases' in header
 * declaration order). {@link #slot} reads that function pointer; the call goes through a
 * {@link MethodHandle} produced by the address-less
 * {@link Linker#downcallHandle(FunctionDescriptor, Linker.Option...)} overload, whose handle takes the
 * target address as an extra leading parameter. The <em>signature</em> is therefore linked once and
 * cached while the <em>address</em> is read per call, which is what makes caching virtual dispatch
 * safe: two objects of one interface may come from different implementation classes with different
 * vtables.
 *
 * <h2>Slot numbers</h2>
 * Every slot constant here and in the aggregates carries the header line its method is declared on, and the
 * arithmetic is always {@code base + ordinal}: 3 for a direct subinterface of {@code IUnknown}, 6 for
 * {@code IDWriteFontFamily}, whose base {@code IDWriteFontList} adds three methods of its own
 * (dwrite.h:1512, :1548). {@code DWNativeTest} proves the table behaviourally, so a wrong slot fails a
 * test rather than corrupting memory silently.
 *
 * <h2>Reference counting</h2>
 * Every {@code _COM_Outptr_} parameter hands back a pointer that already carries one reference for the
 * caller, so the Java peer built around it ({@code new IDWriteFontFamily(ptr)} and friends) owns
 * exactly that one reference and must {@code Release()} it exactly once. This class never
 * {@code AddRef}s implicitly, never stores a {@link MemorySegment} derived from a COM pointer and
 * never lets an {@link Arena} own a COM object: closing an arena here can only free scratch memory.
 * {@link #addRef} and {@link #release} exist because {@code OS.AddRef} and {@code OS.Release} do, and
 * behave identically - including {@code AddRef} having no null guard, which is unreachable because
 * {@code IUnknown.Release()} zeroes the pointer it releases.
 *
 * <h2>Failure to load</h2>
 * {@code directwrite.cpp:906-916} calls {@code LoadLibrary("dwrite.dll")} then
 * {@code GetProcAddress("DWriteCreateFactory")} and returns {@code NULL} when either fails;
 * {@code DWFactory.getFactory()} depends on that null meaning "DirectWrite unavailable".
 * {@link SymbolLookup#libraryLookup} throws instead, so it is wrapped: a missing library or symbol
 * leaves {@link #dwriteCreateFactory} returning {@code 0}. Nothing in this class's initializer, or in an
 * aggregate's, can throw, deliberately - the first thread to touch it may be the "Prism Font Disposer" daemon
 * ({@code Disposer} by way of {@code DWDisposer}), whose loop catches {@code Exception} but not
 * {@code Error}, so an {@code ExceptionInInitializerError} raised there would kill the disposer thread
 * for the life of the process and leak every {@code DisposerRecord} after it.
 * <p>
 * Line numbers into {@code directwrite.cpp}, bare {@code :N} forms included, refer to it at commit
 * {@code 8492cb03b0} ({@code git show 8492cb03b0:modules/javafx.graphics/src/main/native-font/directwrite.cpp});
 * line numbers into {@code dwrite.h}, {@code dwrite_2.h}, {@code d2d1.h}, {@code d2dbasetypes.h},
 * {@code dcommon.h}, {@code wincodec.h} and {@code unknwnbase.h} refer to the Windows SDK 10.0.26100.0
 * ({@code Include/10.0.26100.0/um}).
 */
final class DWNative {

    /** {@code winerror.h}; what {@code directwrite.cpp} leaves {@code hr} at when a call never happened. */
    static final int E_FAIL = 0x80004005;

    /** {@code winerror.h}: what every successful COM call returns. */
    static final int S_OK = 0;

    /** {@code IUnknown} (unknwnbase.h): the one IID COM guarantees pointer identity for. */
    static final String IID_IUNKNOWN = "00000000-0000-0000-c000-000000000046";

    /** {@code IDWriteFactory}: the uuid of the {@code DWRITE_DECLARE_INTERFACE} at dwrite.h:4704. */
    static final String IID_IDWRITE_FACTORY = "b859ee5a-d838-4b5b-a2e8-1adc7d93db48";

    /* IUnknown, unknwnbase.h:198-218: the first three slots of every COM interface, in this order. */
    static final int IUNKNOWN_QUERY_INTERFACE = 0;                     // unknwnbase.h:203
    static final int IUNKNOWN_ADD_REF = 1;                             // unknwnbase.h:210
    static final int IUNKNOWN_RELEASE = 2;                             // unknwnbase.h:214

    /** {@code GUID} (guiddef.h): 16 bytes, 4-byte aligned. */
    static final StructLayout GUID_LAYOUT = MemoryLayout.structLayout(
            JAVA_INT.withName("Data1"),                                    // offset 0
            JAVA_SHORT.withName("Data2"),                                  // offset 4
            JAVA_SHORT.withName("Data3"),                                  // offset 6
            MemoryLayout.sequenceLayout(8, JAVA_BYTE).withName("Data4"));  // offset 8, byteSize 16

    private static final Linker LINKER = Linker.nativeLinker();

    /**
     * The symbols bound by name. Copy-on-write because the {@code ole32.dll} and {@code d2d1.dll}
     * holder classes of {@link DWWicCalls} add to it lazily, on whichever thread first needs COM or Direct2D, while
     * {@link #boundSymbols} may be read from another.
     */
    private static final List<String> BOUND_SYMBOLS = new CopyOnWriteArrayList<>();

    /**
     * One handle per {@link FunctionDescriptor}, never per slot: linkage depends on the signature
     * alone, and the slot only chooses which address that handle is invoked on. The map is consulted
     * when a shape is first bound - class initialization here or in an aggregate - and never on a call
     * path, where every site holds a {@code static final} handle.
     */
    private static final Map<FunctionDescriptor, MethodHandle> HANDLES = new ConcurrentHashMap<>();

    /*
     * The vtable shapes more than one aggregate dispatches through, "this" included and the target address
     * implied by the address-less overload.
     */

    /** {@code ULONG f(this)}: AddRef, Release, GetWeight, GetStretch, GetStyle, GetSimulations, the counts. */
    static final MethodHandle U32_THIS = virtual(FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** {@code HRESULT f(this, void** out)}. */
    static final MethodHandle HR_OUT = virtual(FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    /** {@code HRESULT f(this, UINT32, void** out)}: GetFontFamily(index), GetFont, GetStringLength. */
    static final MethodHandle HR_I_OUT =
            virtual(FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));

    /** {@code HRESULT f(this, void*, void** out)}: QueryInterface, GetFontFromFontFace. */
    static final MethodHandle HR_P_OUT =
            virtual(FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));

    /**
     * {@code HRESULT f(this, void*, void*, void*)} - three pointers after {@code this}:
     * FindFamilyName, FindLocaleName, CreateFontFileReference. Kept apart from {@link DWFontCalls#HR_4A} because
     * {@code IDWriteFontFile::Analyze} takes a fourth out-pointer that lands on the stack at
     * {@code [RSP+0x20]}; one shared five-pointer shape would leave that slot unwritten.
     */
    static final MethodHandle HR_3A =
            virtual(FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));

    /**
     * {@code void f(this, void*)}: {@code IDWriteFontFace::GetMetrics} (an out-pointer, dwrite.h:1092)
     * and {@code IDWriteFontFace::ReleaseFontTable} (an opaque context, dwrite.h:1188). Both return
     * {@code void}, so neither has an error channel.
     */
    static final MethodHandle VOID_THIS_P = virtual(FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));

    /** {@code HRESULT f(this, void*, void*)}: {@code GetDataPointer}, {@code GetSize}, {@code EndDraw}. */
    static final MethodHandle HR_2A =
            virtual(FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));

    /** {@code dwrite.dll}, or {@code null} where it cannot be loaded - see the class comment. */
    private static final SymbolLookup DWRITE = tryLoad("dwrite.dll");

    /**
     * {@code HRESULT DWriteCreateFactory(DWRITE_FACTORY_TYPE, REFIID, IUnknown**)} (dwrite.h:5123), or
     * {@code null} when {@code dwrite.dll} is absent or does not export it.
     */
    private static final MethodHandle DWRITE_CREATE_FACTORY = bindOptional(DWRITE, "dwrite.dll",
            "DWriteCreateFactory", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));

    /** The one 16-byte constant this class keeps for the life of the process. */
    private static final MemorySegment IID_IDWRITE_FACTORY_BYTES = guid(Arena.global(), IID_IDWRITE_FACTORY);

    private DWNative() {
    }

    /* ---------------------------------------------------------------------------------------------
     * Linkage
     * ------------------------------------------------------------------------------------------- */

    /**
     * {@code LoadLibrary(fileName)}, whose failure the C treated as "DirectWrite unavailable"
     * ({@code directwrite.cpp:906-910}). Never throws: {@link SymbolLookup#libraryLookup} raises
     * {@link IllegalArgumentException} for a library that will not load, and this class must be
     * initializable everywhere.
     */
    @SuppressWarnings("restricted")
    static SymbolLookup tryLoad(String fileName) {
        try {
            return SymbolLookup.libraryLookup(fileName, Arena.global());
        } catch (IllegalArgumentException | UnsatisfiedLinkError e) {
            return null;
        }
    }

    /** {@code GetProcAddress}: {@code null} rather than an error when the library or the symbol is absent. */
    @SuppressWarnings("restricted")
    static MethodHandle bindOptional(SymbolLookup library, String libraryName, String name,
                                     FunctionDescriptor descriptor) {
        if (library == null) {
            return null;
        }
        MemorySegment symbol = library.find(name).orElse(null);
        if (symbol == null) {
            return null;
        }
        BOUND_SYMBOLS.add(libraryName + "!" + name);
        return LINKER.downcallHandle(symbol, descriptor);
    }

    /**
     * A handle for one vtable shape. The address-less overload gives a handle whose leading
     * {@link MemorySegment} parameter is the function to call, so one handle serves every object of
     * every interface that has this signature. None of these calls is
     * {@link Linker.Option#critical critical}: DirectWrite allocates, takes locks and can reach the
     * font-cache service.
     */
    @SuppressWarnings("restricted")
    static MethodHandle virtual(FunctionDescriptor descriptor) {
        return HANDLES.computeIfAbsent(descriptor, shape -> LINKER.downcallHandle(shape));
    }

    /**
     * Slot {@code index} of the vtable {@code self} carries in its first word (unknwnbase.h:222). Two
     * reinterprets and two reads; no linkage, nothing retained.
     */
    @SuppressWarnings("restricted")
    static MemorySegment slot(MemorySegment self, int index) {
        MemorySegment vtable = self.reinterpret(ADDRESS.byteSize()).get(ADDRESS, 0);
        return vtable.reinterpret((index + 1L) * ADDRESS.byteSize()).getAtIndex(ADDRESS, index);
    }

    /** The COM object at {@code ptr}, as the zero-length segment {@link #slot} bounds per call. */
    static MemorySegment com(long ptr) {
        return MemorySegment.ofAddress(ptr);
    }

    /**
     * One upcall stub in {@link Arena#global()}: legitimate only because the target is static. The target
     * is a static method of the class that declares the holder whose initializer made {@code lookup}.
     */
    @SuppressWarnings("restricted")
    static MemorySegment upcall(MethodHandles.Lookup lookup, String name, FunctionDescriptor descriptor,
                                Class<?> returnType, Class<?>... parameterTypes)
            throws ReflectiveOperationException {
        MethodHandle target = lookup.findStatic(lookup.lookupClass().getNestHost(), name,
                MethodType.methodType(returnType, parameterTypes));
        return LINKER.upcallStub(target, descriptor, Arena.global());
    }

    /**
     * A pointer that arrived from native code, given the size it is documented to have. Segments from
     * outside have length zero, and this is the only place in the package allowed to widen one.
     */
    @SuppressWarnings("restricted")
    static MemorySegment bounded(MemorySegment segment, long byteSize) {
        return segment.reinterpret(byteSize);
    }

    static Error unexpected(Throwable t) {
        if (t instanceof Error e) {
            return e;
        }
        if (t instanceof RuntimeException e) {
            throw e;
        }
        // Only linkage failures can land here: COM reports failure in the HRESULT, it cannot throw.
        return new AssertionError(t);
    }

    /**
     * {@code guiddef.h} byte order for a {@code REFIID}: {@code Data1}, {@code Data2} and {@code Data3}
     * are little-endian integers, {@code Data4} is eight bytes in writing order. So
     * {@code b859ee5a-d838-4b5b-a2e8-1adc7d93db48} is
     * {@code 5a ee 59 b8 38 d8 5b 4b a2 e8 1a dc 7d 93 db 48}.
     */
    static MemorySegment guid(Arena arena, String uuid) {
        if (uuid.length() != 36 || uuid.charAt(8) != '-' || uuid.charAt(13) != '-'
                || uuid.charAt(18) != '-' || uuid.charAt(23) != '-') {
            throw new IllegalArgumentException("not a uuid: " + uuid);
        }
        String hex = uuid.substring(0, 8) + uuid.substring(9, 13) + uuid.substring(14, 18)
                + uuid.substring(19, 23) + uuid.substring(24);
        byte[] raw = new byte[16];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        MemorySegment id = arena.allocate(GUID_LAYOUT);
        id.set(JAVA_INT, 0, ((raw[0] & 0xFF) << 24) | ((raw[1] & 0xFF) << 16)
                | ((raw[2] & 0xFF) << 8) | (raw[3] & 0xFF));
        id.set(JAVA_SHORT, 4, (short) (((raw[4] & 0xFF) << 8) | (raw[5] & 0xFF)));
        id.set(JAVA_SHORT, 6, (short) (((raw[6] & 0xFF) << 8) | (raw[7] & 0xFF)));
        MemorySegment.copy(raw, 8, id, JAVA_BYTE, 8, 8);
        return id;
    }

    /**
     * The {@code WCHAR const*} form of {@code chars}, which {@code GetCharArrayElements} handed over
     * verbatim: no terminator is added here, because the peers append one to the string they pass
     * ({@code (name + '\0').toCharArray()}). A {@code null} array becomes {@code NULL}, which is what
     * the C's {@code if (arg)} guards left the pointer at.
     */
    static MemorySegment wide(Arena arena, char[] chars) {
        return chars == null ? MemorySegment.NULL : arena.allocateFrom(JAVA_CHAR, chars);
    }

    /* ---------------------------------------------------------------------------------------------
     * Shapes shared by several aggregates
     * ------------------------------------------------------------------------------------------- */

    /** {@code HRESULT f(this, void** out)}: the pointer, or {@code 0} on any failing HRESULT. */
    static long outPointer(long self, int index) {
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment out = scratch.allocate(ADDRESS);
            MemorySegment obj = com(self);
            int hr = (int) HR_OUT.invokeExact(slot(obj, index), obj, out);
            return hr >= 0 ? out.get(ADDRESS, 0).address() : 0L;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code ULONG f(this)}: no out-parameter, no error channel, and therefore no arena. */
    static int u32(long self, int index) {
        MemorySegment obj = com(self);
        try {
            return (int) U32_THIS.invokeExact(slot(obj, index), obj);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /* ---------------------------------------------------------------------------------------------
     * Test and diagnostic support
     * ------------------------------------------------------------------------------------------- */

    /**
     * Runs the initializers of this class and of every aggregate, which loads {@code dwrite.dll} and links
     * every vtable shape; the five lazy holders stay untouched.
     */
    static void ensureLoaded() {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        try {
            for (Class<?> aggregate : List.of(DWLayouts.class, DWComObjectSupport.class, DWFontCalls.class,
                    DWFontFaceCalls.class, DWGeometrySinkObject.class, DWShapingCalls.class,
                    DWAnalysisSinkObject.class, DWTextRendererObject.class, DWInboundTestHooks.class,
                    DWWicCalls.class, DWD2DCalls.class)) {
                lookup.ensureInitialized(aggregate);
            }
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    static boolean isAvailable() {
        return DWRITE_CREATE_FACTORY != null;
    }

    /** The symbols bound by name, as {@code <dll>!<name>}; the vtable methods have no names to list. */
    static List<String> boundSymbols() {
        return Collections.unmodifiableList(BOUND_SYMBOLS);
    }

    /** Whether {@code fileName} can be loaded at all - the {@link #tryLoad} catch, exercised. */
    static boolean canLoad(String fileName) {
        return tryLoad(fileName) != null;
    }

    /** The 16 bytes {@code uuid} marshals to, for the {@code REFIID} byte-order test. */
    static byte[] guidBytes(String uuid) {
        try (Arena scratch = Arena.ofConfined()) {
            return guid(scratch, uuid).toArray(JAVA_BYTE);
        }
    }

    /* ---------------------------------------------------------------------------------------------
     * IUnknown - unknwnbase.h:198-218
     * ------------------------------------------------------------------------------------------- */

    /**
     * {@code IUnknown::QueryInterface}, slot 0. Not one of the JNI natives: it is here because slot 0
     * is the cheapest self-check that word 0 of a COM object really is its vtable, and because code
     * that needs a derived interface asks for it this way.
     *
     * @return the interface pointer, already carrying a reference for the caller, or {@code 0}
     */
    static long queryInterface(long self, String iid) {
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment id = guid(scratch, iid);
            MemorySegment out = scratch.allocate(ADDRESS);
            MemorySegment obj = com(self);
            int hr = (int) HR_P_OUT.invokeExact(slot(obj, IUNKNOWN_QUERY_INTERFACE), obj, id, out);
            return hr >= 0 ? out.get(ADDRESS, 0).address() : 0L;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code IUnknown::AddRef}, slot 1 ({@code directwrite.cpp:920-924}); no null guard, as there. */
    static int addRef(long self) {
        MemorySegment obj = com(self);
        try {
            return (int) U32_THIS.invokeExact(slot(obj, IUNKNOWN_ADD_REF), obj);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code IUnknown::Release}, slot 2 ({@code directwrite.cpp:926-930}): the new reference count.
     * The null guard and the zeroing of the peer's pointer stay in {@code IUnknown.Release()}, which is
     * what makes a double dispose safe.
     */
    static int release(long self) {
        MemorySegment obj = com(self);
        try {
            return (int) U32_THIS.invokeExact(slot(obj, IUNKNOWN_RELEASE), obj);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /* ---------------------------------------------------------------------------------------------
     * IDWriteFactory - dwrite.h:4704
     * ------------------------------------------------------------------------------------------- */

    /**
     * {@code DWriteCreateFactory(factoryType, __uuidof(IDWriteFactory), &factory)}
     * ({@code directwrite.cpp:901-917}): the factory, or {@code 0} when DirectWrite is unavailable or
     * the call failed. The {@code dwrite.dll} handle is never freed, exactly as the C never
     * {@code FreeLibrary}s it.
     */
    static long dwriteCreateFactory(int factoryType) {
        if (DWRITE_CREATE_FACTORY == null) {
            return 0L;
        }
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment out = scratch.allocate(ADDRESS);
            int hr = (int) DWRITE_CREATE_FACTORY.invokeExact(factoryType, IID_IDWRITE_FACTORY_BYTES, out);
            return hr >= 0 ? out.get(ADDRESS, 0).address() : 0L;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /* ---------------------------------------------------------------------------------------------
     * The calls whose bodies are in an aggregate, in the aggregates' order
     * ------------------------------------------------------------------------------------------- */

    static long getSystemFontCollection(long self, boolean checkForUpdates) {
        return DWFontCalls.getSystemFontCollection(self, checkForUpdates);
    }

    static long createFontFileReference(long self, char[] filePath) {
        return DWFontCalls.createFontFileReference(self, filePath);
    }

    static long createFontFace(long self, int fontFaceType, long fontFile, int faceIndex,
                               int fontFaceSimulationFlags) {
        return DWFontCalls.createFontFace(self, fontFaceType, fontFile, faceIndex, fontFaceSimulationFlags);
    }

    static int getFontFamilyCount(long self) {
        return DWFontCalls.getFontFamilyCount(self);
    }

    static long getFontFamily(long self, int index) {
        return DWFontCalls.getFontFamily(self, index);
    }

    static int findFamilyName(long self, char[] familyName) {
        return DWFontCalls.findFamilyName(self, familyName);
    }

    static long getFontFromFontFace(long self, long fontFace) {
        return DWFontCalls.getFontFromFontFace(self, fontFace);
    }

    static int getFontCount(long self) {
        return DWFontCalls.getFontCount(self);
    }

    static long getFont(long self, int index) {
        return DWFontCalls.getFont(self, index);
    }

    static long getFamilyNames(long self) {
        return DWFontCalls.getFamilyNames(self);
    }

    static long getFirstMatchingFont(long self, int weight, int stretch, int style) {
        return DWFontCalls.getFirstMatchingFont(self, weight, stretch, style);
    }

    static long createFontFace(long self) {
        return DWFontCalls.createFontFace(self);
    }

    static long getFaceNames(long self) {
        return DWFontCalls.getFaceNames(self);
    }

    static long getFontFamily(long self) {
        return DWFontCalls.getFontFamily(self);
    }

    static int getStretch(long self) {
        return DWFontCalls.getStretch(self);
    }

    static int getStyle(long self) {
        return DWFontCalls.getStyle(self);
    }

    static int getWeight(long self) {
        return DWFontCalls.getWeight(self);
    }

    static long getInformationalStrings(long self, int informationalStringID) {
        return DWFontCalls.getInformationalStrings(self, informationalStringID);
    }

    static int getSimulations(long self) {
        return DWFontCalls.getSimulations(self);
    }

    static char[] getString(long self, int index, int size) {
        return DWFontCalls.getString(self, index, size);
    }

    static int getStringLength(long self, int index) {
        return DWFontCalls.getStringLength(self, index);
    }

    static int findLocaleName(long self, char[] locale) {
        return DWFontCalls.findLocaleName(self, locale);
    }

    static int analyze(long self, boolean[] isSupportedFontType, int[] fontFileType, int[] fontFaceType,
                       int[] numberOfFaces) {
        return DWFontCalls.analyze(self, isSupportedFontType, fontFileType, fontFaceType, numberOfFaces);
    }

    static DWRITE_GLYPH_METRICS getDesignGlyphMetrics(long self, short glyphIndex, boolean isSideways) {
        return DWFontFaceCalls.getDesignGlyphMetrics(self, glyphIndex, isSideways);
    }

    static long createGlyphRunAnalysis(long self, DWRITE_GLYPH_RUN glyphRun, float pixelsPerDip,
                                       DWRITE_MATRIX transform, int renderingMode, int measuringMode,
                                       float baselineOriginX, float baselineOriginY) {
        return DWFontFaceCalls.createGlyphRunAnalysis(self, glyphRun, pixelsPerDip, transform, renderingMode,
                measuringMode, baselineOriginX, baselineOriginY);
    }

    static RECT getAlphaTextureBounds(long self, int textureType) {
        return DWFontFaceCalls.getAlphaTextureBounds(self, textureType);
    }

    static byte[] createAlphaTexture(long self, int textureType, RECT textureBounds) {
        return DWFontFaceCalls.createAlphaTexture(self, textureType, textureBounds);
    }

    static Path2D getGlyphRunOutline(long self, float emSize, short glyphIndex, boolean isSideways) {
        return DWGeometrySinkObject.getGlyphRunOutline(self, emSize, glyphIndex, isSideways);
    }

    static long createTextAnalyzer(long self) {
        return DWShapingCalls.createTextAnalyzer(self);
    }

    static long createTextFormat(long self, char[] fontFamily, long fontCollection, int fontWeight,
                                 int fontStyle, int fontStretch, float fontSize, char[] localeName) {
        return DWShapingCalls.createTextFormat(self, fontFamily, fontCollection, fontWeight, fontStyle, fontStretch,
                fontSize, localeName);
    }

    static long createTextLayout(long self, char[] text, int start, int count, long textFormat,
                                 float maxWidth, float maxHeight) {
        return DWShapingCalls.createTextLayout(self, text, start, count, textFormat, maxWidth, maxHeight);
    }

    static int analyzeScript(long self, long source, int textPosition, int textLength, long sink) {
        return DWShapingCalls.analyzeScript(self, source, textPosition, textLength, sink);
    }

    static int getGlyphs(long self, char[] textString, int textStart, int textLength, long fontFace,
                         boolean isSideways, boolean isRightToLeft,
                         DWRITE_SCRIPT_ANALYSIS scriptAnalysis, char[] localeName,
                         long numberSubstitution, long[] features, int[] featureRangeLengths,
                         int featureRanges, int maxGlyphCount, short[] clusterMap, short[] textProps,
                         short[] glyphIndices, short[] glyphProps, int[] actualGlyphCount) {
        return DWShapingCalls.getGlyphs(self, textString, textStart, textLength, fontFace, isSideways, isRightToLeft,
                scriptAnalysis, localeName, numberSubstitution, features, featureRangeLengths, featureRanges,
                maxGlyphCount, clusterMap, textProps, glyphIndices, glyphProps, actualGlyphCount);
    }

    static int getGlyphPlacements(long self, char[] textString, short[] clusterMap, short[] textProps,
                                  int textStart, int textLength, short[] glyphIndices,
                                  short[] glyphProps, int glyphCount, long fontFace, float fontEmSize,
                                  boolean isSideways, boolean isRightToLeft,
                                  DWRITE_SCRIPT_ANALYSIS scriptAnalysis, char[] localeName,
                                  long[] features, int[] featureRangeLengths, int featureRanges,
                                  float[] glyphAdvances, float[] glyphOffsets) {
        return DWShapingCalls.getGlyphPlacements(self, textString, clusterMap, textProps, textStart, textLength,
                glyphIndices, glyphProps, glyphCount, fontFace, fontEmSize, isSideways, isRightToLeft, scriptAnalysis,
                localeName, features, featureRangeLengths, featureRanges, glyphAdvances, glyphOffsets);
    }

    static int draw(long self, long clientDrawingContext, long renderer, float originX,
                    float originY) {
        return DWShapingCalls.draw(self, clientDrawingContext, renderer, originX, originY);
    }

    static long newAnalysisSink(char[] text, int start, int length, char[] locale, int direction,
                                long numberSubstitution) {
        return DWAnalysisSinkObject.newAnalysisSink(text, start, length, locale, direction, numberSubstitution);
    }

    static long analysisSinkPointer(long id) {
        return DWAnalysisSinkObject.analysisSinkPointer(id);
    }

    static long analysisSourcePointer(long id) {
        return DWAnalysisSinkObject.analysisSourcePointer(id);
    }

    static boolean analysisSinkNext(long id) {
        return DWAnalysisSinkObject.analysisSinkNext(id);
    }

    static int analysisSinkGetStart(long id) {
        return DWAnalysisSinkObject.analysisSinkGetStart(id);
    }

    static int analysisSinkGetLength(long id) {
        return DWAnalysisSinkObject.analysisSinkGetLength(id);
    }

    static DWRITE_SCRIPT_ANALYSIS analysisSinkGetAnalysis(long id) {
        return DWAnalysisSinkObject.analysisSinkGetAnalysis(id);
    }

    static int analysisSinkAddRef(long id) {
        return DWAnalysisSinkObject.analysisSinkAddRef(id);
    }

    static int analysisSinkRelease(long id) {
        return DWAnalysisSinkObject.analysisSinkRelease(id);
    }

    static void disposeAnalysisSink(long id) {
        DWAnalysisSinkObject.disposeAnalysisSink(id);
    }

    static long newTextRenderer() {
        return DWTextRendererObject.newTextRenderer();
    }

    static long textRendererPointer(long id) {
        return DWTextRendererObject.textRendererPointer(id);
    }

    static boolean textRendererNext(long id) {
        return DWTextRendererObject.textRendererNext(id);
    }

    static int textRendererGetStart(long id) {
        return DWTextRendererObject.textRendererGetStart(id);
    }

    static int textRendererGetLength(long id) {
        return DWTextRendererObject.textRendererGetLength(id);
    }

    static int textRendererGetGlyphCount(long id) {
        return DWTextRendererObject.textRendererGetGlyphCount(id);
    }

    static int textRendererGetTotalGlyphCount(long id) {
        return DWTextRendererObject.textRendererGetTotalGlyphCount(id);
    }

    static long textRendererGetFontFace(long id) {
        return DWTextRendererObject.textRendererGetFontFace(id);
    }

    static int textRendererGetGlyphIndices(long id, int[] glyphs, int start, int slot) {
        return DWTextRendererObject.textRendererGetGlyphIndices(id, glyphs, start, slot);
    }

    static int textRendererGetGlyphAdvances(long id, float[] advances, int start) {
        return DWTextRendererObject.textRendererGetGlyphAdvances(id, advances, start);
    }

    static int textRendererGetGlyphOffsets(long id, float[] offsets, int start) {
        return DWTextRendererObject.textRendererGetGlyphOffsets(id, offsets, start);
    }

    static int textRendererGetClusterMap(long id, short[] clusterMap, int textStart, int glyphStart) {
        return DWTextRendererObject.textRendererGetClusterMap(id, clusterMap, textStart, glyphStart);
    }

    static int textRendererAddRef(long id) {
        return DWTextRendererObject.textRendererAddRef(id);
    }

    static int textRendererRelease(long id) {
        return DWTextRendererObject.textRendererRelease(id);
    }

    static void disposeTextRenderer(long id) {
        DWTextRendererObject.disposeTextRenderer(id);
    }

    static boolean coInitializeEx(int dwCoInit) {
        return DWWicCalls.coInitializeEx(dwCoInit);
    }

    static void coUninitialize() {
        DWWicCalls.coUninitialize();
    }

    static long wicCreateImagingFactory() {
        return DWWicCalls.wicCreateImagingFactory();
    }

    static long d2d1CreateFactory(int factoryType) {
        return DWWicCalls.d2d1CreateFactory(factoryType);
    }

    static long createBitmap(long self, int uiWidth, int uiHeight, int pixelFormat, int options) {
        return DWWicCalls.createBitmap(self, uiWidth, uiHeight, pixelFormat, options);
    }

    static long lock(long self, int x, int y, int width, int height, int flags) {
        return DWWicCalls.lock(self, x, y, width, height, flags);
    }

    static byte[] getDataPointer(long self) {
        return DWWicCalls.getDataPointer(self);
    }

    static int getStride(long self) {
        return DWWicCalls.getStride(self);
    }

    static long createWicBitmapRenderTarget(long self, long target,
                                            D2D1_RENDER_TARGET_PROPERTIES properties) {
        return DWD2DCalls.createWicBitmapRenderTarget(self, target, properties);
    }

    static void beginDraw(long self) {
        DWD2DCalls.beginDraw(self);
    }

    static int endDraw(long self) {
        return DWD2DCalls.endDraw(self);
    }

    static void clear(long self, D2D1_COLOR_F clearColor) {
        DWD2DCalls.clear(self, clearColor);
    }

    static void setTextAntialiasMode(long self, int textAntialiasMode) {
        DWD2DCalls.setTextAntialiasMode(self, textAntialiasMode);
    }

    static void setTransform(long self, D2D1_MATRIX_3X2_F transform) {
        DWD2DCalls.setTransform(self, transform);
    }

    static void drawGlyphRun(long self, D2D1_POINT_2F baselineOrigin, DWRITE_GLYPH_RUN glyphRun,
                             long foregroundBrush, int measuringMode) {
        DWD2DCalls.drawGlyphRun(self, baselineOrigin, glyphRun, foregroundBrush, measuringMode);
    }

    static long createSolidColorBrush(long self, D2D1_COLOR_F color) {
        return DWD2DCalls.createSolidColorBrush(self, color);
    }
}
