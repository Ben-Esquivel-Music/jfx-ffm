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

package test.com.sun.glass.ui.mac;

import com.sun.glass.ui.CommonDialogs.ExtensionFilter;
import com.sun.glass.ui.CommonDialogs.FileChooserResult;
import com.sun.glass.ui.mac.MacFileNSURL;
import com.sun.glass.ui.mac.MacGlassNativeShim;
import com.sun.glass.ui.mac.MacGlassNativeShim.BlockAnswer;
import com.sun.glass.ui.mac.MacGlassNativeShim.FilterDescription;
import com.sun.glass.ui.mac.MacGlassNativeShim.PanelAnswer;
import com.sun.glass.ui.mac.MacGlassNativeShim.Resolution;
import java.io.File;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.StructLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The natives of {@code MacFileNSURL} and {@code MacCommonDialogs} over the {@code gmac_url_*} and
 * {@code gmac_dialog_*} ABI, without a panel: the {@code GmacFileFilter} and {@code GmacDialogCallbacks} layouts
 * against the library's probes; the two slots, through {@code gmac_test_fire_dialog_callback}; the filters a dialog
 * passes its export, through {@code gmac_test_dialog_describe_filters}, which makes the popup titles and file types
 * of them as the exports do; and the Java the JNI ran around each dialog and bookmark call, in its order, with a
 * stand-in where the export would be.
 * <p>
 * No test calls a {@code gmac_dialog_*} export, which runs an {@code NSOpenPanel} or {@code NSSavePanel}, or a
 * bookmark export, which reaches the file system. The URLs are CoreFoundation file URLs made here. The exports the
 * tests call on them message only the {@code NSURL}, as the CoreFoundation twins this test calls directly do (PB3,
 * PB17). In {@code GlassDialogs.m}, {@code gmac_url_start_accessing_security_scoped_resource} (205) and
 * {@code gmac_url_stop_accessing_security_scoped_resource} (210) send the URL one message each, and
 * {@code gmac_url_copy_path} (287) its {@code path} and {@code dataUsingEncoding:} inside a pool, then
 * {@code malloc}s. {@code gmac_test_dialog_describe_filters} (466) makes {@code NSString}s and {@code NSArray}s only,
 * through {@code makeFilterArrays} (54), and {@code gmac_dialog_install_callbacks} ({@code glass_mac_api.m}, 524)
 * copies the table. {@code MacFileNSURL._dispose} compiled to {@code mov x0, x2; b _objc_release}, so its
 * replacement is {@code objc_release} itself (PB2 (i)), and {@link #releaseDropsTheRetainCountOfTheUrlByOne} checks
 * it on the retain count. Nothing here creates an {@code NSApplication}: AppKit's {@code NSApp} is nil before the
 * first call into the library and after the last.
 * <p>
 * Each call runs on a fresh thread whose uncaught-exception handler records what {@code Application} reports, with
 * the main thread's attached flag set as the test says, and, where the test says, as Glass's event thread: a test JVM
 * has none, so {@code ExtensionFilter}, {@code EventLoop} and {@code MacFileNSURL} throw off it.
 */
@EnabledOnOs(OS.MAC)
public class MacDialogNativeTest {

    private static final String DETACHED = "java.lang.RuntimeException: Main Java thread is detached.";

    private static final String THREAD_NAME = "MacDialogNativeTest-call";

    private static final String OFF_EVENT_THREAD = "java.lang.IllegalStateException: This operation is permitted on"
            + " the event thread only; currentThread = " + THREAD_NAME;

    private static final String ENABLE_FILE_NSURL = "glass.macosx.enableFileNSURL";

    private static final String PANEL = "panel";

    private static final int GMAC_OK = 0;
    private static final int GMAC_ERR_NO_DATA = -1;
    private static final int GMAC_UPCALL_OK = 0;
    private static final int GMAC_UPCALL_THREW = 1;

    /** Decomposed, as a CoreFoundation file URL keeps its path, so that {@code [url path]} answers it unchanged. */
    private static final String TEXT_PATH = "/tmp/jfx dialog e\u0301 日本 😀.txt";
    private static final String OTHER_PATH = "/tmp/jfx-dialog/other.dat";

    /** What a call on a fresh thread answered, and what it reported or the stand-in recorded, in order. */
    private record Run<T>(T value, List<String> events) {
    }

    @BeforeAll
    static void loadGlass() {
        MacGlassNatives.require();
        assertTrue(MacGlassNativeShim.nsAppIsNil(), "an NSApplication exists before the first call into libglass");
        MacGlassNativeShim.loadLibrary();
        MacGlassNativeShim.installDialogCallbacks();
    }

    @AfterAll
    static void noApplicationExistsAfterTheLastCall() {
        assertTrue(MacGlassNativeShim.nsAppIsNil(), "an NSApplication exists after the last call into libglass");
    }

    @Test
    public void theFileFilterIsWhereTheCompilerPutIt() {
        StructLayout layout = MacGlassNativeShim.layouts().get("GmacFileFilter");
        List<String> members = List.of("description", "description_length", "extension_count", "extensions",
                "extension_lengths");
        List<Long> offsets = List.of(0L, 8L, 12L, 16L, 24L);
        assertEquals(32, layout.byteSize());
        assertEquals(32, MacGlassNativeShim.sizeOf("GmacFileFilter"));
        assertEquals(members, names(layout));
        for (int member = 0; member < members.size(); member++) {
            long offset = layout.byteOffset(PathElement.groupElement(members.get(member)));
            assertEquals(offsets.get(member), offset, members.get(member));
            assertEquals(offset, MacGlassNativeShim.offsetOf("GmacFileFilter", member),
                    "offsetof " + members.get(member));
        }
        assertEquals(-1, MacGlassNativeShim.offsetOf("GmacFileFilter", members.size()), "past its last member");
        assertEquals(-1, MacGlassNativeShim.offsetOf("GmacFileFilter", -1), "before its first member");
    }

    @Test
    public void theTableHasTwoPointersInSlotOrder() {
        StructLayout table = MacGlassNativeShim.layouts().get("GmacDialogCallbacks");
        assertEquals(List.of("event_loop_enter", "event_loop_leave"), MacGlassNativeShim.dialogSlotNames());
        assertEquals(MacGlassNativeShim.dialogSlotNames(), names(table));
        assertEquals(16, table.byteSize());
        assertEquals(16, MacGlassNativeShim.sizeOf("GmacDialogCallbacks"));
    }

    @Test
    public void eachSlotRunsItsCallOnTheLoopTheIdNames() throws Exception {
        List<String> calls = new CopyOnWriteArrayList<>();
        long loopId = MacGlassNativeShim.registerDialogLoop(() -> calls.add("enter"), () -> calls.add("leave"));
        long otherId = MacGlassNativeShim.registerDialogLoop(() -> calls.add("other enter"),
                () -> calls.add("other leave"));
        try {
            Run<List<Integer>> run = onFreshThread(true, false, events -> List.of(
                    MacGlassNativeShim.fireDialogCallback(0, loopId),
                    MacGlassNativeShim.fireDialogCallback(1, loopId)));
            assertEquals(List.of(GMAC_UPCALL_OK, GMAC_UPCALL_OK), run.value());
            assertEquals(List.of(), run.events());
            assertEquals(List.of("enter", "leave"), calls);
        } finally {
            MacGlassNativeShim.unregisterDialogLoop(loopId);
            MacGlassNativeShim.unregisterDialogLoop(otherId);
        }
    }

    /** The completion handler can come after the call unregistered the loop (latent bug L14). */
    @Test
    public void aLoopIdTheRegistryDoesNotHoldRunsNothing() throws Exception {
        List<String> calls = new CopyOnWriteArrayList<>();
        long loopId = MacGlassNativeShim.registerDialogLoop(() -> calls.add("enter"), () -> calls.add("leave"));
        MacGlassNativeShim.unregisterDialogLoop(loopId);
        assertFalse(MacGlassNativeShim.isDialogLoopRegistered(loopId));
        Run<List<Integer>> run = onFreshThread(true, false, events -> List.of(
                MacGlassNativeShim.fireDialogCallback(0, loopId), MacGlassNativeShim.fireDialogCallback(1, loopId),
                MacGlassNativeShim.fireDialogCallback(1, 0)));
        assertEquals(List.of(GMAC_UPCALL_OK, GMAC_UPCALL_OK, GMAC_UPCALL_OK), run.value());
        assertEquals(List.of(), run.events());
        assertEquals(List.of(), calls);
    }

    @Test
    public void aSlotThatThrowsReportsWhatItThrewAndAnswersThrew() throws Exception {
        IllegalStateException enter = new IllegalStateException("enter");
        IllegalStateException leave = new IllegalStateException("leave");
        long loopId = MacGlassNativeShim.registerDialogLoop(() -> {
            throw enter;
        }, () -> {
            throw leave;
        });
        try {
            Run<List<Integer>> run = onFreshThread(true, false, events -> List.of(
                    MacGlassNativeShim.fireDialogCallback(0, loopId),
                    MacGlassNativeShim.fireDialogCallback(1, loopId)));
            assertEquals(List.of(GMAC_UPCALL_THREW, GMAC_UPCALL_THREW), run.value());
            assertEquals(List.of(enter.toString(), leave.toString()), run.events());
        } finally {
            MacGlassNativeShim.unregisterDialogLoop(loopId);
        }
    }

    @Test
    public void aSlotOutsideTheTableAnswersInt32Min() {
        assertEquals(Integer.MIN_VALUE, MacGlassNativeShim.fireDialogCallback(2, 1));
        assertEquals(Integer.MIN_VALUE, MacGlassNativeShim.fireDialogCallback(-1, 1));
    }

    /** {@code applyExtensions:} kept the path extension of each extension (latent bug L30). */
    @Test
    public void theExportGetsEachDescriptionAndThePathExtensionOfEachExtension() {
        ExtensionFilter[] filters = filters("Text", List.of("*.txt", "*.TXT"), "All", List.of("*.*"), "Star",
                List.of("*"), "日本😀", List.of("*.tar.gz", "noDot"));
        FilterDescription description = MacGlassNativeShim.asEventThread(
                () -> MacGlassNativeShim.describeFilters(filters, null));
        assertNull(description.pending());
        assertEquals(filter("Text") + extension("txt") + extension("TXT") + filter("All") + extension("*")
                + filter("Star") + extension("") + filter("日本😀") + extension("gz") + extension(""),
                description.lines());
    }

    @Test
    public void aNullOrEmptyFilterArrayMakesNoPopupAndLeavesWhatWasPending() {
        RuntimeException pending = new RuntimeException("pending");
        assertEquals(new FilterDescription("", pending), MacGlassNativeShim.describeFilters(null, pending));
        assertEquals(new FilterDescription("", null), MacGlassNativeShim.describeFilters(new ExtensionFilter[0],
                null));
    }

    /**
     * Off the event thread each {@code getDescription()} and {@code extensionsToArray()} throws: each new exception
     * replaced the pending one under JNI (PB11), and a filter without a description or extensions gives an empty
     * title and no file type, where the JNI crashed on {@code GetArrayLength(NULL)} (latent bug L17).
     */
    @Test
    public void aThrowingUpcallReplacesThePendingExceptionAndLeavesItsStringsOut() throws Exception {
        ExtensionFilter[] filters = filters("Text", List.of("*.txt"), "All", List.of("*.*"));
        RuntimeException pending = new RuntimeException("pending");
        Run<FilterDescription> run = onFreshThread(true, false,
                events -> MacGlassNativeShim.describeFilters(filters, pending));
        assertEquals(filter("") + filter(""), run.value().lines());
        assertNotEquals(pending, run.value().pending());
        assertEquals(OFF_EVENT_THREAD, run.value().pending().toString());
        assertEquals(List.of(), run.events());
    }

    /** A null filter, on which the JNI's {@code CallObjectMethod} crashed, is a filter without strings. */
    @Test
    public void aNullFilterIsAnEmptyTitleWithoutFileTypes() {
        ExtensionFilter[] filters = filters("Text", List.of("*.txt"), null, null);
        FilterDescription description = MacGlassNativeShim.asEventThread(
                () -> MacGlassNativeShim.describeFilters(filters, null));
        assertEquals(filter("Text") + extension("txt") + filter(""), description.lines());
        assertInstanceOf(NullPointerException.class, description.pending());
    }

    @Test
    public void anAttachedChooserReportsNothingAndConvertsEachUrlToAFile() throws Exception {
        ExtensionFilter[] filters = filters("Text", List.of("*.txt"), "All", List.of("*.*"));
        long first = MacGlassNativeShim.createFileUrl(TEXT_PATH);
        long second = MacGlassNativeShim.createFileUrl(OTHER_PATH);
        MacGlassNativeShim.cfRetain(first);
        MacGlassNativeShim.cfRetain(second);
        long[] loopIds = new long[1];
        try {
            Run<FileChooserResult> run = onFreshThread(true, true, events -> withFileNSURL(false,
                    () -> MacGlassNativeShim.fileChooser(filters, (described, loopId) -> {
                        loopIds[0] = loopId;
                        events.add(PANEL + " " + MacGlassNativeShim.isDialogLoopRegistered(loopId));
                        events.add(described);
                        return new PanelAnswer(new long[] {first, second}, 1);
                    })));
            assertEquals(List.of(PANEL + " true", filter("Text") + extension("txt") + filter("All") + extension("*")),
                    run.events());
            assertNotEquals(0, loopIds[0]);
            assertFalse(MacGlassNativeShim.isDialogLoopRegistered(loopIds[0]));
            assertEquals(List.of(new File(TEXT_PATH), new File(OTHER_PATH)), run.value().getFiles());
            assertEquals(File.class, run.value().getFiles().get(0).getClass());
            assertSame(filters[1], run.value().getExtensionFilter());
            assertEquals(1, MacGlassNativeShim.cfRetainCount(first), "the conversion released the reference");
            assertEquals(1, MacGlassNativeShim.cfRetainCount(second), "the conversion released the reference");
        } finally {
            MacGlassNativeShim.cfRelease(first);
            MacGlassNativeShim.cfRelease(second);
        }
    }

    /** The leave slot reaches {@code EventLoop.leave(null)} of the dialog's loop, which is not active here. */
    @Test
    public void theLoopOfTheCallIsTheEventLoopItMade() throws Exception {
        Run<FileChooserResult> run = onFreshThread(true, true, events -> MacGlassNativeShim.fileChooser(null,
                (described, loopId) -> {
                    events.add(PANEL + " " + MacGlassNativeShim.fireDialogCallback(1, loopId));
                    return new PanelAnswer(new long[0], -1);
                }));
        assertEquals(List.of("java.lang.IllegalStateException: The event loop object isn't active",
                PANEL + " " + GMAC_UPCALL_THREW), run.events());
        assertEquals(List.of(), run.value().getFiles());
        assertNull(run.value().getExtensionFilter());
    }

    @Test
    public void aDetachedChooserReportsAtTheFilterCheckWhenThereIsAFilter() throws Exception {
        ExtensionFilter[] filters = filters("Text", List.of("*.txt"));
        Run<FileChooserResult> run = onFreshThread(false, true, events -> MacGlassNativeShim.fileChooser(filters,
                cancelled(events, 0)));
        assertEquals(List.of(DETACHED, PANEL), run.events());
        assertEquals(List.of(), run.value().getFiles());
        assertSame(filters[0], run.value().getExtensionFilter());
    }

    @Test
    public void aDetachedChooserWithoutAFilterReportsAfterThePanel() throws Exception {
        Run<FileChooserResult> run = onFreshThread(false, true, events -> MacGlassNativeShim.fileChooser(
                new ExtensionFilter[0], cancelled(events, -1)));
        assertEquals(List.of(PANEL, DETACHED), run.events());
        assertEquals(List.of(), run.value().getFiles());
        assertNull(run.value().getExtensionFilter());
    }

    /**
     * Off the event thread, the filter upcalls replace the detached assertion's exception with theirs, reported once
     * after the filters; {@code new EventLoop()} throws, the export gets loop id 0, and what it threw is reported
     * after the panel.
     */
    @Test
    public void offTheEventThreadTheFiltersAndTheEventLoopEachReportOnce() throws Exception {
        ExtensionFilter[] filters = filters("Text", List.of("*.txt"), "All", List.of("*.*"));
        for (boolean attached : new boolean[] {true, false}) {
            long[] loopIds = {-1};
            Run<FileChooserResult> run = onFreshThread(attached, false, events -> MacGlassNativeShim.fileChooser(
                    filters, (described, loopId) -> {
                        loopIds[0] = loopId;
                        events.add(PANEL);
                        return new PanelAnswer(new long[0], 0);
                    }));
            assertEquals(List.of(OFF_EVENT_THREAD, PANEL, OFF_EVENT_THREAD), run.events(), "attached " + attached);
            assertEquals(0, loopIds[0]);
            assertSame(filters[0], run.value().getExtensionFilter());
        }
    }

    @Test
    public void aDetachedChooserWithoutAFilterReportsTheAssertionBeforeTheEventLoop() throws Exception {
        Run<FileChooserResult> run = onFreshThread(false, false, events -> MacGlassNativeShim.fileChooser(null,
                cancelled(events, -1)));
        assertEquals(List.of(PANEL, DETACHED, OFF_EVENT_THREAD), run.events());
    }

    /** {@code GetObjectArrayElement} threw and the JNI reported it, then passed a null filter (latent bug L15). */
    @Test
    public void aPopupIndexOutsideTheFiltersIsReportedAndChoosesNoFilter() throws Exception {
        ExtensionFilter[] filters = filters("Text", List.of("*.txt"), "All", List.of("*.*"));
        for (long selected : new long[] {-1, 2}) {
            Run<FileChooserResult> run = onFreshThread(true, true, events -> MacGlassNativeShim.fileChooser(filters,
                    cancelled(events, selected)));
            assertEquals(List.of(PANEL, "java.lang.ArrayIndexOutOfBoundsException: Index " + selected
                    + " out of bounds for length 2"), run.events());
            assertNull(run.value().getExtensionFilter());
        }
        Run<FileChooserResult> none = onFreshThread(true, true, events -> MacGlassNativeShim.fileChooser(null,
                cancelled(events, -1)));
        assertEquals(List.of(PANEL), none.events());
    }

    /** The popup's index is an {@code NSInteger}, which the JNI passed as a {@code jsize}. */
    @Test
    public void thePopupIndexIsTruncatedToAnInt() throws Exception {
        ExtensionFilter[] filters = filters("Text", List.of("*.txt"), "All", List.of("*.*"));
        Run<FileChooserResult> run = onFreshThread(true, true, events -> MacGlassNativeShim.fileChooser(filters,
                cancelled(events, (1L << 32) | 1)));
        assertEquals(List.of(PANEL), run.events());
        assertSame(filters[1], run.value().getExtensionFilter());
    }

    @Test
    public void theFolderChooserConvertsTheUrlItGot() throws Exception {
        long url = MacGlassNativeShim.createFileUrl(OTHER_PATH);
        Run<File> run = onFreshThread(true, true, events -> withFileNSURL(false,
                () -> MacGlassNativeShim.folderChooser((described, loopId) -> {
                    events.add(PANEL + " " + described + " " + MacGlassNativeShim.isDialogLoopRegistered(loopId));
                    return new PanelAnswer(new long[] {url}, -1);
                })));
        assertEquals(List.of(PANEL + "  true"), run.events());
        assertEquals(new File(OTHER_PATH), run.value());
    }

    @Test
    public void aDetachedFolderChooserReportsAfterThePanelThenWhatTheEventLoopThrew() throws Exception {
        Run<File> detached = onFreshThread(false, true, events -> MacGlassNativeShim.folderChooser(
                cancelled(events, -1)));
        assertEquals(List.of(PANEL, DETACHED), detached.events());
        assertNull(detached.value());
        Run<File> offEventThread = onFreshThread(false, false, events -> MacGlassNativeShim.folderChooser(
                cancelled(events, -1)));
        assertEquals(List.of(PANEL, DETACHED, OFF_EVENT_THREAD), offEventThread.events());
    }

    @Test
    public void aUrlBecomesAFileOfItsPathOnceItsReferenceIsReleased() throws Exception {
        long url = MacGlassNativeShim.createFileUrl(TEXT_PATH);
        MacGlassNativeShim.cfRetain(url);
        try {
            assertEquals(TEXT_PATH, MacGlassNativeShim.urlPath(url));
            Run<File> run = onFreshThread(true, false, events -> withFileNSURL(false,
                    () -> MacGlassNativeShim.urlToFile(url)));
            assertEquals(List.of(), run.events());
            assertEquals(File.class, run.value().getClass());
            assertEquals(TEXT_PATH, run.value().getPath());
            assertEquals(1, MacGlassNativeShim.cfRetainCount(url));
        } finally {
            MacGlassNativeShim.cfRelease(url);
        }
    }

    @Test
    public void aUrlBecomesAFileNSURLThatKeepsItsReferenceUntilDisposed() throws Exception {
        long url = MacGlassNativeShim.createFileUrl(TEXT_PATH);
        MacGlassNativeShim.cfRetain(url);
        try {
            Run<File> run = onFreshThread(true, true, events -> withFileNSURL(true,
                    () -> MacGlassNativeShim.urlToFile(url)));
            assertEquals(List.of(), run.events());
            MacFileNSURL fileNSURL = assertInstanceOf(MacFileNSURL.class, run.value());
            assertEquals(TEXT_PATH, fileNSURL.getPath());
            assertEquals(url, MacGlassNativeShim.fileNSURLPointer(fileNSURL));
            assertEquals(2, MacGlassNativeShim.cfRetainCount(url));
            MacGlassNativeShim.asEventThread(() -> {
                fileNSURL.dispose();
                return null;
            });
            assertEquals(0, MacGlassNativeShim.fileNSURLPointer(fileNSURL));
            assertEquals(1, MacGlassNativeShim.cfRetainCount(url));
        } finally {
            MacGlassNativeShim.cfRelease(url);
        }
    }

    /** The JNI retained the URL before its constructor ran, and never released it when that threw (latent bug L29). */
    @Test
    public void aFileNSURLWhoseConstructorThrowsIsNullAndKeepsTheReference() throws Exception {
        long url = MacGlassNativeShim.createFileUrl(TEXT_PATH);
        MacGlassNativeShim.cfRetain(url);
        try {
            Run<File> run = onFreshThread(true, false, events -> withFileNSURL(true,
                    () -> MacGlassNativeShim.urlToFile(url)));
            assertEquals(List.of(OFF_EVENT_THREAD), run.events());
            assertNull(run.value());
            assertEquals(2, MacGlassNativeShim.cfRetainCount(url));
        } finally {
            MacGlassNativeShim.cfRelease(url);
            MacGlassNativeShim.cfRelease(url);
        }
    }

    /** {@code [nil path]} is nil, whose {@code NewString(NULL, 0)} is {@code ""} (latent bug L28). */
    @Test
    public void theNullUrlHasTheEmptyPath() {
        assertEquals("", MacGlassNativeShim.urlPath(0));
    }

    @Test
    public void releaseDropsTheRetainCountOfTheUrlByOne() {
        long url = MacGlassNativeShim.createFileUrl(OTHER_PATH);
        MacGlassNativeShim.cfRetain(url);
        assertEquals(2, MacGlassNativeShim.cfRetainCount(url));
        MacGlassNativeShim.releaseUrl(url);
        assertEquals(1, MacGlassNativeShim.cfRetainCount(url));
        MacGlassNativeShim.releaseUrl(0);
        MacGlassNativeShim.cfRelease(url);
    }

    @Test
    public void securityScopeAccessAnswersAsCoreFoundationOnAFileUrl() {
        long url = MacGlassNativeShim.createFileUrl(OTHER_PATH);
        try {
            boolean expected = MacGlassNativeShim.cfStartAccessingSecurityScopedResource(url);
            MacGlassNativeShim.cfStopAccessingSecurityScopedResource(url);
            assertEquals(expected, MacGlassNativeShim.startAccessingSecurityScopedResource(url));
            MacGlassNativeShim.stopAccessingSecurityScopedResource(url);
            assertEquals(1, MacGlassNativeShim.cfRetainCount(url));
        } finally {
            MacGlassNativeShim.cfRelease(url);
        }
    }

    @Test
    public void aBookmarkIsACopyOfItsBlock() throws Exception {
        byte[] data = {0, 1, (byte) 0xFF, 'b', 'o', 'o', 'k'};
        Run<List<byte[]>> run = onFreshThread(true, false, events -> List.of(
                MacGlassNativeShim.bookmark(new BlockAnswer(GMAC_OK, data, data.length)),
                MacGlassNativeShim.bookmark(new BlockAnswer(GMAC_OK, new byte[0], 0))));
        assertEquals(List.of(), run.events());
        assertArrayEquals(data, run.value().get(0));
        assertArrayEquals(new byte[0], run.value().get(1));
    }

    /** The JNI's {@code NewByteArray} of a negative {@code jsize} threw, and its closing check reported it. */
    @Test
    public void aBookmarkOfNegativeLengthReportsTheJnisNegativeArraySizeException() throws Exception {
        Run<List<byte[]>> run = onFreshThread(true, false, events -> {
            List<byte[]> bookmarks = new ArrayList<>();
            bookmarks.add(MacGlassNativeShim.bookmark(new BlockAnswer(GMAC_ERR_NO_DATA, null, -5)));
            bookmarks.add(MacGlassNativeShim.bookmark(new BlockAnswer(GMAC_ERR_NO_DATA, null, 0)));
            return bookmarks;
        });
        assertEquals(List.of("java.lang.NegativeArraySizeException: -5"), run.events());
        assertNull(run.value().get(0));
        assertNull(run.value().get(1));
    }

    /** {@code GetPrimitiveArrayCritical} answered a non-NULL address even for an empty array. */
    @Test
    public void aBookmarkIsResolvedFromACopyOfItsDataThatIsNeverNull() throws Exception {
        List<String> seen = new ArrayList<>();
        for (byte[] data : List.of(new byte[0], new byte[] {1, 2, 3})) {
            Run<MacFileNSURL> run = onFreshThread(true, true, events -> MacGlassNativeShim.fromBookmark(data,
                    (address, copy) -> {
                        seen.add((address != 0) + " " + Arrays.toString(copy));
                        return new Resolution(GMAC_ERR_NO_DATA, 0);
                    }));
            assertNull(run.value());
            assertEquals(List.of(), run.events());
        }
        assertEquals(List.of("true []", "true [1, 2, 3]"), seen);
    }

    @Test
    public void aResolvedBookmarkIsAFileNSURLOfTheUrl() throws Exception {
        long url = MacGlassNativeShim.createFileUrl(OTHER_PATH);
        Run<MacFileNSURL> run = onFreshThread(true, true, events -> withFileNSURL(true,
                () -> MacGlassNativeShim.fromBookmark(new byte[] {7},
                        (address, copy) -> new Resolution(GMAC_OK, url))));
        assertEquals(List.of(), run.events());
        assertEquals(OTHER_PATH, run.value().getPath());
        assertEquals(url, MacGlassNativeShim.fileNSURLPointer(run.value()));
        assertEquals(1, MacGlassNativeShim.cfRetainCount(url));
        MacGlassNativeShim.asEventThread(() -> {
            run.value().dispose();
            return null;
        });
    }

    /** A resolution that answered nil without an error is a {@code MacFileNSURL} of {@code ""} (latent bug L28). */
    @Test
    public void aResolutionWithoutAUrlIsAFileNSURLOfTheEmptyPath() throws Exception {
        Run<MacFileNSURL> run = onFreshThread(true, true, events -> withFileNSURL(true,
                () -> MacGlassNativeShim.fromBookmark(new byte[] {7}, (address, copy) -> new Resolution(GMAC_OK, 0))));
        assertEquals(List.of(), run.events());
        assertEquals("", run.value().getPath());
        assertEquals(0, MacGlassNativeShim.fileNSURLPointer(run.value()));
    }

    /**
     * A plain {@code File}, which the JNI answered from a native declared to answer a {@code MacFileNSURL} once the
     * system property had turned false (latent bug L16), is null, and the URL is released, as for any {@code File}.
     */
    @Test
    public void aResolvedBookmarkThatConvertsToAPlainFileIsNull() throws Exception {
        long url = MacGlassNativeShim.createFileUrl(OTHER_PATH);
        MacGlassNativeShim.cfRetain(url);
        try {
            Run<MacFileNSURL> run = onFreshThread(true, true, events -> withFileNSURL(false,
                    () -> MacGlassNativeShim.fromBookmark(new byte[] {7},
                            (address, copy) -> new Resolution(GMAC_OK, url))));
            assertEquals(List.of(), run.events());
            assertNull(run.value());
            assertEquals(1, MacGlassNativeShim.cfRetainCount(url));
        } finally {
            MacGlassNativeShim.cfRelease(url);
        }
    }

    private static MacGlassNativeShim.PanelStandIn cancelled(List<String> events, long selected) {
        return (described, loopId) -> {
            events.add(PANEL);
            return new PanelAnswer(new long[0], selected);
        };
    }

    /** Filters from description and extension-list pairs, made on the event thread; a null description gives null. */
    private static ExtensionFilter[] filters(Object... pairs) {
        List<String> descriptions = new ArrayList<>();
        List<List<String>> extensions = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            descriptions.add((String) pairs[i]);
            @SuppressWarnings("unchecked")
            List<String> list = (List<String>) pairs[i + 1];
            extensions.add(list);
        }
        return MacGlassNativeShim.extensionFilters(descriptions.toArray(new String[0]), extensions);
    }

    /** The hook's line of a popup title. */
    private static String filter(String title) {
        return "filter" + units(title) + "\n";
    }

    /** The hook's line of a file type. */
    private static String extension(String type) {
        return "extension" + units(type) + "\n";
    }

    private static String units(String s) {
        StringBuilder units = new StringBuilder(" ").append(s.length()).append(':');
        for (char c : s.toCharArray()) {
            units.append("%04x".formatted((int) c));
        }
        return units.toString();
    }

    private static List<String> names(StructLayout layout) {
        return layout.memberLayouts().stream().map(member -> member.name().orElseThrow()).toList();
    }

    /** Runs {@code body} with {@value #ENABLE_FILE_NSURL} set to {@code enabled}, and the old value restored after. */
    private static <T> T withFileNSURL(boolean enabled, Supplier<T> body) {
        String previous = System.getProperty(ENABLE_FILE_NSURL);
        System.setProperty(ENABLE_FILE_NSURL, String.valueOf(enabled));
        try {
            return body.get();
        } finally {
            if (previous == null) {
                System.clearProperty(ENABLE_FILE_NSURL);
            } else {
                System.setProperty(ENABLE_FILE_NSURL, previous);
            }
        }
    }

    /**
     * Runs {@code call} on a new platform thread with the main thread's attached flag set as {@code attached}, as
     * Glass's event thread when {@code eventThread}; restores the flag after. {@code call} gets the list the reports
     * go to, so that a stand-in can add where it ran.
     */
    private static <T> Run<T> onFreshThread(boolean attached, boolean eventThread, Function<List<String>, T> call)
            throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        int previous = MacGlassNativeShim.setMainJavaAttached(attached ? 1 : 0);
        try {
            FutureTask<T> task = new FutureTask<>(() -> eventThread
                    ? MacGlassNativeShim.asEventThread(() -> call.apply(events))
                    : call.apply(events));
            Thread thread = Thread.ofPlatform().name(THREAD_NAME).unstarted(task);
            thread.setUncaughtExceptionHandler((t, e) -> events.add(e.toString()));
            thread.start();
            return new Run<>(task.get(30, TimeUnit.SECONDS), List.copyOf(events));
        } finally {
            MacGlassNativeShim.setMainJavaAttached(previous);
        }
    }
}
