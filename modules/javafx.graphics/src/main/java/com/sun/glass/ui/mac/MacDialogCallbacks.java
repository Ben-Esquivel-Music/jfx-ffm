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

import com.sun.glass.ui.EventLoop;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import static com.sun.glass.ui.mac.MacAppCallbacks.GMAC_UPCALL_OK;
import static com.sun.glass.ui.mac.MacAppCallbacks.GMAC_UPCALL_THREW;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_DIALOG_INSTALL_CALLBACKS;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_OK;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_SIZEOF_DIALOG_CALLBACKS;
import static com.sun.glass.ui.mac.MacGlassNative.LIBRARY_NAME;
import static com.sun.glass.ui.mac.MacGlassNative.reportException;
import static com.sun.glass.ui.mac.MacGlassNative.unexpected;
import static com.sun.glass.ui.mac.MacGlassNative.upcallStub;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * {@code GmacDialogCallbacks}: the upcalls of {@code MacCommonDialogs}' native side, on the AppKit main thread, for
 * the {@code EventLoop} of the panel a {@code gmac_dialog_*} call runs. A loop crosses as the id {@link #register}
 * gave it, which names it as the JNI's global reference named the {@code EventLoop}, until {@link #unregister} after
 * the call. An id the registry does not hold runs nothing: the panel's completion handler can come after the call
 * returned, when {@code enter()} threw at once, where the JNI called through the global reference it had deleted
 * (latent bug L14).
 * <p>
 * No Java exception may escape a slot. The JNI ran {@code GLASS_CHECK_EXCEPTION} at once after each of its two
 * calls, so each target reports what it throws through {@code Application.reportException}. A slot answers
 * {@code GMAC_UPCALL_THREW} when it reported, else {@code GMAC_UPCALL_OK}; the C reads neither.
 */
final class MacDialogCallbacks {

    /** The slots in table order: the index of a name is its slot number. */
    static final List<String> SLOT_NAMES = List.of("event_loop_enter", "event_loop_leave");

    static final StructLayout GMAC_DIALOG_CALLBACKS_LAYOUT = MemoryLayout.structLayout(
            SLOT_NAMES.stream().map(ADDRESS::withName).toArray(MemoryLayout[]::new));

    /** Where the slots of one dialog go: what the JNI called on its {@code EventLoop}, in slot order. */
    interface Receiver {

        void enter();

        void leave();
    }

    private static final Map<Long, Receiver> LOOPS = new ConcurrentHashMap<>();

    private static final AtomicLong NEXT_LOOP_ID = new AtomicLong(1);

    private static final MemorySegment[] STUBS = createStubs();

    private MacDialogCallbacks() {
    }

    /**
     * Once, from {@code MacCommonDialogs}' class initialization, before the first {@code gmac_dialog_*}. The library
     * copies the table, so its memory may go once the call returns.
     */
    static void install() {
        int size = sizeOfTable();
        if (size != GMAC_DIALOG_CALLBACKS_LAYOUT.byteSize()) {
            throw new UnsatisfiedLinkError(LIBRARY_NAME + " layout mismatch: sizeof GmacDialogCallbacks is " + size
                    + ", the layout has " + GMAC_DIALOG_CALLBACKS_LAYOUT.byteSize() + " bytes");
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment table = arena.allocate(GMAC_DIALOG_CALLBACKS_LAYOUT);
            for (int slot = 0; slot < STUBS.length; slot++) {
                table.setAtIndex(ADDRESS, slot, STUBS[slot]);
            }
            int status = (int) GMAC_DIALOG_INSTALL_CALLBACKS.invokeExact(table);
            if (status != GMAC_OK) {
                throw new IllegalStateException("gmac_dialog_install_callbacks answered " + status);
            }
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** A new nonzero id naming {@code loop}, the {@code EventLoop} of one dialog: before its downcall. */
    static long register(EventLoop loop) {
        return register(new EventLoopReceiver(loop));
    }

    static long register(Receiver loop) {
        long loopId = NEXT_LOOP_ID.getAndIncrement();
        LOOPS.put(loopId, loop);
        return loopId;
    }

    /** After the dialog's downcall returned, where the JNI deleted its global reference. 0 names nothing. */
    static void unregister(long loopId) {
        LOOPS.remove(loopId);
    }

    static boolean isRegistered(long loopId) {
        return LOOPS.containsKey(loopId);
    }

    private static int eventLoopEnter(long loopId) {
        try {
            Receiver loop = LOOPS.get(loopId);
            if (loop != null) {
                loop.enter();
            }
            return GMAC_UPCALL_OK;
        } catch (Throwable t) {
            reportException(t);
            return GMAC_UPCALL_THREW;
        }
    }

    private static int eventLoopLeave(long loopId) {
        try {
            Receiver loop = LOOPS.get(loopId);
            if (loop != null) {
                loop.leave();
            }
            return GMAC_UPCALL_OK;
        } catch (Throwable t) {
            reportException(t);
            return GMAC_UPCALL_THREW;
        }
    }

    private static int sizeOfTable() {
        try {
            return (int) GMAC_SIZEOF_DIALOG_CALLBACKS.invokeExact();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static MemorySegment[] createStubs() {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        FunctionDescriptor idOnly = FunctionDescriptor.of(JAVA_INT, JAVA_LONG);
        try {
            return new MemorySegment[] {
                stub(lookup, "eventLoopEnter", idOnly),
                stub(lookup, "eventLoopLeave", idOnly)
            };
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static MemorySegment stub(MethodHandles.Lookup lookup, String name, FunctionDescriptor descriptor)
            throws ReflectiveOperationException {
        MethodHandle target = lookup.findStatic(MacDialogCallbacks.class, name, descriptor.toMethodType());
        return upcallStub(target, descriptor);
    }

    /** {@code CallObjectMethod(enter)}, whose answer the JNI dropped, and {@code CallVoidMethod(leave, NULL)}. */
    private record EventLoopReceiver(EventLoop loop) implements Receiver {

        @Override
        public void enter() {
            loop.enter();
        }

        @Override
        public void leave() {
            loop.leave(null);
        }
    }
}
