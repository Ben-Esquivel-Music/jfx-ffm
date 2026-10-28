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
import static com.sun.javafx.font.directwrite.DWLayouts.DWRITE_SCRIPT_ANALYSIS_LAYOUT;
import static com.sun.javafx.font.directwrite.DWLayouts.offsetOf;
import static com.sun.javafx.font.directwrite.DWNative.E_FAIL;
import static com.sun.javafx.font.directwrite.DWNative.GUID_LAYOUT;
import static com.sun.javafx.font.directwrite.DWNative.IUNKNOWN_ADD_REF;
import static com.sun.javafx.font.directwrite.DWNative.IUNKNOWN_QUERY_INTERFACE;
import static com.sun.javafx.font.directwrite.DWNative.IUNKNOWN_RELEASE;
import static com.sun.javafx.font.directwrite.DWNative.S_OK;
import static com.sun.javafx.font.directwrite.DWNative.addRef;
import static com.sun.javafx.font.directwrite.DWNative.bounded;
import static com.sun.javafx.font.directwrite.DWNative.com;
import static com.sun.javafx.font.directwrite.DWNative.guidBytes;
import static com.sun.javafx.font.directwrite.DWNative.slot;
import static com.sun.javafx.font.directwrite.DWNative.unexpected;
import static com.sun.javafx.font.directwrite.DWNative.upcall;
import static com.sun.javafx.font.directwrite.DWNative.virtual;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_CHAR;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * The text analysis sink and source of {@link DWNative}: one COM object with two interfaces, implemented in
 * Java.
 * <p>
 * IDWriteTextAnalyzer::AnalyzeScript reports its results by calling back, so it needs an object
 * exposing IDWriteTextAnalysisSink, and it reads its input by calling back, so it needs one
 * exposing IDWriteTextAnalysisSource. JavaFX passes the same object as both
 * (IDWriteTextAnalyzer.java:34). The C did that with C++ multiple inheritance
 * (directwrite.cpp:936: "public IDWriteTextAnalysisSink, public IDWriteTextAnalysisSource"),
 * which gives the object two vtable pointers, the sink's at offset 0 and the source's at offset
 * 8, and makes the compiler adjust "this" by 8 at every source call. Java builds the same shape
 * by hand:
 * <pre>
 *     +0   const void* vtblSink     -> 7 upcall stubs, Arena.global()
 *     +8   const void* vtblSource   -> 8 upcall stubs, Arena.global()
 *     +16  int64_t     id           -> registry key, never a Java reference
 * </pre>
 * so the sink stub finds its id at self+16 and the source stub at self+8. Nothing DirectWrite
 * receives points at a Java object, and no stub dereferences a pointer it has not bounded.
 * <p>
 * The two vtables are process-wide: their targets are static methods, so there is one table per
 * class, built once, and the per-instance cost is 24 bytes plus the two text copies. The object
 * itself lives in a confined arena for exactly as long as the caller keeps it, and dispose is
 * mandatory - see disposeAnalysisSink.
 * <p>
 * Line numbers into {@code directwrite.cpp} (bare {@code :N} included) and the SDK headers follow
 * {@link DWNative}: commit {@code 8492cb03b0}, Windows SDK 10.0.26100.0.
 */
final class DWAnalysisSinkObject {

    /** {@code IDWriteTextAnalysisSink} (dwrite.h:2657). */
    static final String IID_IDWRITE_TEXT_ANALYSIS_SINK = "5810cd44-0ca0-4701-b3fa-bec5182ae4f6";

    /** {@code IDWriteTextAnalysisSource} (dwrite.h:2548). */
    static final String IID_IDWRITE_TEXT_ANALYSIS_SOURCE = "688e1a58-5094-47c8-adc8-fbcea60ae92b";

    /* IDWriteTextAnalysisSink (dwrite.h:2657), base IUnknown = 3, four own methods. */
    static final int ANALYSIS_SINK_SET_SCRIPT_ANALYSIS = 3;                // ord 0, dwrite.h:2668
    static final int ANALYSIS_SINK_SET_LINE_BREAKPOINTS = 4;               // ord 1, dwrite.h:2684
    static final int ANALYSIS_SINK_SET_BIDI_LEVEL = 5;                     // ord 2, dwrite.h:2704
    static final int ANALYSIS_SINK_SET_NUMBER_SUBSTITUTION = 6;            // ord 3, dwrite.h:2728
    private static final int ANALYSIS_SINK_SLOT_COUNT = 7;

    /* IDWriteTextAnalysisSource (dwrite.h:2548), base IUnknown = 3, five own methods. */
    static final int ANALYSIS_SOURCE_GET_TEXT_AT_POSITION = 3;             // ord 0, dwrite.h:2576
    static final int ANALYSIS_SOURCE_GET_TEXT_BEFORE_POSITION = 4;         // ord 1, dwrite.h:2601
    static final int ANALYSIS_SOURCE_GET_READING_DIRECTION = 5;            // ord 2, dwrite.h:2610
    static final int ANALYSIS_SOURCE_GET_LOCALE_NAME = 6;                  // ord 3, dwrite.h:2624
    static final int ANALYSIS_SOURCE_GET_NUMBER_SUBSTITUTION = 7;          // ord 4, dwrite.h:2644
    private static final int ANALYSIS_SOURCE_SLOT_COUNT = 8;

    /** The 24-byte object of the class comment. */
    static final StructLayout ANALYSIS_SINK_OBJECT_LAYOUT = MemoryLayout.structLayout(
            ADDRESS.withName("vtblSink"),             // offset 0
            ADDRESS.withName("vtblSource"),           // offset 8
            JAVA_LONG.withName("id"));                // offset 16, byteSize 24

    static final long ANALYSIS_SOURCE_OFFSET =
            offsetOf(ANALYSIS_SINK_OBJECT_LAYOUT, "vtblSource");
    private static final long ANALYSIS_SINK_ID_OFFSET = offsetOf(ANALYSIS_SINK_OBJECT_LAYOUT, "id");

    /**
     * {@code HRESULT f(this, UINT32 textPosition, UINT32 textLength, void const*)}: sink slots 3, 4
     * and 6 - {@code SetScriptAnalysis}, {@code SetLineBreakpoints} and
     * {@code SetNumberSubstitution}.
     */
    static final FunctionDescriptor SINK_RANGE_FD =
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS);

    /**
     * {@code HRESULT SetBidiLevel(this, UINT32, UINT32, UINT8 explicitLevel, UINT8 resolvedLevel)},
     * sink slot 5. The two {@code UINT8}s are the only sub-word integer arguments in the package: on
     * Microsoft x64 they occupy whole argument slots (R9B and the first stack slot) with the upper
     * bits undefined, which is exactly what {@code JAVA_BYTE} in a descriptor means. Widening them
     * to {@code JAVA_INT} would read those undefined bits.
     */
    static final FunctionDescriptor SINK_SET_BIDI_LEVEL_FD =
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_BYTE, JAVA_BYTE);

    /**
     * {@code HRESULT f(this, UINT32 textPosition, void** a, void* b)}: source slots 3, 4, 6 and 7.
     * {@code GetTextAtPosition} and {@code GetTextBeforePosition} take {@code (WCHAR const**, UINT32*)}
     * while {@code GetLocaleName} and {@code GetNumberSubstitution} take {@code (UINT32*, ...**)} -
     * the same ABI shape with the two out-parameters in the opposite order, which is the single
     * easiest thing in these bindings to invert. dwrite.h:2624 and :2644 put the length first.
     */
    static final FunctionDescriptor SOURCE_QUERY_FD =
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS);

    /**
     * {@code DWRITE_READING_DIRECTION GetParagraphReadingDirection(this)}, source slot 5: declared
     * {@code STDMETHOD_(DWRITE_READING_DIRECTION, ...)}, so it returns an enum in EAX and
     * <em>not</em> an HRESULT. A stub that returned {@code S_OK} here would silently report
     * left-to-right for every right-to-left run.
     */
    static final FunctionDescriptor SOURCE_READING_DIRECTION_FD =
            FunctionDescriptor.of(JAVA_INT, ADDRESS);

    private static final byte[] IID_ANALYSIS_SINK_BYTES = guidBytes(IID_IDWRITE_TEXT_ANALYSIS_SINK);
    private static final byte[] IID_ANALYSIS_SOURCE_BYTES = guidBytes(IID_IDWRITE_TEXT_ANALYSIS_SOURCE);

    static final Map<Long, AnalysisSinkSession> ANALYSIS_SINKS = new ConcurrentHashMap<>();

    /** Ids start at 1, so a zeroed or freed word reads as 0 and misses the registry cleanly. */
    private static final AtomicLong NEXT_ANALYSIS_SINK_ID = new AtomicLong(1);

    private DWAnalysisSinkObject() {
    }

    /**
     * The Java replacement for {@code OS._NewJFXTextAnalysisSink}
     * ({@code directwrite.cpp:1200-1205}): a 24-byte COM object plus copies of the text and the
     * locale, all in one confined arena.
     * <p>
     * {@code text} is copied from {@code start} for exactly {@code length} UTF-16 units and is
     * <b>not</b> NUL-terminated, while {@code locale} is copied whole including the terminator the
     * caller appended - the two different rules at {@code directwrite.cpp:1040-1044}. A
     * {@code null} text or locale answers {@code 0}, which the peer turns into {@code null} and
     * {@code DWGlyphLayout} into an unshaped run ({@code DWGlyphLayout.java:57-59}).
     * <p>
     * The returned value is a <b>registry id, not a pointer</b>. {@link #analysisSinkPointer} and
     * {@link #analysisSourcePointer} produce the two interface pointers to hand to
     * {@link DWNative#analyzeScript}. Like the C object it starts with a reference count of 0
     * ({@code directwrite.cpp:1034}); the caller {@code AddRef}s before handing it over and
     * {@code Release}s afterwards, exactly as {@code DWGlyphLayout.java:60,77} does.
     * <p>
     * <b>{@link #disposeAnalysisSink} is mandatory and must run on this thread</b>, in a
     * {@code finally}: the arena is confined, so nothing else can free it, and unlike the C the
     * object holds a registry entry and a run list rather than 24 bytes. There is deliberately no
     * {@code Cleaner} - a cleaning action runs on the cleaner's own thread and
     * {@code Arena.ofConfined().close()} throws {@code WrongThreadException} there, so a
     * {@code Cleaner} would be a safety net that cannot fire.
     */
    static long newAnalysisSink(char[] text, int start, int length, char[] locale, int direction,
                                long numberSubstitution) {
        return newAnalysisSink(text, start, length, locale, direction, numberSubstitution, false);
    }

    /** As {@link #newAnalysisSink}, but the object records every callback for the tests. */
    static long newRecordingAnalysisSink(char[] text, int start, int length, char[] locale,
                                         int direction, long numberSubstitution) {
        return newAnalysisSink(text, start, length, locale, direction, numberSubstitution, true);
    }

    private static long newAnalysisSink(char[] text, int start, int length, char[] locale,
                                        int direction, long numberSubstitution, boolean recording) {
        if (text == null || locale == null || start < 0 || length < 0) {
            return 0L;
        }
        MemorySegment sinkVtable = AnalysisTables.SINK_VTABLE;
        MemorySegment sourceVtable = AnalysisTables.SOURCE_VTABLE;
        if (sinkVtable.address() == 0 || sourceVtable.address() == 0) {
            return 0L;
        }
        Arena arena = Arena.ofConfined();
        try {
            MemorySegment block = arena.allocate(ANALYSIS_SINK_OBJECT_LAYOUT);
            MemorySegment textCopy = arena.allocate(JAVA_CHAR, length);
            MemorySegment.copy(text, start, textCopy, JAVA_CHAR, 0, length);
            MemorySegment localeCopy = arena.allocateFrom(JAVA_CHAR, locale);
            long id = NEXT_ANALYSIS_SINK_ID.getAndIncrement();
            block.set(ADDRESS, 0, sinkVtable);
            block.set(ADDRESS, ANALYSIS_SOURCE_OFFSET, sourceVtable);
            block.set(JAVA_LONG, ANALYSIS_SINK_ID_OFFSET, id);
            if (numberSubstitution != 0) {
                addRef(numberSubstitution);
            }
            ANALYSIS_SINKS.put(id, new AnalysisSinkSession(arena, block, textCopy, localeCopy, length,
                    direction, numberSubstitution, recording));
            return id;
        } catch (Throwable t) {
            arena.close();
            throw t;
        }
    }

    static long analysisSinkPointer(long id) {
        AnalysisSinkSession session = ANALYSIS_SINKS.get(id);
        return session == null ? 0L : session.block.address();
    }

    /** The {@code IDWriteTextAnalysisSource*} of {@code id}: the block plus 8, or {@code 0}. */
    static long analysisSourcePointer(long id) {
        AnalysisSinkSession session = ANALYSIS_SINKS.get(id);
        return session == null ? 0L : session.block.address() + ANALYSIS_SOURCE_OFFSET;
    }

    /**
     * The distance between the two interface pointers of one object, which is the size of a vtable
     * pointer. It is not only this class's own choice: the C++ object had the same shape, and the
     * pointer {@code OS.AnalyzeScript} receives is the <em>class</em> pointer, from which the
     * compiler derived the source pointer by adding exactly this ({@code directwrite.cpp:936},
     * :2216-2219). A test that drives the C++ object through this facade has to make the same
     * adjustment by hand, which is why the constant is readable.
     */
    static long analysisSourceOffset() {
        return ANALYSIS_SOURCE_OFFSET;
    }

    /**
     * {@code IDWriteTextAnalysisSource::GetParagraphReadingDirection}, slot 5, called
     * <em>outbound</em> on any analysis source - the Java-synthesized one or the C++ one. Never a
     * JNI native and no production caller: it is the probe that settles, at runtime rather than by
     * assumption, that a given pointer really is a source and not something 8 bytes away from one.
     * Asking a sink instead lands on {@code SetBidiLevel}, which answers {@code S_OK} (0) and
     * touches nothing, so the probe cannot fault even when its answer is wrong.
     */
    static int getParagraphReadingDirection(long source) {
        MemorySegment obj = com(source);
        try {
            return (int) virtual(SOURCE_READING_DIRECTION_FD)
                    .invokeExact(slot(obj, ANALYSIS_SOURCE_GET_READING_DIRECTION), obj);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code JFXTextAnalysisSink::Next} ({@code directwrite.cpp:1151-1154}): pre-increments from -1
     * and never resets, so a second pass over the same sink yields nothing.
     */
    static boolean analysisSinkNext(long id) {
        AnalysisSinkSession session = ANALYSIS_SINKS.get(id);
        return session != null && session.next();
    }

    /** {@code GetStart} ({@code :1156-1159}): 0 once the cursor is past the last run. */
    static int analysisSinkGetStart(long id) {
        AnalysisSinkSession session = ANALYSIS_SINKS.get(id);
        return session == null ? 0 : session.field(0);
    }

    /** {@code GetLength} ({@code :1161-1164}): 0 once the cursor is past the last run. */
    static int analysisSinkGetLength(long id) {
        AnalysisSinkSession session = ANALYSIS_SINKS.get(id);
        return session == null ? 0 : session.field(1);
    }

    /**
     * {@code GetAnalysis} ({@code :1166-1169}). Out of range the C returned {@code NULL}, which
     * {@code newDWRITE_SCRIPT_ANALYSIS(env, NULL)} still turned into an allocated, <b>zero-filled</b>
     * object rather than {@code null} ({@code directwrite.cpp:384-390}); this does the same.
     */
    static DWRITE_SCRIPT_ANALYSIS analysisSinkGetAnalysis(long id) {
        DWRITE_SCRIPT_ANALYSIS analysis = new DWRITE_SCRIPT_ANALYSIS();
        AnalysisSinkSession session = ANALYSIS_SINKS.get(id);
        if (session != null) {
            analysis.script = (short) session.field(2);
            analysis.shapes = session.field(3);
        }
        return analysis;
    }

    /** {@code AddRef} called from Java: the counter DirectWrite shares, never a native call. */
    static int analysisSinkAddRef(long id) {
        AnalysisSinkSession session = ANALYSIS_SINKS.get(id);
        return session == null ? 1 : session.addRef();
    }

    /**
     * {@code Release} called from Java. Reaching zero marks the object dead and frees nothing: the
     * arena is the owner and {@link #disposeAnalysisSink} is what closes it. The C deleted itself
     * here ({@code directwrite.cpp:1176-1183}), which is why its callers needed no dispose.
     */
    static int analysisSinkRelease(long id) {
        AnalysisSinkSession session = ANALYSIS_SINKS.get(id);
        return session == null ? 0 : session.release();
    }

    /**
     * Frees the block, the text and the locale, releases the number substitution the constructor
     * referenced and removes the registry entry. Idempotent, and a no-op for an id that is already
     * gone. Must run on the thread that called {@link #newAnalysisSink}.
     */
    static void disposeAnalysisSink(long id) {
        AnalysisSinkSession session = ANALYSIS_SINKS.remove(id);
        if (session != null) {
            session.close();
        }
    }

    /** How many analysis sinks are live: the leak check. */
    static int analysisSinkRegistrySize() {
        return ANALYSIS_SINKS.size();
    }

    /** Every callback the object received, in order, when it was made by {@link #newRecordingAnalysisSink}. */
    static List<String> analysisSinkCallbacks(long id) {
        AnalysisSinkSession session = ANALYSIS_SINKS.get(id);
        return session == null ? List.of() : List.copyOf(session.callbacks);
    }

    /**
     * The IIDs DirectWrite asked this object for, each as "uuid S_OK" or "uuid E_FAIL", so that this is
     * measured rather than assumed: an empty list after a real
     * {@code AnalyzeScript} means DirectWrite never queries the object it was handed. It does query
     * the text renderer - see {@link DWTextRendererObject#textRendererQueriedIids}.
     */
    static List<String> analysisSinkQueriedIids(long id) {
        AnalysisSinkSession session = ANALYSIS_SINKS.get(id);
        return session == null ? List.of() : List.copyOf(session.queriedIids);
    }

    /**
     * The session behind a sink pointer, or {@code null} when word 3 names no live object. Every
     * target tolerates the miss and answers the C's value rather than dereferencing anything: an
     * object DirectWrite kept a reference to past {@link #disposeAnalysisSink} leaves freed bytes
     * there, and a COM object must not fault its caller.
     */
    private static AnalysisSinkSession analysisSinkSession(MemorySegment self) {
        return analysisSession(self, ANALYSIS_SINK_ID_OFFSET);
    }

    /** The same object reached through its source pointer, where the id is 8 bytes closer. */
    private static AnalysisSinkSession sourceSession(MemorySegment self) {
        return analysisSession(self, ANALYSIS_SINK_ID_OFFSET - ANALYSIS_SOURCE_OFFSET);
    }

    private static AnalysisSinkSession analysisSession(MemorySegment self, long idOffset) {
        try {
            return ANALYSIS_SINKS.get(bounded(self, idOffset + JAVA_LONG.byteSize())
                    .get(JAVA_LONG, idOffset));
        } catch (Throwable t) {
            return null;
        }
    }

    /* The fifteen upcall targets of the sink and the source. None may throw: an exception escaping
     * into a COM frame would unwind native code. None returns a failure the C could not return
     * either - every collector and every query in directwrite.cpp:1060-1149 returns S_OK
     * unconditionally, and the callers (DWGlyphLayout.addTextRun L62-78, renderShape L359-408) have
     * no try/finally, so a Java exception surfacing there would leak two or three COM objects per
     * occurrence and turn "no runs" into a crash of text measurement. A failure is therefore logged
     * and swallowed. */

    static int onSinkQueryInterface(MemorySegment self, MemorySegment riid, MemorySegment out) {
        return comQueryInterface(self, riid, out, self, analysisSinkSession(self));
    }

    /**
     * The source's {@code QueryInterface} answers with the <b>block</b> pointer, not with
     * {@code self}, for all three IIDs. That is not a slip: {@code JFXTextAnalysisSink::
     * QueryInterface} assigns {@code *ppvObject = this} ({@code directwrite.cpp:1186-1192}), and
     * converting the class pointer to {@code void*} applies no base adjustment, so the C hands back
     * the sink vtable whichever interface asked and whichever IID was requested. Reproducing it
     * keeps this port observably identical to the C; correcting it - answering {@code block + 8} for
     * the source IID - belongs in a separate, behaviour-changing commit, and
     * {@link #analysisSinkQueriedIids} exists to say first whether DirectWrite ever asks at all.
     */
    private static int onSourceQueryInterface(MemorySegment self, MemorySegment riid,
                                              MemorySegment out) {
        MemorySegment block = MemorySegment.ofAddress(self.address() - ANALYSIS_SOURCE_OFFSET);
        return comQueryInterface(self, riid, out, block, sourceSession(self));
    }

    /**
     * The shared body: {@code S_OK} plus an {@code AddRef} for the sink IID, the source IID or
     * {@code IUnknown}, and {@code E_FAIL} with {@code *ppvObject = NULL} for anything else.
     * {@code E_FAIL} rather than {@code E_NOINTERFACE} because that is what the C returned
     * ({@code directwrite.cpp:1194}).
     * <p>
     * An object whose session has gone - a COM client holding a reference past
     * {@link #disposeAnalysisSink} - answers {@code E_FAIL} for every IID rather than handing back a
     * pointer to freed memory. The C's behaviour there was {@code delete this} followed by a virtual
     * call, which is undefined; refusing is the only answer that cannot make things worse.
     */
    private static int comQueryInterface(MemorySegment self, MemorySegment riid, MemorySegment out,
                                         MemorySegment answer, AnalysisSinkSession session) {
        MemorySegment target = MemorySegment.NULL;
        try {
            target = bounded(out, ADDRESS.byteSize());
            byte[] id = bounded(riid, GUID_LAYOUT.byteSize()).toArray(JAVA_BYTE);
            boolean known = session != null
                    && (Arrays.equals(id, IID_ANALYSIS_SINK_BYTES)
                    || Arrays.equals(id, IID_ANALYSIS_SOURCE_BYTES)
                    || Arrays.equals(id, IID_IUNKNOWN_BYTES));
            if (session != null) {
                session.queried(id, known);
            }
            if (known) {
                target.set(ADDRESS, 0, answer);
                session.addRef();
                return S_OK;
            }
            target.set(ADDRESS, 0, MemorySegment.NULL);
            return E_FAIL;
        } catch (Throwable t) {
            logCallbackFailure("IDWriteTextAnalysisSink::QueryInterface", t);
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

    static int onSinkAddRef(MemorySegment self) {
        AnalysisSinkSession session = analysisSinkSession(self);
        return session == null ? 1 : session.addRef();
    }

    static int onSinkRelease(MemorySegment self) {
        AnalysisSinkSession session = analysisSinkSession(self);
        return session == null ? 0 : session.release();
    }

    private static int onSourceAddRef(MemorySegment self) {
        AnalysisSinkSession session = sourceSession(self);
        return session == null ? 1 : session.addRef();
    }

    private static int onSourceRelease(MemorySegment self) {
        AnalysisSinkSession session = sourceSession(self);
        return session == null ? 0 : session.release();
    }

    /**
     * {@code SetScriptAnalysis} ({@code directwrite.cpp:1060-1070}), sink slot 3: appends one run
     * and copies the 8-byte {@code DWRITE_SCRIPT_ANALYSIS} by value, as {@code run.analysis =
     * *scriptAnalysis} did. This is the only sink method that collects anything, and the run list it
     * builds is what {@code DWGlyphLayout.addTextRun} walks.
     */
    static int onSetScriptAnalysis(MemorySegment self, int textPosition, int textLength,
                                   MemorySegment scriptAnalysis) {
        AnalysisSinkSession session = analysisSinkSession(self);
        if (session == null) {
            return S_OK;
        }
        try {
            MemorySegment analysis = bounded(scriptAnalysis, DWRITE_SCRIPT_ANALYSIS_LAYOUT.byteSize());
            int script = analysis.get(JAVA_SHORT, 0) & 0xFFFF;
            int shapes = analysis.get(JAVA_INT, 4);
            session.addRun(textPosition, textLength, script, shapes);
        } catch (Throwable t) {
            logCallbackFailure("IDWriteTextAnalysisSink::SetScriptAnalysis", t);
        }
        return S_OK;
    }

    /** {@code SetLineBreakpoints} ({@code :1072-1077}), sink slot 4: collects nothing, always S_OK. */
    private static int onSetLineBreakpoints(MemorySegment self, int textPosition, int textLength,
                                            MemorySegment lineBreakpoints) {
        AnalysisSinkSession session = analysisSinkSession(self);
        if (session != null) {
            session.record("SetLineBreakpoints " + textPosition + " " + textLength);
        }
        return S_OK;
    }

    /**
     * {@code SetBidiLevel} ({@code :1079-1085}), sink slot 5: collects nothing, always S_OK. The two
     * levels are {@code UINT8}; they are recorded unsigned so a test can see them.
     */
    private static int onSetBidiLevel(MemorySegment self, int textPosition, int textLength,
                                      byte explicitLevel, byte resolvedLevel) {
        AnalysisSinkSession session = analysisSinkSession(self);
        if (session != null) {
            session.record("SetBidiLevel " + textPosition + " " + textLength + " "
                    + (explicitLevel & 0xFF) + " " + (resolvedLevel & 0xFF));
        }
        return S_OK;
    }

    /** {@code SetNumberSubstitution} ({@code :1087-1092}), sink slot 6: collects nothing. */
    private static int onSetNumberSubstitution(MemorySegment self, int textPosition, int textLength,
                                               MemorySegment numberSubstitution) {
        AnalysisSinkSession session = analysisSinkSession(self);
        if (session != null) {
            session.record("SetNumberSubstitution " + textPosition + " " + textLength + " "
                    + (numberSubstitution.address() != 0 ? 1 : 0));
        }
        return S_OK;
    }

    /**
     * {@code GetTextAtPosition} ({@code :1095-1107}), source slot 3: the text from
     * {@code textPosition} to the end, or {@code NULL}/0 at or past the end. The comparison is
     * unsigned, as it was between two {@code UINT32}s.
     */
    private static int onGetTextAtPosition(MemorySegment self, int textPosition,
                                           MemorySegment textString, MemorySegment textLength) {
        AnalysisSinkSession session = sourceSession(self);
        try {
            MemorySegment outText = bounded(textString, ADDRESS.byteSize());
            MemorySegment outLength = bounded(textLength, JAVA_INT.byteSize());
            if (session == null || Integer.compareUnsigned(textPosition, session.textLength) >= 0) {
                outText.set(ADDRESS, 0, MemorySegment.NULL);
                outLength.set(JAVA_INT, 0, 0);
            } else {
                outText.set(ADDRESS, 0,
                        session.text.asSlice((long) textPosition * Character.BYTES));
                outLength.set(JAVA_INT, 0, session.textLength - textPosition);
                session.record("GetTextAtPosition " + textPosition);
            }
        } catch (Throwable t) {
            logCallbackFailure("IDWriteTextAnalysisSource::GetTextAtPosition", t);
        }
        return S_OK;
    }

    /**
     * {@code GetTextBeforePosition} ({@code :1109-1121}), source slot 4: the whole prefix, or
     * {@code NULL}/0 when the position is 0 or past the end.
     */
    private static int onGetTextBeforePosition(MemorySegment self, int textPosition,
                                               MemorySegment textString, MemorySegment textLength) {
        AnalysisSinkSession session = sourceSession(self);
        try {
            MemorySegment outText = bounded(textString, ADDRESS.byteSize());
            MemorySegment outLength = bounded(textLength, JAVA_INT.byteSize());
            if (session == null || textPosition == 0
                    || Integer.compareUnsigned(textPosition, session.textLength) > 0) {
                outText.set(ADDRESS, 0, MemorySegment.NULL);
                outLength.set(JAVA_INT, 0, 0);
            } else {
                outText.set(ADDRESS, 0, session.text);
                outLength.set(JAVA_INT, 0, textPosition);
                session.record("GetTextBeforePosition " + textPosition);
            }
        } catch (Throwable t) {
            logCallbackFailure("IDWriteTextAnalysisSource::GetTextBeforePosition", t);
        }
        return S_OK;
    }

    /**
     * {@code GetParagraphReadingDirection} ({@code :1123-1125}), source slot 5: the direction the
     * constructor was given, verbatim. Not an HRESULT - see {@link #SOURCE_READING_DIRECTION_FD}.
     * A registry miss answers {@code DWRITE_READING_DIRECTION_LEFT_TO_RIGHT} (0), the value a
     * freshly zeroed object would have carried.
     */
    static int onGetParagraphReadingDirection(MemorySegment self) {
        AnalysisSinkSession session = sourceSession(self);
        if (session == null) {
            return 0;
        }
        session.record("GetParagraphReadingDirection");
        return session.readingDirection;
    }

    /**
     * {@code GetLocaleName} ({@code :1127-1135}), source slot 6: the locale copy and
     * {@code textLength - textPosition}, with no bounds check - the C had none, and the
     * {@code fflush(stdout)} at its head is dropped because nothing Java-visible went through the C
     * stdio buffer. <b>The length is written first</b> (dwrite.h:2624).
     */
    private static int onGetLocaleName(MemorySegment self, int textPosition, MemorySegment textLength,
                                       MemorySegment localeName) {
        AnalysisSinkSession session = sourceSession(self);
        try {
            MemorySegment outLength = bounded(textLength, JAVA_INT.byteSize());
            MemorySegment outName = bounded(localeName, ADDRESS.byteSize());
            if (session == null) {
                outName.set(ADDRESS, 0, MemorySegment.NULL);
                outLength.set(JAVA_INT, 0, 0);
            } else {
                outName.set(ADDRESS, 0, session.locale);
                outLength.set(JAVA_INT, 0, session.textLength - textPosition);
                session.record("GetLocaleName " + textPosition);
            }
        } catch (Throwable t) {
            logCallbackFailure("IDWriteTextAnalysisSource::GetLocaleName", t);
        }
        return S_OK;
    }

    /**
     * {@code GetNumberSubstitution} ({@code :1137-1149}), source slot 7: the object the constructor
     * was given, {@code AddRef}ed when it is not {@code NULL} - which it always is, because
     * {@link JFXTextAnalysisSink#create} passes 0, as the C helper it replaced did. Length first
     * here too (dwrite.h:2644).
     */
    private static int onGetNumberSubstitution(MemorySegment self, int textPosition,
                                               MemorySegment textLength,
                                               MemorySegment numberSubstitution) {
        AnalysisSinkSession session = sourceSession(self);
        try {
            MemorySegment outLength = bounded(textLength, JAVA_INT.byteSize());
            MemorySegment outSubstitution = bounded(numberSubstitution, ADDRESS.byteSize());
            if (session == null) {
                outSubstitution.set(ADDRESS, 0, MemorySegment.NULL);
                outLength.set(JAVA_INT, 0, 0);
            } else {
                if (session.numberSubstitution != 0) {
                    addRef(session.numberSubstitution);
                }
                outSubstitution.set(ADDRESS, 0, com(session.numberSubstitution));
                outLength.set(JAVA_INT, 0, session.textLength - textPosition);
                session.record("GetNumberSubstitution " + textPosition);
            }
        } catch (Throwable t) {
            logCallbackFailure("IDWriteTextAnalysisSource::GetNumberSubstitution", t);
        }
        return S_OK;
    }

    /**
     * One {@code AnalyzeScript} object: the arena that owns its three segments, the run list the
     * sink half fills, the reference count DirectWrite shares and the recording the tests read.
     * Nothing here is synchronised, exactly as the {@code std::vector}s were not
     * ({@code directwrite.cpp:1064}): DirectWrite drives the object synchronously on the thread
     * inside {@code AnalyzeScript}, which is the thread that created it.
     */
    static final class AnalysisSinkSession {

        private static final int MAX_RECORDED = 4096;

        private final Arena arena;
        final MemorySegment block;
        final MemorySegment text;
        final MemorySegment locale;
        final int textLength;
        private final int readingDirection;
        private final long numberSubstitution;
        private final boolean recording;
        private final List<int[]> runs = new ArrayList<>();
        private final List<String> callbacks = new ArrayList<>();
        private final List<String> queriedIids = new ArrayList<>();
        private int position = -1;
        int refCount;

        AnalysisSinkSession(Arena arena, MemorySegment block, MemorySegment text, MemorySegment locale,
                            int textLength, int readingDirection, long numberSubstitution,
                            boolean recording) {
            this.arena = arena;
            this.block = block;
            this.text = text;
            this.locale = locale;
            this.textLength = textLength;
            this.readingDirection = readingDirection;
            this.numberSubstitution = numberSubstitution;
            this.recording = recording;
        }

        void addRun(int start, int length, int script, int shapes) {
            runs.add(new int[] { start, length, script, shapes });
            record("SetScriptAnalysis " + start + " " + length + " " + script + " " + shapes);
        }

        boolean next() {
            position++;
            return position < runs.size();
        }

        int field(int which) {
            if (position < 0 || position >= runs.size()) {
                return 0;
            }
            return runs.get(position)[which];
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

        void close() {
            if (numberSubstitution != 0) {
                DWNative.release(numberSubstitution);
            }
            arena.close();
        }
    }

    /**
     * The two analysis vtables, built once in {@link Arena#global()} - legitimate because the
     * targets are static methods and there is exactly one table per process, not one per object.
     * Held by a nested class so that nothing is created until the first sink is asked for, and
     * {@link #build} cannot throw, so this initializer cannot either: a table that could not be
     * built is {@link MemorySegment#NULL} and {@link #newAnalysisSink} then answers {@code 0}, which
     * is what "DirectWrite unavailable" has always looked like to {@code DWGlyphLayout}.
     */
    private static final class AnalysisTables {

        static final MemorySegment SINK_VTABLE = build(true);
        static final MemorySegment SOURCE_VTABLE = build(false);

        private static MemorySegment build(boolean sink) {
            try {
                MethodHandles.Lookup lookup = MethodHandles.lookup();
                int slots = sink ? ANALYSIS_SINK_SLOT_COUNT : ANALYSIS_SOURCE_SLOT_COUNT;
                MemorySegment table = Arena.global()
                        .allocate(ADDRESS.byteSize() * slots, ADDRESS.byteSize());
                table.setAtIndex(ADDRESS, IUNKNOWN_QUERY_INTERFACE, upcall(lookup,
                        sink ? "onSinkQueryInterface" : "onSourceQueryInterface",
                        COM_QUERY_INTERFACE_FD, int.class, MemorySegment.class, MemorySegment.class,
                        MemorySegment.class));
                table.setAtIndex(ADDRESS, IUNKNOWN_ADD_REF, upcall(lookup,
                        sink ? "onSinkAddRef" : "onSourceAddRef", COM_REF_COUNT_FD, int.class,
                        MemorySegment.class));
                table.setAtIndex(ADDRESS, IUNKNOWN_RELEASE, upcall(lookup,
                        sink ? "onSinkRelease" : "onSourceRelease", COM_REF_COUNT_FD, int.class,
                        MemorySegment.class));
                if (sink) {
                    table.setAtIndex(ADDRESS, ANALYSIS_SINK_SET_SCRIPT_ANALYSIS,
                            upcall(lookup, "onSetScriptAnalysis", SINK_RANGE_FD, int.class,
                                    MemorySegment.class, int.class, int.class, MemorySegment.class));
                    table.setAtIndex(ADDRESS, ANALYSIS_SINK_SET_LINE_BREAKPOINTS,
                            upcall(lookup, "onSetLineBreakpoints", SINK_RANGE_FD, int.class,
                                    MemorySegment.class, int.class, int.class, MemorySegment.class));
                    table.setAtIndex(ADDRESS, ANALYSIS_SINK_SET_BIDI_LEVEL,
                            upcall(lookup, "onSetBidiLevel", SINK_SET_BIDI_LEVEL_FD, int.class,
                                    MemorySegment.class, int.class, int.class, byte.class, byte.class));
                    table.setAtIndex(ADDRESS, ANALYSIS_SINK_SET_NUMBER_SUBSTITUTION,
                            upcall(lookup, "onSetNumberSubstitution", SINK_RANGE_FD, int.class,
                                    MemorySegment.class, int.class, int.class, MemorySegment.class));
                } else {
                    table.setAtIndex(ADDRESS, ANALYSIS_SOURCE_GET_TEXT_AT_POSITION,
                            upcall(lookup, "onGetTextAtPosition", SOURCE_QUERY_FD, int.class,
                                    MemorySegment.class, int.class, MemorySegment.class,
                                    MemorySegment.class));
                    table.setAtIndex(ADDRESS, ANALYSIS_SOURCE_GET_TEXT_BEFORE_POSITION,
                            upcall(lookup, "onGetTextBeforePosition", SOURCE_QUERY_FD, int.class,
                                    MemorySegment.class, int.class, MemorySegment.class,
                                    MemorySegment.class));
                    table.setAtIndex(ADDRESS, ANALYSIS_SOURCE_GET_READING_DIRECTION,
                            upcall(lookup, "onGetParagraphReadingDirection",
                                    SOURCE_READING_DIRECTION_FD, int.class, MemorySegment.class));
                    table.setAtIndex(ADDRESS, ANALYSIS_SOURCE_GET_LOCALE_NAME,
                            upcall(lookup, "onGetLocaleName", SOURCE_QUERY_FD, int.class,
                                    MemorySegment.class, int.class, MemorySegment.class,
                                    MemorySegment.class));
                    table.setAtIndex(ADDRESS, ANALYSIS_SOURCE_GET_NUMBER_SUBSTITUTION,
                            upcall(lookup, "onGetNumberSubstitution", SOURCE_QUERY_FD, int.class,
                                    MemorySegment.class, int.class, MemorySegment.class,
                                    MemorySegment.class));
                }
                return table;
            } catch (Throwable t) {
                return MemorySegment.NULL;
            }
        }
    }
}
