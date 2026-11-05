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

import com.sun.glass.ui.CommonDialogs.ExtensionFilter;
import com.sun.glass.ui.CommonDialogs.FileChooserResult;
import com.sun.glass.ui.EventLoop;
import com.sun.glass.ui.mac.MacGlassNative.SystemLibrary;
import java.io.File;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.StructLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_ERR_NO_DATA;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_OFFSETOF_FILE_FILTER;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_OK;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_SIZEOF_FILE_FILTER;
import static com.sun.glass.ui.mac.MacGlassNative.bind;
import static com.sun.glass.ui.mac.MacGlassNative.bounded;
import static com.sun.glass.ui.mac.MacGlassNative.free;
import static com.sun.glass.ui.mac.MacGlassNative.mainThreadDetached;
import static com.sun.glass.ui.mac.MacGlassNative.mainThreadDetachedError;
import static com.sun.glass.ui.mac.MacGlassNative.reportException;
import static com.sun.glass.ui.mac.MacGlassNative.unexpected;
import static com.sun.glass.ui.mac.MacGlassNative.utf16;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_CHAR;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The natives of {@code MacFileNSURL} and {@code MacCommonDialogs}: the {@code gmac_url_*} and {@code gmac_dialog_*}
 * exports, which this class binds in header order when it initializes, {@code objc_release}, the
 * {@code GmacFileFilter}s the dialogs take, and what the JNI built in Java around them: the {@code EventLoop} before
 * the panel, and the {@code File}s, the {@code MacFileNSURL}s and the {@code FileChooserResult} after it. FX thread,
 * but for {@link #path}.
 * <p>
 * The three dialog natives opened with {@code GLASS_ASSERT_MAIN_JAVA_THREAD}, whose exception HotSpot kept pending
 * while their body ran on (PB11). Each dialog evaluates {@code gmac_main_thread_detached} first and reports that
 * exception once, where the first {@code GLASS_CHECK_EXCEPTION} of the body reported it; none throws it. The JNI then
 * crashed in {@code runModally} on the main thread's NULL {@code JNIEnv}; the export prints what
 * {@code GET_MAIN_JENV} printed and does not begin the panel, so the dialog answers as cancelled. The
 * {@code MacFileNSURL} natives asserted nothing.
 * <p>
 * None of these downcalls may be critical: the dialogs run a panel in a nested event loop that calls back into Java,
 * the bookmark exports reach the file system and the others message an {@code NSURL}. Every block they answer is
 * freed with {@code gmac_free}.
 */
final class MacDialogCalls {

    static final StructLayout GMAC_FILE_FILTER_LAYOUT = MemoryLayout.structLayout(
            ADDRESS.withName("description"),
            JAVA_INT.withName("description_length"),
            JAVA_INT.withName("extension_count"),
            ADDRESS.withName("extensions"),
            ADDRESS.withName("extension_lengths"));

    private static final long DESCRIPTION = filterOffset("description");
    private static final long DESCRIPTION_LENGTH = filterOffset("description_length");
    private static final long EXTENSION_COUNT = filterOffset("extension_count");
    private static final long EXTENSIONS = filterOffset("extensions");
    private static final long EXTENSION_LENGTHS = filterOffset("extension_lengths");

    /*
     * Checked here, not in MacDialogCallbacks.install, because MacCommonDialogs' initializer runs install, and
     * MacFileNSURL's bookmark factories reach it through isFileNSURLEnabled and then throw without using this class
     * while glass.macosx.enableFileNSURL is unset, so a check in install would initialize this class on that path
     * (PB23).
     */
    static {
        MacAppCallbacks.requireLayout("GmacFileFilter", GMAC_FILE_FILTER_LAYOUT, GMAC_SIZEOF_FILE_FILTER,
                GMAC_OFFSETOF_FILE_FILTER);
    }

    private static final MethodHandle GMAC_URL_START_ACCESSING_SECURITY_SCOPED_RESOURCE = bind(
            "gmac_url_start_accessing_security_scoped_resource", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle GMAC_URL_STOP_ACCESSING_SECURITY_SCOPED_RESOURCE = bind(
            "gmac_url_stop_accessing_security_scoped_resource", FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle GMAC_URL_GET_BOOKMARK = bind("gmac_url_get_bookmark",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle GMAC_URL_CREATE_FROM_BOOKMARK = bind("gmac_url_create_from_bookmark",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle GMAC_URL_COPY_PATH = bind("gmac_url_copy_path",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle GMAC_DIALOG_OPEN = bind("gmac_dialog_open",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS,
                    JAVA_INT, JAVA_INT, JAVA_LONG, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle GMAC_DIALOG_SAVE = bind("gmac_dialog_save",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS,
                    JAVA_INT, JAVA_INT, JAVA_LONG, ADDRESS, ADDRESS));
    private static final MethodHandle GMAC_DIALOG_FOLDER = bind("gmac_dialog_folder",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, JAVA_LONG, ADDRESS));

    private static final MethodHandle OBJC_RELEASE = SystemLibrary.libobjc().bind("objc_release",
            FunctionDescriptor.ofVoid(ADDRESS));

    /**
     * {@code ExtensionFilter.extensionsToArray()} and {@code new EventLoop()}, private and package-private: the JNI
     * reached both without access control. Reached as {@code GtkDialogCalls} reaches the first.
     */
    private static final MethodHandle EXTENSIONS_TO_ARRAY = extensionsToArray();
    private static final MethodHandle NEW_EVENT_LOOP = eventLoopConstructor();

    /** {@code gmac_url_get_bookmark}, with out-parameters for its block, so that a test can stand in. */
    @FunctionalInterface
    interface BookmarkQuery {

        int query(MemorySegment outBytes, MemorySegment outLength) throws Throwable;
    }

    /** {@code gmac_url_create_from_bookmark}, given Java's copy of the data, so that a test can stand in. */
    @FunctionalInterface
    interface BookmarkResolution {

        int resolve(MemorySegment data, int length, MemorySegment outUrl) throws Throwable;
    }

    /** What a dialog export chose: its URLs, each an {@code NSURL*} retained for the caller, and the popup's index. */
    record Chosen(long[] urls, long selected) {
    }

    /**
     * A dialog export, given the filters Java marshalled, their count and the loop id, so that a test can stand where
     * the panel would be. The allocator is the call's arena, open until the export has returned.
     */
    @FunctionalInterface
    interface Panel {

        Chosen run(SegmentAllocator allocator, MemorySegment filters, int filterCount, long loopId);
    }

    /** The {@code GmacFileFilter}s of a call, NULL and 0 for none, and the exception their upcalls left pending. */
    record FileFilters(MemorySegment array, int count, Throwable pending) {
    }

    /** What a panel chose, and what {@code new EventLoop()} threw before it, if it threw. */
    private record Ran(Chosen chosen, Throwable loopFailure) {
    }

    private MacDialogCalls() {
    }

    /** {@code MacFileNSURL._dispose}: its {@code [url release]} compiled to a tail call of {@code objc_release}. */
    static void release(long url) {
        try {
            OBJC_RELEASE.invokeExact(MemorySegment.ofAddress(url));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    static boolean startAccessingSecurityScopedResource(long url) {
        try {
            return (int) GMAC_URL_START_ACCESSING_SECURITY_SCOPED_RESOURCE.invokeExact(MemorySegment.ofAddress(url))
                    != 0;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    static void stopAccessingSecurityScopedResource(long url) {
        try {
            GMAC_URL_STOP_ACCESSING_SECURITY_SCOPED_RESOURCE.invokeExact(MemorySegment.ofAddress(url));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    static byte[] bookmark(long url, long baseUrl) {
        return bookmark((outBytes, outLength) -> (int) GMAC_URL_GET_BOOKMARK.invokeExact(
                MemorySegment.ofAddress(url), MemorySegment.ofAddress(baseUrl), outBytes, outLength));
    }

    /**
     * {@code _getBookmark}: the bookmark data, or null. For data whose length is negative as a {@code jsize}, the
     * JNI's {@code NewByteArray} threw a {@code NegativeArraySizeException} with the length as its message, and its
     * closing {@code GLASS_CHECK_EXCEPTION} reported it.
     */
    static byte[] bookmark(BookmarkQuery query) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment outBytes = arena.allocate(ADDRESS);
            MemorySegment outLength = arena.allocate(JAVA_INT);
            int status;
            try {
                status = query.query(outBytes, outLength);
            } catch (Throwable t) {
                throw unexpected(t);
            }
            MemorySegment block = outBytes.get(ADDRESS, 0);
            int length = outLength.get(JAVA_INT, 0);
            try {
                if (status == GMAC_OK) {
                    return bounded(block, length).toArray(JAVA_BYTE);
                }
            } finally {
                free(block);
            }
            if (status == GMAC_ERR_NO_DATA && length < 0) {
                reportException(new NegativeArraySizeException(String.valueOf(length)));
            }
            return null;
        }
    }

    static MacFileNSURL fromBookmark(byte[] data, long baseUrl) {
        return fromBookmark(data, (bytes, length, outUrl) -> (int) GMAC_URL_CREATE_FROM_BOOKMARK.invokeExact(bytes,
                length, MemorySegment.ofAddress(baseUrl), outUrl));
    }

    /**
     * {@code _createFromBookmark}: the URL the bookmark resolves to, converted as the dialogs convert theirs
     * ({@link #toFile}), or null. The data crosses as a copy that is not NULL even for an empty array, as
     * {@code GetPrimitiveArrayCritical} answered for one. A plain {@code File}, which the JNI answered from a native
     * declared to answer a {@code MacFileNSURL} when the system property turned false between the caller's check and
     * the conversion's (latent bug L16), is null here.
     */
    static MacFileNSURL fromBookmark(byte[] data, BookmarkResolution resolution) {
        long url;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment bytes = arena.allocate(Math.max(1, data.length));
            MemorySegment.copy(data, 0, bytes, JAVA_BYTE, 0, data.length);
            MemorySegment outUrl = arena.allocate(ADDRESS);
            if (resolution.resolve(bytes, data.length, outUrl) != GMAC_OK) {
                return null;
            }
            url = outUrl.get(ADDRESS, 0).address();
        } catch (Throwable t) {
            throw unexpected(t);
        }
        return toFile(url) instanceof MacFileNSURL fileNSURL ? fileNSURL : null;
    }

    /**
     * The JNI's {@code convertNSURLtoFile} of {@code url}, an {@code NSURL*} retained for the caller: a
     * {@code MacFileNSURL} of its path, which keeps that reference, while {@code MacCommonDialogs.isFileNSURLEnabled()}
     * is true, else a {@code File} of its path, once the reference is released. A step that throws is reported, as
     * the {@code GLASS_CHECK_EXCEPTION} after it reported it: a throwing {@code isFileNSURLEnabled()} counts as false,
     * as its {@code CallStaticBooleanMethod} answered, and a throwing constructor gives null. The reference a
     * {@code MacFileNSURL} constructor that threw was to keep is never released, as the JNI's was not (latent bug
     * L29).
     */
    static File toFile(long url) {
        String path = path(url);
        boolean enabled;
        try {
            enabled = MacCommonDialogs.isFileNSURLEnabled();
        } catch (Throwable t) {
            reportException(t);
            enabled = false;
        }
        if (enabled) {
            try {
                return new MacFileNSURL(path, url);
            } catch (Throwable t) {
                reportException(t);
                return null;
            }
        }
        release(url);
        try {
            return new File(path);
        } catch (Throwable t) {
            reportException(t);
            return null;
        }
    }

    /**
     * Any thread. {@code gmac_url_copy_path}: the UTF-16 units of {@code [url path]}, as the JNI gave them to
     * {@code NewString}; {@code ""} for NULL. Null when the library could not allocate the block.
     */
    static String path(long url) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment outChars = arena.allocate(ADDRESS);
            MemorySegment outLength = arena.allocate(JAVA_INT);
            int status;
            try {
                status = (int) GMAC_URL_COPY_PATH.invokeExact(MemorySegment.ofAddress(url), outChars, outLength);
            } catch (Throwable t) {
                throw unexpected(t);
            }
            MemorySegment chars = outChars.get(ADDRESS, 0);
            try {
                return status == GMAC_OK
                        ? new String(bounded(chars, JAVA_CHAR.byteSize() * outLength.get(JAVA_INT, 0))
                                .toArray(JAVA_CHAR))
                        : null;
            } finally {
                free(chars);
            }
        }
    }

    static FileChooserResult fileOpenChooser(long owner, String folder, String title, boolean multiple,
                                             ExtensionFilter[] filters, int defaultIndex) {
        return fileChooser(filters, (allocator, array, count, loopId) -> {
            MemorySegment outUrls = allocator.allocate(ADDRESS);
            MemorySegment outUrlCount = allocator.allocate(JAVA_INT);
            MemorySegment outSelected = allocator.allocate(JAVA_LONG);
            int status;
            try {
                status = (int) GMAC_DIALOG_OPEN.invokeExact(MemorySegment.ofAddress(owner), utf16(allocator, folder),
                        length(folder), utf16(allocator, title), length(title), multiple ? 1 : 0, array, count,
                        defaultIndex, loopId, outUrls, outUrlCount, outSelected);
            } catch (Throwable t) {
                throw unexpected(t);
            }
            MemorySegment block = outUrls.get(ADDRESS, 0);
            try {
                long[] urls = status == GMAC_OK
                        ? bounded(block, ADDRESS.byteSize() * outUrlCount.get(JAVA_INT, 0)).toArray(JAVA_LONG)
                        : new long[0];
                return new Chosen(urls, outSelected.get(JAVA_LONG, 0));
            } finally {
                free(block);
            }
        });
    }

    static FileChooserResult fileSaveChooser(long owner, String folder, String filename, String title,
                                             ExtensionFilter[] filters, int defaultIndex) {
        return fileChooser(filters, (allocator, array, count, loopId) -> {
            MemorySegment outUrl = allocator.allocate(ADDRESS);
            MemorySegment outSelected = allocator.allocate(JAVA_LONG);
            try {
                GMAC_DIALOG_SAVE.invokeExact(MemorySegment.ofAddress(owner), utf16(allocator, folder), length(folder),
                        utf16(allocator, filename), length(filename), utf16(allocator, title), length(title), array,
                        count, defaultIndex, loopId, outUrl, outSelected);
            } catch (Throwable t) {
                throw unexpected(t);
            }
            return new Chosen(urls(outUrl.get(ADDRESS, 0)), outSelected.get(JAVA_LONG, 0));
        });
    }

    /**
     * The frame of {@code _showFileOpenChooser} and {@code _showFileSaveChooser}. The exception the JNI's
     * {@code applyExtensions:} left pending, the detached assertion's or the last its upcalls threw, is reported once
     * after the filters, where its {@code GLASS_CHECK_EXCEPTION} ran when there was a filter, and else after the
     * panel, where the first {@code GLASS_CHECK_EXCEPTION} after it ran. That check, before the {@code ArrayList},
     * also reported what {@code new EventLoop()} threw, after which the JNI's {@code runModally} had returned without
     * beginning the panel: loop id 0 makes the export do the same. Then each URL is converted ({@link #toFile}) and
     * added, null for one whose conversion failed, and the filter the popup selected is chosen.
     */
    static FileChooserResult fileChooser(ExtensionFilter[] filters, Panel panel) {
        Throwable pending = mainThreadDetached() ? mainThreadDetachedError() : null;
        Ran ran;
        try (Arena arena = Arena.ofConfined()) {
            FileFilters fileFilters = fileFilters(arena, filters, pending);
            pending = fileFilters.pending();
            if (fileFilters.count() > 0 && pending != null) {
                reportException(pending);
                pending = null;
            }
            ran = run(arena, fileFilters, panel);
        }
        reportIfThrown(pending);
        reportIfThrown(ran.loopFailure());
        List<File> files = new ArrayList<>();
        for (long url : ran.chosen().urls()) {
            files.add(toFile(url));
        }
        return new FileChooserResult(files, chosenFilter(filters, ran.chosen().selected()));
    }

    static File folderChooser(long owner, String folder, String title) {
        return folderChooser((allocator, array, count, loopId) -> {
            MemorySegment outUrl = allocator.allocate(ADDRESS);
            try {
                GMAC_DIALOG_FOLDER.invokeExact(MemorySegment.ofAddress(owner), utf16(allocator, folder), length(folder),
                        utf16(allocator, title), length(title), loopId, outUrl);
            } catch (Throwable t) {
                throw unexpected(t);
            }
            return new Chosen(urls(outUrl.get(ADDRESS, 0)), -1);
        });
    }

    /**
     * The frame of {@code _showFolderChooser}, which has no filters: the detached assertion's exception, and then what
     * {@code new EventLoop()} threw, are reported after the panel, where the closing {@code GLASS_CHECK_EXCEPTION}
     * reported what was pending; then the first URL is converted ({@link #toFile}), or the answer is null.
     */
    static File folderChooser(Panel panel) {
        Throwable pending = mainThreadDetached() ? mainThreadDetachedError() : null;
        Ran ran;
        try (Arena arena = Arena.ofConfined()) {
            ran = run(arena, new FileFilters(MemorySegment.NULL, 0, null), panel);
        }
        reportIfThrown(pending);
        reportIfThrown(ran.loopFailure());
        long[] urls = ran.chosen().urls();
        return urls.length > 0 ? toFile(urls[0]) : null;
    }

    /**
     * What the JNI's {@code applyExtensions:} read of {@code filters}, in its order: per filter
     * {@code getDescription()}, then {@code extensionsToArray()}, for the export to copy. A null string is NULL with
     * length 0. An upcall that throws replaces {@code pending}, as HotSpot let a new exception replace a pending one
     * (PB11), and leaves its string NULL. A throwing {@code extensionsToArray()}, after which the JNI's
     * {@code GetArrayLength(NULL)} crashed, gives no extensions (latent bug L17); so does a null filter, whose
     * {@code CallObjectMethod} crashed. None for a null or an empty array.
     */
    static FileFilters fileFilters(Arena arena, ExtensionFilter[] filters, Throwable pending) {
        if (filters == null || filters.length == 0) {
            return new FileFilters(MemorySegment.NULL, 0, pending);
        }
        long size = GMAC_FILE_FILTER_LAYOUT.byteSize();
        MemorySegment array = arena.allocate(GMAC_FILE_FILTER_LAYOUT, filters.length);
        Throwable last = pending;
        for (int i = 0; i < filters.length; i++) {
            String description = null;
            try {
                description = filters[i].getDescription();
            } catch (Throwable t) {
                last = t;
            }
            String[] extensions = null;
            try {
                extensions = (String[]) EXTENSIONS_TO_ARRAY.invokeExact(filters[i]);
            } catch (Throwable t) {
                last = t;
            }
            setFilter(arena, array.asSlice(i * size, size), description, extensions);
        }
        return new FileFilters(array, filters.length, last);
    }

    private static void setFilter(Arena arena, MemorySegment filter, String description, String[] extensions) {
        int count = extensions != null ? extensions.length : 0;
        MemorySegment units = count > 0 ? arena.allocate(ADDRESS, count) : MemorySegment.NULL;
        MemorySegment lengths = count > 0 ? arena.allocate(JAVA_INT, count) : MemorySegment.NULL;
        for (int j = 0; j < count; j++) {
            units.setAtIndex(ADDRESS, j, utf16(arena, extensions[j]));
            lengths.setAtIndex(JAVA_INT, j, length(extensions[j]));
        }
        filter.set(ADDRESS, DESCRIPTION, utf16(arena, description));
        filter.set(JAVA_INT, DESCRIPTION_LENGTH, length(description));
        filter.set(JAVA_INT, EXTENSION_COUNT, count);
        filter.set(ADDRESS, EXTENSIONS, units);
        filter.set(ADDRESS, EXTENSION_LENGTHS, lengths);
    }

    /**
     * {@code new EventLoop()}, registered for the panel's slots while it runs, then the panel. A loop that could not
     * be made is passed as id 0, and what it threw is handed back to be reported.
     */
    private static Ran run(SegmentAllocator allocator, FileFilters filters, Panel panel) {
        long loopId = 0;
        Throwable loopFailure = null;
        try {
            loopId = MacDialogCallbacks.register((EventLoop) NEW_EVENT_LOOP.invokeExact());
        } catch (Throwable t) {
            loopFailure = t;
        }
        try {
            return new Ran(panel.run(allocator, filters.array(), filters.count(), loopId), loopFailure);
        } finally {
            MacDialogCallbacks.unregister(loopId);
        }
    }

    /**
     * The JNI's {@code GetObjectArrayElement(filters, indexOfSelectedItem)}, for a filter array that is not null or
     * empty: an index outside the array was reported, and gave null (latent bug L15).
     */
    private static ExtensionFilter chosenFilter(ExtensionFilter[] filters, long selected) {
        if (filters == null || filters.length == 0) {
            return null;
        }
        try {
            return filters[(int) selected];
        } catch (ArrayIndexOutOfBoundsException e) {
            reportException(e);
            return null;
        }
    }

    private static long[] urls(MemorySegment url) {
        return url.address() != 0 ? new long[] {url.address()} : new long[0];
    }

    private static void reportIfThrown(Throwable t) {
        if (t != null) {
            reportException(t);
        }
    }

    private static int length(String s) {
        return s != null ? s.length() : 0;
    }

    private static long filterOffset(String member) {
        return GMAC_FILE_FILTER_LAYOUT.byteOffset(PathElement.groupElement(member));
    }

    private static MethodHandle extensionsToArray() {
        try {
            return MethodHandles.privateLookupIn(ExtensionFilter.class, MethodHandles.lookup())
                    .findVirtual(ExtensionFilter.class, "extensionsToArray", MethodType.methodType(String[].class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static MethodHandle eventLoopConstructor() {
        try {
            return MethodHandles.privateLookupIn(EventLoop.class, MethodHandles.lookup())
                    .findConstructor(EventLoop.class, MethodType.methodType(void.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
