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

import com.sun.glass.ui.Menu;
import com.sun.glass.ui.MenuItem.Callback;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import static com.sun.glass.ui.mac.MacAppCallbacks.GMAC_UPCALL_OK;
import static com.sun.glass.ui.mac.MacAppCallbacks.GMAC_UPCALL_THREW;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_MENU_INSTALL_CALLBACKS;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_OFFSETOF_MENU_IMAGE;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_OK;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_SIZEOF_MENU_CALLBACKS;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_SIZEOF_MENU_IMAGE;
import static com.sun.glass.ui.mac.MacGlassNative.reportException;
import static com.sun.glass.ui.mac.MacGlassNative.unexpected;
import static com.sun.glass.ui.mac.MacGlassNative.upcallStub;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * {@code GmacMenuCallbacks}: the upcalls of {@code MacMenuDelegate}'s native side, on the AppKit main thread, for the
 * {@code GlassMenu}s {@code gmac_menu_create} and {@code gmac_menu_item_create} made. A {@code GlassMenu} crosses as
 * the id {@link #register} gave it, which names what the JNI's two global references named: the delegate, through
 * which its menu is reached, and the item's callback. The entries are strong, as those references were, so a
 * {@code GlassMenu} that is never removed keeps both (latent bug L9). {@code menu_disposed} is the only slot that
 * forgets an id; besides it, only a create export that made no {@code GlassMenu} does. An id the registry does not
 * hold runs nothing.
 * <p>
 * No Java exception may escape a slot. The JNI checked for none after slots 0-3 and left what they threw pending
 * (latent bug L20) for a later {@code GLASS_CHECK_EXCEPTION} on the main thread; their targets report it at once
 * through {@code Application.reportException} (g3 Risk R5). Slot 4 stands where the JNI deleted its global
 * references, which ran no Java, and reports the same way. A slot answers {@code GMAC_UPCALL_THREW} when it reported,
 * else {@code GMAC_UPCALL_OK}; the C reads neither.
 */
final class MacMenuCallbacks {

    /** The slots in table order: the index of a name is its slot number. */
    static final List<String> SLOT_NAMES = List.of("action", "validate", "menu_opening", "menu_closed",
            "menu_disposed");

    /** Every slot's: {@code int32_t (*)(int64_t menu_id)}. */
    static final FunctionDescriptor SLOT_DESCRIPTOR = FunctionDescriptor.of(JAVA_INT, JAVA_LONG);

    static final StructLayout GMAC_MENU_CALLBACKS_LAYOUT = MemoryLayout.structLayout(
            SLOT_NAMES.stream().map(ADDRESS::withName).toArray(MemoryLayout[]::new));

    /**
     * The menu side of a {@code GlassMenu}'s slots: the {@code Menu} methods the JNI called on the delegate's
     * {@code menu}, which it read at each call.
     */
    interface MenuReceiver {

        void notifyMenuOpening() throws Throwable;

        void notifyMenuClosed() throws Throwable;
    }

    /** What the JNI's global references of one {@code GlassMenu} named: the delegate's menu, and the callback. */
    private record Entry(MenuReceiver menu, Callback callback) {
    }

    @FunctionalInterface
    private interface SlotBody {

        void run(Entry entry) throws Throwable;
    }

    private static final Map<Long, Entry> MENUS = new ConcurrentHashMap<>();

    private static final AtomicLong NEXT_MENU_ID = new AtomicLong(1);

    /** {@code Menu.notifyMenuOpening()} and {@code notifyMenuClosed()} are protected: the JNI called them by name. */
    private static final MethodHandle NOTIFY_MENU_OPENING = menuMethod("notifyMenuOpening");
    private static final MethodHandle NOTIFY_MENU_CLOSED = menuMethod("notifyMenuClosed");

    private static final MemorySegment[] STUBS = createStubs();

    private MacMenuCallbacks() {
    }

    /**
     * Once, from {@code MacMenuDelegate}'s class initialization, before the first {@code gmac_menu_create} or
     * {@code gmac_menu_item_create}: checks the layouts of the table and of {@code GmacMenuImage}, then installs the
     * table. The library copies it, so its memory may go once the call returns.
     */
    static void install() {
        MacAppCallbacks.requireSize("GmacMenuCallbacks", GMAC_MENU_CALLBACKS_LAYOUT,
                probe(GMAC_SIZEOF_MENU_CALLBACKS));
        MacAppCallbacks.requireLayout("GmacMenuImage", MacMenuCalls.GMAC_MENU_IMAGE_LAYOUT, GMAC_SIZEOF_MENU_IMAGE,
                GMAC_OFFSETOF_MENU_IMAGE);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment table = arena.allocate(GMAC_MENU_CALLBACKS_LAYOUT);
            for (int slot = 0; slot < STUBS.length; slot++) {
                table.setAtIndex(ADDRESS, slot, STUBS[slot]);
            }
            int status = (int) GMAC_MENU_INSTALL_CALLBACKS.invokeExact(table);
            if (status != GMAC_OK) {
                throw new IllegalStateException("gmac_menu_install_callbacks answered " + status);
            }
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** The upcall stub of a slot. */
    static MemorySegment stub(int slot) {
        return STUBS[slot];
    }

    /**
     * A new nonzero id naming {@code delegate}, whose menu the menu slots reach, and {@code callback}, which the item
     * slots reach: before the create export of its {@code GlassMenu}.
     */
    static long register(MacMenuDelegate delegate, Callback callback) {
        return register(new DelegateMenu(delegate), callback);
    }

    static long register(MenuReceiver menu, Callback callback) {
        long menuId = NEXT_MENU_ID.getAndIncrement();
        MENUS.put(menuId, new Entry(menu, callback));
        return menuId;
    }

    /** Forgets the id of a {@code GlassMenu} the create export did not make. */
    static void unregister(long menuId) {
        MENUS.remove(menuId);
    }

    /** The callback the item slots of {@code menuId} reach from now on. An id the registry lacks stays out of it. */
    static void setCallback(long menuId, Callback callback) {
        MENUS.computeIfPresent(menuId, (id, entry) -> new Entry(entry.menu(), callback));
    }

    static boolean isRegistered(long menuId) {
        return MENUS.containsKey(menuId);
    }

    /** {@code Callback.action()}; the JNI made no call for an item without a callback. */
    private static int action(long menuId) {
        return dial(menuId, entry -> {
            if (entry.callback() != null) {
                entry.callback().action();
            }
        });
    }

    /**
     * {@code Callback.validate()}. The JNI called it on a NULL callback all the same (latent bug L7), where HotSpot's
     * {@code CallVoidMethod} raised a {@code NullPointerException} without a message, as
     * {@link Objects#requireNonNull(Object)} does.
     */
    private static int validate(long menuId) {
        return dial(menuId, entry -> Objects.requireNonNull(entry.callback()).validate());
    }

    private static int menuOpening(long menuId) {
        return dial(menuId, entry -> entry.menu().notifyMenuOpening());
    }

    private static int menuClosed(long menuId) {
        return dial(menuId, entry -> entry.menu().notifyMenuClosed());
    }

    private static int menuDisposed(long menuId) {
        return dial(menuId, entry -> MENUS.remove(menuId));
    }

    private static int dial(long menuId, SlotBody body) {
        try {
            Entry entry = MENUS.get(menuId);
            if (entry != null) {
                body.run(entry);
            }
            return GMAC_UPCALL_OK;
        } catch (Throwable t) {
            reportException(t);
            return GMAC_UPCALL_THREW;
        }
    }

    private static int probe(MethodHandle sizeOf) {
        try {
            return (int) sizeOf.invokeExact();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static MethodHandle menuMethod(String name) {
        try {
            return MethodHandles.privateLookupIn(Menu.class, MethodHandles.lookup()).findVirtual(Menu.class, name,
                    MethodType.methodType(void.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static MemorySegment[] createStubs() {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        try {
            return new MemorySegment[] {
                stub(lookup, "action"),
                stub(lookup, "validate"),
                stub(lookup, "menuOpening"),
                stub(lookup, "menuClosed"),
                stub(lookup, "menuDisposed")
            };
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static MemorySegment stub(MethodHandles.Lookup lookup, String name) throws ReflectiveOperationException {
        MethodHandle target = lookup.findStatic(MacMenuCallbacks.class, name, SLOT_DESCRIPTOR.toMethodType());
        return upcallStub(target, SLOT_DESCRIPTOR);
    }

    /**
     * The JNI read the delegate's {@code menu} at each call and called through it even when it was null, which raised
     * the {@code NullPointerException} {@link #validate} describes.
     */
    private record DelegateMenu(MacMenuDelegate delegate) implements MenuReceiver {

        @Override
        public void notifyMenuOpening() throws Throwable {
            Menu menu = Objects.requireNonNull(delegate.menu());
            NOTIFY_MENU_OPENING.invokeExact(menu);
        }

        @Override
        public void notifyMenuClosed() throws Throwable {
            Menu menu = Objects.requireNonNull(delegate.menu());
            NOTIFY_MENU_CLOSED.invokeExact(menu);
        }
    }
}
