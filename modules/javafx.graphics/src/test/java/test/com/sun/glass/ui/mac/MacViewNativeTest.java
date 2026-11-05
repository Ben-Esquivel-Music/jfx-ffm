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

import com.sun.glass.ui.View;
import com.sun.glass.ui.mac.MacGlassNativeShim;
import com.sun.glass.ui.mac.MacGlassNativeShim.KeyChars;
import com.sun.glass.ui.mac.MacGlassNativeShim.KeyEventFire;
import com.sun.glass.ui.mac.MacGlassNativeShim.ViewCallbackRecorder;
import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.FieldModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.foreign.AddressLayout;
import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.invoke.MethodHandleInfo;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code MacView} and {@code MacGestureSupport} over {@code GmacViewCallbacks}, {@code GmacGestureCallbacks} and the
 * {@code gmac_view_*} exports: the two tables, each slot's arguments, out-parameters and exception sink, the units a
 * key event passes and the {@code char[]} the key slot makes of them, the registry of view ids, the
 * {@code GmacViewCaps} that {@code _create} builds from the device details, the frames of the view calls, and the
 * {@code MacGestureSupport} handles that {@code MacView}'s adapter invokes.
 * <p>
 * No test makes a view, calls {@code gmac_view_create} or asks AppKit for the double-click interval. Slots are
 * dialled through the {@code gmac_test_fire_*} hooks for ids filed with a recording stand-in, and the calls that would
 * reach AppKit take a stand-in where the export would be. The key-chars hooks make key-down {@code NSEvent}s, which
 * no window or application receives. The other view exports are called only with the NULL view,
 * for which each returns at its first statement, {@code if (!ptr) return}, before any pool or AppKit message:
 * {@code GlassView.m} lines 104, 126, 150, 195, 207, 222, 235, 250, 277, 307 and 319. The uploads are also called
 * with a stand-in view and arguments for which the export returns before it messages the view
 * ({@link #uploadOutcomes}). {@code MacView}, whose initialization installs the tables and asks AppKit for the
 * interval, is never initialized; its nested holder of those handles is, which runs only {@code findStatic}. AppKit's
 * {@code NSApp} is nil before the first call into the library and after the last.
 */
@EnabledOnOs(OS.MAC)
public class MacViewNativeTest {

    private static final String DETACHED = "java.lang.RuntimeException: Main Java thread is detached.";

    private static final int GMAC_UPCALL_OK = 0;
    private static final int GMAC_UPCALL_THREW = 1;
    private static final int GMAC_ERR_NO_DATA = -1;

    private static final int VIEW_SLOT_COUNT = 15;
    private static final int GESTURE_SLOT_COUNT = 5;

    private static final int NOTIFY_KEY = 5;
    private static final int NOTIFY_INPUT_METHOD_CANDIDATE_POS_REQUEST = 7;
    private static final int NOTIFY_DRAG_ENTER = 8;
    private static final int NOTIFY_DRAG_DROP = 10;
    private static final int NOTIFY_DRAG_END = 12;
    private static final int GET_ACCESSIBLE = 13;
    private static final int NOTIFY_DISPOSE = 14;

    /** What the out-parameters of {@code gmac_test_fire_view_callback} hold before a dial: no value a slot writes. */
    private static final byte UNWRITTEN = 0x5A;
    private static final int OUT_BYTES = 24;

    /** {@code NSNotFound}, which the input-method slot narrows to its low 32 bits. */
    private static final long NS_NOT_FOUND = Long.MAX_VALUE;

    private static final String CALLER = "MacViewNativeTest-caller";

    private static final long FAKE_VIEW = 0x7E57_0000_5678L;

    private static final Path HEADER = Path.of("src/main/native-glass/mac/glass_mac_api.h");
    private static final Path VIEW_DELEGATE_SOURCE = Path.of("src/main/native-glass/mac/GlassViewDelegate.m");
    private static final Path API_SOURCE = Path.of("src/main/native-glass/mac/glass_mac_api.m");

    /** {@code kVK_ANSI_A}, whose key sends its characters, the left arrow, which sends none, and a code not mapped. */
    private static final char KEY_A = 0x00;
    private static final char KEY_LEFT_ARROW = 0x7B;
    private static final char KEY_NOT_IN_THE_KEY_MAP = 0x90;

    /** What a key-chars out buffer holds where nothing was written: no unit of {@link #KEY_CHARACTERS}. */
    private static final char UNWRITTEN_UNIT = 0x5A5A;
    private static final int GUARD_UNITS = 16;

    /**
     * Lengths around the 16 units the views keep inline: 20 is the most {@code CGEventKeyboardSetUnicodeString} takes,
     * and the 21 units with a surrogate pair at units 15 and 16 put the pair across the inline end.
     */
    private static final List<String> KEY_CHARACTERS = List.of("", distinctUnits(1), distinctUnits(15),
            distinctUnits(16), distinctUnits(17), distinctUnits(20), "a".repeat(15) + "😀" + "b".repeat(4),
            "😀".repeat(8), "😀".repeat(10), distinctUnits(2000));

    private static final Pattern COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n]*");

    private static final Pattern SLOT = Pattern.compile("\\(\\s*\\*\\s*(\\w+)\\s*\\)");

    private static final Pattern MEMBER = Pattern.compile("(\\w+)\\s*;");

    /** What each slot of {@code gmac_test_fire_view_callback} reaches, in slot order, slot 14 aside. */
    private static final List<String> VIEW_HOOK_CALLS = List.of(
            "notifyView(1001)",
            "notifyResize(1001, 1002)",
            "notifyRepaint(1001, 1002, 1003, 1004)",
            "notifyMenu(1001, 1002, 1003, 1004, true)",
            "notifyMouse(1001, 1002, 1003, 1004, 1005, 1006, 1007, true, false)",
            "notifyKeyAndReturnConsumed(1001, 1002, [0061 00e9 d83d de00 dc00], 1003)",
            "notifyInputMethodMac([0062 65e5 d83d de00], 1001, 1002, 1003, -1, 1004)",
            "notifyInputMethodCandidatePosRequest(1001)",
            "notifyDragEnter(1001, 1002, 1003, 1004, 1005)",
            "notifyDragOver(1001, 1002, 1003, 1004, 1005)",
            "notifyDragDrop(1001, 1002, 1003, 1004, 1005)",
            "notifyDragLeave()",
            "notifyDragEnd(1001)",
            "getAccessible()");

    /** What each slot of {@code gmac_test_fire_gesture_callback} reaches, in slot order. */
    private static final List<String> GESTURE_HOOK_CALLS = List.of(
            "rotateGesturePerformed(1001, 1002, 1003, 1004, 1005, 1.5)",
            "scrollGesturePerformed(1001, 1002, 1003, 1004, 1005, 1006, 1.5, 2.5)",
            "swipeGesturePerformed(1001, 1002, 1003, 1004, 1005, 1006)",
            "magnifyGesturePerformed(1001, 1002, 1003, 1004, 1005, 1.5)",
            "gestureFinished(1001, 1002, 1003, 1004, 1005)");

    private int attachedBefore;

    /** What a dial of {@code gmac_test_fire_view_callback} answered, and the out-parameter bytes afterwards. */
    private record ViewFire(int status, byte[] out) {

        int outInt() {
            return ByteBuffer.wrap(out).order(ByteOrder.nativeOrder()).getInt(0);
        }

        double outDouble(int index) {
            return ByteBuffer.wrap(out).order(ByteOrder.nativeOrder()).getDouble(index * Double.BYTES);
        }

        int outValid() {
            return ByteBuffer.wrap(out).order(ByteOrder.nativeOrder()).getInt(2 * Double.BYTES);
        }

        long outPointer() {
            return ByteBuffer.wrap(out).order(ByteOrder.nativeOrder()).getLong(0);
        }

        boolean unwritten() {
            byte[] expected = new byte[OUT_BYTES];
            Arrays.fill(expected, UNWRITTEN);
            return Arrays.equals(expected, out);
        }
    }

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
    void installTheTables() {
        attachedBefore = MacGlassNativeShim.setMainJavaAttached(1);
        MacGlassNativeShim.installViewCallbacks();
    }

    @AfterEach
    void uninstallTheTablesAndRestoreTheAttachedFlag() {
        MacGlassNativeShim.installViewTable(MemorySegment.NULL);
        MacGlassNativeShim.installGestureTable(MemorySegment.NULL);
        MacGlassNativeShim.setMainJavaAttached(attachedBefore);
    }

    @Test
    public void theViewTableIsTheFifteenAddressesOfTheHeaderInItsOrder() throws IOException {
        StructLayout table = MacGlassNativeShim.layouts().get("GmacViewCallbacks");
        assertEquals(120, table.byteSize());
        assertEquals(120, MacGlassNativeShim.sizeOf("GmacViewCallbacks"));
        List<String> members = memberNames(table);
        assertEquals(headerSlotNames("GmacViewCallbacks"), members);
        assertEquals(MacGlassNativeShim.viewSlotNames(), members);
        assertEquals(VIEW_SLOT_COUNT, members.size());
    }

    @Test
    public void theGestureTableIsTheFiveAddressesOfTheHeaderInItsOrder() throws IOException {
        StructLayout table = MacGlassNativeShim.layouts().get("GmacGestureCallbacks");
        assertEquals(40, table.byteSize());
        assertEquals(40, MacGlassNativeShim.sizeOf("GmacGestureCallbacks"));
        List<String> members = memberNames(table);
        assertEquals(headerSlotNames("GmacGestureCallbacks"), members);
        assertEquals(MacGlassNativeShim.gestureSlotNames(), members);
        assertEquals(GESTURE_SLOT_COUNT, members.size());
    }

    @Test
    public void everyViewCapsMemberIsWhereTheCompilerPutIt() throws IOException {
        StructLayout layout = MacGlassNativeShim.layouts().get("GmacViewCaps");
        assertEquals(40, layout.byteSize());
        assertEquals(40, MacGlassNativeShim.sizeOf("GmacViewCaps"));
        List<String> members = memberNames(layout);
        assertEquals(headerMemberNames("GmacViewCaps"), members);
        for (int member = 0; member < members.size(); member++) {
            String name = members.get(member);
            assertEquals(layout.byteOffset(PathElement.groupElement(name)),
                    MacGlassNativeShim.offsetOf("GmacViewCaps", member), "GmacViewCaps." + name);
        }
        assertEquals(-1, MacGlassNativeShim.offsetOf("GmacViewCaps", members.size()), "past its last member");
        assertEquals(-1, MacGlassNativeShim.offsetOf("GmacViewCaps", -1), "before its first member");
        assertEquals(List.copyOf(MacGlassNativeShim.viewCapsKeys().keySet()), members);
    }

    @Test
    public void everyViewSlotReachesTheViewOfItsIdWithTheHookArguments() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        long viewId = MacGlassNativeShim.registerView(new ViewCallbackRecorder(events, false));
        List<Integer> statuses = onFreshThread(events, () -> fireViewSlotsUpToGetAccessible(viewId).stream()
                .map(ViewFire::status).toList());
        assertEquals(List.of(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), statuses);
        assertEquals(VIEW_HOOK_CALLS, events);
        assertTrue(MacGlassNativeShim.isViewRegistered(viewId), "only notify_dispose forgets an id");
    }

    @Test
    public void everyGestureSlotReachesTheViewOfItsIdWithTheHookArguments() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        long viewId = MacGlassNativeShim.registerView(new ViewCallbackRecorder(events, false));
        List<Integer> statuses = onFreshThread(events, () -> fireEveryGestureSlot(viewId));
        assertEquals(List.of(0, 0, 0, 0, 0), statuses);
        assertEquals(GESTURE_HOOK_CALLS, events);
    }

    @Test
    public void theValueSlotsWriteWhatTheViewAnswered() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        ViewCallbackRecorder view = new ViewCallbackRecorder(events, false);
        view.answerDragAction(0x4FFFFFFF);
        view.answerAccessible(0x1122334455667788L);
        long viewId = MacGlassNativeShim.registerView(view);
        List<ViewFire> fired = onFreshThread(events, () -> fireViewSlotsUpToGetAccessible(viewId));
        assertEquals(1, fired.get(NOTIFY_KEY).outInt(), "consumed");
        ViewFire candidate = fired.get(NOTIFY_INPUT_METHOD_CANDIDATE_POS_REQUEST);
        assertEquals(List.of(1.5, 2.5, 1), List.of(candidate.outDouble(0), candidate.outDouble(1),
                candidate.outValid()));
        for (int slot = NOTIFY_DRAG_ENTER; slot <= NOTIFY_DRAG_DROP; slot++) {
            assertEquals(0x4FFFFFFF, fired.get(slot).outInt(), "the action of slot " + slot);
        }
        assertEquals(0x1122334455667788L, fired.get(GET_ACCESSIBLE).outPointer());
        for (int slot : List.of(0, 1, 2, 3, 4, 6, 11, 12)) {
            assertTrue(fired.get(slot).unwritten(), "slot " + slot + " has no out-parameter");
        }
    }

    @Test
    public void aKeyNotConsumedWritesZero() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        ViewCallbackRecorder view = new ViewCallbackRecorder(events, false);
        view.answerConsumed(false);
        long viewId = MacGlassNativeShim.registerView(view);
        ViewFire fired = onFreshThread(events, () -> fireViewSlot(NOTIFY_KEY, viewId));
        assertEquals(GMAC_UPCALL_OK, fired.status());
        assertEquals(0, fired.outInt());
    }

    @Test
    public void aCandidatePositionThatIsNotTwoValuesWritesNothing() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        ViewCallbackRecorder view = new ViewCallbackRecorder(events, false);
        long viewId = MacGlassNativeShim.registerView(view);
        for (double[] answer : new double[][] {null, {}, {1.5}, {1.5, 2.5, 3.5}}) {
            view.answerCandidatePosition(answer);
            ViewFire fired = onFreshThread(events,
                    () -> fireViewSlot(NOTIFY_INPUT_METHOD_CANDIDATE_POS_REQUEST, viewId));
            assertEquals(GMAC_UPCALL_OK, fired.status());
            assertTrue(fired.unwritten(), "the answer " + Arrays.toString(answer));
        }
        assertEquals(4, events.size());
    }

    @Test
    public void whatAViewSlotThrowsIsReportedAtOnceAndItsOutParametersAreNotWritten() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        long viewId = MacGlassNativeShim.registerView(new ViewCallbackRecorder(events, true));
        List<ViewFire> fired = onFreshThread(events, () -> fireViewSlotsUpToGetAccessible(viewId));
        assertEquals(List.of(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), fired.stream().map(ViewFire::status).toList());
        for (int slot = 0; slot < fired.size(); slot++) {
            assertTrue(fired.get(slot).unwritten(), "slot " + slot);
        }
        assertEquals(reportedAfterEach(VIEW_HOOK_CALLS), events);
    }

    @Test
    public void whatAGestureSlotThrowsIsReportedAtOnce() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        long viewId = MacGlassNativeShim.registerView(new ViewCallbackRecorder(events, true));
        List<Integer> statuses = onFreshThread(events, () -> fireEveryGestureSlot(viewId));
        assertEquals(List.of(1, 1, 1, 1, 1), statuses);
        assertEquals(reportedAfterEach(GESTURE_HOOK_CALLS), events);
    }

    @Test
    public void everyDialOfOneKeyEventPassesOneCharArray() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        ViewCallbackRecorder view = new ViewCallbackRecorder(events, false);
        long viewId = MacGlassNativeShim.registerView(view);
        KeyEventFire fired = onFreshThread(events, () -> MacGlassNativeShim.fireKeyEvent(viewId, 3));
        assertEquals(new KeyEventFire(GMAC_UPCALL_OK, 1), fired);
        assertEquals(List.of(
                "notifyKeyAndReturnConsumed(1001, 2001, [0061 00e9], 3001)",
                "notifyKeyAndReturnConsumed(1002, 2002, [0061 00e9], 3001)",
                "notifyKeyAndReturnConsumed(1003, 2003, [0061 00e9], 3001)"), events);
        List<char[]> chars = view.keyChars();
        assertSame(chars.get(0), chars.get(1));
        assertSame(chars.get(0), chars.get(2));
    }

    @Test
    public void eachKeyEventGetsACharArrayOfItsOwn() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        ViewCallbackRecorder view = new ViewCallbackRecorder(events, false);
        long viewId = MacGlassNativeShim.registerView(view);
        onFreshThread(events, () -> List.of(MacGlassNativeShim.fireKeyEvent(viewId, 2),
                MacGlassNativeShim.fireKeyEvent(viewId, 2)));
        List<char[]> chars = view.keyChars();
        assertEquals(4, chars.size());
        assertSame(chars.get(0), chars.get(1));
        assertSame(chars.get(2), chars.get(3));
        assertNotSame(chars.get(0), chars.get(2));
        assertArrayEquals(chars.get(0), chars.get(2));
    }

    @Test
    public void aDialAfterANestedKeyEventGetsANewCharArray() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        ViewCallbackRecorder view = new ViewCallbackRecorder(events, false);
        long viewId = MacGlassNativeShim.registerView(view);
        List<KeyEventFire> nested = new CopyOnWriteArrayList<>();
        view.duringNextKey(() -> nested.add(MacGlassNativeShim.fireKeyEvent(viewId, 1)));
        KeyEventFire outer = onFreshThread(events, () -> MacGlassNativeShim.fireKeyEvent(viewId, 2));
        assertEquals(new KeyEventFire(GMAC_UPCALL_OK, 1), outer);
        assertEquals(List.of(new KeyEventFire(GMAC_UPCALL_OK, 1)), nested);
        List<char[]> chars = view.keyChars();
        assertEquals(3, chars.size(), "the outer event's first dial, the nested one's, the outer event's second");
        assertNotSame(chars.get(0), chars.get(1));
        assertNotSame(chars.get(1), chars.get(2));
        assertNotSame(chars.get(0), chars.get(2));
        assertArrayEquals(chars.get(0), chars.get(2));
        assertEquals(List.of(
                "notifyKeyAndReturnConsumed(1001, 2001, [0061 00e9], 3001)",
                "notifyKeyAndReturnConsumed(1001, 2001, [0061 00e9], 3001)",
                "notifyKeyAndReturnConsumed(1002, 2002, [0061 00e9], 3001)"), events);
    }

    @Test
    public void aKeyEventThatThrowsIsReportedAtEachDialAndConsumesNothing() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        long viewId = MacGlassNativeShim.registerView(new ViewCallbackRecorder(events, true));
        KeyEventFire fired = onFreshThread(events, () -> MacGlassNativeShim.fireKeyEvent(viewId, 2));
        assertEquals(new KeyEventFire(GMAC_UPCALL_THREW, 0), fired);
        assertEquals(reportedAfterEach(List.of(
                "notifyKeyAndReturnConsumed(1001, 2001, [0061 00e9], 3001)",
                "notifyKeyAndReturnConsumed(1002, 2002, [0061 00e9], 3001)")), events);
    }

    @Test
    public void theKeyCharsHelperCopiesAtMostItsCapacityAndAnswersTheWholeLength() {
        for (String characters : KEY_CHARACTERS) {
            int length = characters.length();
            for (int capacity : capacitiesAround(length)) {
                char[] out = unwrittenUnits(Math.max(length, capacity) + GUARD_UNITS);
                KeyChars copy = MacGlassNativeShim.keyCharsUtf16(KEY_A, characters, capacity, out);
                char[] expected = unwrittenUnits(out.length);
                characters.getChars(0, Math.min(length, capacity), expected, 0);
                String label = length + " units, capacity " + capacity;
                assertEquals(length, copy.answer(), label);
                assertArrayEquals(expected, copy.out(), label);
            }
        }
    }

    @Test
    public void aCapacityBelowOneCopiesNothingNotEvenToTheNullBuffer() {
        for (String characters : KEY_CHARACTERS) {
            for (int capacity : new int[] {0, -1, Integer.MIN_VALUE}) {
                String label = characters.length() + " units, capacity " + capacity;
                assertEquals(new KeyChars(characters.length(), null),
                        MacGlassNativeShim.keyCharsUtf16(KEY_A, characters, capacity, null), label);
                KeyChars copy = MacGlassNativeShim.keyCharsUtf16(KEY_A, characters, capacity,
                        unwrittenUnits(GUARD_UNITS));
                assertEquals(characters.length(), copy.answer(), label);
                assertArrayEquals(unwrittenUnits(GUARD_UNITS), copy.out(), label);
            }
        }
    }

    @Test
    public void aKeyThatSendsNoCharactersCopiesNoneOfThemAndAnswersMinusOne() {
        for (char keyCode : new char[] {KEY_LEFT_ARROW, KEY_NOT_IN_THE_KEY_MAP}) {
            for (String characters : KEY_CHARACTERS) {
                int capacity = characters.length() + GUARD_UNITS;
                KeyChars copy = MacGlassNativeShim.keyCharsUtf16(keyCode, characters, capacity,
                        unwrittenUnits(capacity));
                String label = "key code " + (int) keyCode + ", " + characters.length() + " units";
                assertEquals(-1, copy.answer(), label);
                assertArrayEquals(unwrittenUnits(capacity), copy.out(), label);
            }
        }
    }

    @Test
    public void everyUnitOfAKeyEventReachesTheKeySlotInOrderWhateverItsLength() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        ViewCallbackRecorder view = new ViewCallbackRecorder(events, false);
        long viewId = MacGlassNativeShim.registerView(view);
        List<KeyEventFire> fired = onFreshThread(events, () -> KEY_CHARACTERS.stream()
                .map(characters -> MacGlassNativeShim.fireKeyChars(viewId, KEY_A, characters, '\0')).toList());
        assertEquals(Collections.nCopies(KEY_CHARACTERS.size(), new KeyEventFire(GMAC_UPCALL_OK, 1)), fired);
        assertEquals(KEY_CHARACTERS.stream().map(MacViewNativeTest::keyCharsCall).toList(), events);
        assertKeyChars(KEY_CHARACTERS, view.keyChars());
    }

    @Test
    public void aTextCharIsTheOnlyUnitWhateverTheCharactersOfItsEvent() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        ViewCallbackRecorder view = new ViewCallbackRecorder(events, false);
        long viewId = MacGlassNativeShim.registerView(view);
        List<KeyEventFire> fired = onFreshThread(events, () -> KEY_CHARACTERS.stream()
                .flatMap(characters -> List.of(MacGlassNativeShim.fireKeyChars(viewId, KEY_A, characters, 'A'),
                        MacGlassNativeShim.fireKeyChars(viewId, KEY_LEFT_ARROW, characters, 'é')).stream())
                .toList());
        List<String> textChars = KEY_CHARACTERS.stream().flatMap(characters -> List.of("A", "é").stream())
                .toList();
        assertEquals(Collections.nCopies(textChars.size(), new KeyEventFire(GMAC_UPCALL_OK, 1)), fired);
        assertEquals(textChars.stream().map(MacViewNativeTest::keyCharsCall).toList(), events);
        assertKeyChars(textChars, view.keyChars());
    }

    @Test
    public void aKeyThatSendsNoCharactersPassesAnEmptyArrayWhateverItsCharacters() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        ViewCallbackRecorder view = new ViewCallbackRecorder(events, false);
        long viewId = MacGlassNativeShim.registerView(view);
        List<KeyEventFire> fired = onFreshThread(events, () -> KEY_CHARACTERS.stream()
                .flatMap(characters -> List.of(
                        MacGlassNativeShim.fireKeyChars(viewId, KEY_LEFT_ARROW, characters, '\0'),
                        MacGlassNativeShim.fireKeyChars(viewId, KEY_NOT_IN_THE_KEY_MAP, characters, '\0')).stream())
                .toList());
        List<String> none = Collections.nCopies(2 * KEY_CHARACTERS.size(), "");
        assertEquals(Collections.nCopies(none.size(), new KeyEventFire(GMAC_UPCALL_OK, 1)), fired);
        assertEquals(none.stream().map(MacViewNativeTest::keyCharsCall).toList(), events);
        assertKeyChars(none, view.keyChars());
    }

    @Test
    public void aKeyEventOfMoreThanSixteenUnitsGetsACharArrayOfItsOwn() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        ViewCallbackRecorder view = new ViewCallbackRecorder(events, false);
        long viewId = MacGlassNativeShim.registerView(view);
        String characters = distinctUnits(2000);
        List<KeyEventFire> nested = new CopyOnWriteArrayList<>();
        view.duringNextKey(() -> nested.add(MacGlassNativeShim.fireKeyChars(viewId, KEY_A, characters, '\0')));
        KeyEventFire outer = onFreshThread(events, () -> MacGlassNativeShim.fireKeyEvent(viewId, 2));
        List<KeyEventFire> next = onFreshThread(events, () -> List.of(
                MacGlassNativeShim.fireKeyChars(viewId, KEY_A, characters, '\0'),
                MacGlassNativeShim.fireKeyChars(viewId, KEY_A, characters, '\0')));
        assertEquals(new KeyEventFire(GMAC_UPCALL_OK, 1), outer);
        assertEquals(List.of(new KeyEventFire(GMAC_UPCALL_OK, 1)), nested);
        assertEquals(Collections.nCopies(2, new KeyEventFire(GMAC_UPCALL_OK, 1)), next);
        List<char[]> chars = view.keyChars();
        assertKeyChars(List.of("aé", characters, "aé", characters, characters), chars);
        Set<char[]> distinct = Collections.newSetFromMap(new IdentityHashMap<>());
        distinct.addAll(chars);
        assertEquals(chars.size(), distinct.size(), "each key event, and a dial after a nested one, gets a new char[]");
    }

    /** Each event's units take a 64 KiB heap block, so 1,024 events keep 64 MiB in use if the block is not freed. */
    @Test
    public void theHeapBlockOfALongKeyEventIsFreedAfterItsDial() throws Exception {
        String characters = distinctUnits(32_768);
        int events = 1024;
        List<String> reports = new CopyOnWriteArrayList<>();
        long growth = onFreshThread(reports, () -> {
            fireKeyCharsOfNoView(characters, 64);
            long before = MacGlassNativeShim.mallocBytesInUse();
            fireKeyCharsOfNoView(characters, events);
            return MacGlassNativeShim.mallocBytesInUse() - before;
        });
        assertTrue(growth < 16L << 20, growth + " bytes more in use after " + events + " key events");
        assertEquals(List.of(), reports);
    }

    /**
     * What the key-chars hook dials proves what {@code -_dialKeyEvent:} dials only while both size the units with the
     * one function, and the heap block is freed only if {@code -_dialKeyEvent:} releases it before each return.
     */
    @Test
    public void theViewsAndTheKeyCharsHookSizeTheUnitsWithOneFunctionAndReleaseThem() throws IOException {
        String dial = sourceBlock(VIEW_DELEGATE_SOURCE, "- (BOOL)_dialKeyEvent:");
        String hook = sourceBlock(API_SOURCE, "int32_t gmac_test_fire_key_chars(");
        for (String body : List.of(dial, hook)) {
            assertEquals(1, occurrences(body, "GlassKeyCharsFill(&keyChars, "), body);
            assertTrue(body.contains("GlassKeySend send = {callbacks, "), body);
            assertTrue(body.contains(", keyChars.units, keyChars.count, "), body);
            assertFalse(body.contains("GetJavaKeyCharsUtf16"), body);
            assertFalse(Pattern.compile("\\w\\s*\\[\\s*\\d+\\s*]").matcher(body).find(), body);
            assertTrue(body.indexOf("GlassKeyCharsFill(") < body.indexOf("GlassDialKeySlot("), body);
            assertTrue(body.lastIndexOf("GlassDialKeySlot(") < body.lastIndexOf("GlassKeyCharsRelease(&keyChars);"),
                    body);
        }
        Matcher returns = Pattern.compile("\\breturn\\b").matcher(dial);
        int count = 0;
        while (returns.find()) {
            count++;
            assertTrue(dial.substring(0, returns.start()).strip().endsWith("GlassKeyCharsRelease(&keyChars);"),
                    "return " + count + " of " + dial);
        }
        assertTrue(count > 0, dial);
        assertEquals(count, occurrences(dial, "GlassKeyCharsRelease(&keyChars);"), dial);
    }

    @Test
    public void theInputMethodSlotGetsTheUnitsOfAStringAndTheNarrowedSelection() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        long viewId = MacGlassNativeShim.registerView(new ViewCallbackRecorder(events, false));
        List<Integer> statuses = onFreshThread(events, () -> List.of(
                MacGlassNativeShim.fireInputMethod(viewId, "", false, 1, 0, 0, 0, 0),
                MacGlassNativeShim.fireInputMethod(viewId, "a日😀", false, 1, 7, 2, 1, 2),
                MacGlassNativeShim.fireInputMethod(viewId, "ab", false, 1, 2, 2, NS_NOT_FOUND, 0)));
        assertEquals(List.of(0, 0, 0), statuses);
        assertEquals(List.of(
                "notifyInputMethodMac([], 1, 0, 0, 0, 0)",
                "notifyInputMethodMac([0061 65e5 d83d de00], 1, 7, 2, 1, 2)",
                "notifyInputMethodMac([0061 0062], 1, 2, 2, -1, 0)"), events);
    }

    @Test
    public void committedAttributedTextIsDialledOnlyWithAPositiveLength() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        long viewId = MacGlassNativeShim.registerView(new ViewCallbackRecorder(events, false));
        List<Integer> statuses = onFreshThread(events, () -> List.of(
                MacGlassNativeShim.fireInputMethod(viewId, "ab😀", true, 4, 4, 4, 4, 0),
                MacGlassNativeShim.fireInputMethod(viewId, "ab", true, 4, 0, 0, 0, 0),
                MacGlassNativeShim.fireInputMethod(viewId, "ab", true, 4, -1, 0, 0, 0)));
        assertEquals(List.of(GMAC_UPCALL_OK, Integer.MIN_VALUE, Integer.MIN_VALUE), statuses);
        assertEquals(List.of("notifyInputMethodMac([0061 0062 d83d de00], 4, 4, 4, 4, 0)"), events);
    }

    @Test
    public void disposeForgetsOnlyItsIdAndNoSlotReachesItAfterwards() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        long disposed = MacGlassNativeShim.registerView(new ViewCallbackRecorder(events, true));
        List<String> otherEvents = new CopyOnWriteArrayList<>();
        long other = MacGlassNativeShim.registerView(new ViewCallbackRecorder(otherEvents, false));
        ViewFire dispose = onFreshThread(events, () -> fireViewSlot(NOTIFY_DISPOSE, disposed));
        assertEquals(GMAC_UPCALL_OK, dispose.status());
        assertFalse(MacGlassNativeShim.isViewRegistered(disposed));
        assertTrue(MacGlassNativeShim.isViewRegistered(other));
        assertRunsNothing(disposed, events);
        assertEquals(GMAC_UPCALL_OK, onFreshThread(events, () -> fireViewSlot(NOTIFY_DRAG_END, other)).status());
        assertEquals(List.of("notifyDragEnd(1001)"), otherEvents);
    }

    @Test
    public void anIdTheRegistryNeverHeldRunsNothingAndWritesNothing() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        for (long viewId : new long[] {0L, -1L, Long.MAX_VALUE}) {
            assertRunsNothing(viewId, events);
        }
    }

    @Test
    public void idsAreNonzeroDistinctAndKeptUntilDisposed() throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ids.add(MacGlassNativeShim.registerView(new ViewCallbackRecorder(new CopyOnWriteArrayList<>(), false)));
        }
        assertEquals(3, new HashSet<>(ids).size(), ids.toString());
        for (long viewId : ids) {
            assertNotEquals(0L, viewId);
            assertTrue(MacGlassNativeShim.isViewRegistered(viewId));
            assertEquals(GMAC_UPCALL_OK, fireViewSlot(NOTIFY_DISPOSE, viewId).status());
            assertFalse(MacGlassNativeShim.isViewRegistered(viewId));
        }
    }

    @Test
    public void withoutATableOrItsSlotNothingIsDialled() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        long viewId = MacGlassNativeShim.registerView(new ViewCallbackRecorder(events, false));
        try (Arena arena = Arena.ofConfined()) {
            assertEquals(0, MacGlassNativeShim.installViewTable(arena.allocate(ADDRESS, VIEW_SLOT_COUNT)));
            assertEquals(0, MacGlassNativeShim.installGestureTable(arena.allocate(ADDRESS, GESTURE_SLOT_COUNT)));
            assertNothingDialled(viewId);
        }
        assertEquals(0, MacGlassNativeShim.installViewTable(MemorySegment.NULL));
        assertEquals(0, MacGlassNativeShim.installGestureTable(MemorySegment.NULL));
        assertNothingDialled(viewId);
        MacGlassNativeShim.installViewCallbacks();
        assertEquals(Integer.MIN_VALUE, fireViewSlot(-1, viewId).status());
        assertEquals(Integer.MIN_VALUE, fireViewSlot(VIEW_SLOT_COUNT, viewId).status());
        assertEquals(Integer.MIN_VALUE, MacGlassNativeShim.fireGestureCallback(-1, viewId));
        assertEquals(Integer.MIN_VALUE, MacGlassNativeShim.fireGestureCallback(GESTURE_SLOT_COUNT, viewId));
        assertEquals(new KeyEventFire(Integer.MIN_VALUE, 0), MacGlassNativeShim.fireKeyEvent(viewId, 0));
        assertEquals(List.of(), events);
        assertTrue(MacGlassNativeShim.isViewRegistered(viewId), "notify_dispose never ran");
    }

    @Test
    public void theLibraryDialsItsOwnCopyOfTheTable() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        long viewId = MacGlassNativeShim.registerView(new ViewCallbackRecorder(events, false));
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment table = arena.allocate(ADDRESS, VIEW_SLOT_COUNT);
            for (int slot = 0; slot < VIEW_SLOT_COUNT; slot++) {
                table.setAtIndex(ADDRESS, slot, MacGlassNativeShim.viewCallbackStub(slot));
            }
            assertEquals(0, MacGlassNativeShim.installViewTable(table));
            table.fill((byte) 0);
        }
        assertEquals(GMAC_UPCALL_OK, fireViewSlot(NOTIFY_DRAG_END, viewId).status());
        assertEquals(List.of("notifyDragEnd(1001)"), events);
    }

    @Test
    public void theCapsOfANullMapAreNullAndThoseOfAnEmptyMapAreTheDefaults() {
        assertNull(MacGlassNativeShim.viewCaps(null));
        byte[] defaults = MacGlassNativeShim.defaultViewCaps();
        assertEquals(List.of(0L, 0L, 0L, 0, 0, 0, 1), decodeCaps(defaults));
        assertArrayEquals(defaults, MacGlassNativeShim.viewCaps(Map.of()));
        Map<Object, Object> nullValues = new HashMap<>();
        for (Object key : MacGlassNativeShim.viewCapsKeys().values()) {
            nullValues.put(key, null);
        }
        assertArrayEquals(defaults, MacGlassNativeShim.viewCaps(nullValues), "a key mapped to null");
        assertArrayEquals(defaults, MacGlassNativeShim.viewCaps(Map.of("unrelated", 1L, 99, Boolean.TRUE)));
    }

    @Test
    public void everyMemberTakesTheValueOfItsKey() {
        Map<Object, Object> caps = new HashMap<>();
        caps.put("mtlCommandQueue", 0x1111_2222_3333L);
        caps.put("shareContextPtr", 0x4444_5555_6666L);
        caps.put("contextPtr", 0x7777_8888_9999L);
        caps.put(Integer.valueOf(View.Capability.k3dDepthKeyValue), 24);
        caps.put(Integer.valueOf(View.Capability.kHiDPIAwareKeyValue), Boolean.TRUE);
        caps.put("useMTLInGlassForSW", Boolean.TRUE);
        caps.put("isVsyncEnabled", 0L);
        assertEquals(List.of(0x1111_2222_3333L, 0x4444_5555_6666L, 0x7777_8888_9999L, 24, 1, 1, 0),
                decodeCaps(MacGlassNativeShim.viewCaps(caps)));
        caps.put(View.Capability.kHiDPIAwareKey, Boolean.FALSE);
        caps.put("useMTLInGlassForSW", Boolean.FALSE);
        caps.put("isVsyncEnabled", 1L);
        assertEquals(List.of(0x1111_2222_3333L, 0x4444_5555_6666L, 0x7777_8888_9999L, 24, 0, 0, 1),
                decodeCaps(MacGlassNativeShim.viewCaps(caps)));
    }

    @Test
    public void vsyncIsEnabledOnlyForOneOrNoValue() {
        for (long value : new long[] {0L, 2L, -1L, Long.MIN_VALUE, 1L << 32 | 1L}) {
            assertEquals(0, decodeCaps(MacGlassNativeShim.viewCaps(Map.of("isVsyncEnabled", value))).get(6),
                    "isVsyncEnabled " + value);
        }
        assertEquals(1, decodeCaps(MacGlassNativeShim.viewCaps(Map.of("isVsyncEnabled", 1L))).get(6));
    }

    @Test
    public void whatAReadThrowsIsReportedAndItsMemberKeepsItsDefault() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        Map<Object, Object> throwing = new AbstractMap<>() {
            @Override
            public Object get(Object key) {
                throw new IllegalStateException("get " + key);
            }

            @Override
            public Set<Entry<Object, Object>> entrySet() {
                return Set.of();
            }
        };
        byte[] caps = onFreshThread(events, () -> MacGlassNativeShim.viewCaps(throwing));
        assertArrayEquals(MacGlassNativeShim.defaultViewCaps(), caps);
        List<String> expected = new ArrayList<>();
        for (Object key : MacGlassNativeShim.viewCapsKeys().values()) {
            expected.add("report java.lang.IllegalStateException: get " + key + " on " + CALLER);
        }
        assertEquals(expected, events);
    }

    @Test
    public void aValueOfAnotherTypeIsReportedAndItsMemberKeepsItsDefault() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        Map<Object, Object> wrong = new LinkedHashMap<>();
        for (Object key : MacGlassNativeShim.viewCapsKeys().values()) {
            wrong.put(key, "wrong");
        }
        byte[] caps = onFreshThread(events, () -> MacGlassNativeShim.viewCaps(wrong));
        assertArrayEquals(MacGlassNativeShim.defaultViewCaps(), caps);
        assertEquals(7, events.size(), events.toString());
        for (String event : events) {
            assertTrue(event.startsWith("report java.lang.ClassCastException: "), event);
        }
    }

    @Test
    public void createFilesTheViewBeforeItsDowncallWithItsCapsAndAnswersTheView() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        ViewCallbackRecorder view = new ViewCallbackRecorder(events, false);
        Map<Object, Object> details = Map.of("mtlCommandQueue", 0x1234L, "isVsyncEnabled", 0L);
        List<Long> filed = new CopyOnWriteArrayList<>();
        List<byte[]> passed = new CopyOnWriteArrayList<>();
        long created = onFreshThread(events, () -> MacGlassNativeShim.createView(view, details, (viewId, caps) -> {
            filed.add(viewId);
            passed.add(caps.toArray(JAVA_BYTE));
            events.add("create filed " + MacGlassNativeShim.isViewRegistered(viewId));
            events.add("drag end " + fireViewSlot(NOTIFY_DRAG_END, viewId).status());
            return FAKE_VIEW;
        }));
        assertEquals(FAKE_VIEW, created);
        assertEquals(List.of("create filed true", "notifyDragEnd(1001)", "drag end " + GMAC_UPCALL_OK), events);
        assertArrayEquals(MacGlassNativeShim.viewCaps(details), passed.getFirst());
        long viewId = filed.getFirst();
        assertNotEquals(0L, viewId);
        assertTrue(MacGlassNativeShim.isViewRegistered(viewId), "only notify_dispose forgets the id of a made view");
        assertEquals(GMAC_UPCALL_OK, fireViewSlot(NOTIFY_DISPOSE, viewId).status());
    }

    @Test
    public void createForgetsTheIdOfAViewItDidNotMake() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        List<Long> filed = new CopyOnWriteArrayList<>();
        long created = onFreshThread(events, () -> MacGlassNativeShim.createView(
                new ViewCallbackRecorder(events, false), null, (viewId, caps) -> {
                    filed.add(viewId);
                    return 0L;
                }));
        assertEquals(0L, created);
        assertFalse(MacGlassNativeShim.isViewRegistered(filed.getFirst()), "no notify_dispose comes for it");
        assertEquals(List.of(), events);
    }

    @Test
    public void createForgetsTheIdAndRethrowsWhenItsDowncallThrows() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        List<Long> filed = new CopyOnWriteArrayList<>();
        UnsatisfiedLinkError missing = new UnsatisfiedLinkError("gmac_view_create");
        Throwable thrown = onFreshThread(events, () -> {
            try {
                MacGlassNativeShim.createView(new ViewCallbackRecorder(events, false), null, (viewId, caps) -> {
                    filed.add(viewId);
                    throw missing;
                });
                return null;
            } catch (UnsatisfiedLinkError e) {
                return e;
            }
        });
        assertSame(missing, thrown);
        assertFalse(MacGlassNativeShim.isViewRegistered(filed.getFirst()), "no notify_dispose comes for it");
        assertEquals(List.of(), events);
    }

    /**
     * {@code MacView}'s adapter reaches the five {@code MacGestureSupport} methods through handles that a nested
     * holder finds by their exact types when it initializes, which here leaves {@code MacView} uninitialized: a type
     * that drifted fails this test instead of the first wheel or trackpad event.
     */
    @Test
    public void theGestureHandlesOfAViewAreTheStaticMethodsOfMacGestureSupportOfTheirTypes() {
        Map<String, MethodType> expected = Map.of(
                "rotateGesturePerformed", gestureType(int.class, int.class, int.class, int.class, int.class,
                        float.class),
                "scrollGesturePerformed", gestureType(int.class, int.class, int.class, int.class, int.class,
                        int.class, float.class, float.class),
                "swipeGesturePerformed", gestureType(int.class, int.class, int.class, int.class, int.class,
                        int.class),
                "magnifyGesturePerformed", gestureType(int.class, int.class, int.class, int.class, int.class,
                        float.class),
                "gestureFinished", gestureType(int.class, int.class, int.class, int.class, int.class));
        List<MethodHandleInfo> methods = MacGlassNativeShim.gestureSupportMethods();
        Map<String, MethodType> found = new HashMap<>();
        for (MethodHandleInfo method : methods) {
            assertEquals(MethodHandleInfo.REF_invokeStatic, method.getReferenceKind(), method.toString());
            assertEquals("com.sun.glass.ui.mac.MacGestureSupport", method.getDeclaringClass().getName(),
                    method.toString());
            found.put(method.getName(), method.getMethodType());
        }
        assertEquals(GESTURE_SLOT_COUNT, methods.size());
        assertEquals(expected, found);
    }

    /**
     * The adapter invokes each handle exactly with the view and the arguments of the receiver's method of the same
     * name, so those arguments must have the handle's types after the view.
     */
    @Test
    public void eachGestureMethodOfTheReceiverTakesWhatItsHandleTakesAfterTheView() throws NoSuchMethodException {
        for (MethodHandleInfo method : MacGlassNativeShim.gestureSupportMethods()) {
            Class<?>[] afterView = method.getMethodType().dropParameterTypes(0, 1).parameterArray();
            Method receiver = MacGlassNativeShim.viewReceiverInterface().getMethod(method.getName(), afterView);
            assertEquals(void.class, receiver.getReturnType(), receiver.toString());
        }
    }

    @Test
    public void aViewOfANullMapIsCreatedWithNullCaps() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        List<Long> caps = new CopyOnWriteArrayList<>();
        long created = onFreshThread(events, () -> MacGlassNativeShim.createView(
                new ViewCallbackRecorder(events, false), null, (viewId, struct) -> {
                    caps.add(struct.address());
                    return FAKE_VIEW;
                }));
        assertEquals(FAKE_VIEW, created);
        assertEquals(List.of(0L), caps);
        assertEquals(List.of(), events);
    }

    @Test
    public void aViewCallOnTheNullViewAnswersZeroAndNeitherThrowsNorReportsWhenDetached() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        MacGlassNativeShim.setMainJavaAttached(0);
        Map<String, Object> answers = onFreshThread(events, () -> {
            assertTrue(MacGlassNativeShim.mainThreadDetached());
            return MacGlassNativeShim.viewCallsOnNullView();
        });
        List<String> frames = new CopyOnWriteArrayList<>();
        long frameBuffer = onFreshThread(events, () -> {
            MacGlassNativeShim.viewRunThrowingIfDetached(0L, view -> frames.add("run"));
            return MacGlassNativeShim.viewFrameBuffer(0L, view -> {
                frames.add("frame buffer");
                return 1L;
            });
        });
        assertEquals(List.of(), events);
        assertEquals(List.of(), frames);
        assertEquals(0L, frameBuffer);
        assertEquals(15, answers.size(), "every view call but create and the multi-click time: " + answers.keySet());
        answers.forEach((call, answer) -> assertTrue(answer.equals(0L) || answer.equals(0)
                || answer.equals(Boolean.FALSE) || answer.equals("void"), call + " answered " + answer));
    }

    @Test
    public void detachedBeginAndEndMakeTheirDowncallThenThrow() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        MacGlassNativeShim.setMainJavaAttached(0);
        Throwable thrown = onFreshThread(events, () -> {
            try {
                MacGlassNativeShim.viewRunThrowingIfDetached(FAKE_VIEW, view -> events.add(
                        "begin 0x" + Long.toHexString(view)));
                return null;
            } catch (RuntimeException e) {
                return e;
            }
        });
        assertInstanceOf(RuntimeException.class, thrown);
        assertEquals(DETACHED, thrown.toString());
        assertEquals(List.of("begin 0x" + Long.toHexString(FAKE_VIEW)), events);
    }

    @Test
    public void attachedBeginAndEndOnlyMakeTheirDowncall() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        onFreshThread(events, () -> {
            MacGlassNativeShim.viewRunThrowingIfDetached(FAKE_VIEW, view -> events.add("end"));
            return null;
        });
        assertEquals(List.of("end"), events);
    }

    @Test
    public void aDetachedFrameBufferIsReportedOnceAndAnswered() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        MacGlassNativeShim.setMainJavaAttached(0);
        long frameBuffer = onFreshThread(events, () -> MacGlassNativeShim.viewFrameBuffer(FAKE_VIEW, view -> {
            events.add("frame buffer 0x" + Long.toHexString(view));
            return 0x0BAD_F00DL;
        }));
        assertEquals(0x0BAD_F00DL, frameBuffer);
        assertEquals(List.of("frame buffer 0x" + Long.toHexString(FAKE_VIEW), "report " + DETACHED + " on " + CALLER),
                events);
        MacGlassNativeShim.setMainJavaAttached(1);
        events.clear();
        assertEquals(7L, (long) onFreshThread(events, () -> MacGlassNativeShim.viewFrameBuffer(FAKE_VIEW,
                view -> 7L)));
        assertEquals(List.of(), events);
    }

    @Test
    public void aDetachedMultiClickTimeAsksThenThrows() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        assertEquals(500L, (long) onFreshThread(events, () -> MacGlassNativeShim.multiClickTime(() -> 500L)));
        MacGlassNativeShim.setMainJavaAttached(0);
        Throwable thrown = onFreshThread(events, () -> {
            try {
                MacGlassNativeShim.multiClickTime(() -> {
                    events.add("asked");
                    return 500L;
                });
                return null;
            } catch (RuntimeException e) {
                return e;
            }
        });
        assertEquals(DETACHED, String.valueOf(thrown));
        assertEquals(List.of("asked"), events);
    }

    @Test
    public void aDetachedViewCallWithAClosingCheckMakesItsDowncallThenReportsOnceAndNeverThrows() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        MacGlassNativeShim.setMainJavaAttached(0);
        int answer = onFreshThread(events, () -> {
            assertTrue(MacGlassNativeShim.mainThreadDetached());
            int answered = MacGlassNativeShim.viewCallReportingIfDetached(FAKE_VIEW, view -> {
                events.add("call 0x" + Long.toHexString(view));
                return 7;
            });
            MacGlassNativeShim.viewRunReportingIfDetached(FAKE_VIEW, view -> events.add(
                    "run 0x" + Long.toHexString(view)));
            return answered;
        });
        assertEquals(7, answer);
        String report = "report " + DETACHED + " on " + CALLER;
        assertEquals(List.of("call 0x" + Long.toHexString(FAKE_VIEW), report, "run 0x" + Long.toHexString(FAKE_VIEW),
                report), events);
    }

    @Test
    public void aViewCallWithAClosingCheckReportsNothingForAnAttachedOrTheNullView() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        int attached = onFreshThread(events, () -> {
            assertFalse(MacGlassNativeShim.mainThreadDetached());
            MacGlassNativeShim.viewRunReportingIfDetached(FAKE_VIEW, view -> events.add("run"));
            return MacGlassNativeShim.viewCallReportingIfDetached(FAKE_VIEW, view -> 1);
        });
        MacGlassNativeShim.setMainJavaAttached(0);
        int nullView = onFreshThread(events, () -> MacGlassNativeShim.viewCallReportingIfDetached(0L, view -> {
            events.add("call 0x" + Long.toHexString(view));
            return 0;
        }));
        assertEquals(1, attached);
        assertEquals(0, nullView);
        assertEquals(List.of("run", "call 0x0"), events, "the NULL view's export answers 0 at once");
    }

    @Test
    public void aDetachedUploadMakesItsDowncallThenThrowsWhereItsNativeReachedTheAssertion() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        MacGlassNativeShim.setMainJavaAttached(0);
        Throwable thrown = onFreshThread(events, () -> {
            MacGlassNativeShim.viewUploadThrowingIfDetached(FAKE_VIEW, false, view -> events.add("before"));
            try {
                MacGlassNativeShim.viewUploadThrowingIfDetached(FAKE_VIEW, true, view -> events.add(
                        "past 0x" + Long.toHexString(view)));
                return null;
            } catch (RuntimeException e) {
                return e;
            }
        });
        assertEquals(DETACHED, String.valueOf(thrown));
        assertEquals(List.of("before", "past 0x" + Long.toHexString(FAKE_VIEW)), events);
    }

    @Test
    public void aDetachedUploadThrowsOnlyPastTheChecksItsNativeMadeBeforeTheAssertion() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        MacGlassNativeShim.setMainJavaAttached(0);
        Map<String, String> outcomes = onFreshThread(events, MacViewNativeTest::uploadOutcomes);
        Map<String, String> expected = new LinkedHashMap<>();
        for (String upload : outcomes.keySet()) {
            expected.put(upload, upload.endsWith("throws") ? DETACHED : "returned");
        }
        assertEquals(expected, outcomes);
        assertEquals(List.of(), events);
    }

    @Test
    public void anAttachedUploadNeitherThrowsNorReports() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        Map<String, String> outcomes = onFreshThread(events, MacViewNativeTest::uploadOutcomes);
        assertEquals(Collections.nCopies(outcomes.size(), "returned"), List.copyOf(outcomes.values()),
                outcomes.toString());
        assertEquals(List.of(), events);
    }

    @Test
    public void aDetachedSetParentThrowsForEveryViewButTheNullOneAndAnAttachedOneDoesNot() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        onFreshThread(events, () -> {
            MacGlassNativeShim.setViewParent(FAKE_VIEW);
            return null;
        });
        MacGlassNativeShim.setMainJavaAttached(0);
        Throwable thrown = onFreshThread(events, () -> {
            MacGlassNativeShim.setViewParent(0L);
            try {
                MacGlassNativeShim.setViewParent(FAKE_VIEW);
                return null;
            } catch (RuntimeException e) {
                return e;
            }
        });
        assertEquals(DETACHED, String.valueOf(thrown));
        assertEquals(List.of(), events);
    }

    /** The native's assertion did not read the parent, which {@code View.setWindow} passes. */
    @Test
    public void setParentHandsItsFrameTheViewAlone() throws IOException {
        assertEquals(List.of("load LONG 1", "INVOKESTATIC com/sun/glass/ui/mac/MacViewCalls.setParent(J)V",
                "return VOID"), instructions(macClass("MacView"), "_setParent", "(JJ)V"));
    }

    /**
     * The frame each view call hands its downcall to, as a lambda: a report frame where the native closed with
     * {@code GLASS_CHECK_EXCEPTION}, and the throw frame for {@code _begin} and {@code _end}, which had no closing
     * check and run on the render thread, where the main thread can be detached.
     */
    @Test
    public void eachViewCallHandsItsDowncallToTheDetachedFrameOfItsNative() throws IOException {
        List<String> report = List.of("INVOKEDYNAMIC", "INVOKESTATIC com/sun/glass/ui/mac/MacViewCalls"
                + ".reportIfDetachedAfter(JLcom/sun/glass/ui/mac/MacViewCalls$IntDowncall;)I");
        List<String> reportVoid = List.of("INVOKEDYNAMIC", "INVOKESTATIC com/sun/glass/ui/mac/MacViewCalls"
                + ".runReportingIfDetached(JLcom/sun/glass/ui/mac/MacViewCalls$VoidDowncall;)V");
        List<String> raise = List.of("INVOKEDYNAMIC", "INVOKESTATIC com/sun/glass/ui/mac/MacViewCalls"
                + ".throwIfDetachedAfter(JLcom/sun/glass/ui/mac/MacViewCalls$VoidDowncall;)V");
        Map<String, List<String>> expected = new LinkedHashMap<>();
        expected.put("getX(J)I", report);
        expected.put("getY(J)I", report);
        expected.put("close(J)Z", report);
        expected.put("scheduleRepaint(J)V", reportVoid);
        expected.put("enterFullscreen(JZZZ)Z", report);
        expected.put("exitFullscreen(JZ)V", reportVoid);
        expected.put("enableInputMethodEvents(JZ)V", reportVoid);
        expected.put("finishInputMethodComposition(J)V", reportVoid);
        expected.put("begin(J)V", raise);
        expected.put("end(J)V", raise);
        ClassModel calls = macClass("MacViewCalls");
        Map<String, List<String>> invocations = new LinkedHashMap<>();
        for (String method : expected.keySet()) {
            int descriptor = method.indexOf('(');
            invocations.put(method, instructions(calls, method.substring(0, descriptor),
                    method.substring(descriptor)).stream().filter(code -> code.startsWith("INVOKE")).toList());
        }
        assertEquals(expected, invocations);
    }

    @Test
    public void directPixelsCrossAsTheBaseAddressWhateverTheirPositionAndOthersAsNull() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pixels = arena.allocate(64, 8);
            ByteBuffer direct = pixels.asByteBuffer();
            direct.position(12).limit(20);
            assertEquals(pixels.address(), MacGlassNativeShim.viewPixelsAddress(direct));
            assertEquals(pixels.address(), MacGlassNativeShim.viewPixelsAddress(direct.asReadOnlyBuffer()));
            assertEquals(pixels.address() + 16, MacGlassNativeShim.viewPixelsAddress(pixels.asByteBuffer().slice(16,
                    32)));
            IntBuffer ints = pixels.asByteBuffer().order(ByteOrder.nativeOrder()).asIntBuffer();
            ints.position(3);
            assertEquals(pixels.address(), MacGlassNativeShim.viewPixelsAddress(ints));
            assertEquals(12, direct.position(), "the caller's buffer is left as it was");
            assertEquals(3, ints.position());
        }
        assertEquals(0L, MacGlassNativeShim.viewPixelsAddress(ByteBuffer.allocate(64)));
        assertEquals(0L, MacGlassNativeShim.viewPixelsAddress(IntBuffer.allocate(16)));
        assertEquals(0L, MacGlassNativeShim.viewPixelsAddress(ByteBuffer.allocate(64).asReadOnlyBuffer()));
    }

    @Test
    public void neitherClassDeclaresANativeMethod() throws ClassNotFoundException {
        for (String name : List.of("com.sun.glass.ui.mac.MacView", "com.sun.glass.ui.mac.MacGestureSupport")) {
            Class<?> type = Class.forName(name, false, MacGlassNativeShim.class.getClassLoader());
            List<String> natives = new ArrayList<>();
            for (Method method : type.getDeclaredMethods()) {
                if (Modifier.isNative(method.getModifiers())) {
                    natives.add(method.getName());
                }
            }
            assertEquals(List.of(), natives, name);
        }
    }

    @Test
    public void theMultiClickMaximaAreTheConstantThreeThatTheNativesAnswered() throws IOException {
        Map<String, Object> constants = new LinkedHashMap<>();
        for (FieldModel field : macClass("MacView").fields()) {
            String name = field.fieldName().stringValue();
            if (name.startsWith("multiClickMax")) {
                constants.put(name, field.findAttribute(Attributes.constantValue())
                        .map(value -> value.constant().constantValue()).orElse(null));
            }
        }
        assertEquals(Map.of("multiClickMaxX", 3, "multiClickMaxY", 3), constants);
    }

    /**
     * What each upload, its export included, did for {@link #FAKE_VIEW}: {@code "returned"}, or what it threw. A case
     * whose name ends in {@code throws} got past the checks its native made before
     * {@code GLASS_ASSERT_MAIN_JAVA_THREAD} ({@code GlassView.m:573-576}, {@code :597-607} and {@code :636-646} at
     * 1a6788cc4d); the others stopped at one.
     * <p>
     * Every case returns from its export before the view is messaged, so no view is needed. The direct upload has a
     * size of 0 ({@code GlassView.m:240}), after {@code getGlassView}, which only casts the address
     * ({@code :41-46}). The array uploads return at a check before the assertion's place ({@code :252-258},
     * {@code :279-285}) or, when they got past those, at the length check after it ({@code :260-263},
     * {@code :287-290}), before {@code getGlassView} ({@code :265}, {@code :292}).
     */
    private static Map<String, String> uploadOutcomes() {
        int widestBytes = Integer.MAX_VALUE / 4;
        int widestInts = Integer.MAX_VALUE / 2;
        Map<String, Runnable> uploads = new LinkedHashMap<>();
        uploads.put("direct of no width throws", () -> MacGlassNativeShim.uploadViewPixelsDirect(FAKE_VIEW,
                ByteBuffer.allocateDirect(16), 0, 2));
        uploads.put("direct of no height throws", () -> MacGlassNativeShim.uploadViewPixelsDirect(FAKE_VIEW,
                ByteBuffer.allocateDirect(16), 2, 0));
        uploads.put("bytes short of the image throws", () -> MacGlassNativeShim.uploadViewPixelsBytes(FAKE_VIEW,
                new byte[15], 0, 2, 2));
        uploads.put("bytes at a negative offset", () -> MacGlassNativeShim.uploadViewPixelsBytes(FAKE_VIEW,
                new byte[16], -1, 2, 2));
        uploads.put("bytes of no width", () -> MacGlassNativeShim.uploadViewPixelsBytes(FAKE_VIEW, new byte[16], 0,
                0, 2));
        uploads.put("bytes of no height", () -> MacGlassNativeShim.uploadViewPixelsBytes(FAKE_VIEW, new byte[16], 0,
                2, 0));
        uploads.put("bytes of the widest image throws", () -> MacGlassNativeShim.uploadViewPixelsBytes(FAKE_VIEW,
                new byte[16], 0, widestBytes, 1));
        uploads.put("bytes one pixel wider", () -> MacGlassNativeShim.uploadViewPixelsBytes(FAKE_VIEW, new byte[16],
                0, widestBytes + 1, 1));
        uploads.put("bytes at the last offset of a pixel throws", () -> MacGlassNativeShim.uploadViewPixelsBytes(
                FAKE_VIEW, new byte[16], Integer.MAX_VALUE - 4, 1, 1));
        uploads.put("bytes at an offset past it", () -> MacGlassNativeShim.uploadViewPixelsBytes(FAKE_VIEW,
                new byte[16], Integer.MAX_VALUE - 3, 1, 1));
        uploads.put("ints short of the image throws", () -> MacGlassNativeShim.uploadViewPixelsInts(FAKE_VIEW,
                new int[3], 0, 2, 2));
        uploads.put("ints at a negative offset", () -> MacGlassNativeShim.uploadViewPixelsInts(FAKE_VIEW, new int[4],
                -1, 2, 2));
        uploads.put("ints of no width", () -> MacGlassNativeShim.uploadViewPixelsInts(FAKE_VIEW, new int[4], 0, 0,
                2));
        uploads.put("ints of the widest image throws", () -> MacGlassNativeShim.uploadViewPixelsInts(FAKE_VIEW,
                new int[4], 0, widestInts, 2));
        uploads.put("ints one pixel wider", () -> MacGlassNativeShim.uploadViewPixelsInts(FAKE_VIEW, new int[4], 0,
                widestInts + 1, 2));
        uploads.put("ints at the last offset of a pixel throws", () -> MacGlassNativeShim.uploadViewPixelsInts(
                FAKE_VIEW, new int[4], Integer.MAX_VALUE - 1, 1, 1));
        uploads.put("ints at an offset past it", () -> MacGlassNativeShim.uploadViewPixelsInts(FAKE_VIEW,
                new int[4], Integer.MAX_VALUE, 1, 1));
        Map<String, String> outcomes = new LinkedHashMap<>();
        uploads.forEach((name, upload) -> {
            try {
                upload.run();
                outcomes.put(name, "returned");
            } catch (RuntimeException e) {
                outcomes.put(name, e.toString());
            }
        });
        return outcomes;
    }

    private static ClassModel macClass(String simpleName) throws IOException {
        try (InputStream in = View.class.getModule().getResourceAsStream("com/sun/glass/ui/mac/" + simpleName
                + ".class")) {
            return ClassFile.of().parse(in.readAllBytes());
        }
    }

    /** The instructions of the method {@code name} of {@code descriptor}: loads, invocations and returns spelt out. */
    private static List<String> instructions(ClassModel model, String name, String descriptor) {
        for (MethodModel method : model.methods()) {
            if (method.methodName().equalsString(name) && method.methodType().equalsString(descriptor)) {
                List<String> code = new ArrayList<>();
                for (CodeElement element : method.code().orElseThrow()) {
                    switch (element) {
                        case LoadInstruction load -> code.add("load " + load.typeKind() + " " + load.slot());
                        case InvokeInstruction invoke -> code.add(invoke.opcode() + " "
                                + invoke.owner().asInternalName() + "." + invoke.name().stringValue()
                                + invoke.type().stringValue());
                        case ReturnInstruction ret -> code.add("return " + ret.typeKind());
                        case Instruction other -> code.add(other.opcode().toString());
                        default -> {
                        }
                    }
                }
                return code;
            }
        }
        throw new AssertionError("no " + name + descriptor + " in " + model.thisClass().asInternalName());
    }

    private static void assertRunsNothing(long viewId, List<String> events) throws Exception {
        List<ViewFire> fired = onFreshThread(events, () -> {
            List<ViewFire> all = new ArrayList<>(fireViewSlotsUpToGetAccessible(viewId));
            all.add(fireViewSlot(NOTIFY_DISPOSE, viewId));
            return all;
        });
        List<Integer> gestures = onFreshThread(events, () -> fireEveryGestureSlot(viewId));
        KeyEventFire key = onFreshThread(events, () -> MacGlassNativeShim.fireKeyEvent(viewId, 2));
        KeyEventFire keyChars = onFreshThread(events, () -> MacGlassNativeShim.fireKeyChars(viewId, KEY_A,
                distinctUnits(2000), '\0'));
        int inputMethod = onFreshThread(events, () -> MacGlassNativeShim.fireInputMethod(viewId, "ab", false, 1, 2,
                2, 0, 0));
        assertEquals(List.of(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), fired.stream().map(ViewFire::status)
                .toList(), "view id " + viewId);
        for (int slot = 0; slot < fired.size(); slot++) {
            assertTrue(fired.get(slot).unwritten(), "view id " + viewId + ", slot " + slot);
        }
        assertEquals(List.of(0, 0, 0, 0, 0), gestures, "view id " + viewId);
        assertEquals(new KeyEventFire(GMAC_UPCALL_OK, 0), key, "view id " + viewId);
        assertEquals(new KeyEventFire(GMAC_UPCALL_OK, 0), keyChars, "view id " + viewId);
        assertEquals(GMAC_UPCALL_OK, inputMethod, "view id " + viewId);
        assertEquals(List.of(), events);
    }

    private static void assertNothingDialled(long viewId) {
        List<Integer> statuses = new ArrayList<>();
        for (int slot = 0; slot < VIEW_SLOT_COUNT; slot++) {
            ViewFire fired = fireViewSlot(slot, viewId);
            statuses.add(fired.status());
            assertTrue(fired.unwritten(), "slot " + slot);
        }
        assertEquals(Collections.nCopies(VIEW_SLOT_COUNT, GMAC_ERR_NO_DATA), statuses);
        assertEquals(Collections.nCopies(GESTURE_SLOT_COUNT, GMAC_ERR_NO_DATA), fireEveryGestureSlot(viewId));
        assertEquals(new KeyEventFire(GMAC_ERR_NO_DATA, 0), MacGlassNativeShim.fireKeyEvent(viewId, 1));
        assertEquals(new KeyEventFire(GMAC_ERR_NO_DATA, 0),
                MacGlassNativeShim.fireKeyChars(viewId, KEY_A, distinctUnits(20), '\0'));
        assertEquals(GMAC_ERR_NO_DATA, MacGlassNativeShim.fireInputMethod(viewId, "ab", false, 1, 2, 2, 0, 0));
    }

    /** {@code count} units that differ from one another, so that a unit out of place or out of order shows. */
    private static String distinctUnits(int count) {
        StringBuilder units = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            units.append((char) ('\u4e00' + i));
        }
        return units.toString();
    }

    /** Fires {@code events} key events of {@code characters} for view id 0, which the registry never holds. */
    private static void fireKeyCharsOfNoView(String characters, int events) {
        for (int i = 0; i < events; i++) {
            assertEquals(new KeyEventFire(GMAC_UPCALL_OK, 0),
                    MacGlassNativeShim.fireKeyChars(0L, KEY_A, characters, '\0'), "event " + i);
        }
    }

    /** The capacities from 1 up at the inline end, at 20 and 21 units, and at {@code length}, either side included. */
    private static int[] capacitiesAround(int length) {
        return IntStream.of(1, 15, 16, 17, 20, 21, length - 1, length, length + 1).filter(units -> units > 0)
                .distinct().toArray();
    }

    private static char[] unwrittenUnits(int count) {
        char[] units = new char[count];
        Arrays.fill(units, UNWRITTEN_UNIT);
        return units;
    }

    /** What the recorder logs for the one dial of {@code gmac_test_fire_key_chars} that passes {@code units}. */
    private static String keyCharsCall(String units) {
        StringBuilder hex = new StringBuilder("notifyKeyAndReturnConsumed(1001, 2001, [");
        for (int i = 0; i < units.length(); i++) {
            hex.append(i == 0 ? "" : " ").append(String.format("%04x", (int) units.charAt(i)));
        }
        return hex.append("], 3001)").toString();
    }

    private static void assertKeyChars(List<String> expected, List<char[]> actual) {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            assertArrayEquals(expected.get(i).toCharArray(), actual.get(i), "dial " + i);
        }
    }

    /**
     * The function or method of {@code source} whose definition starts with {@code start}, up to its closing brace at
     * the start of a line, without comments.
     */
    private static String sourceBlock(Path source, String start) throws IOException {
        assertTrue(Files.isRegularFile(source), "run from modules/javafx.graphics: no " + source.toAbsolutePath());
        String text = Files.readString(source);
        int begin = text.indexOf(start);
        assertTrue(begin >= 0, "no " + start + " in " + source);
        assertEquals(-1, text.indexOf(start, begin + 1), "two of " + start + " in " + source);
        int end = text.indexOf("\n}\n", begin);
        assertTrue(end > begin, "no end of " + start + " in " + source);
        String block = COMMENT.matcher(text.substring(begin, end + 2)).replaceAll(" ");
        return LINE_COMMENT.matcher(block).replaceAll(" ");
    }

    private static int occurrences(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) {
            count++;
        }
        return count;
    }

    /** Dials {@code slot} with every out-parameter byte {@link #UNWRITTEN}, and answers the bytes afterwards. */
    private static ViewFire fireViewSlot(int slot, long viewId) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(OUT_BYTES, JAVA_DOUBLE.byteAlignment());
            out.fill(UNWRITTEN);
            int status = MacGlassNativeShim.fireViewCallback(slot, viewId, out);
            return new ViewFire(status, out.toArray(JAVA_BYTE));
        }
    }

    private static List<ViewFire> fireViewSlotsUpToGetAccessible(long viewId) {
        List<ViewFire> fired = new ArrayList<>();
        for (int slot = 0; slot < NOTIFY_DISPOSE; slot++) {
            fired.add(fireViewSlot(slot, viewId));
        }
        return fired;
    }

    private static List<Integer> fireEveryGestureSlot(long viewId) {
        List<Integer> statuses = new ArrayList<>();
        for (int slot = 0; slot < GESTURE_SLOT_COUNT; slot++) {
            statuses.add(MacGlassNativeShim.fireGestureCallback(slot, viewId));
        }
        return statuses;
    }

    private static List<String> reportedAfterEach(List<String> calls) {
        List<String> expected = new ArrayList<>();
        for (String call : calls) {
            expected.add(call);
            expected.add("report java.lang.IllegalStateException: " + call + " on " + CALLER);
        }
        return expected;
    }

    /** Each member of {@code caps} as the Java value it holds: an address as a {@code long}, else an {@code int}. */
    private static List<Object> decodeCaps(byte[] caps) {
        StructLayout layout = MacGlassNativeShim.layouts().get("GmacViewCaps");
        ByteBuffer bytes = ByteBuffer.wrap(caps).order(ByteOrder.nativeOrder());
        List<Object> values = new ArrayList<>();
        for (String name : memberNames(layout)) {
            int offset = Math.toIntExact(layout.byteOffset(PathElement.groupElement(name)));
            boolean address = layout.select(PathElement.groupElement(name)) instanceof AddressLayout;
            values.add(address ? (Object) bytes.getLong(offset) : (Object) bytes.getInt(offset));
        }
        return values;
    }

    private static MethodType gestureType(Class<?>... parametersAfterView) {
        return MethodType.methodType(void.class, View.class).appendParameterTypes(parametersAfterView);
    }

    private static List<String> memberNames(StructLayout layout) {
        return layout.memberLayouts().stream().map(member -> member.name().orElseThrow()).toList();
    }

    private static String headerStruct(String struct) throws IOException {
        assertTrue(Files.isRegularFile(HEADER), "run from modules/javafx.graphics: no " + HEADER.toAbsolutePath());
        String header = COMMENT.matcher(Files.readString(HEADER)).replaceAll(" ");
        Matcher body = Pattern.compile("typedef\\s+struct\\s+" + struct + "\\s*\\{(.*?)}\\s*" + struct + "\\s*;",
                Pattern.DOTALL).matcher(header);
        assertTrue(body.find(), "no " + struct + " in " + HEADER);
        return body.group(1);
    }

    private static List<String> headerSlotNames(String struct) throws IOException {
        List<String> names = new ArrayList<>();
        Matcher slot = SLOT.matcher(headerStruct(struct));
        while (slot.find()) {
            names.add(slot.group(1));
        }
        return names;
    }

    private static List<String> headerMemberNames(String struct) throws IOException {
        List<String> names = new ArrayList<>();
        Matcher member = MEMBER.matcher(headerStruct(struct));
        while (member.find()) {
            names.add(member.group(1));
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
        try {
            return task.get(30, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof Exception cause ? cause : e;
        }
    }
}
