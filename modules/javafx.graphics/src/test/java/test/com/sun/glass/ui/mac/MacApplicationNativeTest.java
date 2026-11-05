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

import com.sun.glass.events.KeyEvent;
import com.sun.glass.ui.Screen;
import com.sun.glass.ui.mac.MacGlassNativeShim;
import com.sun.glass.ui.mac.MacGlassNativeShim.AppCallbackRecorder;
import com.sun.glass.ui.mac.MacGlassNativeShim.UriOpener;
import java.io.ByteArrayOutputStream;
import java.lang.foreign.AddressLayout;
import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.PaddingLayout;
import java.lang.foreign.StructLayout;
import java.lang.foreign.ValueLayout;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Java side of {@code MacApplication}'s FFM binding: the struct layouts against the C compiler's, the
 * {@code GmacAppCallbacks} table dialled through {@code gmac_test_fire_app_callback} into a recorder, with the
 * arguments the header fixes and the exception sink of each slot, the runnable registry, the {@code Screen} builder,
 * the nested-loop value, the {@code _isKeyLocked} switch that moved to Java, the detached-thread outcome of
 * {@code _openURI} with a stand-in for its downcall, the bootstrap's {@code gmac_app_init} and the delegate
 * {@code gmac_app_create_delegate} makes, read back before any run loop could use it, and the downcalls that touch no
 * AppKit without a run loop. Like {@link MacGlassNativeTest}, it proves that no {@code NSApplication} exists before
 * its first call into the library and after its last.
 */
@EnabledOnOs(OS.MAC)
public class MacApplicationNativeTest {

    private static final int GMAC_OK = 0;
    private static final int GMAC_ERR_NO_DATA = -1;
    private static final int GMAC_ERR_UNSUPPORTED_OS = -2;
    private static final int SLOT_COUNT = 21;

    /** What the C's 41 F0 9F 98 80 42 gives through NewStringUTF: F0 stands for itself and the B is lost. */
    private static final String SET_NAME_DECODED = "Að\u009F";

    /** C3 A9 F0 9F 98 80 through NewStringUTF: U+00E9, then F0 for itself, with the continuation bytes uncounted. */
    private static final String THIRD_FILE_DECODED = "éð";

    private static final String DETACHED = "Main Java thread is detached.";

    private static final String OPENED_URI = "https://openjdk.org/";

    /** {@code NSEventModifierFlagCapsLock}, the bit the JNI's {@code _isKeyLocked} masked. */
    private static final long CAPS_LOCK_FLAG = 1L << 16;

    /** What dialling every slot answers when each target throws: 15 builds its String[], so it replies success. */
    private static final String EVERY_SLOT_THREW = "1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,0,1,1,1,1,1";

    private record Fired(int status, List<Throwable> reported) {
    }

    private record Answered<T>(T value, List<Throwable> reported) {
    }

    /** What an open answered, null when it threw {@code thrown}, and what it reported. */
    private record Opened(Integer status, RuntimeException thrown, List<Throwable> reported) {
    }

    @BeforeAll
    static void loadGlass() {
        MacGlassNatives.require();
        assertTrue(MacGlassNativeShim.nsAppIsNil(), "an NSApplication exists before the first call into libglass");
        MacGlassNativeShim.loadLibrary();
    }

    @AfterEach
    void uninstall() {
        MacGlassNativeShim.uninstallAppCallbacks();
    }

    @AfterAll
    static void noApplicationExistsAfterTheLastCall() {
        assertTrue(MacGlassNativeShim.nsAppIsNil(), "an NSApplication exists after the last call into libglass");
    }

    @Test
    public void theLayoutsHaveTheSizesOfTheHeader() {
        Map<String, StructLayout> layouts = MacGlassNativeShim.layouts();
        assertEquals(Map.of("GmacAppCallbacks", 168L, "GmacScreenInfo", 88L, "GmacColor", 24L, "GmacPref", 48L),
                Map.of("GmacAppCallbacks", layouts.get("GmacAppCallbacks").byteSize(),
                        "GmacScreenInfo", layouts.get("GmacScreenInfo").byteSize(),
                        "GmacColor", layouts.get("GmacColor").byteSize(),
                        "GmacPref", layouts.get("GmacPref").byteSize()));
        for (String struct : layouts.keySet()) {
            assertEquals(layouts.get(struct).byteSize(), MacGlassNativeShim.sizeOf(struct), "sizeof " + struct);
        }
    }

    @Test
    public void everyMemberIsWhereTheCompilerPutIt() {
        for (String struct : List.of("GmacScreenInfo", "GmacColor", "GmacPref")) {
            StructLayout layout = MacGlassNativeShim.layouts().get(struct);
            List<String> members = memberNames(layout);
            for (int member = 0; member < members.size(); member++) {
                String name = members.get(member);
                assertEquals(layout.byteOffset(PathElement.groupElement(name)),
                        MacGlassNativeShim.offsetOf(struct, member), struct + "." + name);
            }
            assertEquals(-1, MacGlassNativeShim.offsetOf(struct, members.size()), struct + " past its last member");
            assertEquals(-1, MacGlassNativeShim.offsetOf(struct, -1), struct + " before its first member");
        }
        MacGlassNativeShim.checkLayouts();
    }

    @Test
    public void theTableHasOneAddressPerSlotInHeaderOrder() {
        StructLayout table = MacGlassNativeShim.layouts().get("GmacAppCallbacks");
        assertEquals(MacGlassNativeShim.appSlotNames(), memberNames(table));
        assertEquals(SLOT_COUNT, table.memberLayouts().size());
    }

    @Test
    public void everyMethodTheApplicationReceiverCallsResolves() {
        MacGlassNativeShim.resolveApplicationTargets();
    }

    @Test
    public void eachLifecycleSlotCallsTheNotificationOfItsName() {
        List<String> slots = MacGlassNativeShim.appSlotNames().subList(4, 15);
        assertEquals(slots.stream().map(MacApplicationNativeTest::camelCase).toList(),
                MacGlassNativeShim.applicationNotificationTargets());
    }

    @Test
    public void noSlotIsDialledBeforeATableIsInstalled() throws Exception {
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            assertEquals(GMAC_ERR_NO_DATA, fire(slot).status(), "slot " + slot);
        }
        for (int slot : new int[] {Integer.MIN_VALUE, -1, SLOT_COUNT, Integer.MAX_VALUE}) {
            assertEquals(Integer.MIN_VALUE, MacGlassNativeShim.fireAppCallback(slot), "slot " + slot);
        }
    }

    @Test
    public void everySlotReachesItsTargetWithTheArgumentsOfTheHeader() throws Exception {
        AppCallbackRecorder recorder = install(Set.of());
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            Fired fired = fire(slot);
            assertEquals(0, fired.status(), "slot " + slot);
            if (slot != 20) {
                assertEquals(List.of(), fired.reported(), "slot " + slot);
            }
        }
        List<String> expected = new ArrayList<>();
        expected.add("runRunnable(0x102030405060708)");
        expected.add("fire(1)");
        expected.add("fire(2)");
        expected.add("setName(" + SET_NAME_DECODED + ")");
        IntStream.rangeClosed(4, 14).forEach(slot -> expected.add("fire(" + slot + ")"));
        expected.add("notifyOpenFiles([/a, null, " + THIRD_FILE_DECODED + "])");
        IntStream.rangeClosed(16, 19).forEach(slot -> expected.add("fire(" + slot + ")"));
        expected.add("notifyPreferencesChanged");
        assertEquals(expected, recorder.calls());
    }

    @Test
    public void thePreferencesMapHoldsEveryEntryThatBuilds() throws Exception {
        AppCallbackRecorder recorder = install(Set.of());
        Fired fired = fire(20);
        assertEquals(0, fired.status());
        Map<String, Object> preferences = recorder.preferences().getFirst();
        assertEquals(Set.of("test.color", "test.colors", "test.noColors", "test.string", "test.nullString",
                "test.true", "test.false"), preferences.keySet());
        assertEquals(Color.rgb(255, 128, 0, 0.5), preferences.get("test.color"));
        assertArrayEquals(new Color[] {Color.rgb(1, 2, 3, 0.25), Color.rgb(4, 5, 6, 0.75)},
                (Color[]) preferences.get("test.colors"));
        assertArrayEquals(new Color[0], (Color[]) preferences.get("test.noColors"));
        assertEquals("string", preferences.get("test.string"));
        assertTrue(preferences.containsKey("test.nullString"));
        assertNull(preferences.get("test.nullString"));
        assertEquals(Boolean.TRUE, preferences.get("test.true"));
        assertEquals(Boolean.FALSE, preferences.get("test.false"));
        assertThrows(UnsupportedOperationException.class, () -> preferences.put("test.added", Boolean.TRUE));
    }

    @Test
    public void aColourThatColorRgbRejectsIsReportedAndLeavesItsKeyOut() throws Exception {
        install(Set.of());
        Fired fired = fire(20);
        assertEquals(2, fired.reported().size(), "test.badColor and test.badColors");
        for (Throwable reported : fired.reported()) {
            assertInstanceOf(IllegalArgumentException.class, reported);
        }
    }

    /** HashMap.equals compares the Color[] values by identity, so the JNI's Object.equals never matched either. */
    @Test
    public void aMapHoldingAColourArrayNeverEqualsTheKeptOne() throws Exception {
        AppCallbackRecorder recorder = install(Set.of());
        fire(20);
        fire(20);
        List<Map<String, Object>> notified = recorder.preferences();
        assertEquals(2, notified.size());
        assertEquals(notified.get(0).keySet(), notified.get(1).keySet());
        assertNotEquals(notified.get(0), notified.get(1));
    }

    @Test
    public void aReportSlotThatThrowsIsReportedAndAnswersThrew() throws Exception {
        Set<Integer> reportSlots = new HashSet<>();
        IntStream.range(0, SLOT_COUNT).forEach(reportSlots::add);
        reportSlots.removeAll(Set.of(1, 15, 17, 20));
        install(reportSlots);
        for (int slot : reportSlots) {
            Fired fired = fire(slot);
            assertEquals(1, fired.status(), "slot " + slot);
            assertEquals(1, fired.reported().size(), "slot " + slot);
            assertEquals("slot " + slot, fired.reported().getFirst().getMessage());
        }
    }

    @Test
    public void noSlotThrowsEvenWhenTheReportItselfThrows() throws Exception {
        Map<String, String> child = MacGlassNativeTest.runChild(ThrowingSinkChild.class);
        assertEquals(EVERY_SLOT_THREW, child.get("statuses"));
        assertEquals("21", child.get("reports"), "one per REPORT slot, and the two rejected colours of slot 20");
    }

    @Test
    public void openFilesAnswersOkWhenOnlyTheNotificationThrew() throws Exception {
        install(Set.of(15));
        Fired fired = fire(15);
        assertEquals(0, fired.status(), "the String[] was built, so the C replies success");
        assertEquals(List.of("slot 15"), fired.reported().stream().map(Throwable::getMessage).toList());
    }

    @Test
    public void aThrowingPreferencesNotificationIsReported() throws Exception {
        install(Set.of(20));
        Fired fired = fire(20);
        assertEquals(1, fired.status());
        assertEquals("slot 20", fired.reported().getLast().getMessage());
        assertEquals(3, fired.reported().size(), "the two rejected colours, then the notification");
    }

    @Test
    public void theLaunchableAndTheNestedLoopDescribeWhatTheyThrow() throws Exception {
        Map<String, String> child = MacGlassNativeTest.runChild(DescribeChild.class);
        assertEquals("1,1", child.get("statuses"));
        assertEquals("0", child.get("reported"), "a DESCRIBE slot reports nothing");
        byte[] log = Files.readAllBytes(MacGlassNativeTest.childLog(DescribeChild.class));
        for (int slot : new int[] {1, 17}) {
            byte[] expected = describedLine(slot);
            assertTrue(indexOf(log, expected) >= 0, "no \"" + new String(expected, StandardCharsets.ISO_8859_1)
                    + "\" in " + MacGlassNativeTest.childLog(DescribeChild.class));
        }
    }

    @Test
    public void aRequestTheLibraryDropsLeavesTheRegistryAndThrowsOffTheMainThread() throws Exception {
        assertFalse(MacGlassNativeShim.isMainThread());
        int previous = MacGlassNativeShim.setMainJavaAttached(0);
        try {
            assertEquals(0, previous, "nothing in this JVM ran the run loop, which attaches the main thread");
            assertTrue(MacGlassNativeShim.mainThreadDetached());
            int pending = MacGlassNativeShim.pendingRunnables();
            AtomicBoolean ran = new AtomicBoolean();
            for (Runnable post : List.<Runnable>of(() -> MacGlassNativeShim.invokeLater(() -> ran.set(true)),
                    () -> MacGlassNativeShim.invokeAndWait(() -> ran.set(true)),
                    () -> MacGlassNativeShim.invokeLater(null))) {
                RuntimeException thrown = assertThrows(RuntimeException.class, post::run);
                assertEquals(RuntimeException.class, thrown.getClass());
                assertEquals(DETACHED, thrown.getMessage());
                assertEquals(pending, MacGlassNativeShim.pendingRunnables());
            }
            assertFalse(ran.get());
        } finally {
            MacGlassNativeShim.setMainJavaAttached(previous);
        }
    }

    @Test
    public void aRunnableRunsOnceUnderItsIdAndThenLeavesTheRegistry() {
        int pending = MacGlassNativeShim.pendingRunnables();
        AtomicInteger runs = new AtomicInteger();
        long first = MacGlassNativeShim.registerRunnable(runs::incrementAndGet);
        long second = MacGlassNativeShim.registerRunnable(runs::incrementAndGet);
        assertNotEquals(first, second);
        assertEquals(pending + 2, MacGlassNativeShim.pendingRunnables());
        MacGlassNativeShim.runRunnable(first);
        MacGlassNativeShim.runRunnable(first);
        assertEquals(1, runs.get(), "an id that already ran runs nothing");
        assertEquals(pending + 1, MacGlassNativeShim.pendingRunnables());
        MacGlassNativeShim.runRunnable(second);
        assertEquals(2, runs.get());
        assertEquals(pending, MacGlassNativeShim.pendingRunnables());
    }

    @Test
    public void aNullRunnableIsFiledAndRunsAsNothing() {
        int pending = MacGlassNativeShim.pendingRunnables();
        long id = MacGlassNativeShim.registerRunnable(null);
        assertEquals(pending + 1, MacGlassNativeShim.pendingRunnables());
        MacGlassNativeShim.runRunnable(id);
        assertEquals(pending, MacGlassNativeShim.pendingRunnables());
    }

    @Test
    public void aThrowingRunnableHasLeftTheRegistryWhenItsSlotReportsIt() {
        int pending = MacGlassNativeShim.pendingRunnables();
        long id = MacGlassNativeShim.registerRunnable(() -> {
            throw new IllegalStateException("runnable");
        });
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> MacGlassNativeShim.runRunnable(id));
        assertEquals("runnable", thrown.getMessage());
        assertEquals(pending, MacGlassNativeShim.pendingRunnables());
    }

    @Test
    public void theDataDirectoryReportsTheDetachedAssertionAndStillAnswers() throws Exception {
        assertFalse(MacGlassNativeShim.isMainThread());
        int previous = MacGlassNativeShim.setMainJavaAttached(0);
        try {
            Answered<String> detached = onReportingThread(MacGlassNativeShim::dataDirectory);
            MacGlassNativeShim.setMainJavaAttached(1);
            Answered<String> attached = onReportingThread(MacGlassNativeShim::dataDirectory);
            assertEquals(1, detached.reported().size(), "reported, not thrown: " + detached.reported());
            Throwable reported = detached.reported().getFirst();
            assertEquals(RuntimeException.class, reported.getClass());
            assertEquals(DETACHED, reported.getMessage());
            assertEquals(List.of(), attached.reported());
            assertEquals(attached.value(), detached.value());
            assertTrue(attached.value().startsWith("/") && attached.value().endsWith("/Library/Application Support"),
                    attached.value());
        } finally {
            MacGlassNativeShim.setMainJavaAttached(previous);
        }
    }

    @Test
    public void aDetachedOpenThatStoppedAtNoDefaultBrowserThrowsTheAssertion() throws Exception {
        Opened opened = open(0, OPENED_URI, answering(-1, 0));
        assertNull(opened.status());
        assertNotNull(opened.thrown(), "the JNI returned with the assertion pending");
        assertEquals(RuntimeException.class, opened.thrown().getClass());
        assertEquals(DETACHED, opened.thrown().getMessage());
        assertEquals(List.of(), opened.reported());
    }

    @Test
    public void aDetachedOpenThatCompletedReportsTheAssertionAndAnswersItsStatus() throws Exception {
        Opened opened = open(0, OPENED_URI, answering(-43, 1));
        assertNull(opened.thrown());
        assertEquals(-43, opened.status());
        assertEquals(1, opened.reported().size(), "reported, not thrown: " + opened.reported());
        Throwable reported = opened.reported().getFirst();
        assertEquals(RuntimeException.class, reported.getClass());
        assertEquals(DETACHED, reported.getMessage());
    }

    @Test
    public void anAttachedOpenAnswersItsStatusWhetherOrNotItCompleted() throws Exception {
        for (int completed : new int[] {0, 1}) {
            for (int status : new int[] {-1, 0, -43}) {
                Opened opened = open(1, OPENED_URI, answering(status, completed));
                String outcome = "status " + status + ", completed " + completed;
                assertNull(opened.thrown(), outcome);
                assertEquals(status, opened.status(), outcome);
                assertEquals(List.of(), opened.reported(), outcome);
            }
        }
    }

    @Test
    public void theThreadStateBeforeTheOpenDecidesItsOutcome() throws Exception {
        Opened detachedBefore = open(0, OPENED_URI, (uri, length, outCompleted) -> {
            MacGlassNativeShim.setMainJavaAttached(1);
            outCompleted.set(ValueLayout.JAVA_INT, 0, 0);
            return -1;
        });
        assertNotNull(detachedBefore.thrown(), "detached before the downcall, attached after it");
        assertEquals(DETACHED, detachedBefore.thrown().getMessage());
        Opened attachedBefore = open(1, OPENED_URI, (uri, length, outCompleted) -> {
            MacGlassNativeShim.setMainJavaAttached(0);
            outCompleted.set(ValueLayout.JAVA_INT, 0, 0);
            return -1;
        });
        assertNull(attachedBefore.thrown());
        assertEquals(-1, attachedBefore.status());
        assertEquals(List.of(), attachedBefore.reported());
    }

    @Test
    public void theOpenerIsGivenTheUtf16CodeUnitsOfTheUriAndTheirCount() throws Exception {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("https://example.org/😀", "22 https://example.org/😀");
        expected.put("", "0 ");
        expected.put(null, "0 NULL");
        for (Map.Entry<String, String> uri : expected.entrySet()) {
            List<String> received = new CopyOnWriteArrayList<>();
            open(1, uri.getKey(), (chars, length, outCompleted) -> {
                received.add(length + " " + codeUnits(chars, length));
                return 0;
            });
            assertEquals(List.of(uri.getValue()), received, "uri " + uri.getKey());
        }
    }

    @Test
    public void aNestedLoopReturnsTheLastValueItWasLeftWith() {
        MacGlassNativeShim.keepNestedLoopReturnValue("x");
        MacGlassNativeShim.keepNestedLoopReturnValue(null);
        assertEquals("x", MacGlassNativeShim.takeNestedLoopReturnValue());
        assertNull(MacGlassNativeShim.takeNestedLoopReturnValue(), "taking the value clears it");
        MacGlassNativeShim.keepNestedLoopReturnValue("x");
        MacGlassNativeShim.keepNestedLoopReturnValue("y");
        assertEquals("y", MacGlassNativeShim.takeNestedLoopReturnValue());
    }

    @Test
    public void capsLockIsOnExactlyWhenItsModifierFlagIsSet() {
        Map<Long, Integer> expected = new LinkedHashMap<>();
        expected.put(0L, KeyEvent.KEY_LOCK_OFF);
        expected.put(CAPS_LOCK_FLAG, KeyEvent.KEY_LOCK_ON);
        expected.put(~CAPS_LOCK_FLAG, KeyEvent.KEY_LOCK_OFF);
        expected.put(-1L, KeyEvent.KEY_LOCK_ON);
        expected.put(Long.MIN_VALUE | CAPS_LOCK_FLAG, KeyEvent.KEY_LOCK_ON);
        expected.put(CAPS_LOCK_FLAG << 1 | CAPS_LOCK_FLAG >> 1, KeyEvent.KEY_LOCK_OFF);
        for (Map.Entry<Long, Integer> flags : expected.entrySet()) {
            AtomicInteger reads = new AtomicInteger();
            int state = MacGlassNativeShim.isKeyLocked(KeyEvent.VK_CAPS_LOCK, () -> {
                reads.incrementAndGet();
                return flags.getKey();
            });
            assertEquals(flags.getValue(), state, "modifier flags 0x" + Long.toHexString(flags.getKey()));
            assertEquals(1, reads.get(), "Caps Lock reads the modifier flags once");
        }
    }

    @Test
    public void everyOtherKeyIsUnknownWithoutReadingTheModifierFlags() throws IllegalAccessException {
        List<Integer> keyCodes = new ArrayList<>(List.of(-1, Integer.MIN_VALUE, Integer.MAX_VALUE));
        for (Field field : KeyEvent.class.getFields()) {
            if (field.getName().startsWith("VK_") && field.getType() == int.class
                    && Modifier.isStatic(field.getModifiers()) && field.getInt(null) != KeyEvent.VK_CAPS_LOCK) {
                keyCodes.add(field.getInt(null));
            }
        }
        assertTrue(keyCodes.size() > 100, "KeyEvent's VK_ codes: " + keyCodes);
        for (int keyCode : keyCodes) {
            assertEquals(KeyEvent.KEY_LOCK_UNKNOWN, MacGlassNativeShim.isKeyLocked(keyCode, () -> {
                throw new AssertionError("key " + keyCode + " read the modifier flags");
            }), "key " + keyCode);
            assertEquals(KeyEvent.KEY_LOCK_UNKNOWN, MacGlassNativeShim.isKeyLocked(keyCode), "key " + keyCode);
        }
    }

    @Test
    public void eachScreenInfoMemberBecomesTheConstructorArgumentOfItsPosition() {
        StructLayout layout = MacGlassNativeShim.layouts().get("GmacScreenInfo");
        int dpiOverride = Integer.getInteger("com.sun.javafx.screenDPI", 0);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment block = arena.allocate(layout, 2);
            for (int screen = 0; screen < 2; screen++) {
                fillScreenInfo(block.asSlice(screen * layout.byteSize(), layout.byteSize()), layout, screen);
            }
            Screen[] screens = MacGlassNativeShim.toScreens(block, 2);
            assertEquals(2, screens.length);
            for (int screen = 0; screen < 2; screen++) {
                Screen built = screens[screen];
                int base = 100 * (screen + 1);
                List<Integer> ints = IntStream.rangeClosed(base + 1, base + 15).boxed()
                        .collect(Collectors.toCollection(ArrayList::new));
                if (dpiOverride > 0) {
                    ints.set(13, dpiOverride);
                    ints.set(14, dpiOverride);
                }
                assertEquals(0x1122334455667700L + screen, built.getNativeScreen());
                assertEquals(ints, List.of(built.getDepth(), built.getX(), built.getY(), built.getWidth(),
                        built.getHeight(), built.getPlatformX(), built.getPlatformY(), built.getPlatformWidth(),
                        built.getPlatformHeight(), built.getVisibleX(), built.getVisibleY(), built.getVisibleWidth(),
                        built.getVisibleHeight(), built.getResolutionX(), built.getResolutionY()));
                assertEquals(List.of(base + 16.5f, base + 17.5f, base + 18.5f, base + 19.5f),
                        List.of(built.getPlatformScaleX(), built.getPlatformScaleY(),
                                built.getRecommendedOutputScaleX(), built.getRecommendedOutputScaleY()));
            }
        }
    }

    @Test
    public void noScreensBuildAnEmptyArray() {
        assertEquals(0, MacGlassNativeShim.toScreens(MemorySegment.NULL, 0).length);
    }

    @Test
    public void thereAreNoPreferencesWithoutADelegate() {
        assertNull(MacGlassNativeShim.platformPreferences(0L));
    }

    @Test
    public void withoutARunLoopNeitherTheReactivationNorTheDisplayLinkIsSet() {
        assertFalse(MacGlassNativeShim.isTriggerReactivation());
        assertEquals(0.0, MacGlassNativeShim.videoRefreshPeriod());
    }

    /** {@code gmac_app_init} reads {@code -[NSProcessInfo operatingSystemVersion]}; 0 is the flag's initial value. */
    @Test
    public void thisMacOsIsSupported() {
        assertEquals(GMAC_OK, MacGlassNativeShim.appInit(0));
        MacGlassNativeShim.initApplication(false);
    }

    @Test
    public void anUnsupportedMacOsFailsTheInitializationWithTheExceptionOfTheJni() {
        List<Integer> passed = new ArrayList<>();
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> MacGlassNativeShim.initApplication(true, disableSyncRendering -> {
                    passed.add(disableSyncRendering);
                    return GMAC_ERR_UNSUPPORTED_OS;
                }));
        assertEquals(RuntimeException.class, thrown.getClass());
        assertEquals("Unsupported macOS version", thrown.getMessage());
        MacGlassNativeShim.initApplication(false, disableSyncRendering -> {
            passed.add(disableSyncRendering);
            return GMAC_OK;
        });
        assertEquals(List.of(1, 0), passed);
    }

    /**
     * The C's own comparisons: {@code taskbar_application == 1} and {@code has_launchable != 0}. The delegate is
     * never given to a run loop: {@code initWithTaskbarApplication:launchable:} ({@code GlassApplication.m:193})
     * only records the two flags, and {@code -dealloc} ({@code :205}), with no {@code PlatformSupport} made, runs
     * only {@code [super dealloc]} when {@code gmac_test_inspect_and_release_app_delegate} ({@code :872}) releases
     * it.
     */
    @Test
    public void onlyATaskbarFlagOfOneMakesARegularApplicationAndAnyLaunchableFlagButZeroCounts() {
        int[][] taskbarLaunchableMask = {{1, 1, 3}, {0, 1, 2}, {1, 0, 1}, {0, 0, 0}, {2, 1, 2}, {1, -5, 3}};
        for (int[] flags : taskbarLaunchableMask) {
            String call = "gmac_app_create_delegate(" + flags[0] + ", " + flags[1] + ")";
            long app = MacGlassNativeShim.createAppDelegate(flags[0], flags[1]);
            assertNotEquals(0L, app, call);
            int mask = MacGlassNativeShim.inspectAndReleaseAppDelegate(app);
            assertEquals(flags[2], mask, call);
        }
    }

    /** As {@link #onlyATaskbarFlagOfOneMakesARegularApplicationAndAnyLaunchableFlagButZeroCounts}. */
    @Test
    public void theDelegateOfTheRunLoopRecordsBothFlags() {
        Map<List<Boolean>, Integer> masks = Map.of(List.of(true, true), 3, List.of(false, true), 2,
                List.of(true, false), 1, List.of(false, false), 0);
        masks.forEach((flags, expected) -> {
            long app = MacGlassNativeShim.createAppDelegate(flags.get(0), flags.get(1));
            assertNotEquals(0L, app, "taskbar, launchable " + flags);
            int mask = MacGlassNativeShim.inspectAndReleaseAppDelegate(app);
            assertEquals(expected, mask, "taskbar, launchable " + flags);
        });
    }

    @Test
    public void inspectingNoDelegateAnswersMinusOne() {
        assertEquals(-1, MacGlassNativeShim.inspectAndReleaseAppDelegate(0L));
    }

    /**
     * Fires the two DESCRIBE slots, with a recorder that throws in both, on a thread whose name needs a surrogate
     * pair, and writes their statuses and how many exceptions were reported to the file {@code args[0]}.
     */
    public static final class DescribeChild {

        static final String THREAD_NAME = "describe-😀";

        public static void main(String[] args) throws Exception {
            Path result = Path.of(args[0]);
            if (!MacGlassNativeShim.nsAppIsNil()) {
                Files.writeString(result, "nsAppNilBefore=false\n", StandardCharsets.UTF_8);
                return;
            }
            MacGlassNativeShim.loadLibrary();
            MacGlassNativeShim.installAppCallbacks(new AppCallbackRecorder(Set.of(1, 17)));
            List<Throwable> reported = new CopyOnWriteArrayList<>();
            FutureTask<String> statuses = new FutureTask<>(() -> MacGlassNativeShim.fireAppCallback(1) + ","
                    + MacGlassNativeShim.fireAppCallback(17));
            Thread thread = Thread.ofPlatform().name(THREAD_NAME).unstarted(statuses);
            thread.setUncaughtExceptionHandler((t, e) -> reported.add(e));
            thread.start();
            String answered = statuses.get(30, TimeUnit.SECONDS);
            MacGlassNativeShim.uninstallAppCallbacks();
            boolean nsAppNilAfter = MacGlassNativeShim.nsAppIsNil();
            Files.writeString(result, "statuses=" + answered
                    + "\nreported=" + reported.size()
                    + "\nnsAppNilBefore=true"
                    + "\nnsAppNilAfter=" + nsAppNilAfter + "\n", StandardCharsets.UTF_8);
        }
    }

    /**
     * Fires every slot, with a recorder that throws in each, on a thread whose uncaught-exception handler throws an
     * {@link Error} from every report, and writes the statuses and the number of reports to the file
     * {@code args[0]}. A throwable that escaped a slot would end this JVM, so a written file is the proof.
     */
    public static final class ThrowingSinkChild {

        public static void main(String[] args) throws Exception {
            Path result = Path.of(args[0]);
            if (!MacGlassNativeShim.nsAppIsNil()) {
                Files.writeString(result, "nsAppNilBefore=false\n", StandardCharsets.UTF_8);
                return;
            }
            MacGlassNativeShim.loadLibrary();
            Set<Integer> everySlot = IntStream.range(0, SLOT_COUNT).boxed().collect(Collectors.toSet());
            MacGlassNativeShim.installAppCallbacks(new AppCallbackRecorder(everySlot));
            AtomicInteger reports = new AtomicInteger();
            FutureTask<String> statuses = new FutureTask<>(() -> IntStream.range(0, SLOT_COUNT)
                    .mapToObj(slot -> String.valueOf(MacGlassNativeShim.fireAppCallback(slot)))
                    .collect(Collectors.joining(",")));
            Thread thread = Thread.ofPlatform().name("throwing-sink").unstarted(statuses);
            thread.setUncaughtExceptionHandler((t, e) -> {
                reports.incrementAndGet();
                throw new AssertionError("the report of " + e + " threw");
            });
            thread.start();
            String answered = statuses.get(30, TimeUnit.SECONDS);
            MacGlassNativeShim.uninstallAppCallbacks();
            boolean nsAppNilAfter = MacGlassNativeShim.nsAppIsNil();
            Files.writeString(result, "statuses=" + answered
                    + "\nreports=" + reports.get()
                    + "\nnsAppNilBefore=true"
                    + "\nnsAppNilAfter=" + nsAppNilAfter + "\n", StandardCharsets.UTF_8);
        }
    }

    /** The members of screen {@code screen} in layout order: its address, then 100(screen + 1) + 1, + 2, .... */
    private static void fillScreenInfo(MemorySegment info, StructLayout layout, int screen) {
        int value = 100 * (screen + 1);
        long offset = 0;
        for (MemoryLayout member : layout.memberLayouts()) {
            switch (member) {
                case PaddingLayout _ -> {
                }
                case AddressLayout _ -> info.set(ValueLayout.ADDRESS, offset,
                        MemorySegment.ofAddress(0x1122334455667700L + screen));
                case ValueLayout.OfInt _ -> info.set(ValueLayout.JAVA_INT, offset, ++value);
                case ValueLayout.OfFloat _ -> info.set(ValueLayout.JAVA_FLOAT, offset, ++value + 0.5f);
                default -> throw new AssertionError("no value for " + member);
            }
            offset += member.byteSize();
        }
    }

    private static String camelCase(String snakeCase) {
        StringBuilder camel = new StringBuilder();
        for (String word : snakeCase.split("_")) {
            camel.append(camel.isEmpty() ? word : Character.toUpperCase(word.charAt(0)) + word.substring(1));
        }
        return camel.toString();
    }

    /** The first line ExceptionDescribe printed: the thread name in modified UTF-8, so a surrogate is 3 bytes. */
    private static byte[] describedLine(int slot) {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        line.writeBytes("Exception in thread \"describe-".getBytes(StandardCharsets.US_ASCII));
        line.writeBytes(new byte[] {(byte) 0xED, (byte) 0xA0, (byte) 0xBD, (byte) 0xED, (byte) 0xB8, (byte) 0x80});
        line.writeBytes(("\" java.lang.IllegalStateException: slot " + slot + System.lineSeparator())
                .getBytes(StandardCharsets.US_ASCII));
        return line.toByteArray();
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        for (int start = 0; start + needle.length <= haystack.length; start++) {
            int matched = 0;
            while (matched < needle.length && haystack[start + matched] == needle[matched]) {
                matched++;
            }
            if (matched == needle.length) {
                return start;
            }
        }
        return -1;
    }

    private static AppCallbackRecorder install(Set<Integer> failingSlots) {
        AppCallbackRecorder recorder = new AppCallbackRecorder(failingSlots);
        MacGlassNativeShim.installAppCallbacks(recorder);
        return recorder;
    }

    private static Fired fire(int slot) throws Exception {
        Answered<Integer> answered = onReportingThread(() -> MacGlassNativeShim.fireAppCallback(slot));
        return new Fired(answered.value(), answered.reported());
    }

    private static String codeUnits(MemorySegment chars, int length) {
        if (chars.address() == 0) {
            return "NULL";
        }
        return new String(chars.asSlice(0, ValueLayout.JAVA_CHAR.byteSize() * length).toArray(ValueLayout.JAVA_CHAR));
    }

    /** A stand-in for {@code gmac_app_open_uri} that sets {@code *out_completed} and answers {@code status}. */
    private static UriOpener answering(int status, int completed) {
        return (uri, length, outCompleted) -> {
            outCompleted.set(ValueLayout.JAVA_INT, 0, completed);
            return status;
        };
    }

    /**
     * Opens {@code uri} through {@code opener} on a reporting thread, which is never the main thread, with the
     * library's main-Java-attached flag at {@code attached} until the open returns.
     */
    private static Opened open(int attached, String uri, UriOpener opener) throws Exception {
        int previous = MacGlassNativeShim.setMainJavaAttached(attached);
        try {
            AtomicReference<RuntimeException> thrown = new AtomicReference<>();
            Answered<Integer> answered = onReportingThread(() -> {
                try {
                    return MacGlassNativeShim.openUri(uri, opener);
                } catch (RuntimeException e) {
                    thrown.set(e);
                    return null;
                }
            });
            return new Opened(answered.value(), thrown.get(), answered.reported());
        } finally {
            MacGlassNativeShim.setMainJavaAttached(previous);
        }
    }

    /**
     * Runs {@code task} on a new platform thread whose uncaught-exception handler collects what
     * {@code Application.reportException} hands it.
     */
    private static <T> Answered<T> onReportingThread(Callable<T> task) throws Exception {
        List<Throwable> reported = new CopyOnWriteArrayList<>();
        FutureTask<T> answer = new FutureTask<>(task);
        Thread thread = Thread.ofPlatform().name("MacApplicationNativeTest-report").unstarted(answer);
        thread.setUncaughtExceptionHandler((t, e) -> reported.add(e));
        thread.start();
        return new Answered<>(answer.get(30, TimeUnit.SECONDS), List.copyOf(reported));
    }

    private static List<String> memberNames(StructLayout layout) {
        List<String> names = new ArrayList<>();
        for (MemoryLayout member : layout.memberLayouts()) {
            if (!(member instanceof PaddingLayout)) {
                names.add(member.name().orElseThrow());
            }
        }
        return names;
    }
}
