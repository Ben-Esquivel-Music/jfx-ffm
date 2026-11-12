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
import com.sun.glass.utils.NativeLibLoader;
import com.sun.javafx.font.JniStringCodec;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_CHAR;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The binding of {@code com.sun.glass.ui.mac} to the flat C ABI of {@code libglass}
 * ({@code native-glass/mac/glass_mac_api.h}). Every restricted {@code java.lang.foreign} call of the package is in
 * this file.
 * <p>
 * The class initializer loads the library through {@code Application.loadNativeLibrary()}, as
 * {@code MacApplication} does, so the library is loaded once; it has no {@code JNI_OnLoad}, and loading it runs none
 * of its code. It then refuses a library whose {@code gmac_abi_version} is not {@link #ABI_VERSION}, and runs
 * {@code gmac_init}, which creates the thread-data key that every autorelease pool of the library is kept under. No
 * function that opens a pool may run before it.
 * <p>
 * A library that does not load, a denied native access, a missing symbol of this class or another ABI version is
 * recorded once, as an {@link UnsatisfiedLinkError}: {@code gmac_init} does not run after a failure, and
 * {@link #ensureLoaded} and every downcall bound after the failure throw the recorded error. After a version mismatch
 * that is every downcall but {@link #abiVersion}, which still answers the version the library has.
 * <p>
 * This class binds every export of the header before the {@code MacWindow} section but the test hooks, in header
 * order; the aggregates ({@code MacAppCallbacks}, {@code MacMainLoopCalls}, {@code MacApplicationCalls},
 * {@code MacScreenCalls}, {@code MacPreferenceCalls}, {@code MacKeyCalls}, {@code MacCursorCalls},
 * {@code MacTimerCalls}, {@code MacRobotCalls}, {@code MacWindowCallbacks}, {@code MacWindowCalls},
 * {@code MacViewCallbacks}, {@code MacViewCalls}, {@code MacPasteboardCalls}, {@code MacClipboardCalls},
 * {@code MacDialogCallbacks}, {@code MacDialogCalls}, {@code MacMenuCallbacks}, {@code MacMenuCalls},
 * {@code MacAccessibleCallbacks}, {@code MacAccessibilityCalls}) call them.
 * {@code MacWindowCalls} binds the {@code gmac_window_*} exports of that section itself, {@code MacViewCalls} the
 * {@code gmac_view_*} exports of the {@code MacView} section, {@code MacPasteboardCalls} the
 * {@code gmac_pasteboard_*} exports of the {@code MacPasteboard} section, {@code MacClipboardCalls} its
 * {@code gmac_clipboard_file_reference_path}, {@code MacDialogCalls} the {@code gmac_url_*} and
 * {@code gmac_dialog_*} exports of the {@code MacFileNSURL} and {@code MacCommonDialogs} section and
 * {@code MacMenuCalls} the {@code gmac_menubar_*} and {@code gmac_menu_*} exports of the {@code MacMenuBarDelegate}
 * and {@code MacMenuDelegate} section and {@code MacAccessibilityCalls} the {@code gmac_a11y_*} exports of the
 * {@code MacAccessible} section. An aggregate that binds a symbol of its own does it through
 * {@link #bind} and {@link #bindCritical} in its initializer, on whichever thread first uses it, so concurrently with
 * other aggregates; this class has initialized by then. A symbol such an aggregate lacks fails only its own
 * downcall, with an {@link UnsatisfiedLinkError} naming it, and leaves the facade and every other binding as they
 * are: what a binding does never depends on which aggregate happened to initialize first.
 * <p>
 * An aggregate that calls a system library itself binds it through {@link SystemLibrary}, with the same rules: a
 * function the library lacks fails only its own downcall, and a data symbol it lacks only the reads of its value.
 * {@link #boundSymbols} lists only {@code libglass}'s.
 * <p>
 * Only {@code gmac_abi_version}, {@code gmac_abi_constant}, {@code gmac_main_thread_detached},
 * {@code gmac_timer_paused} and the {@code gmac_sizeof_*} and {@code gmac_offsetof_*} probes are
 * {@link Linker.Option#critical critical(true)} downcalls: they read a constant or a flag. The {@code gmac_test_*}
 * hooks of the header are bound by the test shim, not here.
 */
final class MacGlassNative {

    /** {@code GLASS_MAC_ABI_VERSION}; a library that answers another {@code gmac_abi_version} is refused. */
    static final int ABI_VERSION = 1;

    static final int GMAC_OK = 0;
    static final int GMAC_ERR_NO_DATA = -1;

    static final String LIBRARY_NAME = "glass";

    static final String LIBOBJC = "/usr/lib/libobjc.A.dylib";

    private static final Linker LINKER = Linker.nativeLinker();

    /* Thread-safe: the aggregates bind on whichever threads first use them, while another thread may read. */
    private static final Map<String, FunctionDescriptor> BOUND = Collections.synchronizedMap(new LinkedHashMap<>());
    private static final List<String> CRITICAL_SYMBOLS = new CopyOnWriteArrayList<>();
    private static final List<String> MISSING_SYMBOLS = new CopyOnWriteArrayList<>();

    /*
     * Both are written only while this class initializes, and class initialization publishes them to every thread
     * that uses the class afterwards (JLS 12.4.2), so neither needs to be volatile.
     */
    private static UnsatisfiedLinkError initFailure;
    private static boolean initialized;

    private static final SymbolLookup LOOKUP = loadGlass();

    private static final MethodHandle GMAC_ABI_VERSION = bindCritical("gmac_abi_version",
            FunctionDescriptor.of(JAVA_INT));

    static {
        checkAbiVersion();
    }

    private static final MethodHandle GMAC_ABI_CONSTANT = bindCritical("gmac_abi_constant",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    private static final MethodHandle GMAC_INIT = bind("gmac_init", FunctionDescriptor.of(JAVA_INT));

    static {
        initLibrary();
    }

    private static final MethodHandle GMAC_FREE = bind("gmac_free", FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle GMAC_MAIN_THREAD_DETACHED = bindCritical("gmac_main_thread_detached",
            FunctionDescriptor.of(JAVA_INT));

    static final MethodHandle GMAC_SIZEOF_APP_CALLBACKS = bindCritical("gmac_sizeof_app_callbacks",
            FunctionDescriptor.of(JAVA_INT));
    static final MethodHandle GMAC_SIZEOF_SCREEN_INFO = bindCritical("gmac_sizeof_screen_info",
            FunctionDescriptor.of(JAVA_INT));
    static final MethodHandle GMAC_SIZEOF_COLOR = bindCritical("gmac_sizeof_color", FunctionDescriptor.of(JAVA_INT));
    static final MethodHandle GMAC_SIZEOF_PREF = bindCritical("gmac_sizeof_pref", FunctionDescriptor.of(JAVA_INT));
    static final MethodHandle GMAC_SIZEOF_TIMER_CALLBACKS = bindCritical("gmac_sizeof_timer_callbacks",
            FunctionDescriptor.of(JAVA_INT));
    static final MethodHandle GMAC_SIZEOF_WINDOW_CALLBACKS = bindCritical("gmac_sizeof_window_callbacks",
            FunctionDescriptor.of(JAVA_INT));
    static final MethodHandle GMAC_SIZEOF_VIEW_CAPS = bindCritical("gmac_sizeof_view_caps",
            FunctionDescriptor.of(JAVA_INT));
    static final MethodHandle GMAC_SIZEOF_VIEW_CALLBACKS = bindCritical("gmac_sizeof_view_callbacks",
            FunctionDescriptor.of(JAVA_INT));
    static final MethodHandle GMAC_SIZEOF_GESTURE_CALLBACKS = bindCritical("gmac_sizeof_gesture_callbacks",
            FunctionDescriptor.of(JAVA_INT));
    static final MethodHandle GMAC_SIZEOF_PASTEBOARD_REP = bindCritical("gmac_sizeof_pasteboard_rep",
            FunctionDescriptor.of(JAVA_INT));
    static final MethodHandle GMAC_SIZEOF_PASTEBOARD_ITEM = bindCritical("gmac_sizeof_pasteboard_item",
            FunctionDescriptor.of(JAVA_INT));
    static final MethodHandle GMAC_SIZEOF_FILE_FILTER = bindCritical("gmac_sizeof_file_filter",
            FunctionDescriptor.of(JAVA_INT));
    static final MethodHandle GMAC_SIZEOF_DIALOG_CALLBACKS = bindCritical("gmac_sizeof_dialog_callbacks",
            FunctionDescriptor.of(JAVA_INT));
    static final MethodHandle GMAC_SIZEOF_MENU_IMAGE = bindCritical("gmac_sizeof_menu_image",
            FunctionDescriptor.of(JAVA_INT));
    static final MethodHandle GMAC_SIZEOF_MENU_CALLBACKS = bindCritical("gmac_sizeof_menu_callbacks",
            FunctionDescriptor.of(JAVA_INT));
    static final MethodHandle GMAC_SIZEOF_VARIANT = bindCritical("gmac_sizeof_variant",
            FunctionDescriptor.of(JAVA_INT));
    static final MethodHandle GMAC_SIZEOF_ACCESSIBLE_CALLBACKS = bindCritical("gmac_sizeof_accessible_callbacks",
            FunctionDescriptor.of(JAVA_INT));
    static final MethodHandle GMAC_OFFSETOF_SCREEN_INFO = bindCritical("gmac_offsetof_screen_info",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    static final MethodHandle GMAC_OFFSETOF_COLOR = bindCritical("gmac_offsetof_color",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    static final MethodHandle GMAC_OFFSETOF_PREF = bindCritical("gmac_offsetof_pref",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    static final MethodHandle GMAC_OFFSETOF_VIEW_CAPS = bindCritical("gmac_offsetof_view_caps",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    static final MethodHandle GMAC_OFFSETOF_PASTEBOARD_REP = bindCritical("gmac_offsetof_pasteboard_rep",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    static final MethodHandle GMAC_OFFSETOF_PASTEBOARD_ITEM = bindCritical("gmac_offsetof_pasteboard_item",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    static final MethodHandle GMAC_OFFSETOF_FILE_FILTER = bindCritical("gmac_offsetof_file_filter",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    static final MethodHandle GMAC_OFFSETOF_MENU_IMAGE = bindCritical("gmac_offsetof_menu_image",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    static final MethodHandle GMAC_OFFSETOF_VARIANT = bindCritical("gmac_offsetof_variant",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    static final MethodHandle GMAC_APP_INSTALL_CALLBACKS = bind("gmac_app_install_callbacks",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    static final MethodHandle GMAC_TIMER_INSTALL_CALLBACKS = bind("gmac_timer_install_callbacks",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    static final MethodHandle GMAC_WINDOW_INSTALL_CALLBACKS = bind("gmac_window_install_callbacks",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    static final MethodHandle GMAC_VIEW_INSTALL_CALLBACKS = bind("gmac_view_install_callbacks",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    static final MethodHandle GMAC_GESTURE_INSTALL_CALLBACKS = bind("gmac_gesture_install_callbacks",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    static final MethodHandle GMAC_DIALOG_INSTALL_CALLBACKS = bind("gmac_dialog_install_callbacks",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    static final MethodHandle GMAC_MENU_INSTALL_CALLBACKS = bind("gmac_menu_install_callbacks",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    static final MethodHandle GMAC_A11Y_INSTALL_CALLBACKS = bind("gmac_a11y_install_callbacks",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    static final MethodHandle GMAC_APP_INIT = bind("gmac_app_init", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    static final MethodHandle GMAC_APP_CREATE_DELEGATE = bind("gmac_app_create_delegate",
            FunctionDescriptor.of(ADDRESS, JAVA_INT, JAVA_INT));
    static final MethodHandle GMAC_APP_RUN_LOOP = bind("gmac_app_run_loop", FunctionDescriptor.ofVoid(ADDRESS));
    static final MethodHandle GMAC_APP_FINISH_TERMINATING = bind("gmac_app_finish_terminating",
            FunctionDescriptor.ofVoid(ADDRESS));
    static final MethodHandle GMAC_APP_ENTER_NESTED_LOOP = bind("gmac_app_enter_nested_loop",
            FunctionDescriptor.ofVoid());
    static final MethodHandle GMAC_APP_LEAVE_NESTED_LOOP = bind("gmac_app_leave_nested_loop",
            FunctionDescriptor.ofVoid());
    static final MethodHandle GMAC_APP_INVOKE_LATER = bind("gmac_app_invoke_later",
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG));
    static final MethodHandle GMAC_APP_INVOKE_AND_WAIT = bind("gmac_app_invoke_and_wait",
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG));
    static final MethodHandle GMAC_APP_IS_TRIGGER_REACTIVATION = bind("gmac_app_is_trigger_reactivation",
            FunctionDescriptor.of(JAVA_INT));
    static final MethodHandle GMAC_APP_HIDE = bind("gmac_app_hide", FunctionDescriptor.ofVoid());
    static final MethodHandle GMAC_APP_HIDE_OTHER_APPLICATIONS = bind("gmac_app_hide_other_applications",
            FunctionDescriptor.ofVoid());
    static final MethodHandle GMAC_APP_UNHIDE_ALL_APPLICATIONS = bind("gmac_app_unhide_all_applications",
            FunctionDescriptor.ofVoid());
    static final MethodHandle GMAC_SCREEN_VIDEO_REFRESH_PERIOD = bind("gmac_screen_video_refresh_period",
            FunctionDescriptor.of(JAVA_DOUBLE));
    static final MethodHandle GMAC_SCREENS_QUERY = bind("gmac_screens_query",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    static final MethodHandle GMAC_APP_DATA_DIRECTORY = bind("gmac_app_data_directory",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    static final MethodHandle GMAC_APP_CLASS_NAME = bind("gmac_app_class_name",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    static final MethodHandle GMAC_APP_COLLECT_PREFERENCES = bind("gmac_app_collect_preferences",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    static final MethodHandle GMAC_KEY_JAVA_TO_MAC = bind("gmac_key_java_to_mac",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    static final MethodHandle GMAC_KEY_CODE_FOR_CHAR = bind("gmac_key_code_for_char",
            FunctionDescriptor.of(JAVA_INT, JAVA_CHAR));
    static final MethodHandle GMAC_CURRENT_MODIFIER_FLAGS = bind("gmac_current_modifier_flags",
            FunctionDescriptor.of(JAVA_LONG));
    static final MethodHandle GMAC_APP_OPEN_URI = bind("gmac_app_open_uri",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));

    static final MethodHandle GMAC_IMAGE_CREATE = bind("gmac_image_create",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
    static final MethodHandle GMAC_CURSOR_INIT = bind("gmac_cursor_init", FunctionDescriptor.ofVoid());
    static final MethodHandle GMAC_CURSOR_CREATE = bind("gmac_cursor_create",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT));
    static final MethodHandle GMAC_CURSOR_SET = bind("gmac_cursor_set", FunctionDescriptor.ofVoid(JAVA_INT));
    static final MethodHandle GMAC_CURSOR_SET_CUSTOM = bind("gmac_cursor_set_custom",
            FunctionDescriptor.ofVoid(ADDRESS));
    static final MethodHandle GMAC_CURSOR_SET_VISIBLE = bind("gmac_cursor_set_visible",
            FunctionDescriptor.ofVoid(JAVA_INT));
    static final MethodHandle GMAC_CURSOR_BEST_SIZE = bind("gmac_cursor_best_size",
            FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));

    static final MethodHandle GMAC_TIMER_INIT = bind("gmac_timer_init", FunctionDescriptor.ofVoid());
    static final MethodHandle GMAC_TIMER_START_PERIOD = bind("gmac_timer_start_period",
            FunctionDescriptor.of(ADDRESS, JAVA_LONG, JAVA_INT));
    static final MethodHandle GMAC_TIMER_START_VSYNC = bind("gmac_timer_start_vsync",
            FunctionDescriptor.of(ADDRESS, JAVA_LONG));
    static final MethodHandle GMAC_TIMER_STOP = bind("gmac_timer_stop", FunctionDescriptor.ofVoid(ADDRESS));
    static final MethodHandle GMAC_TIMER_PAUSE = bind("gmac_timer_pause", FunctionDescriptor.ofVoid(ADDRESS));
    static final MethodHandle GMAC_TIMER_RESUME = bind("gmac_timer_resume", FunctionDescriptor.ofVoid(ADDRESS));
    static final MethodHandle GMAC_TIMER_PAUSED = bindCritical("gmac_timer_paused",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    static final MethodHandle GMAC_POOL_PUSH = bind("gmac_pool_push", FunctionDescriptor.ofVoid());
    static final MethodHandle GMAC_POOL_POP = bind("gmac_pool_pop", FunctionDescriptor.ofVoid());
    static final MethodHandle GMAC_KEY_FIND_MAC = bind("gmac_key_find_mac",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    static final MethodHandle GMAC_MOUSE_LOCATION_FLIPPED = bind("gmac_mouse_location_flipped",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));

    static {
        initialized = true;
    }

    private MacGlassNative() {
    }

    /**
     * Runs the class initializer, if it has not run yet: loads the library, checks its ABI version and runs
     * {@code gmac_init}.
     *
     * @throws UnsatisfiedLinkError if the library cannot be loaded, was denied native access, lacks a symbol of this
     *         class or answers another ABI version
     */
    static void ensureLoaded() {
        if (initFailure != null) {
            throw initFailure;
        }
    }

    /**
     * The {@code libglass} symbols bound so far, in binding order: first this class's own, in header order, then
     * those the aggregates bound in their initializers, in the order those bindings happened.
     */
    static List<String> boundSymbols() {
        synchronized (BOUND) {
            return List.copyOf(BOUND.keySet());
        }
    }

    static Map<String, FunctionDescriptor> boundDescriptors() {
        synchronized (BOUND) {
            return Collections.unmodifiableMap(new LinkedHashMap<>(BOUND));
        }
    }

    static List<String> criticalSymbols() {
        return List.copyOf(CRITICAL_SYMBOLS);
    }

    static List<String> missingSymbols() {
        return List.copyOf(MISSING_SYMBOLS);
    }

    static int abiVersion() {
        try {
            return (int) GMAC_ABI_VERSION.invokeExact();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code gmac_abi_constant(index)}: the entry {@code index} of {@code enum GmacAbiConstant}, and
     * {@link Integer#MIN_VALUE} outside the table.
     */
    static int abiConstant(int index) {
        try {
            return (int) GMAC_ABI_CONSTANT.invokeExact(index);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code gmac_init}: creates the thread-data key once per process, however often it is called, and answers
     * {@code GMAC_OK} or the error number {@code pthread_key_create} returned.
     */
    static int init() {
        try {
            return (int) GMAC_INIT.invokeExact();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code gmac_free}: frees a block the library allocated for the caller; {@code NULL} is ignored. */
    static void free(MemorySegment block) {
        try {
            GMAC_FREE.invokeExact(block);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code gmac_main_thread_detached}, the condition of {@code GLASS_ASSERT_MAIN_JAVA_THREAD}: the caller is not
     * the main thread, and the main thread is not attached to Java, because the run loop has not attached it yet or
     * has returned. Any thread.
     */
    static boolean mainThreadDetached() {
        try {
            return (int) GMAC_MAIN_THREAD_DETACHED.invokeExact() != 0;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * What {@code GLASS_ASSERT_MAIN_JAVA_THREAD} raised when {@link #mainThreadDetached} held before the downcall:
     * each site throws it after the call, or reports it, as the JNI site did.
     */
    static RuntimeException mainThreadDetachedError() {
        return new RuntimeException("Main Java thread is detached.");
    }

    /** {@code GLASS_CHECK_EXCEPTION}: the report, and what the report itself throws swallowed. */
    static void reportException(Throwable t) {
        try {
            Application.reportException(t);
        } catch (Throwable ignored) {
            // the macro's second ExceptionClear
        }
    }

    /** A string out of the library, decoded as the JNI's {@code NewStringUTF} decoded it; NULL is null. */
    static String newStringUtf(MemorySegment utf8, int length) {
        if (utf8.address() == 0) {
            return null;
        }
        return JniStringCodec.fromNewStringUtf(bounded(utf8, length).toArray(JAVA_BYTE));
    }

    /** A NUL-terminated string out of the library, decoded as {@code NewStringUTF} decoded it. */
    static String newStringUtf(MemorySegment cString) {
        return JniStringCodec.fromNewStringUtf(bounded(cString, Long.MAX_VALUE));
    }

    /**
     * {@code s} as the UTF-16 code units of a string into the library: NULL for null, and never NULL for an empty
     * string, because the library's conversions answer nil for NULL.
     */
    static MemorySegment utf16(SegmentAllocator allocator, String s) {
        if (s == null) {
            return MemorySegment.NULL;
        }
        MemorySegment chars = allocator.allocate(JAVA_CHAR, Math.max(1, s.length()));
        MemorySegment.copy(s.toCharArray(), 0, chars, JAVA_CHAR, 0, s.length());
        return chars;
    }

    /* Linkage, shared with the aggregates. */

    @SuppressWarnings("restricted")
    static MemorySegment bounded(MemorySegment segment, long byteSize) {
        return segment.reinterpret(byteSize);
    }

    /** Stubs are never freed: a slot may be dialled until the process exits. */
    @SuppressWarnings("restricted")
    static MemorySegment upcallStub(MethodHandle target, FunctionDescriptor descriptor) {
        return LINKER.upcallStub(target, descriptor, Arena.global());
    }

    static MethodHandle bind(String name, FunctionDescriptor descriptor) {
        return link(name, descriptor);
    }

    /** Only for a function that reads a constant or a flag: nothing that allocates, waits, runs AppKit or upcalls. */
    static MethodHandle bindCritical(String name, FunctionDescriptor descriptor) {
        CRITICAL_SYMBOLS.add(name);
        return link(name, descriptor, Linker.Option.critical(true));
    }

    /**
     * Wraps what reaches the catch around a downcall: a linkage failure, or an error from marshalling the arguments,
     * since the C cannot throw into Java. An {@link Error} or {@link RuntimeException} passes through as itself. What
     * Java builds of the result is built after the catch, which holds the downcall alone (PB22).
     */
    static Error unexpected(Throwable t) {
        if (t instanceof Error e) {
            return e;
        }
        if (t instanceof RuntimeException e) {
            throw e;
        }
        return new AssertionError(t);
    }

    /**
     * A failing handle of the descriptor's type, rather than an exception: these run in static initializers, and a
     * class whose initializer threw could not report the failure a second time. A failure while this class
     * initializes fails the facade; after that, only the one downcall.
     */
    @SuppressWarnings("restricted")
    private static MethodHandle link(String name, FunctionDescriptor descriptor, Linker.Option... options) {
        BOUND.put(name, descriptor);
        Optional<MemorySegment> symbol = find(name);
        if (initFailure != null) {
            return failingHandle(descriptor, initFailure);
        }
        UnsatisfiedLinkError failure;
        if (symbol.isPresent()) {
            try {
                return LINKER.downcallHandle(symbol.get(), descriptor, options);
            } catch (IllegalCallerException e) {
                failure = initializationFailed(e);
            }
        } else {
            failure = new UnsatisfiedLinkError("missing native symbol: " + name + " in " + LIBRARY_NAME);
        }
        if (!initialized) {
            recordFailure(failure);
        }
        return failingHandle(descriptor, failure);
    }

    /** Lists a symbol the loaded library lacks even after a failure, so that the census stays complete. */
    private static Optional<MemorySegment> find(String name) {
        if (LOOKUP == null) {
            return Optional.empty();
        }
        Optional<MemorySegment> symbol = LOOKUP.find(name);
        if (symbol.isEmpty()) {
            MISSING_SYMBOLS.add(name);
        }
        return symbol;
    }

    private static MethodHandle failingHandle(FunctionDescriptor descriptor, UnsatisfiedLinkError failure) {
        MethodType type = descriptor.toMethodType();
        MethodHandle thrower = MethodHandles.insertArguments(
                MethodHandles.throwException(type.returnType(), UnsatisfiedLinkError.class), 0, failure);
        return MethodHandles.dropArguments(thrower, 0, type.parameterList());
    }

    private static SymbolLookup loadGlass() {
        try {
            GlassLibrary.load();
            return NativeLibLoader.loadedLibrariesLookup();
        } catch (UnsatisfiedLinkError e) {
            recordFailure(e);
        } catch (RuntimeException e) {
            // NativeLibLoader checks native access (MemorySegment::reinterpret) before it looks for a file, as
            // System.load did: without native access the loader throws IllegalCallerException.
            recordFailure(initializationFailed(e));
        }
        return null;
    }

    private static void checkAbiVersion() {
        if (initFailure != null) {
            return;
        }
        int actual = abiVersion();
        if (actual != ABI_VERSION) {
            recordFailure(new UnsatisfiedLinkError(LIBRARY_NAME + " ABI version mismatch: expected " + ABI_VERSION
                    + ", found " + actual));
        }
    }

    private static void initLibrary() {
        if (initFailure != null) {
            return;
        }
        // The JNI build's JNI_OnLoad ignored a failing pthread_key_create and the library carried on, so a nonzero
        // answer is no error.
        init();
    }

    private static void recordFailure(UnsatisfiedLinkError error) {
        if (initFailure == null) {
            initFailure = error;
        }
    }

    private static UnsatisfiedLinkError initializationFailed(RuntimeException e) {
        UnsatisfiedLinkError error = new UnsatisfiedLinkError(e instanceof IllegalCallerException
                ? LIBRARY_NAME + " was not granted native access: add javafx.graphics - or ALL-UNNAMED, when it"
                        + " is on the class path - to --enable-native-access"
                : LIBRARY_NAME + " could not be initialized: " + e);
        error.initCause(e);
        return error;
    }

    /**
     * A system library an aggregate calls itself, open for the life of the process. A library that does not open
     * fails each of its downcalls and data reads with that error, and after a failure of this class each of them
     * throws that failure, as {@code libglass}'s downcalls do.
     */
    static final class SystemLibrary {

        private static final FunctionDescriptor DATA_VALUE = FunctionDescriptor.of(ADDRESS);

        private final String name;
        private final SymbolLookup lookup;
        private final UnsatisfiedLinkError openFailure;

        private SystemLibrary(String name, SymbolLookup lookup, UnsatisfiedLinkError openFailure) {
            this.name = name;
            this.lookup = lookup;
            this.openFailure = openFailure;
        }

        /**
         * A framework of {@code /System/Library/Frameworks}. The frameworks are only in the dyld shared cache, which
         * only the {@code String} overload of {@link SymbolLookup#libraryLookup} searches.
         */
        static SystemLibrary framework(String name) {
            return open(name, "/System/Library/Frameworks/" + name + ".framework/" + name);
        }

        /** The Objective-C runtime, for {@code objc_release}, which the SDK headers do not declare. */
        static SystemLibrary libobjc() {
            return open("libobjc", LIBOBJC);
        }

        /** libSystem, which the JVM has loaded: libc and libm. */
        static SystemLibrary libSystem() {
            return new SystemLibrary("libSystem", LINKER.defaultLookup(), null);
        }

        /** {@code installName} is in the dyld shared cache, as {@link #framework} says. */
        @SuppressWarnings("restricted")
        private static SystemLibrary open(String name, String installName) {
            if (initFailure != null) {
                return new SystemLibrary(name, null, initFailure);
            }
            try {
                return new SystemLibrary(name, SymbolLookup.libraryLookup(installName, Arena.global()), null);
            } catch (IllegalArgumentException e) {
                UnsatisfiedLinkError error = new UnsatisfiedLinkError("cannot open " + installName + ": "
                        + e.getMessage());
                error.initCause(e);
                return new SystemLibrary(name, null, error);
            }
        }

        @SuppressWarnings("restricted")
        MethodHandle bind(String function, FunctionDescriptor descriptor, Linker.Option... options) {
            UnsatisfiedLinkError failure = failure();
            if (failure != null) {
                return failingHandle(descriptor, failure);
            }
            Optional<MemorySegment> symbol = lookup.find(function);
            if (symbol.isEmpty()) {
                return failingHandle(descriptor, missing(function));
            }
            return LINKER.downcallHandle(symbol.get(), descriptor, options);
        }

        /**
         * A handle answering the pointer a data symbol holds, an immortal constant such as a {@code CFStringRef},
         * read once here. For a symbol the library lacks, each call of the handle throws the one error naming it, as
         * a failing downcall does, rather than failing the initializer of the class that binds it.
         */
        MethodHandle bindDataValue(String symbol) {
            UnsatisfiedLinkError failure = failure();
            if (failure != null) {
                return failingHandle(DATA_VALUE, failure);
            }
            Optional<MemorySegment> address = lookup.find(symbol);
            if (address.isEmpty()) {
                return failingHandle(DATA_VALUE, missing(symbol));
            }
            MemorySegment value = bounded(address.get(), ADDRESS.byteSize()).get(ADDRESS, 0);
            return MethodHandles.constant(MemorySegment.class, value);
        }

        private UnsatisfiedLinkError failure() {
            return initFailure != null ? initFailure : openFailure;
        }

        private UnsatisfiedLinkError missing(String symbol) {
            return new UnsatisfiedLinkError("missing native symbol: " + symbol + " in " + name);
        }
    }

    /**
     * {@code Application.loadNativeLibrary()} is protected, so only a subclass may call it here, and reaching it
     * through {@link MacApplication} would run that class's initializer, which calls {@code gmac_app_init} through
     * this class before this class has bound it.
     */
    private abstract static class GlassLibrary extends Application {

        static void load() {
            loadNativeLibrary();
        }
    }
}
