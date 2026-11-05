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
import com.sun.glass.ui.mac.MacGlassNativeShim;
import com.sun.glass.ui.mac.MacGlassNativeShim.EventReadback;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What can be checked of {@code MacRobot}'s Java natives ({@code MacRobotCalls}) without posting an event, capturing
 * the screen or moving the cursor: the SDK values and C type sizes they spell out, against the SDK
 * {@code libglass} was compiled with ({@code gmac_test_sdk_constant}); the {@code kCGColorSpaceSRGB} they name the
 * captures' colour space by; the pool each call runs in; the Mac key codes {@code gmac_key_find_mac} answers for
 * {@code VK_UNDEFINED}, {@code VK_ESCAPE} and {@code VK_ENTER}, which it answers before it would read the keyboard
 * layout; and the events they build, read back through CoreGraphics before anything posts them, then compared field
 * for field with the events the C made of the same arguments. With no event source, CoreGraphics gives an event the
 * live modifier flags, so each reference is made next to its event. Posting, the screen capture and the cursor are
 * owner-run ({@code RobotTest}). So are the key events, which HIServices posts as it makes them, so none can be read
 * back, and the lookup of layout-sensitive and unmapped codes, which reads the keyboard layout.
 * <p>
 * AppKit's {@code NSApp} is nil before the first call into the library and after the last.
 */
@EnabledOnOs(OS.MAC)
public class MacRobotNativeTest {

    private static final Path HEADER = Path.of("src/main/native-glass/mac/glass_mac_api.h");

    private static final Pattern COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    private static final Pattern SDK_CONSTANT_ENUM = Pattern.compile("enum\\s+GmacSdkConstant\\s*\\{([^}]*)}");

    /** The entries only this test reads, for what CoreGraphics reads back. */
    private static final Set<String> READBACK_ENTRIES = Set.of("GMAC_SDK_EVENT_SCROLL_WHEEL",
            "GMAC_SDK_MOUSE_EVENT_BUTTON_NUMBER", "GMAC_SDK_SCROLL_WHEEL_EVENT_DELTA_AXIS_1");

    /** GlassRobot's MOUSE_LEFT_BTN, MOUSE_RIGHT_BTN, MOUSE_MIDDLE_BTN and MOUSE_BACK_BTN. */
    private static final int LEFT = 1;
    private static final int RIGHT = 2;
    private static final int MIDDLE = 4;
    private static final int BACK = 8;

    private static final float X = 12.5f;
    private static final float Y = 34.25f;

    private static List<String> sdkEntries;

    @BeforeAll
    static void loadGlass() throws IOException {
        MacGlassNatives.require();
        assertTrue(MacGlassNativeShim.nsAppIsNil(), "an NSApplication exists before the first call into libglass");
        MacGlassNativeShim.loadLibrary();
        sdkEntries = headerSdkEntries();
    }

    @AfterAll
    static void noApplicationExistsAfterTheLastCall() {
        assertTrue(MacGlassNativeShim.nsAppIsNil(), "an NSApplication exists after the last call into libglass");
    }

    @Test
    public void everyEntryOfTheSdkTableIsSpelledOutInJavaOrReadBackHere() {
        List<String> java = List.copyOf(MacGlassNativeShim.robotSdkConstants().keySet());
        List<String> rest = new ArrayList<>(sdkEntries);
        rest.removeAll(java);
        assertEquals(READBACK_ENTRIES, Set.copyOf(rest));
        List<String> javaInEnumOrder = new ArrayList<>(sdkEntries);
        javaInEnumOrder.retainAll(java);
        assertEquals(javaInEnumOrder, java, "the Java values come in the enum's order");
    }

    @Test
    public void everySdkValueAndSizeTheJavaSideSpellsOutIsTheSdks() {
        for (Map.Entry<String, Integer> constant : MacGlassNativeShim.robotSdkConstants().entrySet()) {
            assertEquals(sdk(constant.getKey()), (int) constant.getValue(), constant.getKey());
        }
    }

    @Test
    public void indicesOutsideTheSdkTableAnswerInt32Min() {
        for (int index : new int[] {Integer.MIN_VALUE, -1, sdkEntries.size(), Integer.MAX_VALUE}) {
            assertEquals(Integer.MIN_VALUE, MacGlassNativeShim.sdkConstant(index), "index " + index);
        }
    }

    @Test
    public void theCapturesNameTheColourSpaceByTheConstantTheLibraryWasLinkedAgainst() {
        MemorySegment library = MacGlassNativeShim.libraryColorSpaceSrgbName();
        assertNotEquals(0L, library.address());
        assertEquals(library.address(), MacGlassNativeShim.robotColorSpaceSrgbName().address());
    }

    @Test
    public void eachPushRaisesThePoolDepthAndEachPopLowersIt() throws Exception {
        List<Integer> depths = onFreshThread(() -> {
            List<Integer> seen = new ArrayList<>();
            seen.add(MacGlassNativeShim.poolDepth());
            MacGlassNativeShim.robotPoolPush();
            seen.add(MacGlassNativeShim.poolDepth());
            MacGlassNativeShim.robotPoolPush();
            seen.add(MacGlassNativeShim.poolDepth());
            MacGlassNativeShim.robotPoolPop();
            seen.add(MacGlassNativeShim.poolDepth());
            MacGlassNativeShim.robotPoolPop();
            seen.add(MacGlassNativeShim.poolDepth());
            return seen;
        });
        assertEquals(List.of(-1, 1, 2, 1, 0), depths);
    }

    @Test
    public void aMoveWithNoButtonHeldIsAMoveAtTheLocation() {
        assertEquals(new EventReadback(sdk("GMAC_SDK_EVENT_MOUSE_MOVED"), X, Y, 0, 0), moved(0));
    }

    @Test
    public void aMoveWithTheLeftButtonHeldIsALeftDrag() {
        assertEquals(new EventReadback(sdk("GMAC_SDK_EVENT_LEFT_MOUSE_DRAGGED"), X, Y, 0, 0), moved(LEFT));
    }

    @Test
    public void aMoveWithTheRightButtonHeldIsARightDrag() {
        assertEquals(new EventReadback(sdk("GMAC_SDK_EVENT_RIGHT_MOUSE_DRAGGED"), X, Y, 1, 0), moved(RIGHT));
    }

    /** CoreGraphics keeps the button of an other-button event, so latent bug 3 shows: button 3 for the middle one. */
    @Test
    public void aMoveWithTheMiddleButtonHeldIsAnOtherDragOfTheBitCount() {
        assertEquals(new EventReadback(sdk("GMAC_SDK_EVENT_OTHER_MOUSE_DRAGGED"), X, Y, 3, 0), moved(MIDDLE));
    }

    @Test
    public void aMoveWithSeveralButtonsHeldIsADragOfTheHighest() {
        assertEquals(new EventReadback(sdk("GMAC_SDK_EVENT_OTHER_MOUSE_DRAGGED"), X, Y, 3, 0), moved(LEFT | MIDDLE));
        assertEquals(new EventReadback(sdk("GMAC_SDK_EVENT_OTHER_MOUSE_DRAGGED"), X, Y, 4, 0),
                moved(RIGHT | BACK));
    }

    @Test
    public void theLeftButtonPressesAndReleasesAsTheLeftButton() {
        assertEquals(new EventReadback(sdk("GMAC_SDK_EVENT_LEFT_MOUSE_DOWN"), X, Y, 0, 0), button(0, true));
        assertEquals(new EventReadback(sdk("GMAC_SDK_EVENT_LEFT_MOUSE_UP"), X, Y, 0, 0), button(0, false));
    }

    @Test
    public void theRightButtonPressesAndReleasesAsTheRightButton() {
        assertEquals(new EventReadback(sdk("GMAC_SDK_EVENT_RIGHT_MOUSE_DOWN"), X, Y, 1, 0), button(1, true));
        assertEquals(new EventReadback(sdk("GMAC_SDK_EVENT_RIGHT_MOUSE_UP"), X, Y, 1, 0), button(1, false));
    }

    @Test
    public void everyOtherButtonPressesAndReleasesAsTheButtonOfItsBit() {
        assertEquals(new EventReadback(sdk("GMAC_SDK_EVENT_OTHER_MOUSE_DOWN"), X, Y, 2, 0), button(2, true));
        assertEquals(new EventReadback(sdk("GMAC_SDK_EVENT_OTHER_MOUSE_UP"), X, Y, 2, 0), button(2, false));
        assertEquals(new EventReadback(sdk("GMAC_SDK_EVENT_OTHER_MOUSE_DOWN"), X, Y, 4, 0), button(4, true));
    }

    @Test
    public void theWheelScrollsTheFirstAxisByTheAmountInLines() {
        int scrollWheel = sdk("GMAC_SDK_EVENT_SCROLL_WHEEL");
        for (int wheelAmt : new int[] {3, -2, 0}) {
            EventReadback event = read(MacGlassNativeShim.robotScrollWheelEvent(wheelAmt));
            assertEquals(scrollWheel, event.type(), "wheel " + wheelAmt);
            assertEquals(wheelAmt, event.scrollDeltaAxis1(), "wheel " + wheelAmt);
        }
    }

    /** {@code -[GlassRobot mouseMove:]} passed the number of bits up to the highest held button as the button. */
    @Test
    public void everyFieldOfEachMoveIsThatOfTheEventTheCMadeOfItsArguments() {
        assertMove(0, "GMAC_SDK_EVENT_MOUSE_MOVED", 0);
        assertMove(LEFT, "GMAC_SDK_EVENT_LEFT_MOUSE_DRAGGED", 1);
        assertMove(RIGHT, "GMAC_SDK_EVENT_RIGHT_MOUSE_DRAGGED", 2);
        assertMove(MIDDLE, "GMAC_SDK_EVENT_OTHER_MOUSE_DRAGGED", 3);
        assertMove(LEFT | MIDDLE, "GMAC_SDK_EVENT_OTHER_MOUSE_DRAGGED", 3);
        assertMove(RIGHT | BACK, "GMAC_SDK_EVENT_OTHER_MOUSE_DRAGGED", 4);
    }

    @Test
    public void everyFieldOfEachButtonEventIsThatOfTheEventTheCMadeOfItsArguments() {
        assertButton(0, true, "GMAC_SDK_EVENT_LEFT_MOUSE_DOWN");
        assertButton(0, false, "GMAC_SDK_EVENT_LEFT_MOUSE_UP");
        assertButton(1, true, "GMAC_SDK_EVENT_RIGHT_MOUSE_DOWN");
        assertButton(1, false, "GMAC_SDK_EVENT_RIGHT_MOUSE_UP");
        for (int index = 2; index <= 4; index++) {
            assertButton(index, true, "GMAC_SDK_EVENT_OTHER_MOUSE_DOWN");
            assertButton(index, false, "GMAC_SDK_EVENT_OTHER_MOUSE_UP");
        }
    }

    @Test
    public void everyFieldOfEachWheelEventIsThatOfTheEventTheCMadeOfItsArguments() {
        int line = sdk("GMAC_SDK_SCROLL_EVENT_UNIT_LINE");
        for (int wheelAmt : new int[] {3, -2, 0}) {
            assertArrayEquals(data(MacGlassNativeShim.referenceScrollWheelEvent(line, 1, wheelAmt)),
                    data(MacGlassNativeShim.robotScrollWheelEvent(wheelAmt)), "wheel " + wheelAmt);
        }
    }

    @Test
    public void theSerializedFormTellsEventsApartByEachArgument() {
        int otherDragged = sdk("GMAC_SDK_EVENT_OTHER_MOUSE_DRAGGED");
        byte[] drag = data(MacGlassNativeShim.referenceMouseEvent(otherDragged, X, Y, 3));
        assertFalse(Arrays.equals(drag, data(MacGlassNativeShim.referenceMouseEvent(otherDragged, X, Y, 4))),
                "button");
        assertFalse(Arrays.equals(drag, data(MacGlassNativeShim.referenceMouseEvent(otherDragged, X, Y + 1, 3))),
                "location");
        assertFalse(Arrays.equals(drag, data(MacGlassNativeShim.referenceMouseEvent(
                sdk("GMAC_SDK_EVENT_OTHER_MOUSE_DOWN"), X, Y, 3))), "type");
        int line = sdk("GMAC_SDK_SCROLL_EVENT_UNIT_LINE");
        byte[] wheel = data(MacGlassNativeShim.referenceScrollWheelEvent(line, 1, 3));
        assertFalse(Arrays.equals(wheel, data(MacGlassNativeShim.referenceScrollWheelEvent(line, 1, 2))),
                "wheel amount");
    }

    /**
     * In {@code GlassKey.m}, {@code gmac_key_find_mac} (633) answers what {@code GetMacKey} (471) found, and only the
     * search after its table (497-508) reads the keyboard layout. {@code VK_UNDEFINED} returns at 474, before the
     * table. {@code VK_ESCAPE} (table key 0x35) and {@code VK_ENTER} (first table key 0x24) return at 492, because
     * {@code macKeyCodeIsLayoutSensitive} (185) answers NO for both: for 0x24 at 195, for 0x35, outside both ranges,
     * at 210. The -1 is what makes {@code postKeyEvent} post nothing.
     */
    @Test
    public void undefinedFindsNoMacKeyAndEscapeAndEnterFindTheirUsQwertyKeys() {
        assertEquals(-1, MacGlassNativeShim.robotFindMacKey(KeyEvent.VK_UNDEFINED), "VK_UNDEFINED");
        assertEquals(0x35, MacGlassNativeShim.robotFindMacKey(KeyEvent.VK_ESCAPE), "VK_ESCAPE");
        assertEquals(0x24, MacGlassNativeShim.robotFindMacKey(KeyEvent.VK_ENTER), "VK_ENTER");
    }

    @Test
    public void macRobotDeclaresNoNativeMethod() throws ClassNotFoundException {
        Class<?> robot = Class.forName("com.sun.glass.ui.mac.MacRobot", false,
                MacGlassNativeShim.class.getClassLoader());
        List<String> natives = Arrays.stream(robot.getDeclaredMethods())
                .filter(method -> Modifier.isNative(method.getModifiers()))
                .map(Method::getName)
                .toList();
        assertEquals(List.of(), natives);
    }

    private static EventReadback moved(int mouseButtons) {
        return read(MacGlassNativeShim.robotMouseMovedEvent(mouseButtons, X, Y));
    }

    private static EventReadback button(int index, boolean pressed) {
        return read(MacGlassNativeShim.robotMouseButtonEvent(index, pressed, X, Y));
    }

    private static void assertMove(int mouseButtons, String type, int button) {
        assertArrayEquals(data(MacGlassNativeShim.referenceMouseEvent(sdk(type), X, Y, button)),
                data(MacGlassNativeShim.robotMouseMovedEvent(mouseButtons, X, Y)), "buttons held " + mouseButtons);
    }

    private static void assertButton(int index, boolean pressed, String type) {
        assertArrayEquals(data(MacGlassNativeShim.referenceMouseEvent(sdk(type), X, Y, index)),
                data(MacGlassNativeShim.robotMouseButtonEvent(index, pressed, X, Y)),
                "button " + index + (pressed ? " pressed" : " released"));
    }

    /** CoreGraphics' serialized form of {@code event}, which is then released. */
    private static byte[] data(MemorySegment event) {
        assertNotEquals(0L, event.address(), "CoreGraphics made no event");
        try {
            return MacGlassNativeShim.eventData(event);
        } finally {
            MacGlassNativeShim.release(event);
        }
    }

    /** Reads {@code event} back, then releases it. */
    private static EventReadback read(MemorySegment event) {
        assertNotEquals(0L, event.address(), "CoreGraphics made no event");
        try {
            return MacGlassNativeShim.readEvent(event, sdk("GMAC_SDK_MOUSE_EVENT_BUTTON_NUMBER"),
                    sdk("GMAC_SDK_SCROLL_WHEEL_EVENT_DELTA_AXIS_1"));
        } finally {
            MacGlassNativeShim.release(event);
        }
    }

    private static int sdk(String entry) {
        int index = sdkEntries.indexOf(entry);
        assertTrue(index >= 0, "no " + entry + " in enum GmacSdkConstant");
        return MacGlassNativeShim.sdkConstant(index);
    }

    /** The entries of {@code enum GmacSdkConstant} in index order, without the count. */
    private static List<String> headerSdkEntries() throws IOException {
        assertTrue(Files.isRegularFile(HEADER), "run from modules/javafx.graphics: no " + HEADER.toAbsolutePath());
        String header = COMMENT.matcher(Files.readString(HEADER)).replaceAll(" ");
        Matcher body = SDK_CONSTANT_ENUM.matcher(header);
        assertTrue(body.find(), "no enum GmacSdkConstant in " + HEADER);
        List<String> entries = new ArrayList<>();
        for (String entry : body.group(1).split(",")) {
            entries.add(entry.replaceFirst("=.*", "").trim());
        }
        assertEquals("GMAC_SDK_CONSTANT_COUNT", entries.removeLast());
        return entries;
    }

    /**
     * Runs {@code task} on a new platform thread: one that has never opened a pool. A virtual thread would read the
     * thread-specific data of whichever carrier it runs on.
     */
    private static <T> T onFreshThread(Supplier<T> task) throws Exception {
        FutureTask<T> future = new FutureTask<>(task::get);
        Thread.ofPlatform().name("MacRobotNativeTest-fresh").start(future);
        return future.get(30, TimeUnit.SECONDS);
    }
}
