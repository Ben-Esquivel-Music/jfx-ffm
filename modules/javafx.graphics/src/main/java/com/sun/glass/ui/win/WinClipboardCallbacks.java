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
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.util.List;
import static com.sun.glass.ui.win.WinCallSupport.GWIN_ERR_UPCALL;
import static com.sun.glass.ui.win.WinCallSupport.GWIN_OK;
import static com.sun.glass.ui.win.WinCallSupport.describeUpcallFailure;
import static com.sun.glass.ui.win.WinCallSupport.fillCallbackTable;
import static com.sun.glass.ui.win.WinCallSupport.reportUpcallFailure;
import static com.sun.glass.ui.win.WinCallSupport.requireOk;
import static com.sun.glass.ui.win.WinCallSupport.stubsFor;
import static com.sun.glass.ui.win.WinClipboardCalls.gwinAlloc;
import static com.sun.glass.ui.win.WinClipboardCalls.readStringBlock;
import static com.sun.glass.ui.win.WinGlassNative.GWIN_CLIPBOARD_SET_CALLBACKS;
import static com.sun.glass.ui.win.WinGlassNative.GWIN_DND_SET_CALLBACKS;
import static com.sun.glass.ui.win.WinGlassNative.bounded;
import static com.sun.glass.ui.win.WinGlassNative.unexpected;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The two {@link WinGlassNative} callback tables of the clipboard and drag-and-drop peers:
 * {@code GwinClipboardCallbacks} and {@code GwinDndCallbacks}, their descriptors, their install and their sixteen
 * upcall targets.
 * <p>
 * The sixteen slots of {@code GwinClipboardCallbacks} (7) and {@code GwinDndCallbacks} (9) - the delayed render
 * of a paste, the drop-effect reports, the viewer-chain notification, the four drop-target notifications and the
 * drag clipboard's singleton state - are keyed by an {@code int64_t} clipboard id {@code WinSystemClipboard}
 * assigns and by the view id of {@code WinView} for the drop target, so that {@code glass.dll} holds no Java
 * reference on this path.
 * <p>
 * Both tables are installed from {@link WinGlassNative}'s static initializer, for the reason
 * {@link #installDndCallbacks} gives. That is also why this class's initializer reaches no other class, and why
 * its {@code static synchronized} methods are called only from {@link WinGlassNative} (its initializer and its
 * delegations): a caller always passes {@link WinGlassNative}'s initialization before it can hold this class's
 * monitor, so the monitor can never be taken in the opposite order to that initialization.
 */
final class WinClipboardCallbacks {

    /**
     * {@code GwinClipboardCallbacks} ({@code glass_win_api.h}): the three method ids of
     * {@code WinSystemClipboard.initIDs} ({@code fosSerialize}, {@code actionPerformed} at its two
     * sites with their two sinks, {@code contentChanged}), the re-entered dispose export of
     * {@code GlassApplication::RegisterClipboardViewer}, the {@code setPtr} field write and
     * {@code ~ClipboardData}, in declaration order. {@code gwin_sizeof_clipboard_callbacks} pins the size.
     */
    static final StructLayout GWIN_CLIPBOARD_CALLBACKS_LAYOUT = MemoryLayout.structLayout(
            ADDRESS.withName("fos_serialize"),
            ADDRESS.withName("action_performed"),
            ADDRESS.withName("drag_action_performed"),
            ADDRESS.withName("content_changed"),
            ADDRESS.withName("dispose_peer"),
            ADDRESS.withName("set_data_object"),
            ADDRESS.withName("data_object_disposed"));

    /**
     * {@code GwinDndCallbacks} ({@code glass_win_api.h}): the four {@code View.notifyDrag*} ids
     * {@code GlassDnD.cpp} dialled through {@code javaIDs.View}, keyed by {@code WinView}'s view id, and the five
     * {@code WinDnDClipboard} members it reached through {@code ClassForName} + {@code getInstance}, which carry no
     * id at all - their peer is the singleton {@code getInstance()} creates on demand. In declaration order;
     * {@code gwin_sizeof_dnd_callbacks} pins the size.
     */
    static final StructLayout GWIN_DND_CALLBACKS_LAYOUT = MemoryLayout.structLayout(
            ADDRESS.withName("drag_enter"),
            ADDRESS.withName("drag_over"),
            ADDRESS.withName("drag_drop"),
            ADDRESS.withName("drag_leave"),
            ADDRESS.withName("dnd_get_data_object"),
            ADDRESS.withName("dnd_set_data_object"),
            ADDRESS.withName("dnd_set_source_supported_actions"),
            ADDRESS.withName("dnd_set_drag_button"),
            ADDRESS.withName("dnd_get_drag_button"));

    /*
     * The sixteen upcall descriptors, one per slot typedef of GwinClipboardCallbacks and
     * GwinDndCallbacks in glass_win_api.h, in declaration order. The shim builds its recording stubs from
     * these same objects, so what a through-C test proves is the facade's descriptor and not a copy.
     */
    static final FunctionDescriptor CLIPBOARD_FOS_SERIALIZE_FD =
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS);
    static final FunctionDescriptor CLIPBOARD_ACTION_PERFORMED_FD =
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT);
    static final FunctionDescriptor CLIPBOARD_DRAG_ACTION_PERFORMED_FD =
            FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_INT);
    static final FunctionDescriptor CLIPBOARD_CONTENT_CHANGED_FD = FunctionDescriptor.ofVoid(JAVA_LONG);
    static final FunctionDescriptor CLIPBOARD_DISPOSE_PEER_FD = FunctionDescriptor.ofVoid(JAVA_LONG);
    static final FunctionDescriptor CLIPBOARD_SET_DATA_OBJECT_FD = FunctionDescriptor.ofVoid(JAVA_LONG, ADDRESS);
    static final FunctionDescriptor CLIPBOARD_DATA_OBJECT_DISPOSED_FD = FunctionDescriptor.ofVoid(JAVA_LONG);

    /**
     * {@code drag_enter}, {@code drag_over} and {@code drag_drop} are declared with the identical prototype,
     * so the three constants are one descriptor.
     */
    static final FunctionDescriptor DND_DRAG_ENTER_FD = FunctionDescriptor.of(JAVA_INT, JAVA_LONG,
            JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS);
    static final FunctionDescriptor DND_DRAG_OVER_FD = DND_DRAG_ENTER_FD;
    static final FunctionDescriptor DND_DRAG_DROP_FD = DND_DRAG_ENTER_FD;
    static final FunctionDescriptor DND_DRAG_LEAVE_FD = FunctionDescriptor.of(JAVA_INT, JAVA_LONG);
    static final FunctionDescriptor DND_GET_DATA_OBJECT_FD = FunctionDescriptor.of(JAVA_INT, ADDRESS);
    static final FunctionDescriptor DND_SET_DATA_OBJECT_FD = FunctionDescriptor.of(JAVA_INT, ADDRESS);
    static final FunctionDescriptor DND_SET_SOURCE_SUPPORTED_ACTIONS_FD = FunctionDescriptor.of(JAVA_INT, JAVA_INT);
    static final FunctionDescriptor DND_SET_DRAG_BUTTON_FD = FunctionDescriptor.of(JAVA_INT, JAVA_INT);
    static final FunctionDescriptor DND_GET_DRAG_BUTTON_FD = FunctionDescriptor.of(JAVA_INT, ADDRESS);

    /**
     * The slot names of {@link #GWIN_CLIPBOARD_CALLBACKS_LAYOUT} in declaration order, the order the installer writes
     * them.
     */
    static final List<String> CLIPBOARD_SLOT_NAMES = List.of("fos_serialize", "action_performed",
            "drag_action_performed", "content_changed", "dispose_peer", "set_data_object", "data_object_disposed");

    /** The slot names of {@link #GWIN_DND_CALLBACKS_LAYOUT} in declaration order. */
    static final List<String> DND_SLOT_NAMES = List.of("drag_enter", "drag_over", "drag_drop", "drag_leave",
            "dnd_get_data_object", "dnd_set_data_object", "dnd_set_source_supported_actions",
            "dnd_set_drag_button", "dnd_get_drag_button");

    /** The target methods of this class, parallel to {@link #CLIPBOARD_SLOT_NAMES}. */
    private static final String[] CLIPBOARD_SLOT_TARGETS = {"onFosSerialize", "onActionPerformed",
            "onDragActionPerformed", "onContentChanged", "onDisposePeer", "onSetDataObject", "onDataObjectDisposed"};
    private static final FunctionDescriptor[] CLIPBOARD_SLOT_DESCRIPTORS = {CLIPBOARD_FOS_SERIALIZE_FD,
            CLIPBOARD_ACTION_PERFORMED_FD, CLIPBOARD_DRAG_ACTION_PERFORMED_FD, CLIPBOARD_CONTENT_CHANGED_FD,
            CLIPBOARD_DISPOSE_PEER_FD, CLIPBOARD_SET_DATA_OBJECT_FD, CLIPBOARD_DATA_OBJECT_DISPOSED_FD};

    /** The target methods of this class, parallel to {@link #DND_SLOT_NAMES}. */
    private static final String[] DND_SLOT_TARGETS = {"onDragEnter", "onDragOver", "onDragDrop", "onDragLeave",
            "onDndGetDataObject", "onDndSetDataObject", "onDndSetSourceSupportedActions", "onDndSetDragButton",
            "onDndGetDragButton"};
    private static final FunctionDescriptor[] DND_SLOT_DESCRIPTORS = {DND_DRAG_ENTER_FD, DND_DRAG_OVER_FD,
            DND_DRAG_DROP_FD, DND_DRAG_LEAVE_FD, DND_GET_DATA_OBJECT_FD, DND_SET_DATA_OBJECT_FD,
            DND_SET_SOURCE_SUPPORTED_ACTIONS_FD, DND_SET_DRAG_BUTTON_FD, DND_GET_DRAG_BUTTON_FD};

    private static boolean clipboardCallbacksInstalled;
    private static boolean dndCallbacksInstalled;

    /**
     * The seven and the nine stubs, created once in {@link Arena#global()} and kept so that the
     * reinstall methods can write the same addresses again. Never replaced: OLE may deliver a paste
     * for as long as the process owns a clipboard data object, and a drag from another application
     * can arrive at any window.
     */
    private static MemorySegment[] clipboardCallbackStubs;
    private static MemorySegment[] dndCallbackStubs;

    private WinClipboardCallbacks() {
    }

    /**
     * Hands {@code glass.dll} the {@code GwinClipboardCallbacks} table. Called from <b>{@link WinGlassNative}'s
     * own static initializer</b>, at its end, and nowhere else - not from {@code WinApplication}'s as the view and
     * window tables are, and not from a peer's. The reason is the drag table it travels with (see
     * {@link #installDndCallbacks}); the two are installed together so that a {@code gwin_dnd_push}, which needs
     * both, can never find one missing. The install is the flip: without {@code set_data_object} a pushed object
     * is unreachable and leaks, so it travels in the same change set as {@code WinSystemClipboard}'s forwarders.
     * <p>
     * A slot left {@code NULL} would be replaced by an internal no-op in C - and for
     * {@code fos_serialize} that no-op serves nothing to anyone who pastes - so all seven are
     * written. The table is copied by the library; the confined arena that holds it is closed here,
     * the stubs live in {@link Arena#global()}.
     */
    static synchronized void installClipboardCallbacks() {
        if (clipboardCallbacksInstalled) {
            return;
        }
        clipboardCallbackStubs = stubsFor(WinClipboardCallbacks.class, CLIPBOARD_SLOT_TARGETS,
                CLIPBOARD_SLOT_DESCRIPTORS);
        writeClipboardTable();
        clipboardCallbacksInstalled = true;
    }

    /**
     * Hands {@code glass.dll} the table that replaces the eight {@code Call*Method} sites of
     * {@code GlassDnD.cpp} ({@code GwinDndCallbacks}). Called from <b>{@link WinGlassNative}'s own
     * static initializer</b>. The JNI's {@code ClassForName(name, initialize = true)} on the first drag
     * forced {@code WinDnDClipboard}'s initializer to run; a table installed from that initializer
     * would therefore never be installed for a drop from Explorer into a process that has not
     * started a drag of its own - {@code IDropTarget::DragEnter} would find no table and every drop
     * would silently do nothing. Installing from the facade's initializer, which runs before any
     * Glass peer can exist, closes that window, and installing from {@code WinApplication}'s would
     * merely move the dependency one class over.
     * <p>
     * No slot is left {@code NULL}: the C's no-op for the five singleton slots would leave the drag
     * clipboard without its data object. The table is copied by the library; the stubs live in
     * {@link Arena#global()}.
     */
    static synchronized void installDndCallbacks() {
        if (dndCallbacksInstalled) {
            return;
        }
        dndCallbackStubs = stubsFor(WinClipboardCallbacks.class, DND_SLOT_TARGETS, DND_SLOT_DESCRIPTORS);
        writeDndTable();
        dndCallbacksInstalled = true;
    }

    static synchronized boolean clipboardCallbacksInstalled() {
        return clipboardCallbacksInstalled;
    }

    static synchronized boolean dndCallbacksInstalled() {
        return dndCallbacksInstalled;
    }

    /**
     * Writes the production clipboard table again with the stubs {@link #installClipboardCallbacks}
     * created; {@code gwin_clipboard_set_callbacks} copies by value and the last call wins, so this
     * restores after a test has installed a table of its own. Never creates a second set of stubs.
     */
    static synchronized void reinstallClipboardCallbacks() {
        if (!clipboardCallbacksInstalled) {
            installClipboardCallbacks();
            return;
        }
        writeClipboardTable();
    }

    /** The drag-table twin of {@link #reinstallClipboardCallbacks}. */
    static synchronized void reinstallDndCallbacks() {
        if (!dndCallbacksInstalled) {
            installDndCallbacks();
            return;
        }
        writeDndTable();
    }

    /** The seven installed stubs in slot order, for the test that reads them back; empty before the install. */
    static synchronized List<MemorySegment> installedClipboardCallbackStubs() {
        return clipboardCallbacksInstalled ? List.of(clipboardCallbackStubs) : List.of();
    }

    /** The nine installed stubs in slot order; empty before the install. */
    static synchronized List<MemorySegment> installedDndCallbackStubs() {
        return dndCallbacksInstalled ? List.of(dndCallbackStubs) : List.of();
    }

    private static void writeClipboardTable() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment table = arena.allocate(GWIN_CLIPBOARD_CALLBACKS_LAYOUT);
            fillCallbackTable(table, GWIN_CLIPBOARD_CALLBACKS_LAYOUT, CLIPBOARD_SLOT_NAMES, clipboardCallbackStubs);
            requireOk((int) GWIN_CLIPBOARD_SET_CALLBACKS.invokeExact(table), "gwin_clipboard_set_callbacks");
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static void writeDndTable() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment table = arena.allocate(GWIN_DND_CALLBACKS_LAYOUT);
            fillCallbackTable(table, GWIN_DND_CALLBACKS_LAYOUT, DND_SLOT_NAMES, dndCallbackStubs);
            requireOk((int) GWIN_DND_SET_CALLBACKS.invokeExact(table), "gwin_dnd_set_callbacks");
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /*
     * The sixteen targets. As for the view and window sections the marshalling is here and the
     * registry lookup and the peer call are the peer's (WinSystemClipboard for the seven clipboard
     * slots, WinDnDClipboard for the five singleton slots, WinView for the four drag slots - the
     * View.notifyDrag* they reach are protected members of another package). Each catches Throwable,
     * and here that is not merely about the JVM: an escaping Throwable would unwind through a COM
     * vtable frame (ClipboardData::GetData, GlassDropTarget::Drop) into the OLE marshaller and, for a
     * paste or a drop from another application, into that application. The sink is the one the JNI
     * used at each site: describeUpcallFailure (checkJavaException: ExceptionDescribe + clear, no
     * Java code runs) for the slots that answer a status the C maps to E_JAVAEXCEPTION and for
     * data_object_disposed (which fires inside ~ClipboardData, possibly on a foreign OLE frame);
     * reportUpcallFailure (CheckAndClearException: the application's UncaughtExceptionHandler) where
     * the JNI did that - drag_action_performed, content_changed, dispose_peer, set_data_object. Thread:
     * always the JavaFX application thread, nested in a downcall or with no Java frame below; nothing
     * here takes a lock, and no target may call gwin_dnd_push or a gwin_dialog_* function. An id the
     * registry does not know is a stale peer: the slot default, silently. Package-private so that
     * WinGlassNativeShim can drive every arm without a table.
     */

    /**
     * {@code fos_serialize}: the delayed render of one format. The bytes Java produces are copied into
     * a {@link WinClipboardCalls#gwinAlloc} block the C owns from the moment this returns and frees on every path; a
     * null {@code byte[]} is {@code NULL} with length 0 and {@link WinCallSupport#GWIN_OK} (the C answers
     * {@code E_POINTER}); an empty one is a real one-byte block with length 0. On a throw nothing
     * is written and nothing is owned. Re-entrant: an IE shortcut renders {@code text/uri-list} and
     * {@code message/external-body} from inside its own {@code GetData}.
     */
    static int onFosSerialize(long clipboardId, MemorySegment mime, long lindex, MemorySegment outData,
                              MemorySegment outLen) {
        try {
            byte[] bytes = WinSystemClipboard.dispatchFosSerialize(clipboardId, cString(mime), lindex);
            MemorySegment block = MemorySegment.NULL;
            int length = 0;
            if (bytes != null) {
                block = gwinAlloc(bytes.length);
                if (block.equals(MemorySegment.NULL)) {
                    throw new OutOfMemoryError("gwin_alloc(" + bytes.length + ") returned NULL");
                }
                MemorySegment.copy(bytes, 0, bounded(block, bytes.length), JAVA_BYTE, 0, bytes.length);
                length = bytes.length;
            }
            bounded(outData, ADDRESS.byteSize()).set(ADDRESS, 0, block);
            bounded(outLen, JAVA_INT.byteSize()).set(JAVA_INT, 0, length);
            return GWIN_OK;
        } catch (Throwable t) {
            describeUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    /**
     * {@code action_performed}: {@code Clipboard.actionPerformed(int)} from {@code IDataObject::SetData},
     * cross-process.
     */
    static int onActionPerformed(long clipboardId, int action) {
        try {
            WinSystemClipboard.dispatchActionPerformed(clipboardId, action);
            return GWIN_OK;
        } catch (Throwable t) {
            describeUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    /** {@code drag_action_performed}: the same method from the tail of {@code gwin_dnd_push}; result ignored. */
    static void onDragActionPerformed(long clipboardId, int action) {
        try {
            WinSystemClipboard.dispatchActionPerformed(clipboardId, action);
        } catch (Throwable t) {
            reportUpcallFailure(t);
        }
    }

    /**
     * {@code content_changed}: {@code Clipboard.contentChanged()} from {@code WM_DRAWCLIPBOARD}; may be nested in a
     * push.
     */
    static void onContentChanged(long clipboardId) {
        try {
            WinSystemClipboard.dispatchContentChanged(clipboardId);
        } catch (Throwable t) {
            reportUpcallFailure(t);
        }
    }

    /** {@code dispose_peer}: the previously registered peer's dispose, from {@code RegisterClipboardViewer}. */
    static void onDisposePeer(long clipboardId) {
        try {
            WinSystemClipboard.dispatchDisposePeer(clipboardId);
        } catch (Throwable t) {
            reportUpcallFailure(t);
        }
    }

    /** {@code set_data_object}: the JNI's {@code setPtr}, before anything in the push can fail. */
    static void onSetDataObject(long clipboardId, MemorySegment dataObject) {
        try {
            WinSystemClipboard.dispatchSetDataObject(clipboardId, dataObject);
        } catch (Throwable t) {
            reportUpcallFailure(t);
        }
    }

    /** {@code data_object_disposed}: {@code ~ClipboardData}; touches the registry and nothing else. */
    static void onDataObjectDisposed(long clipboardId) {
        try {
            WinSystemClipboard.dispatchDataObjectDisposed(clipboardId);
        } catch (Throwable t) {
            describeUpcallFailure(t);
        }
    }

    /**
     * {@code drag_enter}: {@code View.notifyDragEnter}. The view's answer is written to
     * {@code *out_action}; for an unknown id nothing is written and {@link WinCallSupport#GWIN_OK} is answered, so
     * the C's own default ({@code DROPEFFECT_NONE}) stands; on a throw nothing is written either,
     * and the C writes {@code *pdwEffect} from its 0 before testing the status, as the JNI did.
     */
    static int onDragEnter(long viewId, int x, int y, int xAbs, int yAbs, int recommendedAction,
                           MemorySegment outAction) {
        try {
            writeAction(outAction, WinView.dispatchDragEnter(viewId, x, y, xAbs, yAbs, recommendedAction));
            return GWIN_OK;
        } catch (Throwable t) {
            describeUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    /** {@code drag_over}: {@code View.notifyDragOver}; per mouse move during a drag. */
    static int onDragOver(long viewId, int x, int y, int xAbs, int yAbs, int recommendedAction,
                          MemorySegment outAction) {
        try {
            writeAction(outAction, WinView.dispatchDragOver(viewId, x, y, xAbs, yAbs, recommendedAction));
            return GWIN_OK;
        } catch (Throwable t) {
            describeUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    /** {@code drag_drop}: {@code View.notifyDragDrop}. */
    static int onDragDrop(long viewId, int x, int y, int xAbs, int yAbs, int recommendedAction,
                          MemorySegment outAction) {
        try {
            writeAction(outAction, WinView.dispatchDragDrop(viewId, x, y, xAbs, yAbs, recommendedAction));
            return GWIN_OK;
        } catch (Throwable t) {
            describeUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    /** {@code drag_leave}: {@code View.notifyDragLeave()}, void; the slot answers a status only. */
    static int onDragLeave(long viewId) {
        try {
            boolean ignoredHadView = WinView.dispatchDragLeave(viewId);
            return GWIN_OK;
        } catch (Throwable t) {
            describeUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    /**
     * {@code dnd_get_data_object}: the drag clipboard's handle, through {@code getInstance()} (which may create it).
     */
    static int onDndGetDataObject(MemorySegment outDataObject) {
        try {
            MemorySegment dataObject = WinDnDClipboard.dispatchGetDataObject();
            bounded(outDataObject, ADDRESS.byteSize()).set(ADDRESS, 0, dataObject);
            return GWIN_OK;
        } catch (Throwable t) {
            describeUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    /** {@code dnd_set_data_object}: the object being dragged in, AddRef'd by the C. */
    static int onDndSetDataObject(MemorySegment dataObject) {
        try {
            WinDnDClipboard.dispatchSetDataObject(dataObject);
            return GWIN_OK;
        } catch (Throwable t) {
            describeUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    /** {@code dnd_set_source_supported_actions}: before every drag notification; the C discards the status. */
    static int onDndSetSourceSupportedActions(int actions) {
        try {
            WinDnDClipboard.dispatchSetSourceSupportedActions(actions);
            return GWIN_OK;
        } catch (Throwable t) {
            describeUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    /** {@code dnd_set_drag_button}: 0 from the tail of {@code gwin_dnd_push}; the C discards the status. */
    static int onDndSetDragButton(int button) {
        try {
            WinDnDClipboard.dispatchSetDragButton(button);
            return GWIN_OK;
        } catch (Throwable t) {
            describeUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    /** {@code dnd_get_drag_button}: for the {@code GlassDropSource} constructor; on a throw the C uses 0. */
    static int onDndGetDragButton(MemorySegment outButton) {
        try {
            int button = WinDnDClipboard.dispatchGetDragButton();
            bounded(outButton, JAVA_INT.byteSize()).set(JAVA_INT, 0, button);
            return GWIN_OK;
        } catch (Throwable t) {
            describeUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    /** Writes a drag answer through the out-pointer, or nothing for {@code null} (no view). */
    private static void writeAction(MemorySegment outAction, Integer action) {
        if (action != null) {
            bounded(outAction, JAVA_INT.byteSize()).set(JAVA_INT, 0, action);
        }
    }

    /**
     * A NUL-terminated UTF-16 string the C handed an upcall, copied. The pointer arrives with no
     * length, so the one {@code reinterpret} is to the unbounded size the FFM API documents for
     * exactly this case and the walk stops at the first NUL.
     */
    private static String cString(MemorySegment pointer) {
        return readStringBlock(pointer, 1)[0];
    }
}
