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

import com.sun.glass.ui.win.WinGlassNative.FileDialogResult;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.invoke.MethodHandle;
import static com.sun.glass.ui.win.WinCallSupport.wideString;
import static com.sun.glass.ui.win.WinGlassNative.bindGlass;
import static com.sun.glass.ui.win.WinGlassNative.bindOle32;
import static com.sun.glass.ui.win.WinGlassNative.bounded;
import static com.sun.glass.ui.win.WinGlassNative.unexpected;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_CHAR;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The {@link WinGlassNative} downcalls of the clipboard, drag-and-drop and dialog peers:
 * {@code WinSystemClipboard}, {@code WinDnDClipboard} and {@code WinCommonDialogs}.
 * <p>
 * Twelve of the fifteen natives of {@code GlassClipboard.cpp} (12) and {@code CommonDialogs.cpp} (3) reached
 * OLE, COM vtables, GDI, {@code comdlg32} or {@code shell32} and became the {@code gwin_clipboard_*},
 * {@code gwin_dnd_*} and {@code gwin_dialog_*} exports, additive under ABI 4; the other three were
 * {@code isOwner}, a {@code WRAPPER} ({@link #oleIsCurrentClipboard}), and the two {@code initIDs}. The
 * COM objects themselves - {@code ClipboardData : IDataObject}, {@code GlassDropTarget},
 * {@code GlassDropSource} - stay in C++ because Windows and other processes call their vtables
 * through the OLE marshaller, and are addressed by an opaque {@code gwin_clipboard_t}. The push exports
 * return a status and publish the new handle through {@code set_data_object} at the instant the JNI wrote
 * the field.
 */
final class WinClipboardCalls {

    /**
     * {@code enum GwinDialogStatus}: what {@code gwin_dialog_file} / {@code gwin_dialog_folder}
     * return. Informational only - the JNI could not tell the three apart at its Java boundary, so
     * {@link #dialogFile} and {@link #dialogFolder} key on the out-block and ignore this.
     */
    static final int GWIN_DIALOG_OK = 0;
    static final int GWIN_DIALOG_CANCELLED = 1;
    static final int GWIN_DIALOG_FAILED = 2;

    /**
     * {@code GwinFileFilter} ({@code glass_win_api.h}): one
     * {@code CommonDialogs.ExtensionFilter} flattened by Java - the description and the extensions
     * joined with {@code ';'}, both NUL-terminated UTF-16 and never NULL. {@code gwin_sizeof_file_filter}
     * pins the size.
     */
    static final StructLayout GWIN_FILE_FILTER_LAYOUT = MemoryLayout.structLayout(
            ADDRESS.withName("description"),
            ADDRESS.withName("extensions"));

    /*
     * The clipboard / drag-and-drop / common-dialog section of glass_win_api.h, in the
     * header's order. critical(true) is FORBIDDEN on every one of them: nine block in
     * SendMessage(WM_DO_ACTION) and can upcall, gwin_dnd_push and the two dialogs run nested modal
     * loops, gwin_clipboard_dispose pumps messages, the two set_callbacks (bound by WinGlassNative) publish
     * a pointer the OLE marshaller will dial, and gwin_alloc / gwin_free are called from inside an upcall.
     */

    private static final MethodHandle GWIN_SIZEOF_CLIPBOARD_CALLBACKS =
            bindGlass("gwin_sizeof_clipboard_callbacks", FunctionDescriptor.of(JAVA_INT));

    private static final MethodHandle GWIN_SIZEOF_DND_CALLBACKS =
            bindGlass("gwin_sizeof_dnd_callbacks", FunctionDescriptor.of(JAVA_INT));

    /**
     * {@code gwin_test_fire_clipboard_callback}: the only automated check of the seven descriptors, as
     * {@code gwin_test_fire_callback} is for the view table. Frees the {@code fos_serialize} block itself.
     */
    private static final MethodHandle GWIN_TEST_FIRE_CLIPBOARD_CALLBACK =
            bindGlass("gwin_test_fire_clipboard_callback", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, JAVA_LONG));

    private static final MethodHandle GWIN_TEST_FIRE_DND_CALLBACK = bindGlass("gwin_test_fire_dnd_callback",
            FunctionDescriptor.of(JAVA_LONG, JAVA_INT, JAVA_LONG, ADDRESS));

    private static final MethodHandle GWIN_ALLOC = bindGlass("gwin_alloc",
            FunctionDescriptor.of(ADDRESS, JAVA_LONG));

    private static final MethodHandle GWIN_FREE = bindGlass("gwin_free",
            FunctionDescriptor.ofVoid(ADDRESS));

    /**
     * {@code int32_t gwin_clipboard_register_viewer(int64_t clipboard_id)}; blocks, can upcall {@code dispose_peer}.
     */
    private static final MethodHandle GWIN_CLIPBOARD_REGISTER_VIEWER = bindGlass("gwin_clipboard_register_viewer",
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG));

    /** {@code void gwin_clipboard_dispose(gwin_clipboard_t clip)}; blocks, pumps, re-entrant. */
    private static final MethodHandle GWIN_CLIPBOARD_DISPOSE = bindGlass("gwin_clipboard_dispose",
            FunctionDescriptor.ofVoid(ADDRESS));

    /**
     * {@code int32_t gwin_clipboard_push(gwin_clipboard_t old, int64_t clipboard_id, const uint16_t*
     * mimes, int32_t mime_count, int32_t supported_actions)}. Blocks and upcalls.
     */
    private static final MethodHandle GWIN_CLIPBOARD_PUSH = bindGlass("gwin_clipboard_push",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, JAVA_INT, JAVA_INT));

    /** {@code gwin_clipboard_t gwin_clipboard_pop(gwin_clipboard_t old)}. Blocks. */
    private static final MethodHandle GWIN_CLIPBOARD_POP = bindGlass("gwin_clipboard_pop",
            FunctionDescriptor.of(ADDRESS, ADDRESS));

    private static final MethodHandle GWIN_CLIPBOARD_POP_BYTES = bindGlass("gwin_clipboard_pop_bytes",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS));

    private static final MethodHandle GWIN_CLIPBOARD_POP_MIMES = bindGlass("gwin_clipboard_pop_mimes",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));

    private static final MethodHandle GWIN_CLIPBOARD_PUSH_TARGET_ACTION =
            bindGlass("gwin_clipboard_push_target_action", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));

    private static final MethodHandle GWIN_CLIPBOARD_POP_SUPPORTED_ACTIONS =
            bindGlass("gwin_clipboard_pop_supported_actions", FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /**
     * {@code int32_t gwin_dnd_push(gwin_clipboard_t old, int64_t clipboard_id, const uint16_t* mimes,
     * int32_t mime_count, int32_t supported_actions)}: blocks for the whole drag in
     * {@code DoDragDrop}'s modal loop, with every slot of both tables able to fire nested inside.
     */
    private static final MethodHandle GWIN_DND_PUSH = bindGlass("gwin_dnd_push",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, JAVA_INT, JAVA_INT));

    /** {@code void gwin_dnd_dispose(gwin_clipboard_t clip)}: {@code Release} only. Blocks. */
    private static final MethodHandle GWIN_DND_DISPOSE = bindGlass("gwin_dnd_dispose",
            FunctionDescriptor.ofVoid(ADDRESS));

    private static final MethodHandle GWIN_SIZEOF_FILE_FILTER =
            bindGlass("gwin_sizeof_file_filter", FunctionDescriptor.of(JAVA_INT));

    /**
     * {@code int32_t gwin_dialog_file(void* owner, const uint16_t* folder, const uint16_t* filename,
     * const uint16_t* title, int32_t type, int32_t multiple, const GwinFileFilter* filters, int32_t
     * filter_count, int32_t default_filter_index, uint16_t** out_files, int32_t* out_count, int32_t*
     * out_filter_index)}: a modal dialog on this thread, not marshalled.
     */
    private static final MethodHandle GWIN_DIALOG_FILE = bindGlass("gwin_dialog_file",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS,
                    JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));

    /**
     * {@code int32_t gwin_dialog_folder(void* owner, const uint16_t* folder, const uint16_t* title,
     * uint16_t** out_path)}: a modal dialog on this thread, not marshalled.
     */
    private static final MethodHandle GWIN_DIALOG_FOLDER = bindGlass("gwin_dialog_folder",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));

    /**
     * {@code HRESULT OleIsCurrentClipboard(LPDATAOBJECT pDataObj)}, the one {@code WRAPPER} of the clipboard
     * peers. {@code HRESULT} is a 32-bit {@code LONG}.
     */
    private static final MethodHandle OLE_IS_CURRENT_CLIPBOARD = bindOle32("OleIsCurrentClipboard",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    private WinClipboardCalls() {
    }

    /**
     * {@code sizeof(GwinClipboardCallbacks)} as the C compiler saw it; {@link
     * WinClipboardCallbacks#GWIN_CLIPBOARD_CALLBACKS_LAYOUT} must agree.
     */
    static int sizeOfClipboardCallbacks() {
        try {
            return (int) GWIN_SIZEOF_CLIPBOARD_CALLBACKS.invokeExact();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code sizeof(GwinDndCallbacks)}; {@link WinClipboardCallbacks#GWIN_DND_CALLBACKS_LAYOUT} must agree. */
    static int sizeOfDndCallbacks() {
        try {
            return (int) GWIN_SIZEOF_DND_CALLBACKS.invokeExact();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code sizeof(GwinFileFilter)}; {@link #GWIN_FILE_FILTER_LAYOUT} must agree. */
    static int sizeOfFileFilter() {
        try {
            return (int) GWIN_SIZEOF_FILE_FILTER.invokeExact();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code gwin_test_fire_clipboard_callback}: drives slot {@code slot} of the installed clipboard
     * table with the header's fixed pattern and returns what the hook reports - a status slot's
     * status, 0 for a void slot, {@code fos_serialize}'s {@code *out_len} (or -100 for a NULL block),
     * {@link WinCallSupport#GWIN_ERR_INVALID_ARG} for an unknown slot or an uninstalled table. A test hook.
     */
    static long testFireClipboardCallback(int slot, long clipboardId) {
        try {
            return (long) GWIN_TEST_FIRE_CLIPBOARD_CALLBACK.invokeExact(slot, clipboardId);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code gwin_test_fire_dnd_callback}: the drag-table twin; {@code out} is handed unchanged to
     * the slots with an out-parameter (8 bytes, 8-aligned, or {@code NULL} for the others).
     */
    static long testFireDndCallback(int slot, long viewId, MemorySegment out) {
        try {
            return (long) GWIN_TEST_FIRE_DND_CALLBACK.invokeExact(slot, viewId, out);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * Reads a string block - {@code count} NUL-terminated UTF-16 strings laid end to end - into Java
     * strings, copied. The block's byte length is not known up front (only its string count is), so
     * the {@code reinterpret} here is to an unbounded size and the walk is bounded by the NULs the C
     * wrote; an empty string in the middle is one NUL and does not truncate the list, which is why the
     * count is passed and never derived.
     */
    static String[] readStringBlock(MemorySegment block, int count) {
        MemorySegment chars = bounded(block, Long.MAX_VALUE);
        String[] strings = new String[Math.max(count, 0)];
        long index = 0;
        for (int i = 0; i < strings.length; i++) {
            long start = index;
            while (chars.getAtIndex(JAVA_CHAR, index) != '\0') {
                index++;
            }
            strings[i] = new String(chars.asSlice(start * JAVA_CHAR.byteSize(), (index - start) * JAVA_CHAR.byteSize())
                    .toArray(JAVA_CHAR));
            index++;
        }
        return strings;
    }

    /**
     * A string as the C will read it out of a block: up to its first U+0000. {@code GwinNextString}
     * ({@code GlassStringBlock.h}) walks a block with {@code wcslen}, so a NUL inside a string would end
     * that string there and make its tail the <em>next</em> string, shifting every later entry by one
     * and dropping the last off the count: {@code "x\0y"} + {@code "text/plain"} with count 2 would
     * register "x" and "y" and never advertise {@code text/plain}. Cutting is parity, not sanitising:
     * under JNI each key crossed as its own {@code JString} and the C read it with {@code wcscmp} /
     * {@code RegisterClipboardFormat}, which stop at the first NUL of that key alone - so the JNI implementation
     * in commit {@code 8492cb03b0} advertised "x" and "text/plain", and so does the block.
     */
    private static String upToNul(String string) {
        int nul = string.indexOf('\0');
        return nul < 0 ? string : string.substring(0, nul);
    }

    /**
     * The UTF-16 code units a string block of {@code strings} occupies, including every NUL and the
     * extra one; each string counts {@link #upToNul up to its first NUL}, as it is written.
     */
    static long stringBlockUnits(String[] strings) {
        long units = 1;
        for (String string : strings) {
            units += upToNul(string).length() + 1L;
        }
        return units;
    }

    /**
     * Writes {@code strings} as a string block into {@code block}, which must hold
     * {@link #stringBlockUnits} code units. Strings cross as UTF-16 code units, so a surrogate pair is two
     * units and an unpaired surrogate is one, exactly as {@code GetStringRegion} passed them.
     */
    static void writeStringBlock(MemorySegment block, String[] strings) {
        long index = 0;
        for (String string : strings) {
            char[] chars = upToNul(string).toCharArray();
            MemorySegment.copy(chars, 0, block, JAVA_CHAR, index * JAVA_CHAR.byteSize(), chars.length);
            index += chars.length;
            block.setAtIndex(JAVA_CHAR, index, '\0');
            index++;
        }
        block.setAtIndex(JAVA_CHAR, index, '\0');
    }

    /** A string block of {@code strings} in {@code arena}; a block of one NUL for no strings, never NULL. */
    static MemorySegment allocStringBlock(Arena arena, String[] strings) {
        MemorySegment block = arena.allocate(JAVA_CHAR, stringBlockUnits(strings));
        writeStringBlock(block, strings);
        return block;
    }

    /**
     * The mime names {@code push} receives: an {@code Object[]} from {@code Set.toArray()}, cast
     * element by element. The C cast blindly ({@code JString(env, (jstring) element)}), so a
     * non-String element was undefined behaviour there and is a {@code ClassCastException} here.
     */
    private static String[] mimeNames(Object[] keys) {
        String[] mimes = new String[keys.length];
        for (int i = 0; i < keys.length; i++) {
            mimes[i] = (String) keys[i];
        }
        return mimes;
    }

    /**
     * {@code GwinFileFilter[descriptions.length]} in {@code arena}, or {@code NULL} for a null array.
     * The two are distinct to the C: a NULL pointer skips the filter setup entirely, a non-NULL
     * pointer with a count of 0 still runs {@code SetDefaultExtension(L"")} and
     * {@code SetFileTypes(0, ...)}, which is what stops the shell appending an extension to a typed
     * SAVE name - so an empty array is a real (one-entry-sized, zero-count) block. A null description
     * or extension string becomes {@code ""}; the C's {@code JString} would have crashed on it.
     */
    static MemorySegment allocFileFilters(Arena arena, String[] descriptions, String[] extensions) {
        if (descriptions == null) {
            return MemorySegment.NULL;
        }
        long entries = Math.max(descriptions.length, 1);
        MemorySegment filters = arena.allocate(GWIN_FILE_FILTER_LAYOUT.byteSize() * entries,
                GWIN_FILE_FILTER_LAYOUT.byteAlignment());
        long descriptionOffset = GWIN_FILE_FILTER_LAYOUT.byteOffset(PathElement.groupElement("description"));
        long extensionsOffset = GWIN_FILE_FILTER_LAYOUT.byteOffset(PathElement.groupElement("extensions"));
        for (int i = 0; i < descriptions.length; i++) {
            long base = i * GWIN_FILE_FILTER_LAYOUT.byteSize();
            filters.set(ADDRESS, base + descriptionOffset, wideString(arena, dialogString(descriptions[i])));
            filters.set(ADDRESS, base + extensionsOffset, wideString(arena, dialogString(extensions[i])));
        }
        return filters;
    }

    /**
     * A dialog string is never NULL and possibly empty: the JNI always allocated a {@code JString},
     * so {@code SetFolder} / {@code SetTitle} always ran ({@code SetTitle(L"")} gives an empty title
     * bar, where NULL would give the shell's default). {@code CommonDialogs} already substitutes
     * {@code ""} for a null folder and title; this covers the filename and the filter strings too.
     */
    static String dialogString(String text) {
        return text == null ? "" : text;
    }

    /**
     * {@code void* gwin_alloc(int64_t size)}: the library's {@code malloc}, so that the block
     * {@code fos_serialize} hands the C is one the C can {@code free}. {@code size <= 0} yields a
     * one-byte block, never NULL; NULL only when the heap is exhausted.
     */
    static MemorySegment gwinAlloc(long size) {
        try {
            return (MemorySegment) GWIN_ALLOC.invokeExact(size);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code gwin_free(block)}: every out-block this class reads is released here, exactly once; NULL is a no-op. */
    static void gwinFree(MemorySegment block) {
        try {
            GWIN_FREE.invokeExact(block);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code gwin_clipboard_register_viewer}: {@code WinSystemClipboard.create}. Disposes the
     * previously registered peer through {@code dispose_peer} first, then joins the viewer chain.
     * {@link WinCallSupport#GWIN_ERR_NO_TOOLKIT} when there is no {@code GlassApplication}.
     */
    static int clipboardRegisterViewer(long clipboardId) {
        try {
            return (int) GWIN_CLIPBOARD_REGISTER_VIEWER.invokeExact(clipboardId);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code gwin_clipboard_dispose}: unregister, flush if current, release. The caller nulls its handle. */
    static void clipboardDispose(MemorySegment dataObject) {
        try {
            GWIN_CLIPBOARD_DISPOSE.invokeExact(dataObject);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code gwin_clipboard_push}: {@code WinSystemClipboard.push(Object[] keys, int supportedActions)}.
     * The keys are marshalled as a string block in the order {@code Set.toArray()} gave them (the
     * order drives {@code FORMATETC} insertion); the block is borrowed for the call and copied by
     * {@code pushCommit}.
     */
    static int clipboardPush(MemorySegment old, long clipboardId, Object[] keys, int supportedActions) {
        String[] mimes = mimeNames(keys);
        try (Arena arena = Arena.ofConfined()) {
            return (int) GWIN_CLIPBOARD_PUSH.invokeExact(old, clipboardId, allocStringBlock(arena, mimes),
                    mimes.length, supportedActions);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code gwin_clipboard_pop}: releases {@code old}, then {@code OleGetClipboard}; the new handle, or NULL on
     * failure.
     */
    static MemorySegment clipboardPop(MemorySegment old) {
        try {
            return (MemorySegment) GWIN_CLIPBOARD_POP.invokeExact(old);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code gwin_clipboard_pop_bytes}: the bytes of one format, copied out of the library's block,
     * which is freed here; {@code null} for no data, an empty medium and an OLE failure alike. The
     * status is ignored - the JNI's null {@code byte[]} covered every failure and the caller's
     * fallbacks depend on that.
     */
    static byte[] clipboardPopBytes(MemorySegment dataObject, String mime, long lindex) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment outData = arena.allocate(ADDRESS);
            MemorySegment outLen = arena.allocate(JAVA_INT);
            int ignoredStatus = (int) GWIN_CLIPBOARD_POP_BYTES.invokeExact(dataObject, wideString(arena, mime), lindex,
                    outData, outLen);
            MemorySegment block = outData.get(ADDRESS, 0);
            if (block.equals(MemorySegment.NULL)) {
                return null;
            }
            try {
                return bounded(block, outLen.get(JAVA_INT, 0)).toArray(JAVA_BYTE);
            } finally {
                gwinFree(block);
            }
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code gwin_clipboard_pop_mimes}: the mimes the data object advertises, copied out of the
     * library's string block, which is freed here; {@code null} when there is no data object or the
     * set came out empty. Iteration order was never specified on either side.
     */
    static String[] clipboardPopMimes(MemorySegment dataObject) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment outMimes = arena.allocate(ADDRESS);
            MemorySegment outCount = arena.allocate(JAVA_INT);
            int ignoredStatus = (int) GWIN_CLIPBOARD_POP_MIMES.invokeExact(dataObject, outMimes, outCount);
            MemorySegment block = outMimes.get(ADDRESS, 0);
            if (block.equals(MemorySegment.NULL)) {
                return null;
            }
            try {
                return readStringBlock(block, outCount.get(JAVA_INT, 0));
            } finally {
                gwinFree(block);
            }
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code gwin_clipboard_push_target_action}: {@code CFSTR_PASTESUCCEEDED} + {@code CFSTR_PERFORMEDDROPEFFECT};
     * silent on failure.
     */
    static void clipboardPushTargetAction(MemorySegment dataObject, int actionDone) {
        try {
            GWIN_CLIPBOARD_PUSH_TARGET_ACTION.invokeExact(dataObject, actionDone);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code gwin_clipboard_pop_supported_actions}: {@code ACTION_NONE} for NULL, {@code ACTION_ANY} when unset, else
     * the stored set.
     */
    static int clipboardPopSupportedActions(MemorySegment dataObject) {
        try {
            return (int) GWIN_CLIPBOARD_POP_SUPPORTED_ACTIONS.invokeExact(dataObject);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code gwin_dnd_push}: {@code WinDnDClipboard.push}. Same marshalling as {@link #clipboardPush}; after
     * the drag it fires {@code drag_action_performed} and {@code dnd_set_drag_button(0)}.
     */
    static int dndPush(MemorySegment old, long clipboardId, Object[] keys, int supportedActions) {
        String[] mimes = mimeNames(keys);
        try (Arena arena = Arena.ofConfined()) {
            return (int) GWIN_DND_PUSH.invokeExact(old, clipboardId, allocStringBlock(arena, mimes), mimes.length,
                    supportedActions);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    static void dndDispose(MemorySegment dataObject) {
        try {
            GWIN_DND_DISPOSE.invokeExact(dataObject);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code WinSystemClipboard.isOwner}: {@code false} for a NULL handle <em>without calling OLE</em>
     * - the JNI body's first test ({@code GlassClipboard.cpp}, {@code if (NULL == p) return false})
     * - else {@code OleIsCurrentClipboard(handle) == S_OK}.
     */
    static boolean oleIsCurrentClipboard(MemorySegment dataObject) {
        if (dataObject.equals(MemorySegment.NULL)) {
            return false;
        }
        try {
            return (int) OLE_IS_CURRENT_CLIPBOARD.invokeExact(dataObject) == 0;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code gwin_dialog_file}: {@code WinCommonDialogs._showFileChooser}. The owner is an HWND handle
     * value, never dereferenced here, and the out-block is freed here.
     */
    static FileDialogResult dialogFile(long owner, String folder, String filename, String title, int type,
                                       boolean multiple, String[] filterDescriptions, String[] filterExtensions,
                                       int defaultFilterIndex) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment filters = allocFileFilters(arena, filterDescriptions, filterExtensions);
            int filterCount = filterDescriptions == null ? 0 : filterDescriptions.length;
            MemorySegment outFiles = arena.allocate(ADDRESS);
            MemorySegment outCount = arena.allocate(JAVA_INT);
            MemorySegment outFilterIndex = arena.allocate(JAVA_INT);
            int ignoredStatus = (int) GWIN_DIALOG_FILE.invokeExact(MemorySegment.ofAddress(owner),
                    wideString(arena, dialogString(folder)), wideString(arena, dialogString(filename)),
                    wideString(arena, dialogString(title)), type, multiple ? 1 : 0, filters, filterCount,
                    defaultFilterIndex, outFiles, outCount, outFilterIndex);
            int filterIndex = outFilterIndex.get(JAVA_INT, 0);
            MemorySegment block = outFiles.get(ADDRESS, 0);
            if (block.equals(MemorySegment.NULL)) {
                return new FileDialogResult(null, filterIndex);
            }
            try {
                return new FileDialogResult(readStringBlock(block, outCount.get(JAVA_INT, 0)), filterIndex);
            } finally {
                gwinFree(block);
            }
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code gwin_dialog_folder}: {@code WinCommonDialogs._showFolderChooser}. {@code null} when the
     * user cancelled or the path could not be resolved, else the path; the out-block is freed here.
     */
    static String dialogFolder(long owner, String folder, String title) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment outPath = arena.allocate(ADDRESS);
            int ignoredStatus = (int) GWIN_DIALOG_FOLDER.invokeExact(MemorySegment.ofAddress(owner),
                    wideString(arena, dialogString(folder)), wideString(arena, dialogString(title)), outPath);
            MemorySegment block = outPath.get(ADDRESS, 0);
            if (block.equals(MemorySegment.NULL)) {
                return null;
            }
            try {
                return readStringBlock(block, 1)[0];
            } finally {
                gwinFree(block);
            }
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }
}
