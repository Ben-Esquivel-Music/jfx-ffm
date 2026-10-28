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
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import static com.sun.javafx.font.directwrite.DWComObjectSupport.COM_QUERY_INTERFACE_FD;
import static com.sun.javafx.font.directwrite.DWComObjectSupport.COM_REF_COUNT_FD;
import static com.sun.javafx.font.directwrite.DWComObjectSupport.IID_IUNKNOWN_BYTES;
import static com.sun.javafx.font.directwrite.DWComObjectSupport.logCallbackFailure;
import static com.sun.javafx.font.directwrite.DWComObjectSupport.uuidText;
import static com.sun.javafx.font.directwrite.DWLayouts.DESCRIPTION_CLUSTER_MAP;
import static com.sun.javafx.font.directwrite.DWLayouts.DESCRIPTION_STRING_LENGTH;
import static com.sun.javafx.font.directwrite.DWLayouts.DESCRIPTION_TEXT_POSITION;
import static com.sun.javafx.font.directwrite.DWLayouts.DWRITE_GLYPH_RUN_DESCRIPTION_LAYOUT;
import static com.sun.javafx.font.directwrite.DWLayouts.DWRITE_GLYPH_RUN_LAYOUT;
import static com.sun.javafx.font.directwrite.DWLayouts.DWRITE_MATRIX_LAYOUT;
import static com.sun.javafx.font.directwrite.DWLayouts.RUN_BIDI_LEVEL;
import static com.sun.javafx.font.directwrite.DWLayouts.RUN_FONT_EM_SIZE;
import static com.sun.javafx.font.directwrite.DWLayouts.RUN_FONT_FACE;
import static com.sun.javafx.font.directwrite.DWLayouts.RUN_GLYPH_ADVANCES;
import static com.sun.javafx.font.directwrite.DWLayouts.RUN_GLYPH_COUNT;
import static com.sun.javafx.font.directwrite.DWLayouts.RUN_GLYPH_INDICES;
import static com.sun.javafx.font.directwrite.DWLayouts.RUN_GLYPH_OFFSETS;
import static com.sun.javafx.font.directwrite.DWLayouts.RUN_IS_SIDEWAYS;
import static com.sun.javafx.font.directwrite.DWLayouts.offsetOf;
import static com.sun.javafx.font.directwrite.DWNative.E_FAIL;
import static com.sun.javafx.font.directwrite.DWNative.GUID_LAYOUT;
import static com.sun.javafx.font.directwrite.DWNative.IUNKNOWN_ADD_REF;
import static com.sun.javafx.font.directwrite.DWNative.IUNKNOWN_QUERY_INTERFACE;
import static com.sun.javafx.font.directwrite.DWNative.IUNKNOWN_RELEASE;
import static com.sun.javafx.font.directwrite.DWNative.S_OK;
import static com.sun.javafx.font.directwrite.DWNative.bounded;
import static com.sun.javafx.font.directwrite.DWNative.guidBytes;
import static com.sun.javafx.font.directwrite.DWNative.upcall;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * The text renderer of {@link DWNative}: the second inbound COM object.
 * <p>
 * IDWriteTextLayout::Draw does not return glyphs either; it calls an IDWriteTextRenderer, which
 * derives IDWritePixelSnapping, so one interface and one vtable of ten slots
 * (directwrite.cpp:1231 built the same object with single inheritance). The block is therefore
 * the geometry sink's shape - a vtable pointer and a registry id - and the id is at self+8.
 * <p>
 * This is the object that receives DWRITE_GLYPH_RUN. It is worth being precise about what
 * crosses and how, because it is the only place in the shaping bindings where the argument shapes are not
 * all pointers and integers: DrawGlyphRun takes (this, void* context, FLOAT baselineOriginX,
 * FLOAT baselineOriginY, DWRITE_MEASURING_MODE, DWRITE_GLYPH_RUN const*,
 * DWRITE_GLYPH_RUN_DESCRIPTION const*, IUnknown*), so on Microsoft x64 the two floats are
 * arguments 3 and 4 and travel in XMM2 and XMM3 - by position, not by "the first two floats" -
 * while the enum and the three pointers after them land on the stack at [RSP+0x20] upwards.
 * Neither struct is passed by value: both are const pointers, and nothing in these bindings returns
 * an aggregate. That leaves no unknown classification here; the by-value case the fork has
 * already measured is the geometry sink's D2D1_POINT_2F.
 * <p>
 * Line numbers into {@code directwrite.cpp} (bare {@code :N} included) and the SDK headers follow
 * {@link DWNative}: commit {@code 8492cb03b0}, Windows SDK 10.0.26100.0.
 */
final class DWTextRendererObject {

    /** {@code IDWriteTextRenderer} (dwrite.h:3637). */
    static final String IID_IDWRITE_TEXT_RENDERER = "ef8a8135-5cc6-45fe-8825-c5a0724eb819";

    /** {@code IDWritePixelSnapping} (dwrite.h:3586), the base interface of the renderer. */
    static final String IID_IDWRITE_PIXEL_SNAPPING = "eaf3a2da-ecf4-4d24-b644-b34f6842024b";

    /* IDWritePixelSnapping (dwrite.h:3586), base IUnknown = 3, three own methods; then
     * IDWriteTextRenderer (dwrite.h:3637), base IDWritePixelSnapping = 6, four own methods. */
    static final int RENDERER_IS_PIXEL_SNAPPING_DISABLED = 3;          // ord 0, dwrite.h:3597
    static final int RENDERER_GET_CURRENT_TRANSFORM = 4;               // ord 1, dwrite.h:3611
    static final int RENDERER_GET_PIXELS_PER_DIP = 5;                  // ord 2, dwrite.h:3626
    static final int RENDERER_DRAW_GLYPH_RUN = 6;                      // ord 0, dwrite.h:3662
    static final int RENDERER_DRAW_UNDERLINE = 7;                      // ord 1, dwrite.h:3699
    static final int RENDERER_DRAW_STRIKETHROUGH = 8;                  // ord 2, dwrite.h:3730
    static final int RENDERER_DRAW_INLINE_OBJECT = 9;                  // ord 3, dwrite.h:3758
    private static final int RENDERER_SLOT_COUNT = 10;

    /** {@code { const void* vtbl; uint64_t id; }} - the same 16 bytes as the geometry sink's block. */
    static final StructLayout TEXT_RENDERER_OBJECT_LAYOUT = MemoryLayout.structLayout(
            ADDRESS.withName("vtbl"),                 // offset 0
            JAVA_LONG.withName("id"));                // offset 8, byteSize 16

    private static final long TEXT_RENDERER_ID_OFFSET = offsetOf(TEXT_RENDERER_OBJECT_LAYOUT, "id");

    /** {@code HRESULT f(this, void* context, void* out)}: the three pixel-snapping queries. */
    static final FunctionDescriptor RENDERER_QUERY_FD =
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS);

    /**
     * {@code HRESULT DrawGlyphRun(this, void* context, FLOAT baselineOriginX, FLOAT baselineOriginY,
     * DWRITE_MEASURING_MODE, DWRITE_GLYPH_RUN const*, DWRITE_GLYPH_RUN_DESCRIPTION const*,
     * IUnknown* effect)}, slot 6.
     */
    static final FunctionDescriptor RENDERER_DRAW_GLYPH_RUN_FD = FunctionDescriptor.of(
            JAVA_INT, ADDRESS, ADDRESS, JAVA_FLOAT, JAVA_FLOAT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS);

    /**
     * {@code HRESULT f(this, void* context, FLOAT x, FLOAT y, void const* decoration,
     * IUnknown* effect)}: {@code DrawUnderline} and {@code DrawStrikethrough}, slots 7 and 8.
     */
    static final FunctionDescriptor RENDERER_DECORATION_FD = FunctionDescriptor.of(
            JAVA_INT, ADDRESS, ADDRESS, JAVA_FLOAT, JAVA_FLOAT, ADDRESS, ADDRESS);

    /**
     * {@code HRESULT DrawInlineObject(this, void* context, FLOAT x, FLOAT y, IDWriteInlineObject*,
     * BOOL isSideways, BOOL isRightToLeft, IUnknown* effect)}, slot 9.
     */
    static final FunctionDescriptor RENDERER_INLINE_OBJECT_FD = FunctionDescriptor.of(
            JAVA_INT, ADDRESS, ADDRESS, JAVA_FLOAT, JAVA_FLOAT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS);

    private static final byte[] IID_TEXT_RENDERER_BYTES = guidBytes(IID_IDWRITE_TEXT_RENDERER);
    private static final byte[] IID_PIXEL_SNAPPING_BYTES = guidBytes(IID_IDWRITE_PIXEL_SNAPPING);

    static final Map<Long, TextRendererSession> TEXT_RENDERERS = new ConcurrentHashMap<>();

    private static final AtomicLong NEXT_TEXT_RENDERER_ID = new AtomicLong(1);

    private DWTextRendererObject() {
    }

    /**
     * The Java replacement for {@code OS._NewJFXTextRenderer} ({@code directwrite.cpp:1479-1483}):
     * a 16-byte COM object in its own confined arena, with a reference count of 0 as the C++
     * constructor left it ({@code :1317}). The returned value is a registry id;
     * {@link #textRendererPointer} is the {@code IDWriteTextRenderer*} to pass to {@link DWNative#draw}.
     * {@link #disposeTextRenderer} is mandatory and must run on this thread - see
     * {@link DWAnalysisSinkObject#newAnalysisSink} for why there is no {@code Cleaner}.
     */
    static long newTextRenderer() {
        return newTextRenderer(false);
    }

    /** As {@link #newTextRenderer}, but the object records every callback for the tests. */
    static long newRecordingTextRenderer() {
        return newTextRenderer(true);
    }

    private static long newTextRenderer(boolean recording) {
        MemorySegment vtable = RendererTable.VTABLE;
        if (vtable.address() == 0) {
            return 0L;
        }
        Arena arena = Arena.ofConfined();
        try {
            MemorySegment block = arena.allocate(TEXT_RENDERER_OBJECT_LAYOUT);
            long id = NEXT_TEXT_RENDERER_ID.getAndIncrement();
            block.set(ADDRESS, 0, vtable);
            block.set(JAVA_LONG, TEXT_RENDERER_ID_OFFSET, id);
            TEXT_RENDERERS.put(id, new TextRendererSession(arena, block, recording));
            return id;
        } catch (Throwable t) {
            arena.close();
            throw t;
        }
    }

    static long textRendererPointer(long id) {
        TextRendererSession session = TEXT_RENDERERS.get(id);
        return session == null ? 0L : session.block.address();
    }

    /** {@code JFXTextRenderer::Next} ({@code directwrite.cpp:1430-1433}). */
    static boolean textRendererNext(long id) {
        TextRendererSession session = TEXT_RENDERERS.get(id);
        return session != null && session.next();
    }

    /** {@code GetStart} ({@code :1435-1438}): the <b>description's</b> {@code textPosition}. */
    static int textRendererGetStart(long id) {
        GlyphRunRecord run = currentRun(id);
        return run == null ? 0 : run.textPosition;
    }

    /** {@code GetLength} ({@code :1440-1443}): the description's {@code stringLength}. */
    static int textRendererGetLength(long id) {
        GlyphRunRecord run = currentRun(id);
        return run == null ? 0 : run.stringLength;
    }

    /** {@code GetGlyphCount} ({@code :1445-1448}): the glyph run's {@code glyphCount}. */
    static int textRendererGetGlyphCount(long id) {
        GlyphRunRecord run = currentRun(id);
        return run == null ? 0 : run.glyphCount;
    }

    /** {@code GetTotalGlyphCount} ({@code :1450-1452}): the accumulator, valid before any Next. */
    static int textRendererGetTotalGlyphCount(long id) {
        TextRendererSession session = TEXT_RENDERERS.get(id);
        return session == null ? 0 : session.totalGlyphCount;
    }

    /**
     * {@code GetFontFace} ({@code :1454-1457}): the run's {@code IDWriteFontFace*}, <b>borrowed</b>.
     * The C did not {@code AddRef} it and {@code DWGlyphLayout.renderShape} never releases it
     * ({@code DWGlyphLayout.java:377}) - its lifetime is the layout's. Out of range it is 0, which
     * the peer turns into {@code null} and {@code getFontSlot} into slot -1.
     */
    static long textRendererGetFontFace(long id) {
        GlyphRunRecord run = currentRun(id);
        return run == null ? 0L : run.fontFace;
    }

    /**
     * {@code GetGlyphIndices} ({@code directwrite.cpp:1515-1536}). The clamp is the C's, computed
     * from the whole array length: {@code min(glyphCount, glyphs.length - start)}. Each index is
     * merged with {@code slot}, which the caller has already shifted left by 24
     * ({@code DWGlyphLayout.java:380}), and the {@code UINT16} is zero-extended.
     * <p>
     * A {@code null} array or a negative {@code start} or {@code slot} answers 0, as the C's four
     * early returns did. Where the C would have overrun the array - {@code start} past its end made
     * its {@code copiedCount} negative and its {@code UINT32} loop counter unbounded - this copies
     * nothing and answers 0.
     */
    static int textRendererGetGlyphIndices(long id, int[] glyphs, int start, int slot) {
        if (glyphs == null || start < 0 || slot < 0) {
            return 0;
        }
        GlyphRunRecord run = currentRun(id);
        if (run == null || run.glyphIndices == null) {
            return 0;
        }
        int copied = clamp(glyphs.length - start, run.glyphCount);
        for (int i = 0; i < copied; i++) {
            glyphs[i + start] = (run.glyphIndices[i] & 0xFFFF) | slot;
        }
        return copied;
    }

    /** {@code GetGlyphAdvances} ({@code :1538-1558}): the same clamp, one float per glyph. */
    static int textRendererGetGlyphAdvances(long id, float[] advances, int start) {
        if (advances == null || start < 0) {
            return 0;
        }
        GlyphRunRecord run = currentRun(id);
        if (run == null || run.glyphAdvances == null) {
            return 0;
        }
        int copied = clamp(advances.length - start, run.glyphCount);
        System.arraycopy(run.glyphAdvances, 0, advances, start, copied);
        return copied;
    }

    /**
     * {@code GetGlyphOffsets} ({@code :1560-1583}): {@code DWRITE_GLYPH_OFFSET} flattened as
     * {@code advanceOffset, ascenderOffset} per glyph, so the count is {@code glyphCount * 2} and an
     * odd clamped count is rejected with 0 - <b>after</b> the clamp ({@code :1572}), so a caller
     * whose array leaves room for half a pair gets nothing rather than half of one.
     */
    static int textRendererGetGlyphOffsets(long id, float[] offsets, int start) {
        if (offsets == null || start < 0) {
            return 0;
        }
        GlyphRunRecord run = currentRun(id);
        if (run == null || run.glyphOffsets == null) {
            return 0;
        }
        int copied = clamp(offsets.length - start, run.glyphCount * 2);
        if (copied % 2 != 0) {
            return 0;
        }
        System.arraycopy(run.glyphOffsets, 0, offsets, start, copied);
        return copied;
    }

    /**
     * {@code GetClusterMap} ({@code :1585-1610}): clamped by {@code GetLength()}, the description's
     * {@code stringLength}, and shifted by {@code glyphStart} so that the map is relative to the
     * start of the JavaFX run rather than to the glyph run. {@code glyphStart} is truncated to a
     * {@code short} <b>before</b> the addition and the sum truncated again on store - both
     * truncations are the C's ({@code :1606}) and both are reproduced.
     */
    static int textRendererGetClusterMap(long id, short[] clusterMap, int textStart, int glyphStart) {
        if (clusterMap == null || textStart < 0 || glyphStart < 0) {
            return 0;
        }
        GlyphRunRecord run = currentRun(id);
        if (run == null || run.clusterMap == null) {
            return 0;
        }
        int copied = clamp(clusterMap.length - textStart, run.stringLength);
        short shift = (short) glyphStart;
        for (int i = 0; i < copied; i++) {
            clusterMap[i + textStart] = (short) ((run.clusterMap[i] & 0xFFFF) + shift);
        }
        return copied;
    }

    /** {@code copiedCount = length - start > available ? available : length - start}, never negative. */
    private static int clamp(int room, int available) {
        return Math.max(0, Math.min(room, available));
    }

    private static GlyphRunRecord currentRun(long id) {
        TextRendererSession session = TEXT_RENDERERS.get(id);
        return session == null ? null : session.current();
    }

    /** {@code AddRef} from Java: the counter DirectWrite shares. */
    static int textRendererAddRef(long id) {
        TextRendererSession session = TEXT_RENDERERS.get(id);
        return session == null ? 1 : session.addRef();
    }

    /** {@code Release} from Java: reaching zero marks the object dead and frees nothing. */
    static int textRendererRelease(long id) {
        TextRendererSession session = TEXT_RENDERERS.get(id);
        return session == null ? 0 : session.release();
    }

    /** Frees the block and removes the registry entry. Idempotent; must run on the creating thread. */
    static void disposeTextRenderer(long id) {
        TextRendererSession session = TEXT_RENDERERS.remove(id);
        if (session != null) {
            session.arena.close();
        }
    }

    /** How many text renderers are live: the leak check. */
    static int textRendererRegistrySize() {
        return TEXT_RENDERERS.size();
    }

    /** Every callback the renderer received, in order, when it was made recording. */
    static List<String> textRendererCallbacks(long id) {
        TextRendererSession session = TEXT_RENDERERS.get(id);
        return session == null ? List.of() : List.copyOf(session.callbacks);
    }

    /**
     * The IIDs DirectWrite asked the renderer for, each with the answer it got. Measured, not
     * assumed: {@code dwrite.dll} 10.0.19041 asks a renderer for {@code IDWriteTextRenderer1}
     * (d3e0e934-22a0-427e-aae4-7d9574b59db1, dwrite_2.h:83) before drawing and falls back to the
     * base interface when that is refused - which the C refused too, from the same {@code else}
     * branch ({@code directwrite.cpp:1422-1425}). So this object really is queried, and answering
     * {@code E_FAIL} for an unknown IID is load-bearing rather than defensive.
     */
    static List<String> textRendererQueriedIids(long id) {
        TextRendererSession session = TEXT_RENDERERS.get(id);
        return session == null ? List.of() : List.copyOf(session.queriedIids);
    }

    /**
     * The baseline origin and measuring mode of run {@code index}, which the C dropped on the floor
     * and no accessor could reach: {@code { baselineOriginX bits, baselineOriginY bits,
     * measuringMode, isSideways, bidiLevel, fontEmSize bits }}. Float bits, not floats, so the
     * comparison in a test is exact. This is how the tests show that the two {@code FLOAT}
     * parameters of {@code DrawGlyphRun} are decoded from the right registers: draw the same layout
     * at two origins and the difference is exactly the difference of the origins.
     */
    static long[] textRendererRunGeometry(long id, int index) {
        TextRendererSession session = TEXT_RENDERERS.get(id);
        if (session == null || index < 0 || index >= session.runs.size()) {
            return null;
        }
        GlyphRunRecord run = session.runs.get(index);
        return new long[] {
                Float.floatToRawIntBits(run.baselineOriginX),
                Float.floatToRawIntBits(run.baselineOriginY),
                run.measuringMode, run.isSideways, run.bidiLevel,
                Float.floatToRawIntBits(run.fontEmSize) };
    }

    /** How many glyph runs this renderer collected, without moving its cursor. */
    static int textRendererRunCount(long id) {
        TextRendererSession session = TEXT_RENDERERS.get(id);
        return session == null ? 0 : session.runs.size();
    }

    private static TextRendererSession rendererSession(MemorySegment self) {
        try {
            return TEXT_RENDERERS.get(bounded(self, TEXT_RENDERER_OBJECT_LAYOUT.byteSize())
                    .get(JAVA_LONG, TEXT_RENDERER_ID_OFFSET));
        } catch (Throwable t) {
            return null;
        }
    }

    /* The ten upcall targets of the renderer. Same rules as the sink's: never throw, never return a
     * failure the C could not return. directwrite.cpp:1326-1399 returns S_OK from all seven of the
     * collectors and queries. */

    /**
     * {@code QueryInterface} ({@code directwrite.cpp:1415-1428}): {@code IDWriteTextRenderer},
     * {@code IDWritePixelSnapping} and {@code IUnknown} all answer with {@code self}, which is
     * correct rather than merely faithful - single inheritance means the three interfaces share one
     * vtable pointer at offset 0. Anything else is {@code E_FAIL} with {@code *ppvObject = NULL}.
     */
    private static int onRendererQueryInterface(MemorySegment self, MemorySegment riid,
                                                MemorySegment out) {
        MemorySegment target = MemorySegment.NULL;
        TextRendererSession session = rendererSession(self);
        try {
            target = bounded(out, ADDRESS.byteSize());
            byte[] id = bounded(riid, GUID_LAYOUT.byteSize()).toArray(JAVA_BYTE);
            boolean known = session != null
                    && (Arrays.equals(id, IID_TEXT_RENDERER_BYTES)
                    || Arrays.equals(id, IID_PIXEL_SNAPPING_BYTES)
                    || Arrays.equals(id, IID_IUNKNOWN_BYTES));
            if (session != null) {
                session.queried(id, known);
            }
            if (known) {
                target.set(ADDRESS, 0, self);
                session.addRef();
                return S_OK;
            }
            target.set(ADDRESS, 0, MemorySegment.NULL);
            return E_FAIL;
        } catch (Throwable t) {
            logCallbackFailure("IDWriteTextRenderer::QueryInterface", t);
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

    private static int onRendererAddRef(MemorySegment self) {
        TextRendererSession session = rendererSession(self);
        return session == null ? 1 : session.addRef();
    }

    private static int onRendererRelease(MemorySegment self) {
        TextRendererSession session = rendererSession(self);
        return session == null ? 0 : session.release();
    }

    /**
     * {@code IsPixelSnappingDisabled} ({@code :1376-1382}): writes {@code FALSE}. These three
     * queries are behaviour, not boilerplate - together they tell DirectWrite that positions are
     * snapped, that the transform is the identity and that there is one pixel per DIP, which is what
     * decides the glyph positions the golden pins.
     */
    private static int onIsPixelSnappingDisabled(MemorySegment self, MemorySegment context,
                                                 MemorySegment isDisabled) {
        TextRendererSession session = rendererSession(self);
        try {
            bounded(isDisabled, JAVA_INT.byteSize()).set(JAVA_INT, 0, 0);
            if (session != null) {
                session.record("IsPixelSnappingDisabled");
            }
        } catch (Throwable t) {
            logCallbackFailure("IDWritePixelSnapping::IsPixelSnappingDisabled", t);
        }
        return S_OK;
    }

    /** {@code GetCurrentTransform} ({@code :1384-1391}): the identity {@code DWRITE_MATRIX}. */
    private static int onGetCurrentTransform(MemorySegment self, MemorySegment context,
                                             MemorySegment transform) {
        TextRendererSession session = rendererSession(self);
        try {
            MemorySegment matrix = bounded(transform, DWRITE_MATRIX_LAYOUT.byteSize());
            matrix.setAtIndex(JAVA_FLOAT, 0, 1.0f);
            matrix.setAtIndex(JAVA_FLOAT, 1, 0.0f);
            matrix.setAtIndex(JAVA_FLOAT, 2, 0.0f);
            matrix.setAtIndex(JAVA_FLOAT, 3, 1.0f);
            matrix.setAtIndex(JAVA_FLOAT, 4, 0.0f);
            matrix.setAtIndex(JAVA_FLOAT, 5, 0.0f);
            if (session != null) {
                session.record("GetCurrentTransform");
            }
        } catch (Throwable t) {
            logCallbackFailure("IDWritePixelSnapping::GetCurrentTransform", t);
        }
        return S_OK;
    }

    /** {@code GetPixelsPerDip} ({@code :1393-1399}): 1.0f. */
    private static int onGetPixelsPerDip(MemorySegment self, MemorySegment context,
                                         MemorySegment pixelsPerDip) {
        TextRendererSession session = rendererSession(self);
        try {
            bounded(pixelsPerDip, JAVA_FLOAT.byteSize()).set(JAVA_FLOAT, 0, 1.0f);
            if (session != null) {
                session.record("GetPixelsPerDip");
            }
        } catch (Throwable t) {
            logCallbackFailure("IDWritePixelSnapping::GetPixelsPerDip", t);
        }
        return S_OK;
    }

    /**
     * {@code DrawGlyphRun} ({@code directwrite.cpp:1326-1341}), slot 6: the one callback that
     * collects.
     * <p>
     * The C shallow-copied both structs and kept DirectWrite's pointers, reading the glyph arrays
     * after {@code Draw} had returned - sound only because the {@code IDWriteTextLayout} still owns
     * those buffers until it is released. Java copies the arrays here instead, inside the callback,
     * which is strictly safer and yields the same values. {@code fontFace} is copied as a raw
     * pointer <b>without</b> {@code AddRef}, matching {@code :1454} and the fact that
     * {@code DWGlyphLayout} never releases it.
     * <p>
     * {@code glyphAdvances}, {@code glyphOffsets} and {@code clusterMap} are documented optional
     * ({@code _Field_size_opt_}); the C would have dereferenced NULL in its drains, this stores
     * {@code null} and the matching drain answers 0. The description itself is {@code _In_}, so a
     * NULL there is a fault in the C; here it becomes a run with no cluster map.
     */
    static int onDrawGlyphRun(MemorySegment self, MemorySegment context, float baselineOriginX,
                              float baselineOriginY, int measuringMode, MemorySegment glyphRun,
                              MemorySegment glyphRunDescription, MemorySegment effect) {
        TextRendererSession session = rendererSession(self);
        if (session == null) {
            return S_OK;
        }
        try {
            MemorySegment run = bounded(glyphRun, DWRITE_GLYPH_RUN_LAYOUT.byteSize());
            int glyphCount = Math.max(0, run.get(JAVA_INT, RUN_GLYPH_COUNT));
            long fontFace = run.get(ADDRESS, RUN_FONT_FACE).address();
            float fontEmSize = run.get(JAVA_FLOAT, RUN_FONT_EM_SIZE);
            int isSideways = run.get(JAVA_INT, RUN_IS_SIDEWAYS);
            int bidiLevel = run.get(JAVA_INT, RUN_BIDI_LEVEL);
            short[] glyphIndices = readShorts(run.get(ADDRESS, RUN_GLYPH_INDICES), glyphCount);
            float[] glyphAdvances = readFloats(run.get(ADDRESS, RUN_GLYPH_ADVANCES), glyphCount);
            float[] glyphOffsets = readFloats(run.get(ADDRESS, RUN_GLYPH_OFFSETS), glyphCount * 2);

            int textPosition = 0;
            int stringLength = 0;
            short[] clusterMap = null;
            if (glyphRunDescription.address() != 0) {
                MemorySegment description = bounded(glyphRunDescription,
                        DWRITE_GLYPH_RUN_DESCRIPTION_LAYOUT.byteSize());
                textPosition = description.get(JAVA_INT, DESCRIPTION_TEXT_POSITION);
                stringLength = Math.max(0, description.get(JAVA_INT, DESCRIPTION_STRING_LENGTH));
                clusterMap = readShorts(description.get(ADDRESS, DESCRIPTION_CLUSTER_MAP), stringLength);
            }
            session.add(new GlyphRunRecord(fontFace, fontEmSize, glyphCount, glyphIndices,
                    glyphAdvances, glyphOffsets, isSideways, bidiLevel, textPosition, stringLength,
                    clusterMap, baselineOriginX, baselineOriginY, measuringMode));
        } catch (Throwable t) {
            logCallbackFailure("IDWriteTextRenderer::DrawGlyphRun", t);
        }
        return S_OK;
    }

    /**
     * {@code DrawUnderline} and {@code DrawStrikethrough} ({@code :1343-1361}), slots 7 and 8:
     * {@code S_OK} and nothing collected - JavaFX draws decorations itself. They still occupy their
     * slots, because DirectWrite calls what it likes.
     */
    private static int onDrawDecoration(MemorySegment self, MemorySegment context, float originX,
                                        float originY, MemorySegment decoration, MemorySegment effect) {
        TextRendererSession session = rendererSession(self);
        if (session != null) {
            session.record("DrawDecoration " + Float.toString(originX) + " " + Float.toString(originY));
        }
        return S_OK;
    }

    /** {@code DrawInlineObject} ({@code :1363-1373}), slot 9: {@code S_OK}, nothing collected. */
    private static int onDrawInlineObject(MemorySegment self, MemorySegment context, float originX,
                                          float originY, MemorySegment inlineObject, int isSideways,
                                          int isRightToLeft, MemorySegment effect) {
        TextRendererSession session = rendererSession(self);
        if (session != null) {
            session.record("DrawInlineObject " + Float.toString(originX) + " "
                    + Float.toString(originY) + " " + isSideways + " " + isRightToLeft);
        }
        return S_OK;
    }

    /** {@code count} UINT16s from a possibly NULL pointer, or {@code null} for the NULL. */
    private static short[] readShorts(MemorySegment pointer, int count) {
        if (pointer.address() == 0 || count <= 0) {
            return null;
        }
        return bounded(pointer, (long) count * Short.BYTES).toArray(JAVA_SHORT);
    }

    /** {@code count} FLOATs from a possibly NULL pointer, or {@code null} for the NULL. */
    private static float[] readFloats(MemorySegment pointer, int count) {
        if (pointer.address() == 0 || count <= 0) {
            return null;
        }
        return bounded(pointer, (long) count * Float.BYTES).toArray(JAVA_FLOAT);
    }

    /**
     * One glyph run as {@code DrawGlyphRun} saw it. The C kept {@code DWRITE_GLYPH_RUN} and
     * {@code DWRITE_GLYPH_RUN_DESCRIPTION} by value and their buffers by pointer; this keeps the
     * buffers by value and adds the three arguments the C dropped - the baseline origin and the
     * measuring mode - which are what a test needs to see that the float arguments arrived intact.
     */
    private static final class GlyphRunRecord {

        private final long fontFace;
        private final float fontEmSize;
        private final int glyphCount;
        private final short[] glyphIndices;
        private final float[] glyphAdvances;
        private final float[] glyphOffsets;
        private final int isSideways;
        private final int bidiLevel;
        private final int textPosition;
        private final int stringLength;
        private final short[] clusterMap;
        private final float baselineOriginX;
        private final float baselineOriginY;
        private final int measuringMode;

        GlyphRunRecord(long fontFace, float fontEmSize, int glyphCount, short[] glyphIndices,
                       float[] glyphAdvances, float[] glyphOffsets, int isSideways, int bidiLevel,
                       int textPosition, int stringLength, short[] clusterMap, float baselineOriginX,
                       float baselineOriginY, int measuringMode) {
            this.fontFace = fontFace;
            this.fontEmSize = fontEmSize;
            this.glyphCount = glyphCount;
            this.glyphIndices = glyphIndices;
            this.glyphAdvances = glyphAdvances;
            this.glyphOffsets = glyphOffsets;
            this.isSideways = isSideways;
            this.bidiLevel = bidiLevel;
            this.textPosition = textPosition;
            this.stringLength = stringLength;
            this.clusterMap = clusterMap;
            this.baselineOriginX = baselineOriginX;
            this.baselineOriginY = baselineOriginY;
            this.measuringMode = measuringMode;
        }
    }

    /** One {@code Draw} object: the arena, the runs it collected and the cursor the drains move. */
    static final class TextRendererSession {

        private static final int MAX_RECORDED = 4096;

        private final Arena arena;
        final MemorySegment block;
        private final boolean recording;
        private final List<GlyphRunRecord> runs = new ArrayList<>();
        private final List<String> callbacks = new ArrayList<>();
        private final List<String> queriedIids = new ArrayList<>();
        private int position = -1;
        private int totalGlyphCount;
        int refCount;

        TextRendererSession(Arena arena, MemorySegment block, boolean recording) {
            this.arena = arena;
            this.block = block;
            this.recording = recording;
        }

        void add(GlyphRunRecord run) {
            runs.add(run);
            totalGlyphCount += run.glyphCount;
            record("DrawGlyphRun " + Float.toString(run.baselineOriginX) + " "
                    + Float.toString(run.baselineOriginY) + " " + run.measuringMode + " "
                    + run.glyphCount + " " + run.textPosition + " " + run.stringLength);
        }

        boolean next() {
            position++;
            return position < runs.size();
        }

        GlyphRunRecord current() {
            if (position < 0 || position >= runs.size()) {
                return null;
            }
            return runs.get(position);
        }

        int addRef() {
            return ++refCount;
        }

        int release() {
            return --refCount;
        }

        void record(String callback) {
            if (recording && callbacks.size() < MAX_RECORDED) {
                callbacks.add(callback);
            }
        }

        void queried(byte[] iid, boolean answered) {
            if (queriedIids.size() < 32) {
                queriedIids.add(uuidText(iid) + (answered ? " S_OK" : " E_FAIL"));
            }
        }
    }

    /** The renderer vtable: ten stubs, built once in {@link Arena#global()}, never freed. */
    private static final class RendererTable {

        static final MemorySegment VTABLE = build();

        private static MemorySegment build() {
            try {
                MethodHandles.Lookup lookup = MethodHandles.lookup();
                MemorySegment table = Arena.global()
                        .allocate(ADDRESS.byteSize() * RENDERER_SLOT_COUNT, ADDRESS.byteSize());
                table.setAtIndex(ADDRESS, IUNKNOWN_QUERY_INTERFACE, upcall(lookup,
                        "onRendererQueryInterface", COM_QUERY_INTERFACE_FD, int.class,
                        MemorySegment.class, MemorySegment.class, MemorySegment.class));
                table.setAtIndex(ADDRESS, IUNKNOWN_ADD_REF, upcall(lookup, "onRendererAddRef",
                        COM_REF_COUNT_FD, int.class, MemorySegment.class));
                table.setAtIndex(ADDRESS, IUNKNOWN_RELEASE, upcall(lookup, "onRendererRelease",
                        COM_REF_COUNT_FD, int.class, MemorySegment.class));
                table.setAtIndex(ADDRESS, RENDERER_IS_PIXEL_SNAPPING_DISABLED, upcall(lookup,
                        "onIsPixelSnappingDisabled", RENDERER_QUERY_FD, int.class, MemorySegment.class,
                        MemorySegment.class, MemorySegment.class));
                table.setAtIndex(ADDRESS, RENDERER_GET_CURRENT_TRANSFORM, upcall(lookup,
                        "onGetCurrentTransform", RENDERER_QUERY_FD, int.class, MemorySegment.class,
                        MemorySegment.class, MemorySegment.class));
                table.setAtIndex(ADDRESS, RENDERER_GET_PIXELS_PER_DIP, upcall(lookup,
                        "onGetPixelsPerDip", RENDERER_QUERY_FD, int.class, MemorySegment.class,
                        MemorySegment.class, MemorySegment.class));
                table.setAtIndex(ADDRESS, RENDERER_DRAW_GLYPH_RUN, upcall(lookup, "onDrawGlyphRun",
                        RENDERER_DRAW_GLYPH_RUN_FD, int.class, MemorySegment.class, MemorySegment.class,
                        float.class, float.class, int.class, MemorySegment.class, MemorySegment.class,
                        MemorySegment.class));
                table.setAtIndex(ADDRESS, RENDERER_DRAW_UNDERLINE, upcall(lookup, "onDrawDecoration",
                        RENDERER_DECORATION_FD, int.class, MemorySegment.class, MemorySegment.class,
                        float.class, float.class, MemorySegment.class, MemorySegment.class));
                table.setAtIndex(ADDRESS, RENDERER_DRAW_STRIKETHROUGH, upcall(lookup, "onDrawDecoration",
                        RENDERER_DECORATION_FD, int.class, MemorySegment.class, MemorySegment.class,
                        float.class, float.class, MemorySegment.class, MemorySegment.class));
                table.setAtIndex(ADDRESS, RENDERER_DRAW_INLINE_OBJECT, upcall(lookup,
                        "onDrawInlineObject", RENDERER_INLINE_OBJECT_FD, int.class, MemorySegment.class,
                        MemorySegment.class, float.class, float.class, MemorySegment.class, int.class,
                        int.class, MemorySegment.class));
                return table;
            } catch (Throwable t) {
                return MemorySegment.NULL;
            }
        }
    }
}
