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

import com.sun.glass.ui.mac.MacGlassNativeShim;
import com.sun.glass.ui.mac.MacGlassNativeShim.AccessibleRecorder;
import com.sun.glass.ui.mac.MacGlassNativeShim.ChildrenAnswer;
import com.sun.glass.ui.mac.MacGlassNativeShim.InitEnumAnswer;
import com.sun.glass.ui.mac.MacGlassNativeShim.StyleRun;
import com.sun.glass.ui.mac.MacGlassNativeShim.Variant;
import com.sun.glass.ui.mac.MacGlassNativeShim.VariantObject;
import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import test.com.sun.javafx.test.ParityGate;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.abort;

/**
 * {@code MacAccessible} over {@code GmacAccessibleCallbacks}, the {@code gmac_a11y_*} exports and the AppKit,
 * CoreFoundation and Objective-C runtime calls of {@code MacAccessibilityCalls}: the {@code GmacVariant} layout and
 * the 16-slot table against the library's probes and the header; each slot's target, its out-parameters and its
 * exception sink through {@code gmac_test_fire_accessible_callback}; the registry of peer ids; each kind of
 * {@code MacVariant} as a slot hands it to the C and as the peers make an object of it
 * ({@code gmac_test_a11y_variant_to_object}), read back through {@code gmac_a11y_id_to_variant} and CoreFoundation;
 * and the calls that replaced the JNI's one-call bodies, against the golden of those bodies (audit g4 §9, PB2) and,
 * in this JVM, against the {@code gmac_test_a11y_*} hooks that run their Objective-C.
 * <p>
 * The golden was captured, with {@code -D}{@value #CAPTURE_PROPERTY}{@code =true}, from the JNI natives
 * {@code getString}, {@code isEqualToString}, {@code NSAccessibilityRoleDescription},
 * {@code NSAccessibilityActionDescription} and the {@code ptr}s {@code _initEnum} stored, in this JVM, before
 * {@code MacAccessible} was moved to Java. None of them asserted a thread or reached a {@code GLASS_CHECK_EXCEPTION},
 * and {@code MacAccessible}'s JNI {@code _initIDs} only cached IDs, so the capture needs no child JVM and no
 * {@code MacApplication}. The descriptions are AppKit's localized strings, so the golden is gated on the OS release
 * and the user's preferred languages.
 * <p>
 * No accessibility peer is made: {@code gmac_a11y_create_peer} is called only through a stand-in, and
 * {@code gmac_a11y_peer_id} through a stand-in or on NULL, for which it answers 0 without a message
 * ({@code GlassAccessible.m} 531). {@code gmac_a11y_unignored_children} is called on a null array only: its count of
 * -1 makes {@code arrayOfElements} answer NULL ({@code GlassAccessible.m} 47), and the export returns (515) before
 * {@code NSAccessibilityUnignoredChildren} (516) (PB17). {@code gmac_a11y_invalidate_parent} is not called at all:
 * even on NULL it sends {@code +class} to {@code AccessibleBase} for its {@code isKindOfClass:} argument
 * ({@code AccessibleBase.m} 290), which initializes that class and its AppKit superclass. Nothing posts a
 * notification or asks for an unignored ancestor.
 * {@code gmac_a11y_id_to_variant} messages only the CoreFoundation objects made here or the Foundation objects the
 * variant hook made, and the only AppKit functions called are the two localized-string ones. Nothing here creates an
 * {@code NSApplication}: AppKit's {@code NSApp} is nil before the first call into the library and after the last.
 * <p>
 * Each slot runs on a fresh platform thread standing for the main thread, whose reports go to the test's list.
 */
@EnabledOnOs(OS.MAC)
public class MacAccessibleNativeTest {

    static final String CAPTURE_COMMIT = "1a6788cc4d";
    static final String GOLDEN = "mac-a11y-golden-" + CAPTURE_COMMIT + ".txt";
    static final String CAPTURE_PROPERTY = "jfx.mac.a11y.golden.capture";

    private static final String RESOURCE_DIRECTORY = "src/test/resources/test/com/sun/glass/ui/mac";
    private static final String CLASSES_DIRECTORY = "target/test-classes/test/com/sun/glass/ui/mac";
    private static final List<String> NATIVE_SOURCES = List.of("src/main/native-glass/mac/GlassAccessible.m",
            "src/main/native-glass/mac/a11y/AccessibleBase.m");

    private static final HexFormat HEX = HexFormat.of();

    private static final int GMAC_UPCALL_OK = 0;
    private static final int GMAC_UPCALL_THREW = 1;
    private static final int GMAC_OK = 0;
    private static final int GMAC_ERR_NO_DATA = -1;

    private static final int SLOT_COUNT = 16;
    private static final int ATTRIBUTE_NAMES = 0;
    private static final int ATTRIBUTE_VALUE = 1;
    private static final int IS_ATTRIBUTE_SETTABLE = 2;
    private static final int INDEX_OF_CHILD = 4;
    private static final int ARRAY_ATTRIBUTE_COUNT = 5;
    private static final int ARRAY_ATTRIBUTE_VALUES = 6;
    private static final int PARAMETERIZED_ATTRIBUTE_NAMES = 7;
    private static final int ATTRIBUTE_VALUE_FOR_PARAMETER = 8;
    private static final int ACTION_NAMES = 9;
    private static final int ACTION_DESCRIPTION = 10;
    private static final int IS_IGNORED = 12;
    private static final int HIT_TEST = 13;
    private static final int FOCUSED_UI_ELEMENT = 14;
    private static final int PEER_RELEASED = 15;

    /** {@code MacVariant}'s type constants, which the test spells out and checks against the class. */
    private static final int NS_ARRAY_ID = 1;
    private static final int NS_ARRAY_INT = 3;
    private static final int NS_ATTRIBUTED_STRING = 5;
    private static final int NS_DATE = 7;
    private static final int NS_DICTIONARY = 8;
    private static final int NS_NUMBER_BOOLEAN = 9;
    private static final int NS_NUMBER_INT = 10;
    private static final int NS_NUMBER_DOUBLE = 12;
    private static final int NS_STRING = 13;
    private static final int NS_VALUE_POINT = 15;
    private static final int NS_VALUE_SIZE = 16;
    private static final int NS_VALUE_RECTANGLE = 17;
    private static final int NS_VALUE_RANGE = 18;
    private static final int NS_OBJECT = 19;

    /** The fixed arguments of {@code gmac_test_fire_accessible_callback}, which are no objects. */
    private static final String ATTRIBUTE = "0x102030405060701";
    private static final String VALUE = "0x102030405060702";
    private static final String CHILD = "0x102030405060703";
    private static final String PARAMETER = "0x102030405060704";
    private static final String ACTION = "0x102030405060705";

    private static final byte SENTINEL = (byte) 0xA5;

    private static final String CALLER = "MacAccessibleNativeTest-caller";

    private static final long FAKE_PEER = 0x7E57_0000_A11AL;

    private static final List<String> ENUMS = List.of("MacAttribute", "MacRole", "MacSubrole", "MacAction",
            "MacNotification", "MacOrientation", "MacText");

    private static final Path HEADER = Path.of("src/main/native-glass/mac/glass_mac_api.h");

    private static final Pattern ACCESSIBLE_CALLBACKS = Pattern.compile(
            "typedef\\s+struct\\s+GmacAccessibleCallbacks\\s*\\{(.*?)}\\s*GmacAccessibleCallbacks\\s*;",
            Pattern.DOTALL);

    private static final Pattern SLOT = Pattern.compile("(\\w+)\\s*\\(\\s*\\*\\s*(\\w+)\\s*\\)\\s*\\(([^)]*)\\)");

    private static final Pattern COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    private int attachedBefore;

    /** A string the corpus makes in each process alike: +1 when {@code owned}, else an immortal constant or 0. */
    private record StringCase(String name, LongSupplier make, boolean owned) {

        long create() {
            return make.getAsLong();
        }

        void release(long string) {
            if (owned && string != 0) {
                MacGlassNativeShim.cfRelease(string);
            }
        }
    }

    /** The four natives the golden records, as the JNI or the Java calls them. */
    private interface Natives {

        String utf8(long string);

        boolean isEqualToString(long string1, long string2);

        String roleDescription(long role, long subrole);

        String actionDescription(long action);
    }

    /** What a slot left in its out-parameters, and the status it answered. */
    private record Fired(int status, MemorySegment out) {

        boolean untouched() {
            for (byte b : out.toArray(JAVA_BYTE)) {
                if (b != SENTINEL) {
                    return false;
                }
            }
            return true;
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
    static void noApplicationExistsAfterTheLastCallAndTheOracleRan() {
        assertTrue(MacGlassNativeShim.nsAppIsNil(), "an NSApplication exists after the last call into libglass");
        ParityGate.ledger(MacAccessibleNativeTest.class).assertOracleRan();
    }

    @BeforeEach
    void installTheTable() {
        attachedBefore = MacGlassNativeShim.setMainJavaAttached(1);
        MacGlassNativeShim.installAccessibleCallbacks();
    }

    @AfterEach
    void reinstallTheTableAndRestoreTheAttachedFlag() {
        MacGlassNativeShim.installAccessibleCallbacks();
        MacGlassNativeShim.setMainJavaAttached(attachedBefore);
    }

    @Test
    public void theVariantIsWhereTheCompilerPutIt() {
        StructLayout layout = MacGlassNativeShim.layouts().get("GmacVariant");
        List<String> members = List.of("type", "int1", "int2", "location", "length", "float1", "float2", "float3",
                "float4", "long_array_count", "long1", "double1", "key", "long_array", "int_array",
                "int_array_count", "chars_length", "chars", "utf8", "variant_array", "variant_array_count",
                "utf8_length");
        List<Long> offsets = List.of(0L, 4L, 8L, 12L, 16L, 20L, 24L, 28L, 32L, 36L, 40L, 48L, 56L, 64L, 72L, 80L,
                84L, 88L, 96L, 104L, 112L, 116L);
        assertEquals(120, layout.byteSize());
        assertEquals(120, MacGlassNativeShim.sizeOf("GmacVariant"));
        assertEquals(members, names(layout));
        for (int member = 0; member < members.size(); member++) {
            long offset = layout.byteOffset(PathElement.groupElement(members.get(member)));
            assertEquals(offsets.get(member), offset, members.get(member));
            assertEquals(offset, MacGlassNativeShim.offsetOf("GmacVariant", member), "offsetof " + members.get(member));
        }
        assertEquals(-1, MacGlassNativeShim.offsetOf("GmacVariant", members.size()), "past its last member");
        assertEquals(-1, MacGlassNativeShim.offsetOf("GmacVariant", -1), "before its first member");
    }

    @Test
    public void theVariantTypesAreThoseOfMacVariant() {
        Map<String, Integer> types = MacGlassNativeShim.variantTypes();
        Map<String, Integer> spelled = Map.ofEntries(entry("NSArray_id", NS_ARRAY_ID),
                entry("NSArray_int", NS_ARRAY_INT), entry("NSAttributedString", NS_ATTRIBUTED_STRING),
                entry("NSDate", NS_DATE), entry("NSDictionary", NS_DICTIONARY),
                entry("NSNumber_Boolean", NS_NUMBER_BOOLEAN), entry("NSNumber_Int", NS_NUMBER_INT),
                entry("NSNumber_Double", NS_NUMBER_DOUBLE), entry("NSString", NS_STRING),
                entry("NSValue_point", NS_VALUE_POINT), entry("NSValue_size", NS_VALUE_SIZE),
                entry("NSValue_rectangle", NS_VALUE_RECTANGLE), entry("NSValue_range", NS_VALUE_RANGE),
                entry("NSObject", NS_OBJECT));
        spelled.forEach((name, value) -> assertEquals(value, types.get(name), name));
    }

    @Test
    public void theTableIsTheSixteenSlotsOfTheHeaderInItsOrder() throws IOException {
        StructLayout table = MacGlassNativeShim.layouts().get("GmacAccessibleCallbacks");
        assertEquals(128, table.byteSize());
        assertEquals(128, MacGlassNativeShim.sizeOf("GmacAccessibleCallbacks"));
        List<String> headerNames = headerSlots().stream().map(HeaderSlot::name).toList();
        assertEquals(List.of("attribute_names", "attribute_value", "is_attribute_settable", "set_value",
                "index_of_child", "array_attribute_count", "array_attribute_values", "parameterized_attribute_names",
                "attribute_value_for_parameter", "action_names", "action_description", "perform_action",
                "is_ignored", "hit_test", "focused_ui_element", "peer_released"), headerNames);
        assertEquals(headerNames, MacGlassNativeShim.accessibleSlotNames());
        assertEquals(headerNames, names(table));
    }

    @Test
    public void everySlotIsDialledWithTheCarriersOfItsPrototype() throws IOException {
        List<HeaderSlot> header = headerSlots();
        List<FunctionDescriptor> descriptors = MacGlassNativeShim.accessibleSlotDescriptors();
        assertEquals(header.size(), descriptors.size());
        for (int slot = 0; slot < header.size(); slot++) {
            assertEquals(header.get(slot).type(), descriptors.get(slot).toMethodType(), header.get(slot).name());
        }
    }

    @Test
    public void everySlotReachesTheReceiverOfItsIdAndWritesWhatItAnswered() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        Variant value = Variant.of(NS_STRING).withString("Wert");
        Variant parameterValue = Variant.of(NS_NUMBER_INT).withInts(42, 0);
        Map<String, Object> answers = Map.ofEntries(
                entry("accessibilityAttributeNames", List.of(0x11L, 0x12L)),
                entry("accessibilityAttributeValue", value),
                entry("accessibilityIsAttributeSettable", true),
                entry("accessibilityIndexOfChild", 7L),
                entry("accessibilityArrayAttributeCount", 3),
                entry("accessibilityArrayAttributeValues", List.of(0x21L, 0x22L, 0x23L)),
                entry("accessibilityParameterizedAttributeNames", List.of(0x31L)),
                entry("accessibilityAttributeValueForParameter", parameterValue),
                entry("accessibilityActionNames", List.of()),
                entry("accessibilityActionDescription", "Drücken 😀"),
                entry("accessibilityIsIgnored", true),
                entry("accessibilityHitTest", 0x41L),
                entry("accessibilityFocusedUIElement", 0x51L));
        long peerId = MacGlassNativeShim.registerAccessible(new AccessibleRecorder(events, answers, false));
        List<String> otherEvents = new CopyOnWriteArrayList<>();
        MacGlassNativeShim.registerAccessible(new AccessibleRecorder(otherEvents, answers, false));
        List<Fired> fired = onFreshThread(events, () -> fireEverySlotButReleased(peerId));
        for (int slot = 0; slot < fired.size(); slot++) {
            assertEquals(GMAC_UPCALL_OK, fired.get(slot).status(), "slot " + slot);
        }
        assertEquals(List.of("accessibilityAttributeNames()", "accessibilityAttributeValue(" + ATTRIBUTE + ")",
                "accessibilityIsAttributeSettable(" + ATTRIBUTE + ")",
                "accessibilitySetValue(" + VALUE + ", " + ATTRIBUTE + ")", "accessibilityIndexOfChild(" + CHILD + ")",
                "accessibilityArrayAttributeCount(" + ATTRIBUTE + ")",
                "accessibilityArrayAttributeValues(" + ATTRIBUTE + ", 1001, 1002)",
                "accessibilityParameterizedAttributeNames()",
                "accessibilityAttributeValueForParameter(" + ATTRIBUTE + ", " + PARAMETER + ")",
                "accessibilityActionNames()", "accessibilityActionDescription(" + ACTION + ")",
                "accessibilityPerformAction(" + ACTION + ")", "accessibilityIsIgnored()",
                "accessibilityHitTest(1.5, 2.5)", "accessibilityFocusedUIElement()"), events);
        assertEquals(List.of(), otherEvents);
        assertEquals(List.of(0x11L, 0x12L), takeIds(fired.get(ATTRIBUTE_NAMES)));
        assertEquals(value, MacGlassNativeShim.takeWrittenVariant(fired.get(ATTRIBUTE_VALUE).out()));
        assertEquals(1, fired.get(IS_ATTRIBUTE_SETTABLE).out().get(JAVA_INT, 0));
        assertEquals(7L, fired.get(INDEX_OF_CHILD).out().get(JAVA_LONG, 0));
        assertEquals(3, fired.get(ARRAY_ATTRIBUTE_COUNT).out().get(JAVA_INT, 0));
        assertEquals(List.of(0x21L, 0x22L, 0x23L), takeIds(fired.get(ARRAY_ATTRIBUTE_VALUES)));
        assertEquals(List.of(0x31L), takeIds(fired.get(PARAMETERIZED_ATTRIBUTE_NAMES)));
        assertEquals(parameterValue,
                MacGlassNativeShim.takeWrittenVariant(fired.get(ATTRIBUTE_VALUE_FOR_PARAMETER).out()));
        assertEquals(0L, fired.get(ACTION_NAMES).out().get(JAVA_LONG, 0), "an empty array needs no block");
        assertEquals(List.of(), takeIds(fired.get(ACTION_NAMES)));
        Fired description = fired.get(ACTION_DESCRIPTION);
        assertEquals("Drücken 😀", MacGlassNativeShim.takeCharBlock(description.out().get(JAVA_LONG, 0),
                description.out().get(JAVA_INT, 8)));
        assertEquals(1, fired.get(IS_IGNORED).out().get(JAVA_INT, 0));
        assertEquals(0x41L, fired.get(HIT_TEST).out().get(JAVA_LONG, 0));
        assertEquals(0x51L, fired.get(FOCUSED_UI_ELEMENT).out().get(JAVA_LONG, 0));
        assertTrue(MacGlassNativeShim.isAccessibleRegistered(peerId), "only peer_released forgets an id");
    }

    /** The C set every out-parameter to what the JNI read of a null answer, so a null answer writes nothing. */
    @Test
    public void aNullAnswerLeavesTheOutParametersAsTheCSetThem() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        long peerId = MacGlassNativeShim.registerAccessible(new AccessibleRecorder(events, Map.of(), false));
        List<Fired> fired = onFreshThread(events, () -> fireEverySlotButReleased(peerId));
        for (int slot : new int[] {ATTRIBUTE_NAMES, ATTRIBUTE_VALUE, ARRAY_ATTRIBUTE_VALUES,
                PARAMETERIZED_ATTRIBUTE_NAMES, ATTRIBUTE_VALUE_FOR_PARAMETER, ACTION_NAMES, ACTION_DESCRIPTION}) {
            assertEquals(GMAC_UPCALL_OK, fired.get(slot).status(), "slot " + slot);
            assertTrue(fired.get(slot).untouched(), "slot " + slot + " wrote for a null answer");
        }
        assertEquals(0, fired.get(IS_ATTRIBUTE_SETTABLE).out().get(JAVA_INT, 0));
        assertEquals(0, fired.get(IS_IGNORED).out().get(JAVA_INT, 0));
        assertEquals(0L, fired.get(HIT_TEST).out().get(JAVA_LONG, 0));
        assertEquals(15, events.size());
    }

    @Test
    public void whatASlotThrowsIsReportedAtOnceAndLeavesItsOutParameters() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        long peerId = MacGlassNativeShim.registerAccessible(new AccessibleRecorder(events, Map.of(), true));
        List<Fired> fired = onFreshThread(events, () -> fireEverySlotButReleased(peerId));
        List<String> calls = new ArrayList<>();
        for (int index = 0; index < events.size(); index += 2) {
            calls.add(events.get(index));
            assertEquals(report("java.lang.IllegalStateException: " + events.get(index)), events.get(index + 1));
        }
        assertEquals(15, calls.size(), events.toString());
        for (int slot = 0; slot < fired.size(); slot++) {
            assertEquals(GMAC_UPCALL_THREW, fired.get(slot).status(), "slot " + slot);
            assertTrue(fired.get(slot).untouched(), "slot " + slot + " wrote after its target threw");
        }
    }

    @Test
    public void peerReleasedForgetsOnlyItsIdOnAnyThreadAndNoSlotReachesItAfterwards() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        long released = MacGlassNativeShim.registerAccessible(new AccessibleRecorder(events, Map.of(), true));
        List<String> otherEvents = new CopyOnWriteArrayList<>();
        long other = MacGlassNativeShim.registerAccessible(new AccessibleRecorder(otherEvents, Map.of(), false));
        FutureTask<Integer> release = new FutureTask<>(() -> MacGlassNativeShim.fireAccessibleCallback(
                PEER_RELEASED, released, MemorySegment.NULL));
        Thread.ofPlatform().name("MacAccessibleNativeTest-releaser").start(release);
        assertEquals(GMAC_UPCALL_OK, release.get(30, TimeUnit.SECONDS));
        assertFalse(MacGlassNativeShim.isAccessibleRegistered(released));
        assertTrue(MacGlassNativeShim.isAccessibleRegistered(other));
        List<Fired> fired = onFreshThread(events, () -> fireEverySlotButReleased(released));
        for (Fired slot : fired) {
            assertEquals(GMAC_UPCALL_OK, slot.status());
            assertTrue(slot.untouched());
        }
        assertEquals(List.of(), events);
        assertEquals(GMAC_UPCALL_OK, fire(IS_IGNORED, other).status());
        assertEquals(List.of("accessibilityIsIgnored()"), otherEvents);
    }

    @Test
    public void anIdTheRegistryNeverHeldRunsNothing() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        for (long peerId : new long[] {0L, -1L, Long.MAX_VALUE}) {
            List<Fired> fired = onFreshThread(events, () -> fireEverySlotButReleased(peerId));
            for (Fired slot : fired) {
                assertEquals(GMAC_UPCALL_OK, slot.status(), "peer id " + peerId);
                assertTrue(slot.untouched(), "peer id " + peerId);
            }
            assertEquals(GMAC_UPCALL_OK, fire(PEER_RELEASED, peerId).status());
        }
        assertEquals(List.of(), events);
    }

    @Test
    public void idsAreNonzeroDistinctAndKeptUntilReleased() {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ids.add(MacGlassNativeShim.registerAccessible(new AccessibleRecorder(new CopyOnWriteArrayList<>(),
                    Map.of(), false)));
        }
        assertEquals(3, new HashSet<>(ids).size(), ids.toString());
        for (long peerId : ids) {
            assertNotEquals(0L, peerId);
            assertTrue(MacGlassNativeShim.isAccessibleRegistered(peerId));
            assertEquals(GMAC_UPCALL_OK, fire(PEER_RELEASED, peerId).status());
            assertFalse(MacGlassNativeShim.isAccessibleRegistered(peerId));
        }
    }

    @Test
    public void withoutATableOrItsSlotNothingIsDialled() {
        List<String> events = new CopyOnWriteArrayList<>();
        long peerId = MacGlassNativeShim.registerAccessible(new AccessibleRecorder(events, Map.of(), false));
        try (Arena arena = Arena.ofConfined()) {
            assertEquals(GMAC_OK, MacGlassNativeShim.installAccessibleTable(arena.allocate(ADDRESS, SLOT_COUNT)));
            for (int slot = 0; slot < SLOT_COUNT; slot++) {
                assertEquals(GMAC_ERR_NO_DATA, fire(slot, peerId).status(), "slot " + slot);
            }
        }
        assertEquals(GMAC_OK, MacGlassNativeShim.installAccessibleTable(MemorySegment.NULL));
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            assertEquals(GMAC_ERR_NO_DATA, fire(slot, peerId).status(), "slot " + slot);
        }
        MacGlassNativeShim.installAccessibleCallbacks();
        assertEquals(Integer.MIN_VALUE, fire(-1, peerId).status());
        assertEquals(Integer.MIN_VALUE, fire(SLOT_COUNT, peerId).status());
        assertEquals(List.of(), events);
        assertTrue(MacGlassNativeShim.isAccessibleRegistered(peerId), "peer_released never ran");
    }

    @Test
    public void theLibraryDialsItsOwnCopyOfTheTable() {
        List<String> events = new CopyOnWriteArrayList<>();
        long peerId = MacGlassNativeShim.registerAccessible(new AccessibleRecorder(events, Map.of(), false));
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment table = arena.allocate(ADDRESS, SLOT_COUNT);
            for (int slot = 0; slot < SLOT_COUNT; slot++) {
                table.setAtIndex(ADDRESS, slot, MacGlassNativeShim.accessibleCallbackStub(slot));
            }
            assertEquals(GMAC_OK, MacGlassNativeShim.installAccessibleTable(table));
            table.fill((byte) 0);
        }
        assertEquals(GMAC_UPCALL_OK, fire(IS_IGNORED, peerId).status());
        assertEquals(List.of("accessibilityIsIgnored()"), events);
    }

    /**
     * What a slot hands the C of a variant is every field of it, a null array as a NULL block of a count of -1, a
     * null string as NULL units, an empty one as units all the same, and a null element as the C's null variant.
     */
    @Test
    public void aVariantCrossesWithEveryFieldAndBlock() throws Exception {
        long key = 0x7E57_0000_4B45L;
        Variant style = Variant.of(NS_DICTIONARY).withLongArray(List.of(0x101L, 0x102L))
                .withVariantArray(List.of(Variant.of(NS_STRING).withString("Helvetica"),
                        Variant.of(NS_NUMBER_DOUBLE).withDouble1(13.5)))
                .withStyle(key, 1, 3);
        List<Variant> variants = List.of(
                Variant.of(NS_STRING).withString(""),
                Variant.of(NS_STRING),
                Variant.of(NS_STRING).withString("a\u0000b日本😀\uD800"),
                Variant.of(NS_ARRAY_ID).withLongArray(List.of()),
                Variant.of(NS_ARRAY_ID).withLongArray(List.of(1L, -1L, Long.MIN_VALUE)),
                Variant.of(NS_ARRAY_INT).withIntArray(List.of(3, -4, Integer.MAX_VALUE)),
                Variant.of(NS_VALUE_RECTANGLE).withFloats(1.5f, -2.5f, Float.MAX_VALUE, Float.MIN_VALUE),
                Variant.of(NS_VALUE_RANGE).withInts(-7, 8),
                Variant.of(NS_DATE).withLong1(1_700_000_000L),
                Variant.of(NS_NUMBER_DOUBLE).withDouble1(-0.0),
                Variant.of(NS_ATTRIBUTED_STRING).withString("abcdef").withVariantArray(Arrays.asList(style, null)),
                Variant.of(NS_ATTRIBUTED_STRING).withString("abc").withVariantArray(List.of()));
        for (Variant variant : variants) {
            List<String> events = new CopyOnWriteArrayList<>();
            long peerId = MacGlassNativeShim.registerAccessible(new AccessibleRecorder(events,
                    Map.of("accessibilityAttributeValue", variant), false));
            Fired fired = onFreshThread(events, () -> fire(ATTRIBUTE_VALUE, peerId));
            assertEquals(GMAC_UPCALL_OK, fired.status(), variant.toString());
            assertEquals(variant, MacGlassNativeShim.takeWrittenVariant(fired.out()));
        }
    }

    /** The peers make each kind of variant into the object {@code variantToID} made of it under JNI. */
    @Test
    public void thePeersMakeTheObjectOfEachKindOfVariant() throws Exception {
        assertEquals(Variant.of(NS_NUMBER_BOOLEAN).withInts(1, 0),
                roundTrip(Variant.of(NS_NUMBER_BOOLEAN).withInts(1, 0), NS_NUMBER_BOOLEAN));
        assertEquals(Variant.of(NS_NUMBER_INT).withInts(-7, 0),
                roundTrip(Variant.of(NS_NUMBER_INT).withInts(-7, 0), NS_NUMBER_INT));
        assertEquals(Variant.of(NS_NUMBER_DOUBLE).withDouble1(2.25),
                roundTrip(Variant.of(NS_NUMBER_DOUBLE).withDouble1(2.25), NS_NUMBER_DOUBLE));
        assertEquals(Variant.of(NS_STRING).withString("Drücken 日本"),
                roundTrip(Variant.of(NS_STRING).withString("Drücken 日本"), NS_STRING));
        assertEquals(Variant.of(NS_STRING).withString(""), roundTrip(Variant.of(NS_STRING).withString(""), NS_STRING));
        assertEquals(Variant.of(NS_ARRAY_INT).withIntArray(List.of(3, -4)),
                roundTrip(Variant.of(NS_ARRAY_INT).withIntArray(List.of(3, -4)), NS_ARRAY_INT));
        assertEquals(Variant.of(NS_VALUE_POINT).withFloats(1.5f, 2.5f, 0f, 0f),
                roundTrip(Variant.of(NS_VALUE_POINT).withFloats(1.5f, 2.5f, 9f, 9f), NS_VALUE_POINT));
        assertEquals(Variant.of(NS_VALUE_SIZE).withFloats(3f, 4f, 0f, 0f),
                roundTrip(Variant.of(NS_VALUE_SIZE).withFloats(3f, 4f, 0f, 0f), NS_VALUE_SIZE));
        assertEquals(Variant.of(NS_VALUE_RECTANGLE).withFloats(1f, 2f, 3f, 4f),
                roundTrip(Variant.of(NS_VALUE_RECTANGLE).withFloats(1f, 2f, 3f, 4f), NS_VALUE_RECTANGLE));
        assertEquals(Variant.of(NS_VALUE_RANGE).withInts(5, 6),
                roundTrip(Variant.of(NS_VALUE_RANGE).withInts(5, 6), NS_VALUE_RANGE));
        long first = MacGlassNativeShim.cfString("first");
        long second = MacGlassNativeShim.cfString("second");
        try {
            assertEquals(Variant.of(NS_ARRAY_ID).withLongArray(List.of(first, second)),
                    roundTrip(Variant.of(NS_ARRAY_ID).withLongArray(List.of(first, second)), NS_ARRAY_ID));
            assertEquals(Variant.of(NS_ARRAY_ID).withLongArray(List.of()),
                    roundTrip(Variant.of(NS_ARRAY_ID).withLongArray(List.of()), NS_ARRAY_ID));
            VariantObject object = makeObject(Variant.of(NS_OBJECT).withLong1(first));
            assertEquals(new VariantObject(GMAC_OK, first), object, "an NSObject variant is its id");
            MacGlassNativeShim.cfRelease(object.object());
        } finally {
            MacGlassNativeShim.cfRelease(first);
            MacGlassNativeShim.cfRelease(second);
        }
    }

    @Test
    public void aDateIsTheSecondsSince1970() throws Exception {
        VariantObject date = makeObject(Variant.of(NS_DATE).withLong1(1_700_000_000L));
        assertEquals(GMAC_OK, date.status());
        try {
            assertEquals(1_700_000_000.0, MacGlassNativeShim.cfDateSince1970(date.object()));
        } finally {
            MacGlassNativeShim.cfRelease(date.object());
        }
    }

    @Test
    public void aDictionaryHasTheValuesOfItsKeysAndNoneWhenTheCountsDiffer() throws Exception {
        long width = MacGlassNativeShim.cfString("width");
        long name = MacGlassNativeShim.cfString("name");
        try {
            Variant dictionary = Variant.of(NS_DICTIONARY).withLongArray(List.of(width, name))
                    .withVariantArray(List.of(Variant.of(NS_NUMBER_INT).withInts(5, 0),
                            Variant.of(NS_STRING).withString("v")));
            VariantObject object = makeObject(dictionary);
            assertEquals(GMAC_OK, object.status());
            try {
                assertEquals(2, MacGlassNativeShim.cfDictionaryCount(object.object()));
                assertEquals(Variant.of(NS_NUMBER_INT).withInts(5, 0), MacGlassNativeShim.idToMacVariant(
                        MacGlassNativeShim.cfDictionaryValue(object.object(), width), NS_NUMBER_INT));
                assertEquals(Variant.of(NS_STRING).withString("v"), MacGlassNativeShim.idToMacVariant(
                        MacGlassNativeShim.cfDictionaryValue(object.object(), name), NS_STRING));
            } finally {
                MacGlassNativeShim.cfRelease(object.object());
            }
            Variant uneven = dictionary.withVariantArray(List.of(Variant.of(NS_NUMBER_INT)));
            assertEquals(new VariantObject(GMAC_ERR_NO_DATA, 0L), makeObject(uneven));
            Variant nullValue = dictionary.withVariantArray(Arrays.asList(Variant.of(NS_NUMBER_INT), null));
            assertEquals(new VariantObject(GMAC_ERR_NO_DATA, 0L), makeObject(nullValue),
                    "a null value is left out, as jArrayToNSArray left it out, and then the counts differ");
        } finally {
            MacGlassNativeShim.cfRelease(width);
            MacGlassNativeShim.cfRelease(name);
        }
    }

    @Test
    public void anAttributedStringHasEveryStyleWithAKeyAndAValue() throws Exception {
        long font = MacGlassNativeShim.cfString("AXFont");
        long ignored = MacGlassNativeShim.cfString("AXIgnored");
        try {
            Variant style = Variant.of(NS_NUMBER_INT).withInts(9, 0).withStyle(font, 1, 2);
            Variant keyless = Variant.of(NS_NUMBER_INT).withInts(8, 0).withStyle(0L, 0, 6);
            Variant valueless = Variant.of(NS_STRING).withStyle(ignored, 0, 6);
            Variant text = Variant.of(NS_ATTRIBUTED_STRING).withString("abcdef")
                    .withVariantArray(Arrays.asList(style, null, keyless, valueless));
            VariantObject object = makeObject(text);
            assertEquals(GMAC_OK, object.status());
            try {
                assertEquals("abcdef", MacGlassNativeShim.attributedStringText(object.object()));
                StyleRun run = MacGlassNativeShim.attributedStringAttribute(object.object(), 1, font);
                assertEquals(1, run.location());
                assertEquals(2, run.length());
                assertEquals(Variant.of(NS_NUMBER_INT).withInts(9, 0),
                        MacGlassNativeShim.idToMacVariant(run.value(), NS_NUMBER_INT));
                assertEquals(0L, MacGlassNativeShim.attributedStringAttribute(object.object(), 0, ignored).value());
            } finally {
                MacGlassNativeShim.cfRelease(object.object());
            }
            assertEquals(new VariantObject(GMAC_ERR_NO_DATA, 0L),
                    makeObject(Variant.of(NS_ATTRIBUTED_STRING).withVariantArray(List.of(style))),
                    "an attributed string of a null string is nil");
        } finally {
            MacGlassNativeShim.cfRelease(font);
            MacGlassNativeShim.cfRelease(ignored);
        }
    }

    @Test
    public void aNullVariantAndANullStringAreNil() throws Exception {
        assertEquals(new VariantObject(GMAC_ERR_NO_DATA, 0L), makeObject(null));
        assertEquals(new VariantObject(GMAC_ERR_NO_DATA, 0L), makeObject(Variant.of(NS_STRING)));
        assertEquals(new VariantObject(GMAC_ERR_NO_DATA, 0L), makeObject(Variant.of(NS_ARRAY_ID)));
    }

    /** {@code idToMacVariant} sets the fields the JNI set for the type, and only those. */
    @Test
    public void idToMacVariantReadsWhatTheJniReadOfEachType() {
        long number = MacGlassNativeShim.cfNumber(7);
        long fraction = MacGlassNativeShim.cfNumber(2.75);
        long string = MacGlassNativeShim.cfString("Wert é");
        long empty = MacGlassNativeShim.cfString("");
        long array = MacGlassNativeShim.cfArray(number, string);
        long numbers = MacGlassNativeShim.cfArray(number, fraction);
        long none = MacGlassNativeShim.cfArray();
        try {
            assertEquals(Variant.of(NS_NUMBER_BOOLEAN).withInts(1, 0),
                    MacGlassNativeShim.idToMacVariant(MacGlassNativeShim.cfBoolean(true), NS_NUMBER_BOOLEAN));
            assertEquals(Variant.of(NS_NUMBER_BOOLEAN),
                    MacGlassNativeShim.idToMacVariant(MacGlassNativeShim.cfBoolean(false), NS_NUMBER_BOOLEAN));
            assertEquals(Variant.of(NS_NUMBER_INT).withInts(7, 0),
                    MacGlassNativeShim.idToMacVariant(number, NS_NUMBER_INT));
            assertEquals(Variant.of(NS_NUMBER_INT).withInts(2, 0),
                    MacGlassNativeShim.idToMacVariant(fraction, NS_NUMBER_INT));
            assertEquals(Variant.of(NS_NUMBER_DOUBLE).withDouble1(2.75),
                    MacGlassNativeShim.idToMacVariant(fraction, NS_NUMBER_DOUBLE));
            assertEquals(Variant.of(NS_STRING).withString("Wert é"),
                    MacGlassNativeShim.idToMacVariant(string, NS_STRING));
            assertEquals(Variant.of(NS_STRING).withString(""), MacGlassNativeShim.idToMacVariant(empty, NS_STRING));
            assertEquals(Variant.of(NS_ARRAY_ID).withLongArray(List.of(number, string)),
                    MacGlassNativeShim.idToMacVariant(array, NS_ARRAY_ID));
            assertEquals(Variant.of(NS_ARRAY_INT).withIntArray(List.of(7, 2)),
                    MacGlassNativeShim.idToMacVariant(numbers, NS_ARRAY_INT));
            assertEquals(Variant.of(NS_ARRAY_ID).withLongArray(List.of()),
                    MacGlassNativeShim.idToMacVariant(none, NS_ARRAY_ID));
            assertEquals(Variant.of(NS_ARRAY_INT).withIntArray(List.of()),
                    MacGlassNativeShim.idToMacVariant(none, NS_ARRAY_INT));
            assertEquals(Variant.of(NS_DATE), MacGlassNativeShim.idToMacVariant(number, NS_DATE),
                    "a type the JNI read nothing of");
            assertEquals(Variant.of(NS_ARRAY_ID).withLongArray(List.of()),
                    MacGlassNativeShim.idToMacVariant(0L, NS_ARRAY_ID), "nil answers 0 to -count");
            assertEquals(Variant.of(NS_STRING), MacGlassNativeShim.idToMacVariant(0L, NS_STRING),
                    "nil answers a NULL UTF8String");
        } finally {
            for (long object : new long[] {number, fraction, string, empty, array, numbers, none}) {
                MacGlassNativeShim.cfRelease(object);
            }
        }
    }

    @Test
    public void anAllocationTheExportCouldNotMakeIsTheOutOfMemoryErrorOfTheJni() {
        assertThrows(OutOfMemoryError.class, () -> MacGlassNativeShim.idToMacVariant(0L, NS_STRING,
                GMAC_ERR_NO_DATA));
        assertThrows(OutOfMemoryError.class, () -> MacGlassNativeShim.unignoredChildren(new long[] {1L},
                (ids, count) -> new ChildrenAnswer(GMAC_ERR_NO_DATA, null)));
        assertNull(MacGlassNativeShim.unignoredChildren(null,
                (ids, count) -> new ChildrenAnswer(GMAC_ERR_NO_DATA, null)), "a null array is null");
    }

    @Test
    public void unignoredChildrenPassesTheIdsAndAnswersThoseOfTheExport() {
        List<String> passed = new ArrayList<>();
        long[] children = MacGlassNativeShim.unignoredChildren(new long[] {0x11L, 0x12L, 0x13L}, (ids, count) -> {
            passed.add(ids + " " + count);
            return new ChildrenAnswer(GMAC_OK, new long[] {0x12L});
        });
        assertArrayEquals(new long[] {0x12L}, children);
        long[] none = MacGlassNativeShim.unignoredChildren(new long[0], (ids, count) -> {
            passed.add(ids + " " + count);
            return new ChildrenAnswer(GMAC_OK, null);
        });
        assertArrayEquals(new long[0], none);
        assertEquals(List.of("[17, 18, 19] 3", "null 0"), passed);
        assertNull(MacGlassNativeShim.unignoredChildren(null), "the export answers a null array before AppKit");
    }

    @Test
    public void createAccessiblePeerFilesANewIdBeforeTheExportAndTheIdNamesItsAccessible() throws Exception {
        Object accessible = MacGlassNativeShim.newMacAccessible();
        List<String> events = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        for (String role : new String[] {"BUTTON", "TEXT_FIELD"}) {
            long peer = MacGlassNativeShim.createAccessiblePeer(accessible, role, (units, length, peerId) -> {
                ids.add(peerId);
                events.add(units + " " + length + " " + MacGlassNativeShim.isAccessibleRegistered(peerId));
                return FAKE_PEER;
            });
            assertEquals(FAKE_PEER, peer);
        }
        assertEquals(List.of("BUTTON 6 true", "TEXT_FIELD 10 true"), events);
        assertEquals(2, new HashSet<>(ids).size(), "getNativeAccessible makes a peer again after dispose");
        for (long peerId : ids) {
            assertSame(accessible, MacGlassNativeShim.glassAccessibleToMacAccessible(FAKE_PEER, object -> peerId));
            assertEquals(GMAC_UPCALL_OK, fire(PEER_RELEASED, peerId).status());
            assertNull(MacGlassNativeShim.glassAccessibleToMacAccessible(FAKE_PEER, object -> peerId));
        }
        assertNull(MacGlassNativeShim.glassAccessibleToMacAccessible(0L), "gmac_a11y_peer_id answers 0 for NULL");
    }

    @Test
    public void aPeerTheExportCouldNotMakeLeavesNoIdBehind() {
        Object accessible = MacGlassNativeShim.newMacAccessible();
        List<Long> ids = new ArrayList<>();
        long peer = MacGlassNativeShim.createAccessiblePeer(accessible, "BUTTON", (units, length, peerId) -> {
            ids.add(peerId);
            assertTrue(MacGlassNativeShim.isAccessibleRegistered(peerId), "filed before the export");
            return 0L;
        });
        assertEquals(0L, peer);
        assertEquals(1, ids.size());
        assertFalse(MacGlassNativeShim.isAccessibleRegistered(ids.get(0)), "no peer_released comes for it");
    }

    @Test
    public void destroyAccessiblePeerReleasesThePeerOnce() {
        long peer = MacGlassNativeShim.cfString("a peer stands here");
        MacGlassNativeShim.cfRetain(peer);
        long before = MacGlassNativeShim.cfRetainCount(peer);
        MacGlassNativeShim.destroyAccessiblePeer(peer);
        assertEquals(before - 1, MacGlassNativeShim.cfRetainCount(peer));
        MacGlassNativeShim.cfRelease(peer);
    }

    /**
     * PB3 forbids calling {@code NSAccessibilityPostNotification} and {@code NSAccessibilityUnignoredAncestor}, so
     * only a lookup shows that they resolve. The other tests call the other AppKit functions
     * {@code MacAccessibilityCalls} binds, through the AppKit it opens.
     */
    @Test
    public void theAppKitFunctionsNoTestMayCallAreAppKitExports() {
        assertFalse(MacGlassNativeShim.appKitExports("NSAccessibilityNoSuchFunction"), "the lookup can fail");
        for (String function : List.of("NSAccessibilityPostNotification", "NSAccessibilityUnignoredAncestor")) {
            assertTrue(MacGlassNativeShim.appKitExports(function), function);
        }
    }

    @Test
    public void isEqualToStringIsThatOfItsObjectiveCBody() {
        ParityGate.Ledger ledger = ParityGate.ledger(MacAccessibleNativeTest.class);
        ledger.oracleAvailable();
        List<StringCase> cases = equalityCorpus();
        long[] strings = create(cases);
        try {
            for (int first = 0; first < cases.size(); first++) {
                for (int second = 0; second < cases.size(); second++) {
                    assertEquals(MacGlassNativeShim.oracleIsEqualToString(strings[first], strings[second]),
                            MacGlassNativeShim.isEqualToString(strings[first], strings[second]),
                            cases.get(first).name() + " and " + cases.get(second).name());
                    ledger.compared();
                }
            }
        } finally {
            release(cases, strings);
        }
    }

    @Test
    public void theUtf8ExtractionIsThatOfItsObjectiveCBody() {
        ParityGate.Ledger ledger = ParityGate.ledger(MacAccessibleNativeTest.class);
        ledger.oracleAvailable();
        for (StringCase stringCase : utf8Corpus()) {
            long string = stringCase.create();
            try {
                assertEquals(quote(MacGlassNativeShim.oracleUtf8String(string)),
                        quote(MacGlassNativeShim.utf8String(string)), stringCase.name());
                ledger.compared();
            } finally {
                stringCase.release(string);
            }
        }
    }

    @Test
    public void theDescriptionsAreThoseTheirObjectiveCBodiesRead() {
        ParityGate.Ledger ledger = ParityGate.ledger(MacAccessibleNativeTest.class);
        ledger.oracleAvailable();
        List<Long> subroles = new ArrayList<>(List.of(0L));
        subroles.addAll(MacGlassNativeShim.accessibleEnumPointers("MacSubrole"));
        for (long role : MacGlassNativeShim.accessibleEnumPointers("MacRole")) {
            for (long subrole : subroles) {
                assertEquals(quote(MacGlassNativeShim.oracleRoleDescription(role, subrole)),
                        quote(MacGlassNativeShim.roleDescription(role, subrole)));
                ledger.compared();
            }
        }
        for (long action : MacGlassNativeShim.accessibleEnumPointers("MacAction")) {
            assertEquals(quote(MacGlassNativeShim.oracleActionDescription(action)),
                    quote(MacGlassNativeShim.actionDescription(action)));
            ledger.compared();
        }
        for (StringCase extra : extraActions()) {
            long action = extra.create();
            try {
                assertEquals(quote(MacGlassNativeShim.oracleActionDescription(action)),
                        quote(MacGlassNativeShim.actionDescription(action)), extra.name());
                ledger.compared();
            } finally {
                extra.release(action);
            }
        }
    }

    /** An AppKit name is the data symbol's value itself; an "AX" name a string of its own, equal to the C's. */
    @Test
    public void everyEnumPointerIsThatOfTheObjectiveCBody() {
        ParityGate.Ledger ledger = ParityGate.ledger(MacAccessibleNativeTest.class);
        ledger.oracleAvailable();
        for (String enumName : ENUMS) {
            List<String> names = MacGlassNativeShim.accessibleEnumNames(enumName);
            List<Long> pointers = MacGlassNativeShim.accessibleEnumPointers(enumName);
            for (int index = 0; index < names.size(); index++) {
                assertEnumPointer(names.get(index), pointers.get(index), enumName + "." + names.get(index));
                ledger.compared();
            }
        }
    }

    @Test
    public void initEnumStopsAtTheFirstNameWithoutAPointer() {
        InitEnumAnswer answer = MacGlassNativeShim.initEnumProbe();
        assertFalse(answer.answer());
        assertEquals(MacGlassNativeShim.oracleEnumPointer("NSAccessibilityRoleAttribute"), answer.pointers().get(0));
        assertEnumPointer("AXJFXProbe", answer.pointers().get(1), "AXJFXProbe");
        assertEquals(List.of(0L, 0L), answer.pointers().subList(2, 4), "the names after the first without one");
        assertNull(MacGlassNativeShim.oracleEnumPointer("NSAccessibilityNoSuchProbeAttribute"));
        assertFalse(MacGlassNativeShim.initEnumOfNoConstants());
        MacGlassNativeShim.cfRelease(answer.pointers().get(1));
    }

    @Test
    public void aNameIsLookedUpOrMadeAsTheObjectiveCBodyDid() {
        for (String name : List.of("", "A", "AX", "xAX", "AXRole", "NSAccessibilityRoleAttribute",
                "NSAccessibilityNoSuchAttribute", "NSApp", "nsaccessibilityroleattribute")) {
            Long pointer = MacGlassNativeShim.enumPointer(name);
            if (pointer == null) {
                assertNull(MacGlassNativeShim.oracleEnumPointer(name), "the C found " + quote(name));
            } else {
                assertEnumPointer(name, pointer, quote(name));
                if (name.startsWith("AX")) {
                    MacGlassNativeShim.cfRelease(pointer);
                }
            }
        }
    }

    @Test
    public void theNativesAreThoseOfTheJniBodies() throws Exception {
        if (Boolean.getBoolean(CAPTURE_PROPERTY)) {
            capture();
            abort("the accessibility golden was captured to " + GOLDEN + "; a capture verifies nothing");
        }
        Map<String, String> golden = readGolden();
        ParityGate.Ledger ledger = ParityGate.ledger(MacAccessibleNativeTest.class);
        List<String> differences = new ArrayList<>();
        machine().forEach((key, value) -> {
            if (!value.equals(golden.get(key))) {
                differences.add(key + "=" + value + " (golden: " + golden.get(key) + ")");
            }
        });
        ledger.requireOracle(differences.isEmpty(), () -> "the accessibility golden describes another machine: "
                + differences + ". AppKit's localized descriptions are the OS release's and the user's languages'."
                + " A cross-machine oracle is a re-capture from the JNI natives of " + CAPTURE_COMMIT
                + " on that machine.");
        assertFalse(MacGlassNativeShim.accessibleGoldenNativesExist(),
                "the corpus would run the JNI natives, not the Java");
        Map<String, String> expected = compared(golden);
        Map<String, String> actual = run(javaNatives());
        assertEquals(expected.keySet(), actual.keySet(), "the cases of the corpus and of the golden");
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            assertEquals(entry.getValue(), actual.get(entry.getKey()), entry.getKey());
            ledger.compared();
        }
    }

    @Test
    public void noAccessibleNativeIsLeft() {
        assertEquals(List.of(), MacGlassNativeShim.accessibleNatives());
    }

    private static void assertEnumPointer(String name, long pointer, String what) {
        Long oracle = MacGlassNativeShim.oracleEnumPointer(name);
        assertTrue(oracle != null, "the C found no pointer for " + what);
        if (name.startsWith("AX")) {
            try {
                assertNotEquals(0L, pointer, what);
                assertTrue(MacGlassNativeShim.oracleIsEqualToString(oracle, pointer), what);
                assertEquals(name, MacGlassNativeShim.oracleUtf8String(pointer), what);
            } finally {
                MacGlassNativeShim.cfRelease(oracle);
            }
        } else {
            assertEquals(oracle, pointer, what);
        }
    }

    private static List<StringCase> equalityCorpus() {
        return List.of(
                new StringCase("null", () -> 0L, false),
                new StringCase("roleConstant", () -> MacGlassNativeShim.appKitConstant("NSAccessibilityRoleAttribute"),
                        false),
                new StringCase("titleConstant",
                        () -> MacGlassNativeShim.appKitConstant("NSAccessibilityTitleAttribute"), false),
                owned("role", "AXRole"),
                new StringCase("roleMutable", () -> MacGlassNativeShim.cfMutableString("AXRole"), true),
                owned("roles", "AXRoles"),
                owned("lowerRole", "axrole"),
                owned("empty", ""),
                new StringCase("emptyMutable", () -> MacGlassNativeShim.cfMutableString(""), true),
                owned("eAcuteComposed", "é"),
                owned("eAcuteDecomposed", "é"),
                owned("supplementary", "a😀b"),
                owned("long", "AXRole".repeat(40)));
    }

    private static List<StringCase> utf8Corpus() {
        return List.of(
                new StringCase("null", () -> 0L, false),
                new StringCase("roleConstant", () -> MacGlassNativeShim.appKitConstant("NSAccessibilityRoleAttribute"),
                        false),
                owned("ascii", "AXRole"),
                owned("empty", ""),
                owned("latin1", "Drücken"),
                owned("bmp", "日本語"),
                owned("supplementary", "a😀b"),
                owned("embeddedNul", "a\u0000b"),
                owned("loneHigh", "a\uD800b"),
                owned("loneLow", "\uDC00"),
                owned("swappedPair", "\uDE00\uD83D"),
                new StringCase("mutableSupplementary", () -> MacGlassNativeShim.cfMutableString("x😀"), true),
                owned("long", "é".repeat(300)));
    }

    /** Action names beyond the {@code MacAction}s: AppKit's own, one it lacks, and odd strings. */
    private static List<StringCase> extraActions() {
        return List.of(owned("showAlternateUI", "AXShowAlternateUI"), owned("scrollToVisible", "AXScrollToVisible"),
                owned("unknown", "AXNoSuchAction"), owned("empty", ""), owned("supplementary", "日本😀"));
    }

    private static StringCase owned(String name, String value) {
        return new StringCase(name, () -> MacGlassNativeShim.cfString(value), true);
    }

    private static long[] create(List<StringCase> cases) {
        long[] strings = new long[cases.size()];
        for (int index = 0; index < strings.length; index++) {
            strings[index] = cases.get(index).create();
        }
        return strings;
    }

    private static void release(List<StringCase> cases, long[] strings) {
        for (int index = 0; index < strings.length; index++) {
            cases.get(index).release(strings[index]);
        }
    }

    private static Natives jniNatives() {
        return new Natives() {
            @Override
            public String utf8(long string) {
                return MacGlassNativeShim.jniGetString(string);
            }

            @Override
            public boolean isEqualToString(long string1, long string2) {
                return MacGlassNativeShim.jniIsEqualToString(string1, string2);
            }

            @Override
            public String roleDescription(long role, long subrole) {
                return MacGlassNativeShim.jniRoleDescription(role, subrole);
            }

            @Override
            public String actionDescription(long action) {
                return MacGlassNativeShim.jniActionDescription(action);
            }
        };
    }

    private static Natives javaNatives() {
        return new Natives() {
            @Override
            public String utf8(long string) {
                return MacGlassNativeShim.utf8String(string);
            }

            @Override
            public boolean isEqualToString(long string1, long string2) {
                return MacGlassNativeShim.isEqualToString(string1, string2);
            }

            @Override
            public String roleDescription(long role, long subrole) {
                return MacGlassNativeShim.roleDescription(role, subrole);
            }

            @Override
            public String actionDescription(long action) {
                return MacGlassNativeShim.actionDescription(action);
            }
        };
    }

    /** Every case of the corpus through {@code natives}, by golden key. */
    private static Map<String, String> run(Natives natives) {
        Map<String, String> lines = new TreeMap<>();
        for (String enumName : ENUMS) {
            List<String> names = MacGlassNativeShim.accessibleEnumNames(enumName);
            List<Long> pointers = MacGlassNativeShim.accessibleEnumPointers(enumName);
            for (int index = 0; index < names.size(); index++) {
                long pointer = pointers.get(index);
                lines.put("case.enum." + enumName + "." + names.get(index),
                        pointer != 0 ? quote(natives.utf8(pointer)) : "0");
            }
        }
        for (StringCase stringCase : utf8Corpus()) {
            long string = stringCase.create();
            try {
                lines.put("case.utf8." + stringCase.name(), quote(natives.utf8(string)));
            } finally {
                stringCase.release(string);
            }
        }
        List<StringCase> cases = equalityCorpus();
        long[] strings = create(cases);
        try {
            for (int first = 0; first < cases.size(); first++) {
                for (int second = 0; second < cases.size(); second++) {
                    lines.put("case.isEqualToString." + cases.get(first).name() + "." + cases.get(second).name(),
                            String.valueOf(natives.isEqualToString(strings[first], strings[second])));
                }
            }
        } finally {
            release(cases, strings);
        }
        List<String> roles = MacGlassNativeShim.accessibleEnumNames("MacRole");
        List<Long> rolePointers = MacGlassNativeShim.accessibleEnumPointers("MacRole");
        List<String> subroles = new ArrayList<>(List.of("none"));
        subroles.addAll(MacGlassNativeShim.accessibleEnumNames("MacSubrole"));
        List<Long> subrolePointers = new ArrayList<>(List.of(0L));
        subrolePointers.addAll(MacGlassNativeShim.accessibleEnumPointers("MacSubrole"));
        for (int role = 0; role < roles.size(); role++) {
            for (int subrole = 0; subrole < subroles.size(); subrole++) {
                lines.put("case.roleDescription." + roles.get(role) + "." + subroles.get(subrole),
                        quote(natives.roleDescription(rolePointers.get(role), subrolePointers.get(subrole))));
            }
        }
        List<String> actions = MacGlassNativeShim.accessibleEnumNames("MacAction");
        List<Long> actionPointers = MacGlassNativeShim.accessibleEnumPointers("MacAction");
        for (int action = 0; action < actions.size(); action++) {
            lines.put("case.actionDescription." + actions.get(action),
                    quote(natives.actionDescription(actionPointers.get(action))));
        }
        for (StringCase extra : extraActions()) {
            long action = extra.create();
            try {
                lines.put("case.actionDescription.extra." + extra.name(), quote(natives.actionDescription(action)));
            } finally {
                extra.release(action);
            }
        }
        return lines;
    }

    private static String quote(String s) {
        return MacClipboardNativeTest.quote(s);
    }

    private static Map<String, String> compared(Map<String, String> lines) {
        Map<String, String> compared = new TreeMap<>();
        lines.forEach((key, value) -> {
            if (key.startsWith("case.")) {
                compared.put(key, value);
            }
        });
        return compared;
    }

    /** The machine the golden describes: AppKit's localized strings are the OS release's and the user's languages'. */
    private static Map<String, String> machine() {
        Map<String, String> keys = new TreeMap<>();
        keys.put("machine.os.name", System.getProperty("os.name"));
        keys.put("machine.os.version", System.getProperty("os.version"));
        keys.put("machine.os.arch", System.getProperty("os.arch"));
        keys.put("machine.preferredLanguages", String.join(",", MacGlassNativeShim.preferredLanguages()));
        return keys;
    }

    private static Map<String, String> readGolden() throws IOException {
        Map<String, String> golden = new LinkedHashMap<>();
        try (InputStream in = MacAccessibleNativeTest.class.getResourceAsStream(GOLDEN)) {
            if (in == null) {
                fail("no " + GOLDEN + " on the test class path: the golden records what the JNI natives of "
                        + CAPTURE_COMMIT + " did, and with no capture there is nothing to compare");
            }
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int equals = line.indexOf('=');
                golden.put(line.substring(0, equals), line.substring(equals + 1));
            }
        }
        return golden;
    }

    /** Refuses unless the JNI natives still exist, so that the Java can never regenerate the golden. */
    private static void capture() throws Exception {
        if (!MacGlassNativeShim.accessibleGoldenNativesExist()) {
            fail("refusing to capture: MacAccessible no longer declares the JNI natives "
                    + MacGlassNativeShim.ACCESSIBLE_GOLDEN_NATIVES + ", so a capture would record the Java it is"
                    + " meant to verify");
        }
        Path module = Path.of("").toAbsolutePath();
        Path source = module.resolve(RESOURCE_DIRECTORY).resolve(GOLDEN);
        if (Files.exists(source)) {
            fail("a golden already exists at " + source + "; it records what the JNI natives did");
        }
        Map<String, String> cases = run(jniNatives());
        List<String> lines = new ArrayList<>(List.of(
                "# MacAccessible golden: what the JNI natives getString, isEqualToString,",
                "# NSAccessibilityRoleDescription and NSAccessibilityActionDescription answered for each case of",
                "# MacAccessibleNativeTest.run(), and the content of each ptr the JNI _initEnum stored, read with"
                        + " getString, in the",
                "# surefire JVM. Compared exactly. Captured from the slice-10 native-step tree on top of "
                        + CAPTURE_COMMIT + ", not from that",
                "# commit: getString, isEqualToString and both descriptions had their " + CAPTURE_COMMIT
                        + " bodies, and _initEnum went through",
                "# the extracted enumConstantPointer. The capture.nativeSource.* keys hash that tree's sources.",
                "# Keys: machine.* gate the comparison; capture.* and info.* are recorded, never compared; case.* are",
                "# compared. enum.<Enum>.<Constant> is the ptr's string, or 0; roleDescription.<Role>.<Subrole> passes",
                "# NULL for the subrole none. A value is null, or the string quoted, every unit outside printable",
                "# ASCII, a quote or a backslash as \\uXXXX."));
        machine().forEach((key, value) -> lines.add(key + "=" + value));
        lines.add("capture.commit=" + git(module, "rev-parse", "HEAD"));
        lines.add("capture.jniLibrary.sha256=" + sha256(module.resolve("target/native/bin/libglass.dylib")));
        for (String nativeSource : NATIVE_SOURCES) {
            lines.add("capture.nativeSource." + Path.of(nativeSource).getFileName() + ".sha256="
                    + sha256(module.resolve(nativeSource)));
        }
        lines.add("capture.accessibleGoldenNatives=" + MacGlassNativeShim.accessibleGoldenNativesExist());
        lines.add("info.java.vm=" + System.getProperty("java.vm.name") + " " + System.getProperty("java.vm.version"));
        cases.forEach((key, value) -> lines.add(key + "=" + value));
        byte[] content = (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(source.getParent());
        Files.write(source, content);
        Path classes = module.resolve(CLASSES_DIRECTORY);
        Files.createDirectories(classes);
        Files.write(classes.resolve(GOLDEN), content);
    }

    private static String git(Path directory, String... arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git", "-C", directory.toString()));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        return process.waitFor() == 0 ? output : "(git failed: " + output + ")";
    }

    private static String sha256(Path file) throws IOException, NoSuchAlgorithmException {
        return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    /**
     * The variant as a slot hands it to the C, made into an object by the peers' C and read back as {@code type}
     * through {@code idToMacVariant}.
     */
    private static Variant roundTrip(Variant variant, int type) throws Exception {
        VariantObject object = makeObject(variant);
        assertEquals(GMAC_OK, object.status(), variant.toString());
        try {
            return MacGlassNativeShim.idToMacVariant(object.object(), type);
        } finally {
            MacGlassNativeShim.cfRelease(object.object());
        }
    }

    /** The object the peers make of {@code variant}, as slot 1 answered it: retained, which the caller releases. */
    private static VariantObject makeObject(Variant variant) throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        Map<String, Object> answers = new LinkedHashMap<>();
        answers.put("accessibilityAttributeValue", variant);
        long peerId = MacGlassNativeShim.registerAccessible(new AccessibleRecorder(events, answers, false));
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment value = arena.allocate(MacGlassNativeShim.layouts().get("GmacVariant"));
            value.set(JAVA_INT, 36, -1);
            value.set(JAVA_INT, 80, -1);
            value.set(JAVA_INT, 112, -1);
            assertEquals(GMAC_UPCALL_OK, MacGlassNativeShim.fireAccessibleCallback(ATTRIBUTE_VALUE, peerId, value));
            return MacGlassNativeShim.variantToObject(value);
        } finally {
            fire(PEER_RELEASED, peerId);
        }
    }

    private static List<Long> takeIds(Fired fired) {
        return MacGlassNativeShim.takeIdBlock(fired.out().get(JAVA_LONG, 0), fired.out().get(JAVA_INT, 8));
    }

    private static List<Fired> fireEverySlotButReleased(long peerId) {
        List<Fired> fired = new ArrayList<>();
        for (int slot = 0; slot < PEER_RELEASED; slot++) {
            fired.add(fire(slot, peerId));
        }
        return fired;
    }

    /** Dials {@code slot} with its out-parameters in 120 bytes of {@link #SENTINEL}, which outlive the call. */
    private static Fired fire(int slot, long peerId) {
        MemorySegment out = Arena.ofAuto().allocate(120, 8);
        out.fill(SENTINEL);
        return new Fired(MacGlassNativeShim.fireAccessibleCallback(slot, peerId, out), out);
    }

    private static String report(String exception) {
        return "report " + exception + " on " + CALLER;
    }

    private static List<String> names(StructLayout layout) {
        return layout.memberLayouts().stream().map(member -> member.name().orElseThrow()).toList();
    }

    private record HeaderSlot(String name, MethodType type) {
    }

    /** The slots of {@code GmacAccessibleCallbacks} in the header, each with the carriers of its prototype. */
    private static List<HeaderSlot> headerSlots() throws IOException {
        assertTrue(Files.isRegularFile(HEADER), "run from modules/javafx.graphics: no " + HEADER.toAbsolutePath());
        String header = COMMENT.matcher(Files.readString(HEADER)).replaceAll(" ");
        Matcher table = ACCESSIBLE_CALLBACKS.matcher(header);
        assertTrue(table.find(), "no GmacAccessibleCallbacks in " + HEADER);
        List<HeaderSlot> slots = new ArrayList<>();
        Matcher slot = SLOT.matcher(table.group(1));
        while (slot.find()) {
            List<Class<?>> parameters = new ArrayList<>();
            for (String parameter : slot.group(3).split(",")) {
                parameters.add(carrier(parameter.trim().replaceFirst("\\w+$", "")));
            }
            slots.add(new HeaderSlot(slot.group(2), MethodType.methodType(carrier(slot.group(1)), parameters)));
        }
        return slots;
    }

    /** Any pointer is an {@code ADDRESS}. */
    private static Class<?> carrier(String cType) {
        String type = cType.replace("const", "").trim();
        if (type.endsWith("*")) {
            return MemorySegment.class;
        }
        return switch (type) {
            case "int32_t" -> int.class;
            case "int64_t" -> long.class;
            case "float" -> float.class;
            default -> throw new AssertionError("no carrier for the C type " + cType);
        };
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
