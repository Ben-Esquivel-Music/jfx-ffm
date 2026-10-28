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
import com.sun.javafx.geom.PathIterator;
import com.sun.javafx.logging.PlatformLogger;
import com.sun.javafx.util.Logging;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import static com.sun.javafx.font.directwrite.DWComObjectSupport.IID_IUNKNOWN_BYTES;
import static com.sun.javafx.font.directwrite.DWFontFaceCalls.FONT_FACE_GET_GLYPH_RUN_OUTLINE;
import static com.sun.javafx.font.directwrite.DWLayouts.D2D1_BEZIER_SEGMENT_LAYOUT;
import static com.sun.javafx.font.directwrite.DWLayouts.D2D1_POINT_2F_LAYOUT;
import static com.sun.javafx.font.directwrite.DWLayouts.offsetOf;
import static com.sun.javafx.font.directwrite.DWNative.E_FAIL;
import static com.sun.javafx.font.directwrite.DWNative.GUID_LAYOUT;
import static com.sun.javafx.font.directwrite.DWNative.IID_IDWRITE_FACTORY;
import static com.sun.javafx.font.directwrite.DWNative.IID_IUNKNOWN;
import static com.sun.javafx.font.directwrite.DWNative.IUNKNOWN_ADD_REF;
import static com.sun.javafx.font.directwrite.DWNative.IUNKNOWN_QUERY_INTERFACE;
import static com.sun.javafx.font.directwrite.DWNative.IUNKNOWN_RELEASE;
import static com.sun.javafx.font.directwrite.DWNative.S_OK;
import static com.sun.javafx.font.directwrite.DWNative.bounded;
import static com.sun.javafx.font.directwrite.DWNative.com;
import static com.sun.javafx.font.directwrite.DWNative.guid;
import static com.sun.javafx.font.directwrite.DWNative.guidBytes;
import static com.sun.javafx.font.directwrite.DWNative.slot;
import static com.sun.javafx.font.directwrite.DWNative.unexpected;
import static com.sun.javafx.font.directwrite.DWNative.upcall;
import static com.sun.javafx.font.directwrite.DWNative.virtual;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * {@code IDWriteFontFace::GetGlyphRunOutline} for {@link DWNative}, and the geometry sink it drives: a COM
 * object implemented in Java.
 * <p>
 * IDWriteFontFace::GetGlyphRunOutline does not return an outline; it calls one back, into an
 * ID2D1SimplifiedGeometrySink the caller supplies (dwrite.h:1041 typedefs IDWriteGeometrySink to
 * that interface, so one IID answers both names). The C implemented it as the C++ class
 * JFXGeometrySink, directwrite.cpp:1616-1766. Java implements it here, with no C at all: a
 * 16-byte block whose word 0 points at a ten-entry table of Linker.upcallStub segments and whose
 * word 1 is a registry id - never a Java reference, never a pointer DirectWrite could dereference.
 * <p>
 * Everything DirectWrite does with this object happens synchronously, on the calling thread,
 * inside the GetGlyphRunOutline frame; it keeps no pointer afterwards, which the C proves by
 * deleting its sink the moment the call returns (:1810). So the vtable is process-wide (the
 * targets are static methods; one table, built once, in Arena.global()) while the object itself
 * lives in the per-call confined arena.
 * <p>
 * Line numbers into {@code directwrite.cpp} (bare {@code :N} included) and the SDK headers follow
 * {@link DWNative}: commit {@code 8492cb03b0}, Windows SDK 10.0.26100.0.
 */
final class DWGeometrySinkObject {

    /*
     * ID2D1SimplifiedGeometrySink : IUnknown, d2d1.h:2174 - the one interface this package
     * implements rather than calls. dwrite.h:1041 typedefs IDWriteGeometrySink to it, so one vtable
     * answers both names. These are the slots of the table this class builds, not of one it reads.
     */
    private static final int SINK_SET_FILL_MODE = 3;                   // ord 0, d2d1.h:2177
    private static final int SINK_SET_SEGMENT_FLAGS = 4;               // ord 1, d2d1.h:2181
    private static final int SINK_BEGIN_FIGURE = 5;                    // ord 2, d2d1.h:2185
    private static final int SINK_ADD_LINES = 6;                       // ord 3, d2d1.h:2190
    private static final int SINK_ADD_BEZIERS = 7;                     // ord 4, d2d1.h:2195
    private static final int SINK_END_FIGURE = 8;                      // ord 5, d2d1.h:2200
    private static final int SINK_CLOSE = 9;                           // ord 6, d2d1.h:2204
    private static final int SINK_SLOT_COUNT = 10;

    /** The object DirectWrite is handed: {@code { const void* vtbl; uint64_t sessionId; }}, 16 bytes. */
    private static final StructLayout SINK_LAYOUT = MemoryLayout.structLayout(
            ADDRESS.withName("vtbl"),                 // offset 0
            JAVA_LONG.withName("user"));              // offset 8, byteSize 16

    /**
     * {@code HRESULT f(this, FLOAT emSize, UINT16 const* glyphIndices, FLOAT const* glyphAdvances,
     * DWRITE_GLYPH_OFFSET const* glyphOffsets, UINT32 glyphCount, BOOL isSideways, BOOL isRightToLeft,
     * IDWriteGeometrySink* sink)}: {@code IDWriteFontFace::GetGlyphRunOutline} (dwrite.h:1209).
     * The only call in this package that upcalls, which is why {@code critical(true)} is impossible
     * for it rather than merely unwise.
     */
    private static final MethodHandle HR_GLYPH_RUN_OUTLINE = virtual(FunctionDescriptor.of(
            JAVA_INT, ADDRESS, JAVA_FLOAT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT,
            ADDRESS));

    /* The nine shapes of the geometry sink, used both to build the upcall stubs and, in the
     * self-test, to call the resulting vtable the way DirectWrite will. */

    /** {@code HRESULT QueryInterface(this, REFIID, void** out)}, sink slot 0. */
    private static final FunctionDescriptor SINK_QUERY_INTERFACE_FD =
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS);

    /** {@code ULONG AddRef(this)} / {@code ULONG Release(this)} / {@code HRESULT Close(this)}. */
    private static final FunctionDescriptor SINK_THIS_FD = FunctionDescriptor.of(JAVA_INT, ADDRESS);

    /** {@code void SetFillMode(this, int)} / {@code void SetSegmentFlags(this, int)} / EndFigure. */
    private static final FunctionDescriptor SINK_THIS_INT_FD =
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT);

    /**
     * {@code void BeginFigure(this, D2D1_POINT_2F startPoint, D2D1_FIGURE_BEGIN figureBegin)}, sink
     * slot 5: the one by-value aggregate in the package. See {@link DWLayouts#D2D1_POINT_2F_LAYOUT}.
     */
    private static final FunctionDescriptor SINK_BEGIN_FIGURE_FD =
            FunctionDescriptor.ofVoid(ADDRESS, D2D1_POINT_2F_LAYOUT, JAVA_INT);

    /** {@code void AddLines(this, const D2D1_POINT_2F*, UINT32)} and {@code AddBeziers}, by pointer. */
    private static final FunctionDescriptor SINK_ARRAY_FD =
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT);

    /**
     * {@code ID2D1SimplifiedGeometrySink} (d2d1.h:2174). {@code IDWriteGeometrySink} is a typedef for
     * the same interface (dwrite.h:1041), so this one IID answers both of the C QueryInterface arms.
     */
    static final String IID_ID2D1_SIMPLIFIED_GEOMETRY_SINK = "2cd9069e-12e2-11dc-9fed-001143a055f9";

    private static final long SINK_USER_OFFSET = offsetOf(SINK_LAYOUT, "user");

    private static final byte[] IID_GEOMETRY_SINK_BYTES = guidBytes(IID_ID2D1_SIMPLIFIED_GEOMETRY_SINK);

    /**
     * The sessions of the outline calls currently on some stack, keyed by the id stored in word 1 of
     * the object DirectWrite holds. A registry rather than a {@code ThreadLocal} because the house
     * rule is a registry and because a {@code ThreadLocal} would hide re-entrancy; a miss is always
     * tolerated, so a callback arriving after the entry is gone is a no-op rather than a crash.
     */
    private static final Map<Long, OutlineSession> SINKS = new ConcurrentHashMap<>();

    /** Ids start at 1, so a zeroed or freed word 1 reads as 0 and misses the registry cleanly. */
    private static final AtomicLong NEXT_SINK_ID = new AtomicLong(1);

    private DWGeometrySinkObject() {
    }

    /**
     * {@code IDWriteFontFace::GetGlyphRunOutline}, slot 14 (dwrite.h:1209)
     * ({@code directwrite.cpp:1769-1812}), driving a Java-synthesized sink.
     * <p>
     * Four of the eight arguments are fixed exactly as the C fixed them: one glyph, a one-element
     * {@code UINT16} array, {@code NULL} advances and {@code NULL} offsets - {@code MemorySegment.NULL},
     * not zero-filled arrays, because DirectWrite documents NULL as "use the font's own advances" -
     * and {@code isRightToLeft} FALSE. The result is a {@code Path2D} with winding rule
     * {@code WIND_EVEN_ODD}, which is the literal 0 the C passed at {@code :1804}.
     * <p>
     * Failure is {@code null} and nothing else, as it was in JNI: {@code PrismFontStrike} and
     * {@code CompositeStrike} test the result for null and have no catch. An {@code Error} raised in a
     * callback still propagates - the C would have hit {@code std::terminate} there - but a
     * {@code RuntimeException} is logged and turned into {@code null}, so this method throws nothing
     * the JNI version could not.
     */
    static Path2D getGlyphRunOutline(long self, float emSize, short glyphIndex, boolean isSideways) {
        MemorySegment vtable = Sink.VTABLE;
        if (vtable.address() == 0) {
            return null;
        }
        OutlineSession session = new OutlineSession(false);
        Long id = NEXT_SINK_ID.getAndIncrement();
        SINKS.put(id, session);
        int hr;
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment sink = allocateSink(scratch, vtable, id);
            MemorySegment indices = scratch.allocateFrom(JAVA_SHORT, glyphIndex);
            MemorySegment obj = com(self);
            hr = (int) HR_GLYPH_RUN_OUTLINE.invokeExact(slot(obj, FONT_FACE_GET_GLYPH_RUN_OUTLINE),
                    obj, emSize, indices, MemorySegment.NULL, MemorySegment.NULL, 1,
                    isSideways ? 1 : 0, 0, sink);
        } catch (Throwable t) {
            throw unexpected(t);
        } finally {
            SINKS.remove(id);
        }
        Throwable failure = session.pending;
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure != null) {
            PlatformLogger logger = Logging.getJavaFXLogger();
            if (logger.isLoggable(PlatformLogger.Level.SEVERE)) {
                logger.severe("glyph outline callback failed for glyph " + (glyphIndex & 0xFFFF), failure);
            }
            return null;
        }
        return hr >= 0 ? session.toPath() : null;
    }

    /** How many outline calls are on some stack right now: 0 between calls, or a leaked id. */
    static int sinkRegistrySize() {
        return SINKS.size();
    }

    /** The 16-byte object DirectWrite receives: the shared vtable, then this call's registry id. */
    private static MemorySegment allocateSink(Arena arena, MemorySegment vtable, long id) {
        MemorySegment sink = arena.allocate(SINK_LAYOUT);
        sink.set(ADDRESS, 0, vtable);
        sink.set(JAVA_LONG, SINK_USER_OFFSET, id);
        return sink;
    }

    /**
     * The session behind the object at {@code self}, or {@code null} when word 1 names no live call.
     * Every upcall target tolerates that miss and returns its sentinel rather than dereferencing
     * anything: an outline call whose arena has already closed leaves freed bytes there, and a COM
     * object must not fault its caller.
     */
    private static OutlineSession sinkSession(MemorySegment self) {
        try {
            return SINKS.get(bounded(self, SINK_LAYOUT.byteSize()).get(JAVA_LONG, SINK_USER_OFFSET));
        } catch (Throwable t) {
            return null;
        }
    }

    /* The ten upcall targets. None may throw: an exception escaping into DirectWrite would unwind a
     * native frame. Each stashes instead, which also makes every later callback of the same call a
     * no-op, so a half-built path is never handed back. */

    private static int sinkQueryInterface(MemorySegment self, MemorySegment riid, MemorySegment out) {
        MemorySegment target = MemorySegment.NULL;
        try {
            target = bounded(out, ADDRESS.byteSize());
            byte[] id = bounded(riid, GUID_LAYOUT.byteSize()).toArray(JAVA_BYTE);
            if (Arrays.equals(id, IID_GEOMETRY_SINK_BYTES) || Arrays.equals(id, IID_IUNKNOWN_BYTES)) {
                target.set(ADDRESS, 0, self);
                sinkAddRef(self);
                return S_OK;
            }
            target.set(ADDRESS, 0, MemorySegment.NULL);
            // E_FAIL, not E_NOINTERFACE: directwrite.cpp:1762 answers E_FAIL and callers see that.
            return E_FAIL;
        } catch (Throwable t) {
            stash(self, t);
            try {
                if (target.address() != 0) {
                    target.set(ADDRESS, 0, MemorySegment.NULL);
                }
            } catch (Throwable ignored) {
                // the out-pointer itself is unusable; there is nothing further to report to COM
            }
            return E_FAIL;
        }
    }

    private static int sinkAddRef(MemorySegment self) {
        OutlineSession session = sinkSession(self);
        return session == null ? 1 : session.addRef();
    }

    /**
     * Counts down but frees nothing. The C starts its count at 0 ({@code :1666}), frees at 0
     * ({@code :1744-1750}) and is also deleted unconditionally by the entry point ({@code :1810}) -
     * two frees that have never both fired, which is positive evidence that DirectWrite does not drive
     * this count to zero. Here the confined arena is the only owner, so the question cannot arise.
     */
    private static int sinkRelease(MemorySegment self) {
        OutlineSession session = sinkSession(self);
        return session == null ? 0 : session.release();
    }

    /** Ignored, exactly as at {@code directwrite.cpp:1689-1691}: the winding rule is hardcoded. */
    private static void sinkSetFillMode(MemorySegment self, int fillMode) {
    }

    /** Ignored, exactly as at {@code directwrite.cpp:1693-1695}. */
    private static void sinkSetSegmentFlags(MemorySegment self, int vertexFlags) {
    }

    /**
     * The by-value {@code D2D1_POINT_2F} arrives as an 8-byte segment the linker materialises for the
     * duration of this upcall only; its two floats are read here and the segment is never stored.
     * {@code figureBegin} (hollow or filled) is discarded and the point becomes a move-to whatever it
     * says, as at {@code directwrite.cpp:1697-1702}.
     */
    private static void sinkBeginFigure(MemorySegment self, MemorySegment startPoint, int figureBegin) {
        OutlineSession session = sinkSession(self);
        if (session == null || session.failed()) {
            return;
        }
        try {
            session.segment(PathIterator.SEG_MOVETO, startPoint.get(JAVA_FLOAT, 0),
                    startPoint.get(JAVA_FLOAT, 4));
        } catch (Throwable t) {
            session.fail(t);
        }
    }

    /** {@code pointsCount} is a {@code UINT32}; widen it unsigned before sizing the array. */
    private static void sinkAddLines(MemorySegment self, MemorySegment points, int pointsCount) {
        OutlineSession session = sinkSession(self);
        if (session == null || session.failed()) {
            return;
        }
        try {
            long stride = D2D1_POINT_2F_LAYOUT.byteSize();
            long count = Integer.toUnsignedLong(pointsCount);
            MemorySegment array = bounded(points, count * stride);
            for (long i = 0; i < count; i++) {
                long base = i * stride;
                session.segment(PathIterator.SEG_LINETO, array.get(JAVA_FLOAT, base),
                        array.get(JAVA_FLOAT, base + 4));
            }
        } catch (Throwable t) {
            session.fail(t);
        }
    }

    /**
     * Every curve is emitted as a cubic, never a quadratic, even when {@code point1 == point2} - the
     * simplified geometry sink has no quadratic callback and the C said so at
     * {@code directwrite.cpp:1717-1719}. {@code D2D1_BEZIER_SEGMENT} is 24 bytes and crosses by
     * pointer; only {@code BeginFigure} takes an aggregate by value.
     */
    private static void sinkAddBeziers(MemorySegment self, MemorySegment beziers, int beziersCount) {
        OutlineSession session = sinkSession(self);
        if (session == null || session.failed()) {
            return;
        }
        try {
            long stride = D2D1_BEZIER_SEGMENT_LAYOUT.byteSize();
            long count = Integer.toUnsignedLong(beziersCount);
            MemorySegment array = bounded(beziers, count * stride);
            for (long i = 0; i < count; i++) {
                long base = i * stride;
                session.segment(PathIterator.SEG_CUBICTO,
                        array.get(JAVA_FLOAT, base), array.get(JAVA_FLOAT, base + 4),
                        array.get(JAVA_FLOAT, base + 8), array.get(JAVA_FLOAT, base + 12),
                        array.get(JAVA_FLOAT, base + 16), array.get(JAVA_FLOAT, base + 20));
            }
        } catch (Throwable t) {
            session.fail(t);
        }
    }

    /** A close with no coordinates whatever {@code figureEnd} says, as at {@code :1730-1733}. */
    private static void sinkEndFigure(MemorySegment self, int figureEnd) {
        OutlineSession session = sinkSession(self);
        if (session == null || session.failed()) {
            return;
        }
        try {
            session.segment(PathIterator.SEG_CLOSE);
        } catch (Throwable t) {
            session.fail(t);
        }
    }

    /** Always {@code S_OK}, as at {@code directwrite.cpp:1735-1737}. */
    private static int sinkClose(MemorySegment self) {
        return S_OK;
    }

    private static void stash(MemorySegment self, Throwable t) {
        OutlineSession session = sinkSession(self);
        if (session != null) {
            session.fail(t);
        }
    }

    /**
     * The growable buffers behind one outline call - the Java counterpart of the two
     * {@code std::vector}s at {@code directwrite.cpp:1661-1662} - plus the reference count DirectWrite
     * may move and the slot that holds a failure until the native frame has unwound.
     * <p>
     * The arrays handed to {@link Path2D} are always fresh and exactly the right length. That is not
     * tidiness: {@code Path2D(int, byte[], int, float[], int)} stores both arrays <em>by reference</em>
     * and its javadoc says the caller promises to drop every other reference, so a shared or
     * over-long buffer would let one glyph read another glyph's coordinates with no exception
     * anywhere. One session per call, one copy per session.
     */
    private static final class OutlineSession {

        private final boolean failForTesting;
        private byte[] types = new byte[32];
        private float[] coords = new float[64];
        private int numTypes;
        private int numCoords;
        private int refCount;
        private Throwable pending;

        OutlineSession(boolean failForTesting) {
            this.failForTesting = failForTesting;
        }

        int addRef() {
            return ++refCount;
        }

        int release() {
            return --refCount;
        }

        boolean failed() {
            return pending != null;
        }

        void fail(Throwable t) {
            if (pending == null) {
                pending = t;
            }
        }

        void segment(int type, float... values) {
            if (failForTesting) {
                throw new IllegalStateException("injected geometry sink failure");
            }
            if (numTypes == types.length) {
                types = Arrays.copyOf(types, types.length * 2);
            }
            types[numTypes++] = (byte) type;
            if (numCoords + values.length > coords.length) {
                coords = Arrays.copyOf(coords, Math.max(coords.length * 2, numCoords + values.length));
            }
            for (float value : values) {
                coords[numCoords++] = value;
            }
        }

        Path2D toPath() {
            return new Path2D(Path2D.WIND_EVEN_ODD, Arrays.copyOf(types, numTypes), numTypes,
                    Arrays.copyOf(coords, numCoords), numCoords);
        }
    }

    /**
     * The ten upcall stubs, built once and never freed - the one legitimate {@link Arena#global()} in
     * this package, because the targets are static methods and there is exactly one table per process.
     * Held by a nested class so that nothing is created until the first outline is asked for, and
     * {@link #build()} cannot throw, so this initializer cannot either: a table that could not be made
     * is {@link MemorySegment#NULL} and {@link #getGlyphRunOutline} then answers {@code null}, which is
     * what every failure in this area has always looked like to the callers.
     */
    private static final class Sink {

        static final MemorySegment VTABLE = build();

        private static MemorySegment build() {
            try {
                MethodHandles.Lookup lookup = MethodHandles.lookup();
                MemorySegment table = Arena.global()
                        .allocate(ADDRESS.byteSize() * SINK_SLOT_COUNT, ADDRESS.byteSize());
                table.setAtIndex(ADDRESS, IUNKNOWN_QUERY_INTERFACE, upcall(lookup, "sinkQueryInterface",
                        SINK_QUERY_INTERFACE_FD, int.class, MemorySegment.class, MemorySegment.class,
                        MemorySegment.class));
                table.setAtIndex(ADDRESS, IUNKNOWN_ADD_REF,
                        upcall(lookup, "sinkAddRef", SINK_THIS_FD, int.class, MemorySegment.class));
                table.setAtIndex(ADDRESS, IUNKNOWN_RELEASE,
                        upcall(lookup, "sinkRelease", SINK_THIS_FD, int.class, MemorySegment.class));
                table.setAtIndex(ADDRESS, SINK_SET_FILL_MODE, upcall(lookup, "sinkSetFillMode",
                        SINK_THIS_INT_FD, void.class, MemorySegment.class, int.class));
                table.setAtIndex(ADDRESS, SINK_SET_SEGMENT_FLAGS, upcall(lookup, "sinkSetSegmentFlags",
                        SINK_THIS_INT_FD, void.class, MemorySegment.class, int.class));
                table.setAtIndex(ADDRESS, SINK_BEGIN_FIGURE, upcall(lookup, "sinkBeginFigure",
                        SINK_BEGIN_FIGURE_FD, void.class, MemorySegment.class, MemorySegment.class,
                        int.class));
                table.setAtIndex(ADDRESS, SINK_ADD_LINES, upcall(lookup, "sinkAddLines",
                        SINK_ARRAY_FD, void.class, MemorySegment.class, MemorySegment.class, int.class));
                table.setAtIndex(ADDRESS, SINK_ADD_BEZIERS, upcall(lookup, "sinkAddBeziers",
                        SINK_ARRAY_FD, void.class, MemorySegment.class, MemorySegment.class, int.class));
                table.setAtIndex(ADDRESS, SINK_END_FIGURE, upcall(lookup, "sinkEndFigure",
                        SINK_THIS_INT_FD, void.class, MemorySegment.class, int.class));
                table.setAtIndex(ADDRESS, SINK_CLOSE,
                        upcall(lookup, "sinkClose", SINK_THIS_FD, int.class, MemorySegment.class));
                return table;
            } catch (Throwable t) {
                return MemorySegment.NULL;
            }
        }
    }

    /* ---------------------------------------------------------------------------------------------
     * Sink self-test hooks
     *
     * These call the synthesized vtable the way DirectWrite calls it - through the function pointers
     * in the table, with the same FunctionDescriptors - so a test can check the object in isolation
     * before any glyph is involved. They prove the table is well formed and that the by-value point
     * survives a round trip; they cannot prove the Microsoft x64 register assignment, because both
     * ends are the same linker. That proof is the outline parity against the JNI path.
     * ------------------------------------------------------------------------------------------- */

    /**
     * Drives {@code QueryInterface}, {@code AddRef}, {@code Release} and {@code Close} through the
     * sink vtable.
     *
     * @return thirteen values, or {@code null} when the vtable could not be built:
     *         {@code 0} QueryInterface(ID2D1SimplifiedGeometrySink) HRESULT,
     *         {@code 1} 1 if it wrote self, {@code 2} QueryInterface(IUnknown) HRESULT,
     *         {@code 3} 1 if it wrote self, {@code 4} QueryInterface(unrelated IID) HRESULT unsigned,
     *         {@code 5} 1 if it wrote NULL, {@code 6..9} AddRef then three Releases,
     *         {@code 10} Close HRESULT, {@code 11} the registry size during the calls,
     *         {@code 12} AddRef after the count reached zero, which must still answer
     */
    static long[] sinkSelfTest() {
        MemorySegment vtable = Sink.VTABLE;
        if (vtable.address() == 0) {
            return null;
        }
        OutlineSession session = new OutlineSession(false);
        Long id = NEXT_SINK_ID.getAndIncrement();
        SINKS.put(id, session);
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment sink = allocateSink(scratch, vtable, id);
            MethodHandle queryInterface = virtual(SINK_QUERY_INTERFACE_FD);
            MethodHandle counted = virtual(SINK_THIS_FD);
            MemorySegment out = scratch.allocate(ADDRESS);
            long[] result = new long[13];

            MemorySegment sinkIid = guid(scratch, IID_ID2D1_SIMPLIFIED_GEOMETRY_SINK);
            result[0] = Integer.toUnsignedLong((int) queryInterface.invokeExact(
                    slot(sink, IUNKNOWN_QUERY_INTERFACE), sink, sinkIid, out));
            result[1] = out.get(ADDRESS, 0).address() == sink.address() ? 1 : 0;

            MemorySegment unknownIid = guid(scratch, IID_IUNKNOWN);
            result[2] = Integer.toUnsignedLong((int) queryInterface.invokeExact(
                    slot(sink, IUNKNOWN_QUERY_INTERFACE), sink, unknownIid, out));
            result[3] = out.get(ADDRESS, 0).address() == sink.address() ? 1 : 0;

            MemorySegment otherIid = guid(scratch, IID_IDWRITE_FACTORY);
            result[4] = Integer.toUnsignedLong((int) queryInterface.invokeExact(
                    slot(sink, IUNKNOWN_QUERY_INTERFACE), sink, otherIid, out));
            result[5] = out.get(ADDRESS, 0).address() == 0 ? 1 : 0;

            result[6] = (int) counted.invokeExact(slot(sink, IUNKNOWN_ADD_REF), sink);
            result[7] = (int) counted.invokeExact(slot(sink, IUNKNOWN_RELEASE), sink);
            result[8] = (int) counted.invokeExact(slot(sink, IUNKNOWN_RELEASE), sink);
            result[9] = (int) counted.invokeExact(slot(sink, IUNKNOWN_RELEASE), sink);
            result[10] = Integer.toUnsignedLong((int) counted.invokeExact(slot(sink, SINK_CLOSE), sink));
            result[11] = SINKS.size();
            result[12] = (int) counted.invokeExact(slot(sink, IUNKNOWN_ADD_REF), sink);
            return result;
        } catch (Throwable t) {
            throw unexpected(t);
        } finally {
            SINKS.remove(id);
        }
    }

    /**
     * Drives one whole figure through the sink vtable with known coordinates and returns the
     * {@code Path2D} the session built: {@code SetFillMode} and {@code SetSegmentFlags} (both ignored),
     * one {@code BeginFigure} carrying the by-value {@code D2D1_POINT_2F}, one {@code AddLines} of two
     * points, one {@code AddBeziers} of one segment and one {@code EndFigure}.
     */
    static Path2D sinkSelfTestOutline() {
        MemorySegment vtable = Sink.VTABLE;
        if (vtable.address() == 0) {
            return null;
        }
        OutlineSession session = new OutlineSession(false);
        Long id = NEXT_SINK_ID.getAndIncrement();
        SINKS.put(id, session);
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment sink = allocateSink(scratch, vtable, id);
            driveOneFigure(scratch, sink);
            return session.pending == null ? session.toPath() : null;
        } catch (Throwable t) {
            throw unexpected(t);
        } finally {
            SINKS.remove(id);
        }
    }

    /**
     * The same figure, but with a session whose first append throws, to show what a failing callback
     * does: the failure is stashed rather than thrown into the caller, every later callback of the
     * same call becomes a no-op, no partial path is built and the registry entry still goes away.
     *
     * @return {@code { 1 if a failure was stashed, segments appended, coordinates appended,
     *         registry size afterwards }}
     */
    static long[] sinkSelfTestFailure() {
        MemorySegment vtable = Sink.VTABLE;
        if (vtable.address() == 0) {
            return null;
        }
        OutlineSession session = new OutlineSession(true);
        Long id = NEXT_SINK_ID.getAndIncrement();
        SINKS.put(id, session);
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment sink = allocateSink(scratch, vtable, id);
            driveOneFigure(scratch, sink);
        } catch (Throwable t) {
            throw unexpected(t);
        } finally {
            SINKS.remove(id);
        }
        return new long[] { session.failed() ? 1 : 0, session.numTypes, session.numCoords, SINKS.size() };
    }

    /** The coordinates {@link #sinkSelfTestOutline} and {@link #sinkSelfTestFailure} feed the sink. */
    static final float[] SINK_SELF_TEST_FIGURE = {
            1.5f, -2.25f,                                   // BeginFigure, by value
            3.5f, 4.5f, -5.5f, 6.5f,                        // AddLines, two points
            7.5f, 8.5f, 9.5f, 10.5f, 11.5f, 12.5f };        // AddBeziers, one cubic

    private static void driveOneFigure(Arena scratch, MemorySegment sink) throws Throwable {
        MethodHandle withInt = virtual(SINK_THIS_INT_FD);
        MethodHandle beginFigure = virtual(SINK_BEGIN_FIGURE_FD);
        MethodHandle withArray = virtual(SINK_ARRAY_FD);
        MethodHandle counted = virtual(SINK_THIS_FD);
        float[] figure = SINK_SELF_TEST_FIGURE;

        MemorySegment start = scratch.allocate(D2D1_POINT_2F_LAYOUT);
        start.setAtIndex(JAVA_FLOAT, 0, figure[0]);
        start.setAtIndex(JAVA_FLOAT, 1, figure[1]);
        MemorySegment lines = scratch.allocate(D2D1_POINT_2F_LAYOUT, 2);
        for (int i = 0; i < 4; i++) {
            lines.setAtIndex(JAVA_FLOAT, i, figure[2 + i]);
        }
        MemorySegment beziers = scratch.allocate(D2D1_BEZIER_SEGMENT_LAYOUT);
        for (int i = 0; i < 6; i++) {
            beziers.setAtIndex(JAVA_FLOAT, i, figure[6 + i]);
        }

        withInt.invokeExact(slot(sink, SINK_SET_FILL_MODE), sink, 1);
        withInt.invokeExact(slot(sink, SINK_SET_SEGMENT_FLAGS), sink, 1);
        beginFigure.invokeExact(slot(sink, SINK_BEGIN_FIGURE), sink, start, 0);
        withArray.invokeExact(slot(sink, SINK_ADD_LINES), sink, lines, 2);
        withArray.invokeExact(slot(sink, SINK_ADD_BEZIERS), sink, beziers, 1);
        withInt.invokeExact(slot(sink, SINK_END_FIGURE), sink, 0);
        int ignored = (int) counted.invokeExact(slot(sink, SINK_CLOSE), sink);
    }
}
