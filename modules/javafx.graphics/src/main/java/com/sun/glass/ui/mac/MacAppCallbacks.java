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

import com.sun.glass.ui.Application;
import com.sun.glass.ui.Screen;
import com.sun.javafx.font.JniStringCodec;
import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.PaddingLayout;
import java.lang.foreign.StructLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_APP_INSTALL_CALLBACKS;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_OFFSETOF_COLOR;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_OFFSETOF_PREF;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_OFFSETOF_SCREEN_INFO;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_OK;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_SIZEOF_APP_CALLBACKS;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_SIZEOF_COLOR;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_SIZEOF_PREF;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_SIZEOF_SCREEN_INFO;
import static com.sun.glass.ui.mac.MacGlassNative.LIBRARY_NAME;
import static com.sun.glass.ui.mac.MacGlassNative.bounded;
import static com.sun.glass.ui.mac.MacGlassNative.newStringUtf;
import static com.sun.glass.ui.mac.MacGlassNative.reportException;
import static com.sun.glass.ui.mac.MacGlassNative.unexpected;
import static com.sun.glass.ui.mac.MacGlassNative.upcallStub;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * {@code GmacAppCallbacks}: the upcalls of {@code MacApplication}'s native side, on the AppKit main thread. Every
 * target catches what it is given to run and disposes of it as the JNI site did; {@code glass_mac_api.h} names the
 * sink of each slot.
 */
final class MacAppCallbacks {

    static final int GMAC_UPCALL_OK = 0;
    static final int GMAC_UPCALL_THREW = 1;

    static final int RUN_RUNNABLE = 0;
    static final int RUN_LAUNCHABLE = 1;
    static final int SET_EVENT_THREAD = 2;
    static final int SET_NAME = 3;
    static final int NOTIFY_WILL_FINISH_LAUNCHING = 4;
    static final int NOTIFY_OPEN_FILES = 15;
    static final int NOTIFY_DID_TERMINATE = 16;
    static final int ENTER_NESTED_EVENT_LOOP = 17;
    static final int LEAVE_NESTED_EVENT_LOOP = 18;
    static final int NOTIFY_SCREEN_SETTINGS_CHANGED = 19;
    static final int NOTIFY_PREFERENCES_CHANGED = 20;

    /** The slots in table order: the index of a name is its slot number. */
    static final List<String> SLOT_NAMES = List.of("run_runnable", "run_launchable", "set_event_thread", "set_name",
            "notify_will_finish_launching", "notify_did_finish_launching", "notify_will_become_active",
            "notify_did_become_active", "notify_will_resign_active", "notify_did_resign_active", "notify_will_hide",
            "notify_did_hide", "notify_will_unhide", "notify_did_unhide", "notify_will_quit", "notify_open_files",
            "notify_did_terminate", "enter_nested_event_loop", "leave_nested_event_loop",
            "notify_screen_settings_changed", "notify_preferences_changed");

    static final StructLayout GMAC_APP_CALLBACKS_LAYOUT = MemoryLayout.structLayout(
            SLOT_NAMES.stream().map(ADDRESS::withName).toArray(MemoryLayout[]::new));

    /** Where the slots go: {@code MacApplication} in production. */
    interface Receiver {

        void runRunnable(long runnableId) throws Throwable;

        /** A slot without arguments. */
        void fire(int slot) throws Throwable;

        void setName(String name) throws Throwable;

        void notifyOpenFiles(String[] files) throws Throwable;

        void notifyPreferencesChanged(Map<String, Object> preferences) throws Throwable;
    }

    private static final class Installation {

        final Receiver receiver;

        /* Main thread only, as PlatformSupport's currentPreferences was. */
        Map<String, Object> keptPreferences;

        Installation(Receiver receiver) {
            this.receiver = receiver;
        }
    }

    private static volatile Installation installation;

    private static final MemorySegment[] STUBS = createStubs();

    /** Never closed: closing it would close fd 2. */
    private static final FileOutputStream STANDARD_ERROR = new FileOutputStream(FileDescriptor.err);

    private MacAppCallbacks() {
    }

    /** Launcher thread, once, before {@code gmac_app_create_delegate} creates the delegate. */
    static void install(MacApplication application, Runnable launchable) {
        install(new ApplicationReceiver(application, launchable));
    }

    static void install(Receiver receiver) {
        checkLayouts();
        installation = new Installation(receiver);
        // The library copies the table, so its memory may go once the call returns.
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment table = arena.allocate(GMAC_APP_CALLBACKS_LAYOUT);
            for (int slot = 0; slot < STUBS.length; slot++) {
                table.setAtIndex(ADDRESS, slot, STUBS[slot]);
            }
            int status = (int) GMAC_APP_INSTALL_CALLBACKS.invokeExact(table);
            if (status != GMAC_OK) {
                throw new IllegalStateException("gmac_app_install_callbacks answered " + status);
            }
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** Refuses a library whose structs are not the layouts of this package. */
    static void checkLayouts() {
        requireSize("GmacAppCallbacks", GMAC_APP_CALLBACKS_LAYOUT, probe(GMAC_SIZEOF_APP_CALLBACKS));
        requireLayout("GmacScreenInfo", MacScreenCalls.GMAC_SCREEN_INFO_LAYOUT, GMAC_SIZEOF_SCREEN_INFO,
                GMAC_OFFSETOF_SCREEN_INFO);
        requireLayout("GmacColor", MacPreferenceCalls.GMAC_COLOR_LAYOUT, GMAC_SIZEOF_COLOR, GMAC_OFFSETOF_COLOR);
        requireLayout("GmacPref", MacPreferenceCalls.GMAC_PREF_LAYOUT, GMAC_SIZEOF_PREF, GMAC_OFFSETOF_PREF);
    }

    static void requireLayout(String struct, StructLayout layout, MethodHandle sizeOf, MethodHandle offsetOf) {
        requireSize(struct, layout, probe(sizeOf));
        int member = 0;
        for (MemoryLayout element : layout.memberLayouts()) {
            if (element instanceof PaddingLayout) {
                continue;
            }
            String name = element.name().orElseThrow();
            long expected = layout.byteOffset(PathElement.groupElement(name));
            int actual = probe(offsetOf, member);
            if (actual != expected) {
                throw new UnsatisfiedLinkError(LIBRARY_NAME + " layout mismatch: " + struct + "." + name + " is at "
                        + actual + ", the layout has it at " + expected);
            }
            member++;
        }
        if (probe(offsetOf, member) != -1) {
            throw new UnsatisfiedLinkError(LIBRARY_NAME + " layout mismatch: " + struct + " has more than the "
                    + member + " members of the layout");
        }
    }

    static void requireSize(String struct, StructLayout layout, int size) {
        if (size != layout.byteSize()) {
            throw new UnsatisfiedLinkError(LIBRARY_NAME + " layout mismatch: sizeof " + struct + " is " + size
                    + ", the layout has " + layout.byteSize() + " bytes");
        }
    }

    private static int probe(MethodHandle sizeOf) {
        try {
            return (int) sizeOf.invokeExact();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static int probe(MethodHandle offsetOf, int member) {
        try {
            return (int) offsetOf.invokeExact(member);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static MemorySegment[] createStubs() {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        try {
            MethodHandle fire = lookup.findStatic(MacAppCallbacks.class, "fire",
                    MethodType.methodType(int.class, int.class));
            MemorySegment[] stubs = new MemorySegment[SLOT_NAMES.size()];
            for (int slot = 0; slot < stubs.length; slot++) {
                stubs[slot] = switch (slot) {
                    case RUN_RUNNABLE -> stub(lookup, "runRunnable", FunctionDescriptor.of(JAVA_INT, JAVA_LONG));
                    case SET_NAME -> stub(lookup, "setName", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
                    case NOTIFY_OPEN_FILES -> stub(lookup, "notifyOpenFiles",
                            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
                    case NOTIFY_PREFERENCES_CHANGED -> stub(lookup, "notifyPreferencesChanged",
                            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
                    default -> upcallStub(MethodHandles.insertArguments(fire, 0, slot),
                            FunctionDescriptor.of(JAVA_INT));
                };
            }
            return stubs;
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static MemorySegment stub(MethodHandles.Lookup lookup, String name, FunctionDescriptor descriptor)
            throws ReflectiveOperationException {
        return upcallStub(lookup.findStatic(MacAppCallbacks.class, name, descriptor.toMethodType()), descriptor);
    }

    private static int runRunnable(long runnableId) {
        try {
            installation.receiver.runRunnable(runnableId);
            return GMAC_UPCALL_OK;
        } catch (Throwable t) {
            reportException(t);
            return GMAC_UPCALL_THREW;
        }
    }

    private static int fire(int slot) {
        try {
            installation.receiver.fire(slot);
            return GMAC_UPCALL_OK;
        } catch (Throwable t) {
            if (slot == RUN_LAUNCHABLE || slot == ENTER_NESTED_EVENT_LOOP) {
                describe(t);
            } else {
                // leave_nested_event_loop too: its JNI left the exception for a later check, which an upcall cannot.
                reportException(t);
            }
            return GMAC_UPCALL_THREW;
        }
    }

    private static int setName(MemorySegment utf8, int length) {
        try {
            installation.receiver.setName(newStringUtf(utf8, length));
            return GMAC_UPCALL_OK;
        } catch (Throwable t) {
            reportException(t);
            return GMAC_UPCALL_THREW;
        }
    }

    /** THREW only when the {@code String[]} could not be built: the C then replies failure to AppKit. */
    private static int notifyOpenFiles(MemorySegment utf8, MemorySegment lengths, int count) {
        String[] files;
        try {
            files = fileNames(utf8, lengths, count);
        } catch (Throwable t) {
            reportException(t);
            return GMAC_UPCALL_THREW;
        }
        try {
            installation.receiver.notifyOpenFiles(files);
        } catch (Throwable t) {
            reportException(t);
        }
        return GMAC_UPCALL_OK;
    }

    /** A name that cannot be built is reported and stays null, as the JNI went on after a failed element. */
    private static String[] fileNames(MemorySegment utf8, MemorySegment lengths, int count) {
        String[] files = new String[count];
        MemorySegment names = bounded(utf8, ADDRESS.byteSize() * count);
        MemorySegment byteCounts = bounded(lengths, JAVA_INT.byteSize() * count);
        for (int i = 0; i < count; i++) {
            try {
                files[i] = newStringUtf(names.getAtIndex(ADDRESS, i), byteCounts.getAtIndex(JAVA_INT, i));
            } catch (Throwable t) {
                reportException(t);
            }
        }
        return files;
    }

    /**
     * Notifies with an unmodifiable view of the new map when it differs from the one kept from the last call. A
     * step that throws is reported and ends the call, as each step's {@code GLASS_CHECK_EXCEPTION} did.
     */
    private static int notifyPreferencesChanged(MemorySegment entries, int count) {
        Installation current = installation;
        try {
            HashMap<String, Object> preferences = MacPreferenceCalls.toMap(entries, count);
            if (changed(preferences, current.keptPreferences)) {
                current.keptPreferences = preferences;
                current.receiver.notifyPreferencesChanged(Collections.unmodifiableMap(preferences));
            }
            return GMAC_UPCALL_OK;
        } catch (Throwable t) {
            reportException(t);
            return GMAC_UPCALL_THREW;
        }
    }

    /** A failing {@code equals} is reported and counts as a change: the JNI read its answer as false. */
    private static boolean changed(Map<String, Object> preferences, Map<String, Object> kept) {
        try {
            return !preferences.equals(kept);
        } catch (Throwable t) {
            reportException(t);
            return true;
        }
    }

    /**
     * What JNI {@code ExceptionDescribe} printed: HotSpot writes the prefix to fd 2 itself, with the thread name in
     * modified UTF-8, then calls {@code printStackTrace} and swallows what that throws.
     */
    private static void describe(Throwable t) {
        try {
            byte[] name = JniStringCodec.toModifiedUtf8(Thread.currentThread().getName());
            ByteArrayOutputStream prefix = new ByteArrayOutputStream();
            prefix.writeBytes("Exception in thread \"".getBytes(StandardCharsets.US_ASCII));
            prefix.write(name, 0, name.length - 1);
            prefix.writeBytes("\" ".getBytes(StandardCharsets.US_ASCII));
            STANDARD_ERROR.write(prefix.toByteArray());
            t.printStackTrace();
        } catch (Throwable ignored) {
            // ExceptionDescribe cleared it
        }
    }

    /** The methods the JNI called by name, whatever their access. */
    static final class ApplicationReceiver implements Receiver {

        /** The targets of slots 4-14, in slot order. */
        static final List<String> NOTIFICATIONS = List.of("notifyWillFinishLaunching",
                "notifyDidFinishLaunching", "notifyWillBecomeActive", "notifyDidBecomeActive", "notifyWillResignActive",
                "notifyDidResignActive", "notifyWillHide", "notifyDidHide", "notifyWillUnhide", "notifyDidUnhide",
                "notifyWillQuit");

        private static final MethodHandles.Lookup APPLICATION = privateLookup(Application.class);
        private static final MethodHandles.Lookup MAC_APPLICATION = privateLookup(MacApplication.class);

        private static final MethodType NO_ARGUMENTS = MethodType.methodType(void.class);

        private static final List<MethodHandle> NOTIFICATION_TARGETS = NOTIFICATIONS.stream()
                .map(name -> findVirtual(APPLICATION, Application.class, name, NO_ARGUMENTS))
                .toList();
        private static final MethodHandle SET_EVENT_THREAD_TARGET = findVirtual(MAC_APPLICATION, MacApplication.class,
                "setEventThread", NO_ARGUMENTS);
        private static final MethodHandle NOTIFY_DID_TERMINATE_TARGET = findVirtual(MAC_APPLICATION,
                MacApplication.class, "notifyApplicationDidTerminate", NO_ARGUMENTS);
        private static final MethodHandle NOTIFY_OPEN_FILES_TARGET = findVirtual(APPLICATION, Application.class,
                "notifyOpenFiles", MethodType.methodType(void.class, String[].class));
        private static final MethodHandle NOTIFY_PREFERENCES_CHANGED_TARGET = findVirtual(APPLICATION,
                Application.class, "notifyPreferencesChanged", MethodType.methodType(void.class, Map.class));
        private static final MethodHandle ENTER_NESTED_EVENT_LOOP_TARGET = findStatic(APPLICATION,
                "enterNestedEventLoop", MethodType.methodType(Object.class)).asType(NO_ARGUMENTS);
        private static final MethodHandle LEAVE_NESTED_EVENT_LOOP_TARGET = findStatic(APPLICATION,
                "leaveNestedEventLoop", MethodType.methodType(void.class, Object.class));

        private final MacApplication application;
        private final Runnable launchable;

        ApplicationReceiver(MacApplication application, Runnable launchable) {
            this.application = application;
            this.launchable = launchable;
        }

        @Override
        public void runRunnable(long runnableId) {
            MacMainLoopCalls.runRunnable(runnableId);
        }

        @Override
        public void fire(int slot) throws Throwable {
            switch (slot) {
                case RUN_LAUNCHABLE -> launchable.run();
                case SET_EVENT_THREAD -> SET_EVENT_THREAD_TARGET.invokeExact(application);
                case NOTIFY_DID_TERMINATE -> NOTIFY_DID_TERMINATE_TARGET.invokeExact(application);
                case ENTER_NESTED_EVENT_LOOP -> ENTER_NESTED_EVENT_LOOP_TARGET.invokeExact();
                case LEAVE_NESTED_EVENT_LOOP -> LEAVE_NESTED_EVENT_LOOP_TARGET.invokeExact((Object) null);
                case NOTIFY_SCREEN_SETTINGS_CHANGED -> Screen.notifySettingsChanged();
                default -> NOTIFICATION_TARGETS.get(slot - NOTIFY_WILL_FINISH_LAUNCHING)
                        .invokeExact((Application) application);
            }
        }

        @Override
        public void setName(String name) {
            application.setName(name);
        }

        @Override
        public void notifyOpenFiles(String[] files) throws Throwable {
            NOTIFY_OPEN_FILES_TARGET.invokeExact((Application) application, files);
        }

        @Override
        public void notifyPreferencesChanged(Map<String, Object> preferences) throws Throwable {
            NOTIFY_PREFERENCES_CHANGED_TARGET.invokeExact((Application) application, preferences);
        }

        private static MethodHandles.Lookup privateLookup(Class<?> target) {
            try {
                return MethodHandles.privateLookupIn(target, MethodHandles.lookup());
            } catch (IllegalAccessException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        private static MethodHandle findVirtual(MethodHandles.Lookup lookup, Class<?> owner, String name,
                                                MethodType type) {
            try {
                return lookup.findVirtual(owner, name, type);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        private static MethodHandle findStatic(MethodHandles.Lookup lookup, String name, MethodType type) {
            try {
                return lookup.findStatic(Application.class, name, type);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
    }
}
