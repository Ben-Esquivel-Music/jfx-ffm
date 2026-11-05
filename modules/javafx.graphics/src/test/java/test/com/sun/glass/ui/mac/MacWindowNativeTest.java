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

import com.sun.glass.ui.Screen;
import com.sun.glass.ui.mac.MacGlassNativeShim;
import com.sun.glass.ui.mac.MacGlassNativeShim.WindowCallbackRecorder;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code MacWindow} over {@code GmacWindowCallbacks} and the {@code gmac_window_*} exports: the 10-slot table, each
 * slot's arguments and exception sink, the registry of window ids, the frames of the window calls, and
 * {@code _isRightToLeftLayoutDirection}'s CoreFoundation chain against the {@code NSLocale} body it replaced.
 * <p>
 * No test makes a window or calls a {@code gmac_window_*} export. Slots are dialled through
 * {@code gmac_test_fire_window_callback} for ids filed with a recording stand-in, and the window calls are reached
 * with the NULL window or with a stand-in where the export would be. {@code MacWindow}, whose initialization installs
 * the table, is never initialized. AppKit's {@code NSApp} is nil before the first call into the library and after the
 * last, here and in every child JVM.
 */
@EnabledOnOs(OS.MAC)
public class MacWindowNativeTest {

    private static final String DETACHED = "java.lang.RuntimeException: Main Java thread is detached.";

    private static final int GMAC_UPCALL_OK = 0;

    private static final int SLOT_COUNT = 10;

    private static final int NOTIFY_CLOSE = 4;
    private static final int NOTIFY_DISPOSE = 9;

    private static final long LANGUAGE_DIRECTION_LEFT_TO_RIGHT = 1;
    private static final long LANGUAGE_DIRECTION_RIGHT_TO_LEFT = 2;

    private static final String CALLER = "MacWindowNativeTest-caller";

    private static final long FAKE_WINDOW = 0x7E57_0000_1234L;

    private static final Path HEADER = Path.of("src/main/native-glass/mac/glass_mac_api.h");

    private static final Pattern WINDOW_CALLBACKS = Pattern.compile(
            "typedef\\s+struct\\s+GmacWindowCallbacks\\s*\\{(.*?)}\\s*GmacWindowCallbacks\\s*;", Pattern.DOTALL);

    private static final Pattern SLOT = Pattern.compile("\\(\\s*\\*\\s*(\\w+)\\s*\\)");

    private static final Pattern COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    /** What each slot of {@code gmac_test_fire_window_callback} reaches, in slot order, slot 9 aside. */
    private static final List<String> HOOK_CALLS = List.of(
            "notifyMove(1001, 1002, true)",
            "notifyResize(1001, 1002, 1003)",
            "notifyMoveToAnotherScreen",
            "notifyScaleChanged(1.5, 2.5, 3.5, 4.5)",
            "notifyClose()",
            "notifyFocus(1001)",
            "notifyFocusUngrab()",
            "notifyFocusDisabled()",
            "notifyDestroy()");

    /**
     * The {@code -AppleLanguages} lists the child JVMs run with, and the direction of the first one's language. The
     * region and script variants keep their language's direction, and a list is read at its first entry only.
     */
    private static final Map<String, Long> PREFERRED_LANGUAGES = preferredLanguages();

    private int attachedBefore;

    @BeforeAll
    static void loadGlass() {
        MacGlassNatives.require();
        assertTrue(MacGlassNativeShim.nsAppIsNil(), "an NSApplication exists before the first call into libglass");
        MacGlassNativeShim.loadLibrary();
        assertFalse(MacGlassNativeShim.isMainThread(), "the calls must run off the main thread to be detachable");
    }

    @AfterAll
    static void noApplicationExistsAfterTheLastCall() {
        assertTrue(MacGlassNativeShim.nsAppIsNil(), "an NSApplication exists after the last call into libglass");
    }

    @BeforeEach
    void installTheTable() {
        attachedBefore = MacGlassNativeShim.setMainJavaAttached(1);
        MacGlassNativeShim.installWindowCallbacks();
    }

    @AfterEach
    void uninstallTheTableAndRestoreTheAttachedFlag() {
        MacGlassNativeShim.installWindowTable(MemorySegment.NULL);
        MacGlassNativeShim.setMainJavaAttached(attachedBefore);
    }

    @Test
    public void theTableIsTheTenAddressesOfTheHeaderInItsOrder() throws IOException {
        StructLayout table = MacGlassNativeShim.layouts().get("GmacWindowCallbacks");
        assertEquals(80, table.byteSize());
        assertEquals(80, MacGlassNativeShim.sizeOf("GmacWindowCallbacks"));
        List<String> members = table.memberLayouts().stream().map(member -> member.name().orElseThrow()).toList();
        assertEquals(headerSlotNames(), members);
        assertEquals(MacGlassNativeShim.windowSlotNames(), members);
        assertEquals(SLOT_COUNT, members.size());
    }

    @Test
    public void everySlotReachesTheWindowOfItsIdWithTheHookArguments() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        WindowCallbackRecorder window = new WindowCallbackRecorder(events, false);
        long windowId = MacGlassNativeShim.registerWindow(window);
        List<Integer> statuses = onFreshThread(events, () -> fireSlotsUpToDestroy(windowId));
        assertEquals(List.of(0, 0, 0, 0, 0, 0, 0, 0, 0), statuses);
        assertEquals(HOOK_CALLS, events);
        assertTrue(MacGlassNativeShim.isWindowRegistered(windowId), "only notify_dispose forgets an id");
    }

    @Test
    public void theScreenOfSlot2IsBuiltFromEveryMemberOfTheScreenInfo() throws Exception {
        WindowCallbackRecorder window = new WindowCallbackRecorder(new CopyOnWriteArrayList<>(), false);
        long windowId = MacGlassNativeShim.registerWindow(window);
        assertEquals(GMAC_UPCALL_OK, MacGlassNativeShim.fireWindowCallback(2, windowId));
        assertEquals(1, window.screens().size());
        Screen screen = window.screens().getFirst();
        assertEquals(0x0102030405060708L, screen.getNativeScreen());
        assertEquals(List.of(1001, 1002, 1003, 1004, 1005, 1006, 1007, 1008, 1009, 1010, 1011, 1012, 1013, 1014,
                        1015),
                List.of(screen.getDepth(), screen.getX(), screen.getY(), screen.getWidth(), screen.getHeight(),
                        screen.getPlatformX(), screen.getPlatformY(), screen.getPlatformWidth(),
                        screen.getPlatformHeight(), screen.getVisibleX(), screen.getVisibleY(),
                        screen.getVisibleWidth(), screen.getVisibleHeight(), screen.getResolutionX(),
                        screen.getResolutionY()));
        assertEquals(List.of(1.5f, 2.5f, 3.5f, 4.5f), List.of(screen.getPlatformScaleX(), screen.getPlatformScaleY(),
                screen.getRecommendedOutputScaleX(), screen.getRecommendedOutputScaleY()));
    }

    @Test
    public void whatASlotThrowsIsReportedAtOnceOnTheMainThreadAndAnsweredThrew() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        WindowCallbackRecorder window = new WindowCallbackRecorder(events, true);
        long windowId = MacGlassNativeShim.registerWindow(window);
        List<Integer> statuses = onFreshThread(events, () -> fireSlotsUpToDestroy(windowId));
        assertEquals(List.of(1, 1, 1, 1, 1, 1, 1, 1, 1), statuses);
        List<String> expected = new ArrayList<>();
        for (String call : HOOK_CALLS) {
            expected.add(call);
            expected.add("report java.lang.IllegalStateException: " + call + " on " + CALLER);
        }
        assertEquals(expected, events);
    }

    @Test
    public void disposeForgetsOnlyItsIdAndNoSlotReachesItAfterwards() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        long disposed = MacGlassNativeShim.registerWindow(new WindowCallbackRecorder(events, true));
        List<String> otherEvents = new CopyOnWriteArrayList<>();
        long other = MacGlassNativeShim.registerWindow(new WindowCallbackRecorder(otherEvents, false));
        int status = onFreshThread(events, () -> MacGlassNativeShim.fireWindowCallback(NOTIFY_DISPOSE, disposed));
        assertEquals(GMAC_UPCALL_OK, status);
        assertFalse(MacGlassNativeShim.isWindowRegistered(disposed));
        assertTrue(MacGlassNativeShim.isWindowRegistered(other));
        List<Integer> statuses = onFreshThread(events, () -> fireEverySlot(disposed));
        assertEquals(List.of(0, 0, 0, 0, 0, 0, 0, 0, 0, 0), statuses);
        assertEquals(List.of(), events);
        assertEquals(GMAC_UPCALL_OK, MacGlassNativeShim.fireWindowCallback(NOTIFY_CLOSE, other));
        assertEquals(List.of("notifyClose()"), otherEvents);
    }

    @Test
    public void anIdTheRegistryNeverHeldRunsNothing() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        for (long windowId : new long[] {0L, -1L, Long.MAX_VALUE}) {
            List<Integer> statuses = onFreshThread(events, () -> fireEverySlot(windowId));
            assertEquals(List.of(0, 0, 0, 0, 0, 0, 0, 0, 0, 0), statuses, "window id " + windowId);
        }
        assertEquals(List.of(), events);
    }

    @Test
    public void idsAreNonzeroDistinctAndKeptUntilDisposed() {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ids.add(MacGlassNativeShim.registerWindow(new WindowCallbackRecorder(new CopyOnWriteArrayList<>(),
                    false)));
        }
        assertEquals(3, new HashSet<>(ids).size(), ids.toString());
        for (long windowId : ids) {
            assertNotEquals(0L, windowId);
            assertTrue(MacGlassNativeShim.isWindowRegistered(windowId));
            assertEquals(GMAC_UPCALL_OK, MacGlassNativeShim.fireWindowCallback(NOTIFY_DISPOSE, windowId));
            assertFalse(MacGlassNativeShim.isWindowRegistered(windowId));
        }
    }

    @Test
    public void createFilesTheWindowBeforeItsDowncallWhoseSlotsReachItAndAnswersTheWindow() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        WindowCallbackRecorder window = new WindowCallbackRecorder(events, false);
        List<Long> filed = new CopyOnWriteArrayList<>();
        long created = onFreshThread(events, () -> MacGlassNativeShim.createWindow(window, 0x11L, 0x22L, 0x33,
                (owner, screen, styleMask, windowId) -> {
                    filed.add(windowId);
                    boolean registered = MacGlassNativeShim.isWindowRegistered(windowId);
                    events.add("create 0x" + Long.toHexString(owner) + " 0x" + Long.toHexString(screen) + " 0x"
                            + Integer.toHexString(styleMask) + " filed " + registered);
                    events.add("close " + MacGlassNativeShim.fireWindowCallback(NOTIFY_CLOSE, windowId));
                    return FAKE_WINDOW;
                }));
        assertEquals(FAKE_WINDOW, created);
        assertEquals(List.of("create 0x11 0x22 0x33 filed true", "notifyClose()", "close " + GMAC_UPCALL_OK), events);
        long windowId = filed.getFirst();
        assertNotEquals(0L, windowId);
        assertTrue(MacGlassNativeShim.isWindowRegistered(windowId), "only notify_dispose forgets an id");
        assertEquals(GMAC_UPCALL_OK, MacGlassNativeShim.fireWindowCallback(NOTIFY_DISPOSE, windowId));
        assertFalse(MacGlassNativeShim.isWindowRegistered(windowId));
    }

    @Test
    public void aDetachedCreateMakesItsWindowThenReportsOnceAndNeverThrows() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        WindowCallbackRecorder window = new WindowCallbackRecorder(events, false);
        MacGlassNativeShim.setMainJavaAttached(0);
        long created = onFreshThread(events, () -> MacGlassNativeShim.createWindow(window, 0L, 0L, 0,
                (owner, screen, styleMask, windowId) -> {
                    events.add("create filed " + MacGlassNativeShim.isWindowRegistered(windowId));
                    return FAKE_WINDOW;
                }));
        assertEquals(FAKE_WINDOW, created);
        assertEquals(List.of("create filed true", "report " + DETACHED + " on " + CALLER), events);
    }

    @Test
    public void withoutATableOrItsSlotNothingIsDialled() {
        List<String> events = new CopyOnWriteArrayList<>();
        long windowId = MacGlassNativeShim.registerWindow(new WindowCallbackRecorder(events, false));
        try (Arena arena = Arena.ofConfined()) {
            assertEquals(0, MacGlassNativeShim.installWindowTable(arena.allocate(ADDRESS, SLOT_COUNT)));
            assertEquals(List.of(-1, -1, -1, -1, -1, -1, -1, -1, -1, -1), fireEverySlot(windowId));
        }
        assertEquals(0, MacGlassNativeShim.installWindowTable(MemorySegment.NULL));
        assertEquals(List.of(-1, -1, -1, -1, -1, -1, -1, -1, -1, -1), fireEverySlot(windowId));
        MacGlassNativeShim.installWindowCallbacks();
        assertEquals(Integer.MIN_VALUE, MacGlassNativeShim.fireWindowCallback(-1, windowId));
        assertEquals(Integer.MIN_VALUE, MacGlassNativeShim.fireWindowCallback(SLOT_COUNT, windowId));
        assertEquals(List.of(), events);
        assertTrue(MacGlassNativeShim.isWindowRegistered(windowId), "notify_dispose never ran");
    }

    @Test
    public void theLibraryDialsItsOwnCopyOfTheTable() {
        List<String> events = new CopyOnWriteArrayList<>();
        long windowId = MacGlassNativeShim.registerWindow(new WindowCallbackRecorder(events, false));
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment table = arena.allocate(ADDRESS, SLOT_COUNT);
            for (int slot = 0; slot < SLOT_COUNT; slot++) {
                table.setAtIndex(ADDRESS, slot, MacGlassNativeShim.windowCallbackStub(slot));
            }
            assertEquals(0, MacGlassNativeShim.installWindowTable(table));
            table.fill((byte) 0);
        }
        assertEquals(GMAC_UPCALL_OK, MacGlassNativeShim.fireWindowCallback(NOTIFY_CLOSE, windowId));
        assertEquals(List.of("notifyClose()"), events);
    }

    @Test
    public void aWindowCallOnTheNullWindowReachesNeitherItsExportNorTheAssertion() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        MacGlassNativeShim.setMainJavaAttached(0);
        Map<String, Object> answers = onFreshThread(events, MacGlassNativeShim::windowCallsOnNullWindow);
        int frameAnswer = onFreshThread(events, () -> {
            MacGlassNativeShim.windowRun(0L, window -> events.add("downcall"));
            MacGlassNativeShim.windowRunUnasserted(0L, window -> events.add("downcall"));
            return MacGlassNativeShim.windowCall(0L, window -> {
                events.add("downcall");
                return 1;
            });
        });
        assertEquals(List.of(), events);
        assertEquals(0, frameAnswer);
        assertEquals(27, answers.size(), "every window export but gmac_window_create: " + answers.keySet());
        answers.forEach((call, answer) -> assertTrue(answer.equals(Boolean.FALSE) || answer.equals("void"),
                call + " answered " + answer));
    }

    @Test
    public void aDetachedWindowCallMakesItsDowncallThenReportsAndNeverThrows() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        MacGlassNativeShim.setMainJavaAttached(0);
        int answer = onFreshThread(events, () -> {
            assertTrue(MacGlassNativeShim.mainThreadDetached());
            int answered = MacGlassNativeShim.windowCall(FAKE_WINDOW, window -> {
                events.add("call 0x" + Long.toHexString(window));
                return 7;
            });
            MacGlassNativeShim.windowRun(FAKE_WINDOW, window -> events.add("run 0x" + Long.toHexString(window)));
            return answered;
        });
        assertEquals(7, answer);
        String report = "report " + DETACHED + " on " + CALLER;
        assertEquals(List.of("call 0x" + Long.toHexString(FAKE_WINDOW), report,
                "run 0x" + Long.toHexString(FAKE_WINDOW), report), events);
    }

    @Test
    public void anAttachedWindowCallReportsNothing() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        int answer = onFreshThread(events, () -> {
            assertFalse(MacGlassNativeShim.mainThreadDetached());
            MacGlassNativeShim.windowRun(FAKE_WINDOW, window -> events.add("run"));
            return MacGlassNativeShim.windowCall(FAKE_WINDOW, window -> 1);
        });
        assertEquals(1, answer);
        assertEquals(List.of("run"), events);
    }

    @Test
    public void toFrontHasNoAssertion() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        MacGlassNativeShim.setMainJavaAttached(0);
        onFreshThread(events, () -> {
            MacGlassNativeShim.windowRunUnasserted(FAKE_WINDOW, window -> events.add("toFront 0x"
                    + Long.toHexString(window)));
            return null;
        });
        assertEquals(List.of("toFront 0x" + Long.toHexString(FAKE_WINDOW)), events);
    }

    @Test
    public void theIconCrossesAsTheBaseAddressOfADirectBufferWhateverItsPositionAndElseAsNull() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pixels = arena.allocate(64);
            ByteBuffer direct = pixels.asByteBuffer();
            direct.position(12).limit(20);
            assertEquals(pixels.address(), MacGlassNativeShim.windowIconPixels(direct));
            assertEquals(pixels.address(), MacGlassNativeShim.windowIconPixels(direct.asReadOnlyBuffer()));
            ByteBuffer slice = pixels.asByteBuffer().slice(16, 32);
            assertEquals(pixels.address() + 16, MacGlassNativeShim.windowIconPixels(slice));
            assertEquals(12, direct.position(), "the caller's buffer is left as it was");
        }
        assertEquals(0L, MacGlassNativeShim.windowIconPixels(ByteBuffer.allocate(64)));
        assertEquals(0L, MacGlassNativeShim.windowIconPixels(null));
    }

    @Test
    public void theLayoutDirectionRunsInAnAutoreleasePoolAsTheNSLocaleBodyDid() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        List<Integer> oracle = onFreshThread(events, () -> {
            int before = MacGlassNativeShim.poolDepth();
            MacGlassNativeShim.nslocaleLanguageDirection();
            return List.of(before, MacGlassNativeShim.poolDepth());
        });
        List<Integer> java = onFreshThread(events, () -> {
            int before = MacGlassNativeShim.poolDepth();
            MacGlassNativeShim.isRightToLeftLayoutDirection();
            return List.of(before, MacGlassNativeShim.poolDepth());
        });
        assertEquals(List.of(-1, 0), oracle, "a fresh thread has no pool data until its first pool, then depth 0");
        assertEquals(oracle, java);
        assertEquals(List.of(), events);
    }

    @Test
    public void theLayoutDirectionIsTheRightToLeftOfTheChainAndReportsOnceWhenDetached() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        long direction = MacGlassNativeShim.preferredLanguageDirection();
        boolean attached = onFreshThread(events, MacGlassNativeShim::isRightToLeftLayoutDirection);
        assertEquals(List.of(), events);
        MacGlassNativeShim.setMainJavaAttached(0);
        boolean detached = onFreshThread(events, MacGlassNativeShim::isRightToLeftLayoutDirection);
        assertEquals(List.of("report " + DETACHED + " on " + CALLER), events);
        assertEquals(direction == MacGlassNativeShim.languageDirectionRightToLeft(), attached);
        assertEquals(attached, detached);
        assertEquals(LANGUAGE_DIRECTION_RIGHT_TO_LEFT, MacGlassNativeShim.languageDirectionRightToLeft());
    }

    @Test
    public void theCoreFoundationChainAnswersWhatTheNSLocaleBodyAnsweredForEachPreferredLanguage() throws Exception {
        Map<String, Future<Map<String, String>>> children = new LinkedHashMap<>();
        try (ExecutorService launcher = Executors.newFixedThreadPool(4)) {
            int index = 0;
            for (String languages : PREFERRED_LANGUAGES.keySet()) {
                String name = "LayoutDirectionChild-" + index++;
                children.put(languages, launcher.submit(() -> MacGlassNativeTest.runChild(LayoutDirectionChild.class,
                        name, List.of("-AppleLanguages", languages))));
            }
            for (Map.Entry<String, Future<Map<String, String>>> child : children.entrySet()) {
                String languages = child.getKey();
                Map<String, String> result = child.getValue().get(120, TimeUnit.SECONDS);
                long expected = PREFERRED_LANGUAGES.get(languages);
                String what = "-AppleLanguages " + languages;
                assertEquals(result.get("nsLocale"), result.get("coreFoundation"), what + ": CF chain vs NSLocale");
                assertEquals(String.valueOf(expected), result.get("nsLocale"),
                        what + ": the direction of its language");
                assertEquals(String.valueOf(expected == LANGUAGE_DIRECTION_RIGHT_TO_LEFT), result.get("rightToLeft"),
                        what + ": _isRightToLeftLayoutDirection");
                assertEquals("", result.get("reports"), what + ": the child ran attached");
            }
        }
    }

    /**
     * Answers the CoreFoundation chain, the {@code NSLocale} oracle and {@code _isRightToLeftLayoutDirection} under
     * the {@code -AppleLanguages} its command line gives {@code NSUserDefaults} and {@code CFPreferences}. Writes
     * them to the file {@code args[0]}, and nothing else if an {@code NSApplication} exists before its first call into
     * the library.
     */
    public static final class LayoutDirectionChild {

        public static void main(String[] args) throws Exception {
            Path result = Path.of(args[0]);
            if (!MacGlassNativeShim.nsAppIsNil()) {
                Files.writeString(result, "nsAppNilBefore=false\n", StandardCharsets.UTF_8);
                return;
            }
            MacGlassNativeShim.loadLibrary();
            List<String> reports = new CopyOnWriteArrayList<>();
            Thread.currentThread().setUncaughtExceptionHandler((thread, e) -> reports.add(String.valueOf(e)));
            MacGlassNativeShim.setMainJavaAttached(1);
            long coreFoundation = MacGlassNativeShim.preferredLanguageDirection();
            int nsLocale = MacGlassNativeShim.nslocaleLanguageDirection();
            boolean rightToLeft = MacGlassNativeShim.isRightToLeftLayoutDirection();
            boolean nsAppNilAfter = MacGlassNativeShim.nsAppIsNil();
            Files.writeString(result, "coreFoundation=" + coreFoundation
                    + "\nnsLocale=" + nsLocale
                    + "\nrightToLeft=" + rightToLeft
                    + "\nreports=" + String.join(" | ", reports)
                    + "\nnsAppNilBefore=true"
                    + "\nnsAppNilAfter=" + nsAppNilAfter + "\n", StandardCharsets.UTF_8);
        }
    }

    private static Map<String, Long> preferredLanguages() {
        Map<String, Long> languages = new LinkedHashMap<>();
        for (String language : List.of("en", "en-US", "en-GB", "ja", "ja-JP", "zh-Hans", "zh-Hans-CN", "zh-Hant-TW")) {
            languages.put("(" + language + ")", LANGUAGE_DIRECTION_LEFT_TO_RIGHT);
        }
        for (String language : List.of("ar", "ar-SA", "ar-EG", "he", "he-IL", "fa", "fa-IR", "ur", "ur-PK")) {
            languages.put("(" + language + ")", LANGUAGE_DIRECTION_RIGHT_TO_LEFT);
        }
        languages.put("(ar, en)", LANGUAGE_DIRECTION_RIGHT_TO_LEFT);
        languages.put("(en, ar)", LANGUAGE_DIRECTION_LEFT_TO_RIGHT);
        return languages;
    }

    private static List<Integer> fireSlotsUpToDestroy(long windowId) {
        List<Integer> statuses = new ArrayList<>();
        for (int slot = 0; slot < NOTIFY_DISPOSE; slot++) {
            statuses.add(MacGlassNativeShim.fireWindowCallback(slot, windowId));
        }
        return statuses;
    }

    private static List<Integer> fireEverySlot(long windowId) {
        List<Integer> statuses = new ArrayList<>();
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            statuses.add(MacGlassNativeShim.fireWindowCallback(slot, windowId));
        }
        return statuses;
    }

    private static List<String> headerSlotNames() throws IOException {
        assertTrue(Files.isRegularFile(HEADER), "run from modules/javafx.graphics: no " + HEADER.toAbsolutePath());
        String header = COMMENT.matcher(Files.readString(HEADER)).replaceAll(" ");
        Matcher table = WINDOW_CALLBACKS.matcher(header);
        assertTrue(table.find(), "no GmacWindowCallbacks in " + HEADER);
        List<String> names = new ArrayList<>();
        Matcher slot = SLOT.matcher(table.group(1));
        while (slot.find()) {
            names.add(slot.group(1));
        }
        return names;
    }

    /**
     * Runs {@code call} on a new platform thread named {@link #CALLER}, standing for the main thread, whose reports
     * go to {@code events}.
     */
    private static <T> T onFreshThread(List<String> events, Callable<T> call) throws Exception {
        FutureTask<T> task = new FutureTask<>(call);
        Thread thread = Thread.ofPlatform().name(CALLER).unstarted(task);
        thread.setUncaughtExceptionHandler((t, e) -> events.add("report " + e + " on " + t.getName()));
        thread.start();
        return task.get(30, TimeUnit.SECONDS);
    }
}
