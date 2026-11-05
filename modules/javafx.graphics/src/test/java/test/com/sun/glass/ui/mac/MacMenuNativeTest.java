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
import com.sun.glass.ui.Menu;
import com.sun.glass.ui.MenuItem.Callback;
import com.sun.glass.ui.Pixels;
import com.sun.glass.ui.mac.MacGlassNativeShim;
import com.sun.glass.ui.mac.MacGlassNativeShim.ImageReadback;
import com.sun.glass.ui.mac.MacGlassNativeShim.KeyEquivalent;
import com.sun.glass.ui.mac.MacGlassNativeShim.MenuIcon;
import com.sun.glass.ui.mac.MacGlassNativeShim.MenuRecorder;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.invoke.MethodHandleInfo;
import java.lang.invoke.MethodType;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code MacMenuBarDelegate} and {@code MacMenuDelegate} over {@code GmacMenuCallbacks} and the
 * {@code gmac_menubar_*} and {@code gmac_menu_*} exports: the {@code GmacMenuImage} layout and the 5-slot table
 * against the library's probes and the header, each slot's target and exception sink through
 * {@code gmac_test_fire_menu_callback}, the registry of menu ids, the Java each create, image and callback call runs
 * around its export, with a stand-in where the export would be, and the key equivalents and modifier masks
 * {@code gmac_menu_set_shortcut} gives, through {@code gmac_test_menu_key_equivalent}.
 * <p>
 * No {@code GlassMenu} or {@code GlassMenubar} is made. Of the exports, only {@code gmac_menu_set_shortcut},
 * {@code gmac_menu_set_image} and {@code gmac_menu_set_checked} are called, on the NULL menu, which reaches nothing
 * past the pool (PB17). In {@code GlassMenu.m}, {@code gmac_menu_set_shortcut} (449) and
 * {@code gmac_menu_set_checked} (493) message the NULL {@code GlassMenu}, which runs no method, and
 * {@code gmac_menu_set_image} (461) tests it (468) and, for a NULL icon, does nothing else.
 * {@code gmac_test_menu_key_equivalent} (505) runs {@code menuKeyEquivalent} (52), which makes {@code NSString}s
 * only, through {@code GetStringForJavaKey} ({@code GlassKey.m}, 508), a switch over static values. The images come
 * from {@code Pixels.attachData}, which makes an {@code NSImage} of its pixels, and are read back and released through
 * {@code gmac_test_inspect_and_release_image}. Making a {@code MacMenuDelegate} runs its initialization, which
 * installs the table. Nothing here creates an {@code NSApplication}: AppKit's {@code NSApp} is nil before the first
 * call into the library and after the last.
 * <p>
 * Each call runs on a fresh platform thread standing for the main thread, whose reports go to the test's list, with
 * the main thread's attached flag set as the test says.
 */
@EnabledOnOs(OS.MAC)
public class MacMenuNativeTest {

    private static final String DETACHED = "java.lang.RuntimeException: Main Java thread is detached.";

    /** What HotSpot's {@code CallVoidMethod} raised on a NULL receiver. */
    private static final String NULL_RECEIVER = "java.lang.NullPointerException";

    private static final int GMAC_UPCALL_OK = 0;
    private static final int GMAC_UPCALL_THREW = 1;
    private static final int GMAC_ERR_NO_DATA = -1;

    private static final int SLOT_COUNT = 5;
    private static final int ACTION = 0;
    private static final int VALIDATE = 1;
    private static final int MENU_OPENING = 2;
    private static final int MENU_CLOSED = 3;
    private static final int MENU_DISPOSED = 4;

    /** {@code NSEventModifierFlag{Shift,Control,Option,Command}}. */
    private static final long SHIFT_MASK = 1L << 17;
    private static final long CONTROL_MASK = 1L << 18;
    private static final long OPTION_MASK = 1L << 19;
    private static final long COMMAND_MASK = 1L << 20;

    private static final String CALLER = "MacMenuNativeTest-caller";

    private static final long FAKE_MENU = 0x7E57_0000_4D45L;

    private static final String TITLE = "Fichier é 日本😀";

    private static final Path HEADER = Path.of("src/main/native-glass/mac/glass_mac_api.h");

    private static final Pattern MENU_CALLBACKS = Pattern.compile(
            "typedef\\s+struct\\s+GmacMenuCallbacks\\s*\\{(.*?)}\\s*GmacMenuCallbacks\\s*;", Pattern.DOTALL);

    private static final Pattern SLOT = Pattern.compile("(\\w+)\\s*\\(\\s*\\*\\s*(\\w+)\\s*\\)\\s*\\(([^)]*)\\)");

    private static final Pattern COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    private int attachedBefore;

    /** The calls of the two {@code Callback} methods, as lines in a list. */
    private record RecordingCallback(String name, List<String> events, boolean failing) implements Callback {

        @Override
        public void action() {
            record("action");
        }

        @Override
        public void validate() {
            record("validate");
        }

        private void record(String method) {
            String call = name + "." + method + "()";
            events.add(call);
            if (failing) {
                throw new IllegalStateException(call);
            }
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
    void installTheTable() {
        attachedBefore = MacGlassNativeShim.setMainJavaAttached(1);
        MacGlassNativeShim.installMenuCallbacks();
    }

    @AfterEach
    void uninstallTheTableAndRestoreTheAttachedFlag() {
        MacGlassNativeShim.installMenuTable(MemorySegment.NULL);
        MacGlassNativeShim.setMainJavaAttached(attachedBefore);
    }

    @Test
    public void theMenuImageIsWhereTheCompilerPutIt() {
        StructLayout layout = MacGlassNativeShim.layouts().get("GmacMenuImage");
        List<String> members = List.of("image", "width", "height", "scale_x", "scale_y");
        List<Long> offsets = List.of(0L, 8L, 12L, 16L, 20L);
        assertEquals(24, layout.byteSize());
        assertEquals(24, MacGlassNativeShim.sizeOf("GmacMenuImage"));
        assertEquals(members, names(layout));
        for (int member = 0; member < members.size(); member++) {
            long offset = layout.byteOffset(PathElement.groupElement(members.get(member)));
            assertEquals(offsets.get(member), offset, members.get(member));
            assertEquals(offset, MacGlassNativeShim.offsetOf("GmacMenuImage", member),
                    "offsetof " + members.get(member));
        }
        assertEquals(-1, MacGlassNativeShim.offsetOf("GmacMenuImage", members.size()), "past its last member");
        assertEquals(-1, MacGlassNativeShim.offsetOf("GmacMenuImage", -1), "before its first member");
    }

    @Test
    public void theTableIsTheFiveSlotsOfTheHeaderInItsOrder() throws IOException {
        StructLayout table = MacGlassNativeShim.layouts().get("GmacMenuCallbacks");
        assertEquals(40, table.byteSize());
        assertEquals(40, MacGlassNativeShim.sizeOf("GmacMenuCallbacks"));
        List<HeaderSlot> header = headerSlots();
        List<String> headerNames = header.stream().map(HeaderSlot::name).toList();
        assertEquals(List.of("action", "validate", "menu_opening", "menu_closed", "menu_disposed"), headerNames);
        assertEquals(headerNames, MacGlassNativeShim.menuSlotNames());
        assertEquals(headerNames, names(table));
    }

    @Test
    public void everySlotIsDialledWithTheCarriersOfItsPrototype() throws IOException {
        MethodType descriptor = MacGlassNativeShim.menuSlotDescriptor().toMethodType();
        assertEquals(MethodType.methodType(int.class, long.class), descriptor);
        for (HeaderSlot slot : headerSlots()) {
            assertEquals(slot.type(), descriptor, slot.name());
        }
    }

    @Test
    public void everySlotReachesTheEntryOfItsIdOnly() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        long menuId = MacGlassNativeShim.registerMenu(new MenuRecorder(events, false),
                new RecordingCallback("callback", events, false));
        List<String> otherEvents = new CopyOnWriteArrayList<>();
        long otherId = MacGlassNativeShim.registerMenu(new MenuRecorder(otherEvents, false),
                new RecordingCallback("other", otherEvents, false));
        List<Integer> statuses = onFreshThread(events, () -> fireSlotsUpToClosed(menuId));
        assertEquals(List.of(GMAC_UPCALL_OK, GMAC_UPCALL_OK, GMAC_UPCALL_OK, GMAC_UPCALL_OK), statuses);
        assertEquals(List.of("callback.action()", "callback.validate()", "notifyMenuOpening()", "notifyMenuClosed()"),
                events);
        assertEquals(List.of(), otherEvents);
        assertTrue(MacGlassNativeShim.isMenuRegistered(menuId), "only menu_disposed forgets an id");
        assertTrue(MacGlassNativeShim.isMenuRegistered(otherId));
    }

    @Test
    public void actionWithoutACallbackMakesNoCallAndValidateReportsTheNullReceiver() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        long menuId = MacGlassNativeShim.registerMenu(new MenuRecorder(events, false), null);
        List<Integer> statuses = onFreshThread(events, () -> List.of(
                MacGlassNativeShim.fireMenuCallback(ACTION, menuId),
                MacGlassNativeShim.fireMenuCallback(VALIDATE, menuId)));
        assertEquals(List.of(GMAC_UPCALL_OK, GMAC_UPCALL_THREW), statuses);
        assertEquals(List.of(report(NULL_RECEIVER)), events);
    }

    @Test
    public void whatASlotThrowsIsReportedAtOnceAndAnsweredThrew() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        long menuId = MacGlassNativeShim.registerMenu(new MenuRecorder(events, true),
                new RecordingCallback("callback", events, true));
        List<Integer> statuses = onFreshThread(events, () -> fireSlotsUpToClosed(menuId));
        assertEquals(List.of(GMAC_UPCALL_THREW, GMAC_UPCALL_THREW, GMAC_UPCALL_THREW, GMAC_UPCALL_THREW), statuses);
        List<String> expected = new ArrayList<>();
        for (String call : List.of("callback.action()", "callback.validate()", "notifyMenuOpening()",
                "notifyMenuClosed()")) {
            expected.add(call);
            expected.add(report("java.lang.IllegalStateException: " + call));
        }
        assertEquals(expected, events);
    }

    /** The JNI read the delegate's {@code menu} at each call and called through it when it was null. */
    @Test
    public void theMenuSlotsOfAnItemsDelegateReportTheNullReceiver() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        long menuId = MacGlassNativeShim.registerMenuItemDelegate(new RecordingCallback("callback", events, false));
        List<Integer> statuses = onFreshThread(events, () -> fireSlotsUpToClosed(menuId));
        assertEquals(List.of(GMAC_UPCALL_OK, GMAC_UPCALL_OK, GMAC_UPCALL_THREW, GMAC_UPCALL_THREW), statuses);
        assertEquals(List.of("callback.action()", "callback.validate()", report(NULL_RECEIVER),
                report(NULL_RECEIVER)), events);
    }

    @Test
    public void theMenuSlotsOfADelegateInvokeTheProtectedNotifyMethodsOfItsMenu() {
        List<String> methods = new ArrayList<>();
        for (MethodHandleInfo info : MacGlassNativeShim.menuMethods()) {
            assertEquals(MethodHandleInfo.REF_invokeVirtual, info.getReferenceKind(), info.toString());
            methods.add(info.getDeclaringClass().getName() + "." + info.getName() + info.getMethodType());
        }
        assertEquals(List.of(Menu.class.getName() + ".notifyMenuOpening()void",
                Menu.class.getName() + ".notifyMenuClosed()void"), methods);
    }

    @Test
    public void disposeForgetsOnlyItsIdAndNoSlotReachesItAfterwards() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        long disposed = MacGlassNativeShim.registerMenu(new MenuRecorder(events, true),
                new RecordingCallback("disposed", events, true));
        List<String> otherEvents = new CopyOnWriteArrayList<>();
        long other = MacGlassNativeShim.registerMenu(new MenuRecorder(otherEvents, false), null);
        int status = onFreshThread(events, () -> MacGlassNativeShim.fireMenuCallback(MENU_DISPOSED, disposed));
        assertEquals(GMAC_UPCALL_OK, status);
        assertFalse(MacGlassNativeShim.isMenuRegistered(disposed));
        assertTrue(MacGlassNativeShim.isMenuRegistered(other));
        assertEquals(List.of(0, 0, 0, 0, 0), onFreshThread(events, () -> fireEverySlot(disposed)));
        assertEquals(List.of(), events);
        assertEquals(GMAC_UPCALL_OK, MacGlassNativeShim.fireMenuCallback(MENU_OPENING, other));
        assertEquals(List.of("notifyMenuOpening()"), otherEvents);
    }

    @Test
    public void anIdTheRegistryNeverHeldRunsNothing() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        for (long menuId : new long[] {0L, -1L, Long.MAX_VALUE}) {
            assertEquals(List.of(0, 0, 0, 0, 0), onFreshThread(events, () -> fireEverySlot(menuId)),
                    "menu id " + menuId);
        }
        assertEquals(List.of(), events);
    }

    @Test
    public void idsAreNonzeroDistinctAndKeptUntilDisposed() {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ids.add(MacGlassNativeShim.registerMenu(new MenuRecorder(new CopyOnWriteArrayList<>(), false), null));
        }
        assertEquals(3, new HashSet<>(ids).size(), ids.toString());
        for (long menuId : ids) {
            assertNotEquals(0L, menuId);
            assertTrue(MacGlassNativeShim.isMenuRegistered(menuId));
            assertEquals(GMAC_UPCALL_OK, MacGlassNativeShim.fireMenuCallback(MENU_DISPOSED, menuId));
            assertFalse(MacGlassNativeShim.isMenuRegistered(menuId));
        }
    }

    @Test
    public void withoutATableOrItsSlotNothingIsDialled() {
        List<String> events = new CopyOnWriteArrayList<>();
        long menuId = MacGlassNativeShim.registerMenu(new MenuRecorder(events, false),
                new RecordingCallback("callback", events, false));
        try (Arena arena = Arena.ofConfined()) {
            assertEquals(0, MacGlassNativeShim.installMenuTable(arena.allocate(ADDRESS, SLOT_COUNT)));
            assertEquals(List.of(-1, -1, -1, -1, -1), fireEverySlot(menuId));
        }
        assertEquals(0, MacGlassNativeShim.installMenuTable(MemorySegment.NULL));
        assertEquals(List.of(-1, -1, -1, -1, -1), fireEverySlot(menuId));
        MacGlassNativeShim.installMenuCallbacks();
        assertEquals(Integer.MIN_VALUE, MacGlassNativeShim.fireMenuCallback(-1, menuId));
        assertEquals(Integer.MIN_VALUE, MacGlassNativeShim.fireMenuCallback(SLOT_COUNT, menuId));
        assertEquals(List.of(), events);
        assertTrue(MacGlassNativeShim.isMenuRegistered(menuId), "menu_disposed never ran");
    }

    @Test
    public void theLibraryDialsItsOwnCopyOfTheTable() {
        List<String> events = new CopyOnWriteArrayList<>();
        long menuId = MacGlassNativeShim.registerMenu(new MenuRecorder(events, false), null);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment table = arena.allocate(ADDRESS, SLOT_COUNT);
            for (int slot = 0; slot < SLOT_COUNT; slot++) {
                table.setAtIndex(ADDRESS, slot, MacGlassNativeShim.menuCallbackStub(slot));
            }
            assertEquals(0, MacGlassNativeShim.installMenuTable(table));
            table.fill((byte) 0);
        }
        assertEquals(GMAC_UPCALL_OK, MacGlassNativeShim.fireMenuCallback(MENU_CLOSED, menuId));
        assertEquals(List.of("notifyMenuClosed()"), events);
    }

    @Test
    public void setCallbackReplacesTheCallbackTheItemSlotsReach() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        long menuId = MacGlassNativeShim.registerMenu(new MenuRecorder(events, false),
                new RecordingCallback("first", events, false));
        onFreshThread(events, () -> {
            MacGlassNativeShim.setMenuCallback(menuId, FAKE_MENU, new RecordingCallback("second", events, false));
            return fireSlotsUpToClosed(menuId);
        });
        assertEquals(List.of("second.action()", "second.validate()", "notifyMenuOpening()", "notifyMenuClosed()"),
                events);
        events.clear();
        List<Integer> statuses = onFreshThread(events, () -> {
            MacGlassNativeShim.setMenuCallback(menuId, FAKE_MENU, null);
            return List.of(MacGlassNativeShim.fireMenuCallback(ACTION, menuId),
                    MacGlassNativeShim.fireMenuCallback(VALIDATE, menuId));
        });
        assertEquals(List.of(GMAC_UPCALL_OK, GMAC_UPCALL_THREW), statuses);
        assertEquals(List.of(report(NULL_RECEIVER)), events);
    }

    /** The JNI dereferenced the NULL {@code GlassMenu} and crashed (latent bug L35). */
    @Test
    public void setCallbackOnTheNullMenuChangesNothing() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        long menuId = MacGlassNativeShim.registerMenu(new MenuRecorder(events, false),
                new RecordingCallback("kept", events, false));
        onFreshThread(events, () -> {
            MacGlassNativeShim.setMenuCallback(menuId, 0L, new RecordingCallback("dropped", events, false));
            return MacGlassNativeShim.fireMenuCallback(ACTION, menuId);
        });
        assertEquals(List.of("kept.action()"), events);
    }

    @Test
    public void setCallbackDoesNotRefileADisposedId() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        long menuId = MacGlassNativeShim.registerMenu(new MenuRecorder(events, false), null);
        assertEquals(GMAC_UPCALL_OK, MacGlassNativeShim.fireMenuCallback(MENU_DISPOSED, menuId));
        onFreshThread(events, () -> {
            MacGlassNativeShim.setMenuCallback(menuId, FAKE_MENU, new RecordingCallback("late", events, false));
            return MacGlassNativeShim.fireMenuCallback(ACTION, menuId);
        });
        assertFalse(MacGlassNativeShim.isMenuRegistered(menuId));
        assertEquals(List.of(), events);
    }

    @Test
    public void aDetachedSetCallbackUpdatesTheRegistryThenReportsOnce() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        long menuId = MacGlassNativeShim.registerMenu(new MenuRecorder(events, false), null);
        MacGlassNativeShim.setMainJavaAttached(0);
        onFreshThread(events, () -> {
            MacGlassNativeShim.setMenuCallback(menuId, FAKE_MENU, new RecordingCallback("callback", events, false));
            return MacGlassNativeShim.fireMenuCallback(ACTION, menuId);
        });
        assertEquals(List.of(report(DETACHED), "callback.action()"), events);
    }

    @Test
    public void createMenuFilesItsIdBeforeTheDowncallAndPassesTheTitleAsUtf16() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        long menuId = MacGlassNativeShim.registerMenu(new MenuRecorder(events, false), null);
        long created = onFreshThread(events, () -> MacGlassNativeShim.createMenu(menuId, TITLE, true,
                (id, title, length, enabled) -> {
                    events.add("create " + (id == menuId) + " " + MacGlassNativeShim.isMenuRegistered(id) + " "
                            + title + " " + length + " " + enabled);
                    return FAKE_MENU;
                }));
        assertEquals(FAKE_MENU, created);
        assertEquals(List.of("create true true " + TITLE + " " + TITLE.length() + " 1"), events);
        assertTrue(MacGlassNativeShim.isMenuRegistered(menuId), "only menu_disposed forgets an id");
    }

    @Test
    public void aNullTitleIsNullAndAnEmptyOneIsEmpty() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        long menuId = MacGlassNativeShim.registerMenu(new MenuRecorder(events, false), null);
        onFreshThread(events, () -> {
            for (String title : new String[] {null, ""}) {
                MacGlassNativeShim.createMenu(menuId, title, false, (id, units, length, enabled) -> {
                    events.add("[" + units + "] " + length + " " + enabled);
                    return FAKE_MENU;
                });
            }
            return null;
        });
        assertEquals(List.of("[null] 0 0", "[] 0 0"), events);
    }

    @Test
    public void aCreateThatMadeNoGlassMenuForgetsItsId() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        long menuId = MacGlassNativeShim.registerMenu(new MenuRecorder(events, false), null);
        long itemId = MacGlassNativeShim.registerMenu(new MenuRecorder(events, false), null);
        List<Long> created = onFreshThread(events, () -> List.of(
                MacGlassNativeShim.createMenu(menuId, "menu", true, (id, title, length, enabled) -> 0L),
                MacGlassNativeShim.createMenuItem(itemId, "item", 'a', 0, null, true, false,
                        (id, title, length, shortcut, modifiers, icon, enabled, checked) -> 0L)));
        assertEquals(List.of(0L, 0L), created);
        assertFalse(MacGlassNativeShim.isMenuRegistered(menuId));
        assertFalse(MacGlassNativeShim.isMenuRegistered(itemId));
        assertEquals(List.of(), events);
    }

    @Test
    public void createMenuItemPassesItsArgumentsAndNoIconWithoutPixels() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        long itemId = MacGlassNativeShim.registerMenu(new MenuRecorder(events, false), null);
        int modifiers = KeyEvent.MODIFIER_COMMAND | KeyEvent.MODIFIER_SHIFT;
        long created = onFreshThread(events, () -> MacGlassNativeShim.createMenuItem(itemId, TITLE, 'S', modifiers,
                null, false, true, (id, title, length, shortcut, mask, icon, enabled, checked) -> {
                    events.add("create " + (id == itemId) + " " + MacGlassNativeShim.isMenuRegistered(id) + " "
                            + title + " " + length + " " + shortcut + " " + mask + " " + icon + " " + enabled + " "
                            + checked);
                    return FAKE_MENU;
                }));
        assertEquals(FAKE_MENU, created);
        assertEquals(List.of("create true true " + TITLE + " " + TITLE.length() + " S " + modifiers + " null 0 1"),
                events);
    }

    @Test
    public void anItemsIconIsTheImageAttachDataMadeWithThePixelsSizeAndScales() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        long itemId = MacGlassNativeShim.registerMenu(new MenuRecorder(events, false), null);
        Pixels pixels = MacGlassNativeShim.newPixels(4, 2, IntBuffer.allocate(8), 2.0f, 1.5f);
        List<MenuIcon> icons = new CopyOnWriteArrayList<>();
        List<ImageReadback> images = new CopyOnWriteArrayList<>();
        onFreshThread(events, () -> MacGlassNativeShim.createMenuItem(itemId, "item", 'a', 0, pixels, true, false,
                (id, title, length, shortcut, modifiers, icon, enabled, checked) -> {
                    icons.add(icon);
                    images.add(readAndRelease(icon));
                    return FAKE_MENU;
                }));
        assertIconOfFourByTwoPixels(icons, images);
        assertEquals(List.of(), events);
    }

    @Test
    public void whatAttachDataThrowsIsReportedAfterTheDowncallWhichGetsNoImage() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        long itemId = MacGlassNativeShim.registerMenu(new MenuRecorder(events, false), null);
        Pixels pixels = MacGlassNativeShim.throwingPixels(new IllegalStateException("attachData"));
        onFreshThread(events, () -> MacGlassNativeShim.createMenuItem(itemId, "item", 'a', 0, pixels, true, false,
                (id, title, length, shortcut, modifiers, icon, enabled, checked) -> {
                    events.add("create " + icon);
                    return FAKE_MENU;
                }));
        assertEquals(List.of("create " + new MenuIcon(0L, 1, 1, 1.0f, 1.0f),
                report("java.lang.IllegalStateException: attachData")), events);
    }

    @Test
    public void aDetachedCreateMakesItsMenuThenReportsOnce() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        long menuId = MacGlassNativeShim.registerMenu(new MenuRecorder(events, false), null);
        MacGlassNativeShim.setMainJavaAttached(0);
        long created = onFreshThread(events, () -> MacGlassNativeShim.createMenu(menuId, "menu", true,
                (id, title, length, enabled) -> {
                    events.add("create");
                    return FAKE_MENU;
                }));
        assertEquals(FAKE_MENU, created);
        assertEquals(List.of("create", report(DETACHED)), events);
    }

    /** What {@code attachData} threw replaced the assertion's exception under JNI (PB11). */
    @Test
    public void aDetachedItemWithAThrowingIconReportsOnlyWhatAttachDataThrew() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        long itemId = MacGlassNativeShim.registerMenu(new MenuRecorder(events, false), null);
        Pixels pixels = MacGlassNativeShim.throwingPixels(new IllegalStateException("attachData"));
        MacGlassNativeShim.setMainJavaAttached(0);
        onFreshThread(events, () -> MacGlassNativeShim.createMenuItem(itemId, "item", 'a', 0, pixels, true, false,
                (id, title, length, shortcut, modifiers, icon, enabled, checked) -> {
                    events.add("create");
                    return FAKE_MENU;
                }));
        assertEquals(List.of("create", report("java.lang.IllegalStateException: attachData")), events);
    }

    /**
     * {@code attachData} reaches {@code MacPixels._attachInt}, which reports the assertion of its own before it
     * returns, and the item's report follows the downcall, as under the JNI wrappers: HotSpot set the item's pending
     * exception aside for the {@code attachData} call and restored it afterwards (PB11).
     */
    @Test
    public void aDetachedItemWithAnIconReportsInAttachDataAndAfterTheDowncall() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        long itemId = MacGlassNativeShim.registerMenu(new MenuRecorder(events, false), null);
        Pixels pixels = MacGlassNativeShim.newPixels(4, 2, IntBuffer.allocate(8), 2.0f, 1.5f);
        List<MenuIcon> icons = new CopyOnWriteArrayList<>();
        List<ImageReadback> images = new CopyOnWriteArrayList<>();
        MacGlassNativeShim.setMainJavaAttached(0);
        onFreshThread(events, () -> MacGlassNativeShim.createMenuItem(itemId, "item", 'a', 0, pixels, true, false,
                (id, title, length, shortcut, modifiers, icon, enabled, checked) -> {
                    events.add("create");
                    icons.add(icon);
                    images.add(readAndRelease(icon));
                    return FAKE_MENU;
                }));
        assertEquals(List.of(report(DETACHED), "create", report(DETACHED)), events);
        assertIconOfFourByTwoPixels(icons, images);
    }

    @Test
    public void setPixelsAttachesTheIconBeforeItsDowncall() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        Pixels pixels = MacGlassNativeShim.newPixels(4, 2, IntBuffer.allocate(8), 2.0f, 1.5f);
        List<MenuIcon> icons = new CopyOnWriteArrayList<>();
        List<ImageReadback> images = new CopyOnWriteArrayList<>();
        onFreshThread(events, () -> {
            MacGlassNativeShim.setMenuPixels(FAKE_MENU, pixels, (menu, icon) -> {
                events.add("set " + (menu == FAKE_MENU));
                icons.add(icon);
                images.add(readAndRelease(icon));
            });
            return null;
        });
        assertEquals(List.of("set true"), events);
        assertIconOfFourByTwoPixels(icons, images);
    }

    @Test
    public void setPixelsWithoutPixelsPassesNoIcon() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        onFreshThread(events, () -> {
            MacGlassNativeShim.setMenuPixels(FAKE_MENU, null, (menu, icon) -> events.add("set " + icon));
            return null;
        });
        assertEquals(List.of("set null"), events);
    }

    /** Messaging the nil {@code GlassMenu} ran no {@code _setPixels:}, so no {@code attachData}. */
    @Test
    public void setPixelsOnTheNullMenuCallsNoAttachDataAndPassesNoIcon() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        Pixels pixels = MacGlassNativeShim.throwingPixels(new IllegalStateException("attachData"));
        onFreshThread(events, () -> {
            MacGlassNativeShim.setMenuPixels(0L, pixels, (menu, icon) -> events.add("set " + menu + " " + icon));
            MacGlassNativeShim.setMenuPixels(0L, pixels);
            return null;
        });
        assertEquals(List.of("set 0 null"), events);
    }

    @Test
    public void aDetachedSetPixelsReportsWhatAttachDataThrewInsteadOfTheAssertion() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        Pixels pixels = MacGlassNativeShim.throwingPixels(new IllegalStateException("attachData"));
        MacGlassNativeShim.setMainJavaAttached(0);
        onFreshThread(events, () -> {
            MacGlassNativeShim.setMenuPixels(FAKE_MENU, pixels, (menu, icon) -> events.add("set " + icon));
            MacGlassNativeShim.setMenuPixels(FAKE_MENU, null, (menu, icon) -> events.add("set " + icon));
            return null;
        });
        assertEquals(List.of("set " + new MenuIcon(0L, 1, 1, 1.0f, 1.0f),
                report("java.lang.IllegalStateException: attachData"), "set null", report(DETACHED)), events);
    }

    @Test
    public void theNullMenuCallsMakeTheirDowncallsAndReportOnlyWhenDetached() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        onFreshThread(events, MacMenuNativeTest::callExportsOnTheNullMenu);
        assertEquals(List.of(), events);
        MacGlassNativeShim.setMainJavaAttached(0);
        onFreshThread(events, MacMenuNativeTest::callExportsOnTheNullMenu);
        assertEquals(List.of(report(DETACHED), report(DETACHED), report(DETACHED)), events);
    }

    @Test
    public void theKeyEquivalentIsTheKeyOrItsFunctionKeyWithTheModifierFlagsOfItsModifiers() {
        assertKeyEquivalent('A', KeyEvent.MODIFIER_COMMAND, "a", COMMAND_MASK);
        assertKeyEquivalent('a', KeyEvent.MODIFIER_COMMAND, "a", COMMAND_MASK);
        assertKeyEquivalent((char) KeyEvent.VK_F1, KeyEvent.MODIFIER_NONE, "p", 0);
        assertKeyEquivalent((char) KeyEvent.VK_LEFT, KeyEvent.MODIFIER_SHIFT, "←", SHIFT_MASK);
        assertKeyEquivalent((char) KeyEvent.VK_ESCAPE, KeyEvent.MODIFIER_CONTROL, "⎋", CONTROL_MASK);
        assertKeyEquivalent((char) KeyEvent.VK_DELETE, KeyEvent.MODIFIER_OPTION, "⌦", OPTION_MASK);
        assertKeyEquivalent((char) KeyEvent.VK_MINUS, KeyEvent.MODIFIER_COMMAND, "–", COMMAND_MASK);
        assertKeyEquivalent((char) KeyEvent.VK_UNDEFINED, KeyEvent.MODIFIER_COMMAND, "", COMMAND_MASK);
    }

    @Test
    public void theFunctionModifierSpellsTheFunctionKeysAndSetsNoFlag() {
        assertKeyEquivalent((char) KeyEvent.VK_F1, KeyEvent.MODIFIER_FUNCTION, "", 0);
        assertKeyEquivalent((char) KeyEvent.VK_F12, KeyEvent.MODIFIER_FUNCTION | KeyEvent.MODIFIER_COMMAND,
                "", COMMAND_MASK);
        assertKeyEquivalent((char) KeyEvent.VK_F13, KeyEvent.MODIFIER_FUNCTION, "", 0);
        int every = KeyEvent.MODIFIER_SHIFT | KeyEvent.MODIFIER_FUNCTION | KeyEvent.MODIFIER_CONTROL
                | KeyEvent.MODIFIER_OPTION | KeyEvent.MODIFIER_COMMAND;
        assertKeyEquivalent((char) KeyEvent.VK_F24, every, "",
                SHIFT_MASK | CONTROL_MASK | OPTION_MASK | COMMAND_MASK);
    }

    @Test
    public void aShortBufferGetsAPrefixAndTheWholeLength() {
        assertEquals(new KeyEquivalent(1, "", COMMAND_MASK), MacGlassNativeShim.menuKeyEquivalent('a',
                KeyEvent.MODIFIER_COMMAND, 0));
    }

    private static Void callExportsOnTheNullMenu() {
        MacGlassNativeShim.setMenuShortcut(0L, 'a', KeyEvent.MODIFIER_COMMAND);
        MacGlassNativeShim.setMenuChecked(0L, true);
        MacGlassNativeShim.setMenuPixels(0L, null);
        return null;
    }

    private static void assertKeyEquivalent(char shortcut, int modifiers, String keyEquivalent, long mask) {
        String what = "shortcut 0x" + Integer.toHexString(shortcut) + ", modifiers 0x" + Integer.toHexString(modifiers);
        assertEquals(new KeyEquivalent(keyEquivalent.length(), keyEquivalent, mask),
                MacGlassNativeShim.menuKeyEquivalent(shortcut, modifiers, 8), what);
    }

    /** The icon of a 4x2 {@code Pixels} of scales 2 and 1.5: its image, read back and released, is of the pixels. */
    private static void assertIconOfFourByTwoPixels(List<MenuIcon> icons, List<ImageReadback> images) {
        assertEquals(1, icons.size());
        MenuIcon icon = icons.getFirst();
        assertNotEquals(0L, icon.image());
        assertEquals(List.of(4, 2, 2.0f, 1.5f), List.of(icon.width(), icon.height(), icon.scaleX(), icon.scaleY()));
        assertEquals(List.of(4, 2), images.getFirst().values().subList(2, 4), "the CGImage's width and height");
    }

    private static ImageReadback readAndRelease(MenuIcon icon) {
        return MacGlassNativeShim.inspectAndReleaseImage(MemorySegment.ofAddress(icon.image()), 0);
    }

    private static List<Integer> fireSlotsUpToClosed(long menuId) {
        List<Integer> statuses = new ArrayList<>();
        for (int slot = ACTION; slot <= MENU_CLOSED; slot++) {
            statuses.add(MacGlassNativeShim.fireMenuCallback(slot, menuId));
        }
        return statuses;
    }

    private static List<Integer> fireEverySlot(long menuId) {
        List<Integer> statuses = new ArrayList<>();
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            statuses.add(MacGlassNativeShim.fireMenuCallback(slot, menuId));
        }
        return statuses;
    }

    private static String report(String exception) {
        return "report " + exception + " on " + CALLER;
    }

    private static List<String> names(StructLayout layout) {
        return layout.memberLayouts().stream().map(member -> member.name().orElseThrow()).toList();
    }

    private record HeaderSlot(String name, MethodType type) {
    }

    /** The slots of {@code GmacMenuCallbacks} in the header, each with the carriers of its prototype. */
    private static List<HeaderSlot> headerSlots() throws IOException {
        assertTrue(Files.isRegularFile(HEADER), "run from modules/javafx.graphics: no " + HEADER.toAbsolutePath());
        String header = COMMENT.matcher(Files.readString(HEADER)).replaceAll(" ");
        Matcher table = MENU_CALLBACKS.matcher(header);
        assertTrue(table.find(), "no GmacMenuCallbacks in " + HEADER);
        List<HeaderSlot> slots = new ArrayList<>();
        Matcher slot = SLOT.matcher(table.group(1));
        while (slot.find()) {
            List<Class<?>> parameters = new ArrayList<>();
            for (String parameter : slot.group(3).split(",")) {
                parameters.add(carrier(parameter.trim().replaceFirst("\\s+\\w+$", "")));
            }
            slots.add(new HeaderSlot(slot.group(2), MethodType.methodType(carrier(slot.group(1)), parameters)));
        }
        return slots;
    }

    private static Class<?> carrier(String cType) {
        return switch (cType) {
            case "int32_t" -> int.class;
            case "int64_t" -> long.class;
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
