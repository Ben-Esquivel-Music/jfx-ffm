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
import com.sun.glass.ui.CommonDialogs.ExtensionFilter;
import com.sun.glass.ui.CommonDialogs.FileChooserResult;
import com.sun.glass.ui.Menu;
import com.sun.glass.ui.MenuItem.Callback;
import com.sun.glass.ui.Pixels;
import com.sun.glass.ui.Screen;
import com.sun.glass.ui.Size;
import com.sun.glass.ui.View;
import java.io.File;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleInfo;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntUnaryOperator;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.function.LongToIntFunction;
import java.util.function.LongUnaryOperator;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BOOLEAN;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_CHAR;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Test access to {@link MacGlassNative} and its aggregates, and the shim-only test hooks of {@code glass_mac_api.h},
 * which the facade does not bind: {@code gmac_test_pool_depth}, {@code gmac_test_set_main_java_attached},
 * {@code gmac_test_string_or_empty}, {@code gmac_test_string_or_nil}, {@code gmac_test_fire_app_callback},
 * {@code gmac_test_inspect_and_release_app_delegate}, {@code gmac_test_inspect_and_release_image},
 * {@code gmac_test_fire_timer_callback},
 * {@code gmac_test_sdk_constant}, {@code gmac_test_color_space_srgb_name}, {@code gmac_test_fire_window_callback},
 * {@code gmac_test_nslocale_language_direction}, {@code gmac_test_fire_view_callback},
 * {@code gmac_test_fire_gesture_callback}, {@code gmac_test_fire_key_event}, {@code gmac_test_key_chars_utf16},
 * {@code gmac_test_fire_key_chars},
 * {@code gmac_test_fire_input_method}, {@code gmac_test_default_view_caps}, {@code gmac_test_clipboard_mime_to_uti},
 * {@code gmac_test_clipboard_uti_to_mime}, {@code gmac_test_pasteboard_utis_block},
 * {@code gmac_test_pasteboard_raw_image}, {@code gmac_test_pasteboard_raw_image_bounds},
 * {@code gmac_test_pasteboard_describe_items}, {@code gmac_test_fire_dialog_callback},
 * {@code gmac_test_dialog_describe_filters}, {@code gmac_test_fire_menu_callback},
 * {@code gmac_test_menu_key_equivalent}, {@code gmac_test_fire_accessible_callback},
 * {@code gmac_test_a11y_variant_to_object}, {@code gmac_test_a11y_utf8_string},
 * {@code gmac_test_a11y_is_equal_to_string} and {@code gmac_test_a11y_enum_pointer}. The hooks are bound only once
 * the facade has run {@code gmac_init}, because the string, image, locale, key-chars, input-method, clipboard,
 * pasteboard, filter, key-equivalent, variant and accessibility string hooks open an autorelease pool.
 * <p>
 * Of the bootstrap, {@code gmac_app_init} reads {@code -[NSProcessInfo operatingSystemVersion]} and sets a flag, and
 * is called only with 0, the flag's initial value. {@code gmac_app_create_delegate} only allocates and initializes a
 * {@code GlassApplication}, which no run loop is given: {@code gmac_test_inspect_and_release_app_delegate} releases
 * it, and its {@code -dealloc}, with no {@code PlatformSupport} made, runs only {@code [super dealloc]}.
 * <p>
 * Nothing here calls a {@code gmac_timer_*} export but {@code gmac_timer_install_callbacks}: the timer calls take a
 * {@link TimerStandIn} where the library's timers would be, and MacTimer, whose initialization creates the display
 * link, is never initialized. Of {@code MacRobotCalls}, only the event builders, the pool, the SDK values and the
 * binding of its key lookup ({@link #robotFindMacKey}) are reachable: nothing here posts an event, captures the
 * screen or moves the cursor. Nothing here calls a {@code gmac_window_*} export: the window calls are reached with
 * the NULL window or with a stand-in downcall, and {@code MacWindow}, whose initialization installs the window
 * table, is never initialized. The {@code gmac_view_*} exports are called only with the NULL view, for which each
 * returns before anything else, or, for the uploads, with arguments for which the export returns before it messages
 * the view, and never {@code gmac_view_create} or {@code gmac_view_multi_click_time_ms}: those calls take a
 * stand-in, and {@code MacView}, whose initialization installs the view tables and asks AppKit for the double-click
 * interval, is never initialized. Nothing here calls a {@code gmac_pasteboard_*} export, which would talk to the
 * pasteboard server: the {@code MacPasteboard} calls are reached with the test hooks or a stand-in where the export
 * would be. Nothing here calls a {@code gmac_dialog_*} export, which would run a panel, or a bookmark export, which
 * reaches the file system: the dialog and bookmark calls take a stand-in where the export would be. The only
 * {@code gmac_url_*} exports called are {@code gmac_url_copy_path} and the two security-scope ones, on URLs
 * CoreFoundation made, and they message nothing but the {@code NSURL}. No {@code GlassMenu} or {@code GlassMenubar} is
 * made: the create calls and the image call take a stand-in where the export would be, and the only
 * {@code gmac_menu_*} exports called are {@code gmac_menu_set_shortcut}, {@code gmac_menu_set_image} and
 * {@code gmac_menu_set_checked}, on the NULL menu, which they message as nil. Making a {@code MacMenuDelegate} runs
 * its initialization, which installs the menu table and makes no {@code GlassMenu}.
 * <p>
 * No accessibility peer is made: {@code gmac_a11y_create_peer} is reached only through a stand-in, and
 * {@code gmac_a11y_peer_id} through a stand-in or on NULL, for which it answers 0 without a message.
 * {@code gmac_a11y_invalidate_parent} is never called: even on NULL it sends {@code +class} to {@code AccessibleBase},
 * which initializes that class and its AppKit superclass. {@code gmac_a11y_unignored_children} is called only on a
 * null array, for which it answers before it reaches AppKit, and {@code gmac_a11y_id_to_variant} only on objects
 * CoreFoundation or the variant hook made. Nothing here posts an accessibility notification or asks for an unignored
 * ancestor; the only AppKit functions called are the localized-string functions
 * {@code NSAccessibilityRoleDescription} and {@code NSAccessibilityActionDescription}. Making a {@code MacAccessible}
 * runs its initialization, which installs the accessibility table and reads the {@code com.apple.Cocoa} bundle's data
 * symbols.
 * <p>
 * Three process probes bypass {@code libglass}: whether the calling thread is the main thread
 * ({@code pthread_main_np}), whether AppKit's {@code NSApp} is nil, and the bytes the malloc zones hold in use
 * ({@code malloc_zone_statistics}). CoreGraphics reads events back ({@link #readEvent}, {@link #eventData})
 * and makes the reference events the C would have made ({@link #referenceMouseEvent},
 * {@link #referenceScrollWheelEvent}), none of them posted.
 */
@SuppressWarnings("restricted")
public final class MacGlassNativeShim {

    static final String APPKIT = "/System/Library/Frameworks/AppKit.framework/Versions/C/AppKit";

    private static final int RTLD_LAZY = 0x1;
    private static final int RTLD_NOLOAD = 0x10;

    private static final int IMAGE_READBACK_VALUES = 9;

    /** {@code malloc_statistics_t}. */
    private static final StructLayout MALLOC_STATISTICS = MemoryLayout.structLayout(
            JAVA_INT.withName("blocks_in_use"), MemoryLayout.paddingLayout(4), JAVA_LONG.withName("size_in_use"),
            JAVA_LONG.withName("max_size_in_use"), JAVA_LONG.withName("size_allocated"));

    /** {@code kCFURLPOSIXPathStyle}. */
    private static final long CF_URL_POSIX_PATH_STYLE = 0;

    /** {@code kCFNumberSInt32Type} and {@code kCFNumberDoubleType}. */
    private static final long CF_NUMBER_SINT32_TYPE = 3;
    private static final long CF_NUMBER_DOUBLE_TYPE = 13;

    /** {@code kCFAbsoluteTimeIntervalSince1970}: CoreFoundation counts from 2001. */
    private static final double CF_ABSOLUTE_TIME_INTERVAL_SINCE_1970 = 978307200.0;

    private static final Linker LINKER = Linker.nativeLinker();

    private MacGlassNativeShim() {
    }

    /** What a {@code gmac_test_string_*} hook answered: -1 for nil, else the length, and the code units it copied. */
    public record Conversion(int result, String copied) {
    }

    /** {@code gmac_app_open_uri}'s signature, for a stand-in of the library. */
    @FunctionalInterface
    public interface UriOpener {

        int open(MemorySegment uri, int length, MemorySegment outCompleted) throws Throwable;
    }

    /** {@code gmac_cursor_create}'s signature, with the cursor as an address, for a stand-in of the library. */
    @FunctionalInterface
    public interface CursorFactory {

        long create(MemorySegment image, int x, int y);
    }

    /** {@code gmac_cursor_best_size}'s signature, for a stand-in of the library. */
    @FunctionalInterface
    public interface BestSizeQuery {

        void query(int width, int height, MemorySegment outWidth, MemorySegment outHeight);
    }

    /** {@code gmac_window_create}'s signature, with the pointers as addresses, for a stand-in of the library. */
    @FunctionalInterface
    public interface WindowFactory {

        long create(long owner, long screen, int styleMask, long windowId);
    }

    /**
     * {@code gmac_view_create}'s signature, with the view as an address, for a stand-in of the library. The caps are
     * valid for the call only.
     */
    @FunctionalInterface
    public interface ViewFactory {

        long create(long viewId, MemorySegment caps);
    }

    /**
     * What {@code gmac_test_fire_key_event} or {@code gmac_test_fire_key_chars} answered: the last dial's status and
     * the first dial's consumed flag.
     */
    public record KeyEventFire(int status, int consumed) {
    }

    /** What {@code gmac_test_key_chars_utf16} answered, and its out buffer afterwards, null for the NULL one. */
    public record KeyChars(int answer, char[] out) {
    }

    /** The {@code gmac_timer_*} calls of {@code MacTimer}, for a stand-in of the library's timers. */
    public interface TimerStandIn {

        long startPeriod(long timerId, int period);

        long startVsync(long timerId);

        void stop(long timer);

        void pause(long timer);

        void resume(long timer);

        boolean paused(long timer);
    }

    /**
     * Loads the library through the facade, then initializes {@code MacWindowCalls}, {@code MacViewCalls},
     * {@code MacPasteboardCalls}, {@code MacClipboardCalls}, {@code MacDialogCalls}, {@code MacMenuCalls} and
     * {@code MacAccessibilityCalls}, which bind the {@code gmac_window_*}, the {@code gmac_view_*}, the
     * {@code gmac_pasteboard_*} exports, {@code gmac_clipboard_file_reference_path}, the {@code gmac_url_*} and
     * {@code gmac_dialog_*} exports, the {@code gmac_menubar_*} and {@code gmac_menu_*} exports and the
     * {@code gmac_a11y_*} exports in their initializers, so that the symbol lists hold those bindings next, in header
     * order, before any a test binds itself.
     */
    public static void loadLibrary() {
        MacGlassNative.ensureLoaded();
        try {
            MethodHandles.lookup().ensureInitialized(MacWindowCalls.class);
            MethodHandles.lookup().ensureInitialized(MacViewCalls.class);
            MethodHandles.lookup().ensureInitialized(MacPasteboardCalls.class);
            MethodHandles.lookup().ensureInitialized(MacClipboardCalls.class);
            MethodHandles.lookup().ensureInitialized(MacDialogCalls.class);
            MethodHandles.lookup().ensureInitialized(MacMenuCalls.class);
            MethodHandles.lookup().ensureInitialized(MacAccessibilityCalls.class);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    public static List<String> boundSymbols() {
        return MacGlassNative.boundSymbols();
    }

    /** The Java carrier types of every descriptor the facade bound, by symbol, in binding order. */
    public static Map<String, MethodType> boundCarrierTypes() {
        return carrierTypes(MacGlassNative.boundDescriptors());
    }

    public static List<String> criticalSymbols() {
        return MacGlassNative.criticalSymbols();
    }

    public static List<String> missingSymbols() {
        return MacGlassNative.missingSymbols();
    }

    /** Binds as an aggregate does, from its initializer: after the facade has initialized, on any thread. */
    public static MethodHandle bind(String name, FunctionDescriptor descriptor) {
        return MacGlassNative.bind(name, descriptor);
    }

    public static MethodHandle bindCritical(String name, FunctionDescriptor descriptor) {
        return MacGlassNative.bindCritical(name, descriptor);
    }

    /** A downcall of a framework's function, as {@code int (void)}, and a read of one of its data symbols. */
    public record FrameworkBindings(MethodHandle function, MethodHandle dataValue) {
    }

    /**
     * Binds {@code function} and {@code dataSymbol} through one {@code SystemLibrary} of {@code framework}, as an
     * aggregate binds them in its initializer.
     */
    public static FrameworkBindings bindFramework(String framework, String function, String dataSymbol) {
        MacGlassNative.SystemLibrary library = MacGlassNative.SystemLibrary.framework(framework);
        return new FrameworkBindings(library.bind(function, FunctionDescriptor.of(JAVA_INT)),
                library.bindDataValue(dataSymbol));
    }

    /** The Java carrier types of the hooks this shim binds, by symbol, in binding order. */
    public static Map<String, MethodType> testHookCarrierTypes() {
        return carrierTypes(Hooks.DESCRIPTORS);
    }

    public static int expectedAbiVersion() {
        return MacGlassNative.ABI_VERSION;
    }

    public static int abiVersion() {
        return MacGlassNative.abiVersion();
    }

    public static int abiConstant(int index) {
        return MacGlassNative.abiConstant(index);
    }

    public static int init() {
        return MacGlassNative.init();
    }

    public static void free(MemorySegment block) {
        MacGlassNative.free(block);
    }

    public static boolean mainThreadDetached() {
        return MacGlassNative.mainThreadDetached();
    }

    /** {@code gmac_test_pool_depth}: the calling thread's pool depth, -1 before its first pool. */
    public static int poolDepth() {
        try {
            return (int) Hooks.POOL_DEPTH.invokeExact();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code gmac_test_set_main_java_attached}: sets the flag the detached probe reads; answers the previous value. */
    public static int setMainJavaAttached(int attached) {
        try {
            return (int) Hooks.SET_MAIN_JAVA_ATTACHED.invokeExact(attached);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code gmac_test_string_or_empty}, the conversion of {@code GlassHelper}'s {@code nsStringWithJavaString}. */
    public static Conversion stringOrEmpty(String value, int capacity) {
        return convert(Hooks.STRING_OR_EMPTY, value, capacity);
    }

    /** {@code gmac_test_string_or_nil}, the conversion of {@code GlassAccessible}'s {@code jStringToNSString}. */
    public static Conversion stringOrNil(String value, int capacity) {
        return convert(Hooks.STRING_OR_NIL, value, capacity);
    }

    /** {@code gmac_test_fire_app_callback}: dials a slot of the installed table on this thread. */
    public static int fireAppCallback(int slot) {
        try {
            return (int) Hooks.FIRE_APP_CALLBACK.invokeExact(slot);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code gmac_app_init(disableSyncRendering)}, the facade's binding. */
    public static int appInit(int disableSyncRendering) {
        try {
            return (int) MacGlassNative.GMAC_APP_INIT.invokeExact(disableSyncRendering);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** What {@code MacApplication}'s class initialization runs. */
    public static void initApplication(boolean disableSyncRendering) {
        MacApplicationCalls.init(disableSyncRendering);
    }

    /** {@code MacApplication}'s class initialization with {@code appInit} where {@code gmac_app_init} would be. */
    public static void initApplication(boolean disableSyncRendering, IntUnaryOperator appInit) {
        MacApplicationCalls.init(disableSyncRendering, appInit);
    }

    /** The delegate {@code MacApplication.runLoop} creates, as an address. */
    public static long createAppDelegate(boolean taskbarApplication, boolean hasLaunchable) {
        return MacMainLoopCalls.createDelegate(taskbarApplication, hasLaunchable);
    }

    /** {@code gmac_app_create_delegate} with exactly these arguments, which {@code MacApplication} never passes. */
    public static long createAppDelegate(int taskbarApplication, int hasLaunchable) {
        try {
            MemorySegment app = (MemorySegment) MacGlassNative.GMAC_APP_CREATE_DELEGATE.invokeExact(
                    taskbarApplication, hasLaunchable);
            return app.address();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code gmac_test_inspect_and_release_app_delegate}: the mask it reads back from {@code app}, a delegate no run
     * loop has used, which it then releases; -1 for 0.
     */
    public static int inspectAndReleaseAppDelegate(long app) {
        try {
            return (int) Hooks.INSPECT_AND_RELEASE_APP_DELEGATE.invokeExact(MemorySegment.ofAddress(app));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * What {@code gmac_test_inspect_and_release_image} read back from an {@code NSImage}: its answer, the 9 values it
     * wrote and the first {@code min(answer, capacity)} bytes of the pixel data.
     */
    public record ImageReadback(int result, List<Integer> values, byte[] pixels) {
    }

    /**
     * {@code gmac_test_inspect_and_release_image}: reads {@code image} back, then releases it. A value the hook did not
     * write reads {@link Integer#MIN_VALUE}.
     */
    public static ImageReadback inspectAndReleaseImage(MemorySegment image, int capacity) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment values = arena.allocate(JAVA_INT, IMAGE_READBACK_VALUES);
            for (int index = 0; index < IMAGE_READBACK_VALUES; index++) {
                values.setAtIndex(JAVA_INT, index, Integer.MIN_VALUE);
            }
            MemorySegment pixels = arena.allocate(Math.max(1, capacity));
            int result = (int) Hooks.INSPECT_AND_RELEASE_IMAGE.invokeExact(image, values, pixels, capacity);
            int copied = Math.min(Math.max(result, 0), capacity);
            List<Integer> read = Arrays.stream(values.toArray(JAVA_INT)).boxed().toList();
            return new ImageReadback(result, read, pixels.asSlice(0, copied).toArray(JAVA_BYTE));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code MacPixels._attachInt} with exactly these arguments, on a 1x1 {@code MacPixels} whose own pixels the
     * method does not read: the JNI native while {@link #pixelsAttachIsNative}, the Java checks afterwards.
     */
    public static void attachInt(long ptr, int width, int height, IntBuffer ints, int[] array, int offset) {
        attachTarget()._attachInt(ptr, width, height, ints, array, offset);
    }

    /** {@code MacPixels._attachByte} with exactly these arguments, as {@link #attachInt}. */
    public static void attachByte(long ptr, int width, int height, ByteBuffer bytes, byte[] array, int offset) {
        attachTarget()._attachByte(ptr, width, height, bytes, array, offset);
    }

    /** Whether {@code MacPixels._attachInt} and {@code _attachByte} are still the JNI natives of the library. */
    public static boolean pixelsAttachIsNative() {
        try {
            return Modifier.isNative(MacPixels.class.getDeclaredMethod("_attachInt", long.class, int.class,
                    int.class, IntBuffer.class, int[].class, int.class).getModifiers())
                    && Modifier.isNative(MacPixels.class.getDeclaredMethod("_attachByte", long.class, int.class,
                    int.class, ByteBuffer.class, byte[].class, int.class).getModifiers());
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A direct buffer of {@code byteSize} bytes at address 0. The JNI took NULL from {@code GetDirectBufferAddress}
     * for it and passed NULL on, so nothing ever reads it; only its capacity counts.
     */
    public static ByteBuffer nullAddressBuffer(int byteSize) {
        return MemorySegment.NULL.reinterpret(byteSize).asByteBuffer();
    }

    /**
     * Runs {@code MacApplication}'s class initializer, whose {@code gmac_app_init} reads
     * {@code -[NSProcessInfo operatingSystemVersion]}. At 1a6788cc4d it was the JNI {@code _initIDs}, which also
     * cached the JNI IDs, among them the {@code Application.reportException} that every closing
     * {@code GLASS_CHECK_EXCEPTION} called: without them a JNI body that ended with an exception pending called
     * through a NULL method ID. Only the capture of a JNI golden needed it.
     */
    public static void initializeMacApplication() {
        try {
            MethodHandles.lookup().ensureInitialized(MacApplication.class);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static MacPixels attachTarget() {
        return new MacPixels(1, 1, IntBuffer.allocate(1));
    }

    /** {@code MacPixels.getNativeFormat_impl()}. */
    public static int pixelsNativeFormat() {
        return MacPixels.getNativeFormat_impl();
    }

    /** {@code gmac_image_create}, through the facade's binding. */
    public static void imageCreate(MemorySegment pixels, int width, int height, MemorySegment outImage) {
        try {
            MacGlassNative.GMAC_IMAGE_CREATE.invokeExact(pixels, width, height, outImage);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** A {@code MacPixels} of {@code ints}, which the constructor slices, as every {@code Pixels} does. */
    public static Pixels newPixels(int width, int height, IntBuffer ints) {
        return new MacPixels(width, height, ints);
    }

    /** A {@code MacPixels} of {@code bytes}, as {@link #newPixels(int, int, IntBuffer)}. */
    public static Pixels newPixels(int width, int height, ByteBuffer bytes) {
        return new MacPixels(width, height, bytes);
    }

    /** A {@code MacPixels} of {@code ints} with the given scales, as {@link #newPixels(int, int, IntBuffer)}. */
    public static Pixels newPixels(int width, int height, IntBuffer ints, float scaleX, float scaleY) {
        return new MacPixels(width, height, ints, scaleX, scaleY);
    }

    /**
     * What {@code MacCursor._createCursor} does before {@code gmac_cursor_create}: the private
     * {@code Pixels.attachData}, called through {@code MacCursorCalls}' handle, writes the image into {@code slot}.
     */
    public static void attachData(Pixels pixels, MemorySegment slot) throws Throwable {
        MacCursorCalls.attachData(pixels, slot);
    }

    /** {@code MacCursor._createCursor} with {@code factory} where the library's {@code gmac_cursor_create} would be. */
    public static long createCursor(int x, int y, Pixels pixels, CursorFactory factory) {
        return MacCursorCalls.createCursor(x, y, pixels, factory::create);
    }

    /** {@code MacCursor._getBestSize} with {@code query} where the library's {@code gmac_cursor_best_size} would be. */
    public static Size bestSize(int width, int height, BestSizeQuery query) {
        return MacCursorCalls.bestSize(width, height, query::query);
    }

    /** Installs the production table, with {@code recorder} where {@code MacApplication} would be. */
    public static void installAppCallbacks(AppCallbackRecorder recorder) {
        MacAppCallbacks.install(recorder);
    }

    public static void uninstallAppCallbacks() {
        try {
            int status = (int) MacGlassNative.GMAC_APP_INSTALL_CALLBACKS.invokeExact(MemorySegment.NULL);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    public static List<String> appSlotNames() {
        return MacAppCallbacks.SLOT_NAMES;
    }

    /** Resolves every method {@code MacApplication}'s receiver calls, without initializing {@code MacApplication}. */
    public static void resolveApplicationTargets() {
        try {
            MethodHandles.lookup().ensureInitialized(MacAppCallbacks.ApplicationReceiver.class);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    public static void checkLayouts() {
        MacAppCallbacks.checkLayouts();
    }

    /** The layouts of the package by C struct name. */
    public static Map<String, StructLayout> layouts() {
        Map<String, StructLayout> layouts = new LinkedHashMap<>();
        layouts.put("GmacAppCallbacks", MacAppCallbacks.GMAC_APP_CALLBACKS_LAYOUT);
        layouts.put("GmacScreenInfo", MacScreenCalls.GMAC_SCREEN_INFO_LAYOUT);
        layouts.put("GmacColor", MacPreferenceCalls.GMAC_COLOR_LAYOUT);
        layouts.put("GmacPref", MacPreferenceCalls.GMAC_PREF_LAYOUT);
        layouts.put("GmacTimerCallbacks", MacTimerCalls.GMAC_TIMER_CALLBACKS_LAYOUT);
        layouts.put("GmacWindowCallbacks", MacWindowCallbacks.GMAC_WINDOW_CALLBACKS_LAYOUT);
        layouts.put("GmacViewCaps", MacViewCalls.GMAC_VIEW_CAPS_LAYOUT);
        layouts.put("GmacViewCallbacks", MacViewCallbacks.GMAC_VIEW_CALLBACKS_LAYOUT);
        layouts.put("GmacGestureCallbacks", MacViewCallbacks.GMAC_GESTURE_CALLBACKS_LAYOUT);
        layouts.put("GmacPasteboardRep", MacPasteboardCalls.GMAC_PASTEBOARD_REP_LAYOUT);
        layouts.put("GmacPasteboardItem", MacPasteboardCalls.GMAC_PASTEBOARD_ITEM_LAYOUT);
        layouts.put("GmacFileFilter", MacDialogCalls.GMAC_FILE_FILTER_LAYOUT);
        layouts.put("GmacDialogCallbacks", MacDialogCallbacks.GMAC_DIALOG_CALLBACKS_LAYOUT);
        layouts.put("GmacMenuImage", MacMenuCalls.GMAC_MENU_IMAGE_LAYOUT);
        layouts.put("GmacMenuCallbacks", MacMenuCallbacks.GMAC_MENU_CALLBACKS_LAYOUT);
        layouts.put("GmacVariant", MacAccessibleCallbacks.GMAC_VARIANT_LAYOUT);
        layouts.put("GmacAccessibleCallbacks", MacAccessibleCallbacks.GMAC_ACCESSIBLE_CALLBACKS_LAYOUT);
        return layouts;
    }

    /** {@code gmac_sizeof_*} of a struct of {@link #layouts}. */
    public static int sizeOf(String struct) {
        MethodHandle probe = switch (struct) {
            case "GmacAppCallbacks" -> MacGlassNative.GMAC_SIZEOF_APP_CALLBACKS;
            case "GmacScreenInfo" -> MacGlassNative.GMAC_SIZEOF_SCREEN_INFO;
            case "GmacColor" -> MacGlassNative.GMAC_SIZEOF_COLOR;
            case "GmacPref" -> MacGlassNative.GMAC_SIZEOF_PREF;
            case "GmacTimerCallbacks" -> MacGlassNative.GMAC_SIZEOF_TIMER_CALLBACKS;
            case "GmacWindowCallbacks" -> MacGlassNative.GMAC_SIZEOF_WINDOW_CALLBACKS;
            case "GmacViewCaps" -> MacGlassNative.GMAC_SIZEOF_VIEW_CAPS;
            case "GmacViewCallbacks" -> MacGlassNative.GMAC_SIZEOF_VIEW_CALLBACKS;
            case "GmacGestureCallbacks" -> MacGlassNative.GMAC_SIZEOF_GESTURE_CALLBACKS;
            case "GmacPasteboardRep" -> MacGlassNative.GMAC_SIZEOF_PASTEBOARD_REP;
            case "GmacPasteboardItem" -> MacGlassNative.GMAC_SIZEOF_PASTEBOARD_ITEM;
            case "GmacFileFilter" -> MacGlassNative.GMAC_SIZEOF_FILE_FILTER;
            case "GmacDialogCallbacks" -> MacGlassNative.GMAC_SIZEOF_DIALOG_CALLBACKS;
            case "GmacMenuImage" -> MacGlassNative.GMAC_SIZEOF_MENU_IMAGE;
            case "GmacMenuCallbacks" -> MacGlassNative.GMAC_SIZEOF_MENU_CALLBACKS;
            case "GmacVariant" -> MacGlassNative.GMAC_SIZEOF_VARIANT;
            case "GmacAccessibleCallbacks" -> MacGlassNative.GMAC_SIZEOF_ACCESSIBLE_CALLBACKS;
            default -> throw new IllegalArgumentException(struct);
        };
        try {
            return (int) probe.invokeExact();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code gmac_offsetof_*} of a struct of {@link #layouts} but the callback tables, which have none.
     */
    public static int offsetOf(String struct, int member) {
        MethodHandle probe = switch (struct) {
            case "GmacScreenInfo" -> MacGlassNative.GMAC_OFFSETOF_SCREEN_INFO;
            case "GmacColor" -> MacGlassNative.GMAC_OFFSETOF_COLOR;
            case "GmacPref" -> MacGlassNative.GMAC_OFFSETOF_PREF;
            case "GmacViewCaps" -> MacGlassNative.GMAC_OFFSETOF_VIEW_CAPS;
            case "GmacPasteboardRep" -> MacGlassNative.GMAC_OFFSETOF_PASTEBOARD_REP;
            case "GmacPasteboardItem" -> MacGlassNative.GMAC_OFFSETOF_PASTEBOARD_ITEM;
            case "GmacFileFilter" -> MacGlassNative.GMAC_OFFSETOF_FILE_FILTER;
            case "GmacMenuImage" -> MacGlassNative.GMAC_OFFSETOF_MENU_IMAGE;
            case "GmacVariant" -> MacGlassNative.GMAC_OFFSETOF_VARIANT;
            default -> throw new IllegalArgumentException(struct);
        };
        try {
            return (int) probe.invokeExact(member);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** The constants of the header that the Java side spells out, by macro. */
    public static Map<String, Integer> javaConstants() {
        Map<String, Integer> constants = new LinkedHashMap<>();
        constants.put("GMAC_OK", MacGlassNative.GMAC_OK);
        constants.put("GMAC_ERR_NO_DATA", MacGlassNative.GMAC_ERR_NO_DATA);
        constants.put("GMAC_ERR_UNSUPPORTED_OS", MacApplicationCalls.GMAC_ERR_UNSUPPORTED_OS);
        constants.put("GMAC_UPCALL_OK", MacAppCallbacks.GMAC_UPCALL_OK);
        constants.put("GMAC_UPCALL_THREW", MacAppCallbacks.GMAC_UPCALL_THREW);
        constants.put("GMAC_PREF_COLOR", MacPreferenceCalls.GMAC_PREF_COLOR);
        constants.put("GMAC_PREF_COLOR_ARRAY", MacPreferenceCalls.GMAC_PREF_COLOR_ARRAY);
        constants.put("GMAC_PREF_STRING", MacPreferenceCalls.GMAC_PREF_STRING);
        constants.put("GMAC_PREF_BOOLEAN", MacPreferenceCalls.GMAC_PREF_BOOLEAN);
        constants.put("GMAC_MODIFIER_FLAG_CAPS_LOCK", MacKeyCalls.GMAC_MODIFIER_FLAG_CAPS_LOCK);
        constants.put("GMAC_PASTEBOARD_REP_BAD", MacPasteboardCalls.GMAC_PASTEBOARD_REP_BAD);
        constants.put("GMAC_PASTEBOARD_REP_NONE", MacPasteboardCalls.GMAC_PASTEBOARD_REP_NONE);
        constants.put("GMAC_PASTEBOARD_REP_STRING", MacPasteboardCalls.GMAC_PASTEBOARD_REP_STRING);
        constants.put("GMAC_PASTEBOARD_REP_BYTES", MacPasteboardCalls.GMAC_PASTEBOARD_REP_BYTES);
        constants.put("GMAC_PASTEBOARD_REP_IMAGE", MacPasteboardCalls.GMAC_PASTEBOARD_REP_IMAGE);
        constants.put("GMAC_PASTEBOARD_ITEM_NULL", MacPasteboardCalls.GMAC_PASTEBOARD_ITEM_NULL);
        return constants;
    }

    /** {@code MacApplication.getPlatformPreferences} for the delegate {@code app}. */
    public static Map<String, Object> platformPreferences(long app) {
        return MacPreferenceCalls.platformPreferences(app);
    }

    public static boolean isTriggerReactivation() {
        return MacApplicationCalls.isTriggerReactivation();
    }

    public static double videoRefreshPeriod() {
        return MacScreenCalls.videoRefreshPeriod();
    }

    public static void invokeLater(Runnable runnable) {
        MacMainLoopCalls.invokeLater(runnable);
    }

    public static void invokeAndWait(Runnable runnable) {
        MacMainLoopCalls.invokeAndWait(runnable);
    }

    public static int pendingRunnables() {
        return MacMainLoopCalls.pendingRunnables();
    }

    /** Files a runnable as a posting does, without posting it. */
    public static long registerRunnable(Runnable runnable) {
        return MacMainLoopCalls.register(runnable);
    }

    /** What the {@code run_runnable} slot does in production with the id it is given. */
    public static void runRunnable(long runnableId) {
        MacMainLoopCalls.runRunnable(runnableId);
    }

    /** {@code MacApplication.getDataDirectory}'s downcall, with the detached assertion of its JNI site. */
    public static String dataDirectory() {
        return MacApplicationCalls.dataDirectory();
    }

    /** {@code MacApplication._openURI} with {@code opener} where the library's {@code gmac_app_open_uri} would be. */
    public static int openUri(String uri, UriOpener opener) {
        return MacApplicationCalls.openUri(uri, opener::open);
    }

    /** The {@code Screen} objects {@code staticScreen_getScreens} builds from a {@code GmacScreenInfo} block. */
    public static Screen[] toScreens(MemorySegment block, int count) {
        return MacScreenCalls.toScreens(block, count);
    }

    /** The {@code Application} methods slots 4-14 call, in slot order. */
    public static List<String> applicationNotificationTargets() {
        return MacAppCallbacks.ApplicationReceiver.NOTIFICATIONS;
    }

    public static void keepNestedLoopReturnValue(Object value) {
        MacMainLoopCalls.keepNestedLoopReturnValue(value);
    }

    public static Object takeNestedLoopReturnValue() {
        return MacMainLoopCalls.takeNestedLoopReturnValue();
    }

    public static int isKeyLocked(int keyCode) {
        return MacKeyCalls.isKeyLocked(keyCode);
    }

    /** {@code _isKeyLocked}'s switch, with {@code modifierFlags} where {@code +[NSEvent modifierFlags]} would be. */
    public static int isKeyLocked(int keyCode, LongSupplier modifierFlags) {
        return MacKeyCalls.isKeyLocked(keyCode, modifierFlags);
    }

    /** {@code gmac_test_fire_timer_callback}: dials {@code tick(timerId)} {@code count} times; its status. */
    public static int fireTimerCallback(long timerId, int count, boolean onNewThread) {
        try {
            return (int) Hooks.FIRE_TIMER_CALLBACK.invokeExact(timerId, count, onNewThread ? 1 : 0);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** Installs the production timer table, as MacTimer's initialization does. */
    public static void installTimerCallbacks() {
        MacTimerCalls.installCallbacks();
    }

    /** {@code gmac_timer_install_callbacks(table)}; NULL uninstalls the table. */
    public static int installTimerTable(MemorySegment table) {
        try {
            return (int) MacGlassNative.GMAC_TIMER_INSTALL_CALLBACKS.invokeExact(table);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** The upcall stub of the production {@code tick} slot. */
    public static MemorySegment timerTickStub() {
        return MacTimerCalls.tickStub();
    }

    public static String displayLinkThreadName() {
        return MacTimerCalls.DISPLAY_LINK_THREAD_NAME;
    }

    /** {@code MacTimer._getMinPeriod}, with the detached assertion of its JNI site. */
    public static int timerMinPeriod() {
        return MacTimerCalls.minPeriod();
    }

    /** {@code MacTimer._getMaxPeriod}, with the detached assertion of its JNI site. */
    public static int timerMaxPeriod() {
        return MacTimerCalls.maxPeriod();
    }

    /** {@code MacTimer._start(Runnable, int)} with {@code timers} where the library's timers would be. */
    public static long startPeriodTimer(Runnable runnable, int period, TimerStandIn timers) {
        return MacTimerCalls.startPeriod(runnable, period, adapt(timers));
    }

    /** {@code MacTimer._start(Runnable)} with {@code timers} where the library's timers would be. */
    public static long startVsyncTimer(Runnable runnable, TimerStandIn timers) {
        return MacTimerCalls.startVsync(runnable, adapt(timers));
    }

    public static void stopTimer(long timer, TimerStandIn timers) {
        MacTimerCalls.stop(timer, adapt(timers));
    }

    public static void pauseTimer(long timer, TimerStandIn timers) {
        MacTimerCalls.pause(timer, adapt(timers));
    }

    public static void resumeTimer(long timer, TimerStandIn timers) {
        MacTimerCalls.resume(timer, adapt(timers));
    }

    /** {@code gmac_test_fire_window_callback}: dials slot {@code slot} of the installed table for {@code windowId}. */
    public static int fireWindowCallback(int slot, long windowId) {
        try {
            return (int) Hooks.FIRE_WINDOW_CALLBACK.invokeExact(slot, windowId);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** Installs the production window table, as MacWindow's initialization does. */
    public static void installWindowCallbacks() {
        MacWindowCallbacks.install();
    }

    /** {@code gmac_window_install_callbacks(table)}; NULL uninstalls the table. */
    public static int installWindowTable(MemorySegment table) {
        try {
            return (int) MacGlassNative.GMAC_WINDOW_INSTALL_CALLBACKS.invokeExact(table);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    public static List<String> windowSlotNames() {
        return MacWindowCallbacks.SLOT_NAMES;
    }

    /** The upcall stub of a production window slot. */
    public static MemorySegment windowCallbackStub(int slot) {
        return MacWindowCallbacks.stub(slot);
    }

    /** Files {@code recorder} as {@code _createWindow} files a window before {@code gmac_window_create}; its id. */
    public static long registerWindow(WindowCallbackRecorder recorder) {
        return MacWindowCallbacks.register(recorder);
    }

    public static boolean isWindowRegistered(long windowId) {
        return MacWindowCallbacks.isRegistered(windowId);
    }

    /**
     * {@code MacWindow._createWindow} for {@code recorder}, with {@code factory} where {@code gmac_window_create}
     * would be: the window's address.
     */
    public static long createWindow(WindowCallbackRecorder recorder, long owner, long screen, int styleMask,
                                    WindowFactory factory) {
        return MacWindowCalls.create(recorder, owner, screen, styleMask, factory::create);
    }

    /** The address {@code MacWindow._setIcon} gives {@code gmac_window_set_icon} for {@code pixels}; 0 for NULL. */
    public static long windowIconPixels(ByteBuffer pixels) {
        return MacWindowCalls.baseAddress(pixels).address();
    }

    /**
     * Every {@code MacWindowCalls} call that takes a window, given the NULL window, and what each answered: none may
     * reach its export or the detached assertion. Those that answer nothing appear as {@code "void"}.
     */
    public static Map<String, Object> windowCallsOnNullWindow() {
        Map<String, Object> answers = new LinkedHashMap<>();
        answers.put("close", MacWindowCalls.close(0L));
        answers.put("setView", MacWindowCalls.setView(0L, () -> 1L));
        answers.put("setMenubar", MacWindowCalls.setMenubar(0L, 1L));
        answers.put("minimize", MacWindowCalls.minimize(0L, true));
        answers.put("maximize", MacWindowCalls.maximize(0L, true, false));
        MacWindowCalls.setBounds(0L, 1, 2, true, true, 3, 4, 5, 6, 0.5f, 0.5f);
        answers.put("setBounds", "void");
        answers.put("setVisible", MacWindowCalls.setVisible(0L, true));
        answers.put("setResizable", MacWindowCalls.setResizable(0L, true));
        answers.put("requestFocus", MacWindowCalls.requestFocus(0L));
        MacWindowCalls.setFocusable(0L, true);
        answers.put("setFocusable", "void");
        answers.put("setTitle", MacWindowCalls.setTitle(0L, "title"));
        MacWindowCalls.setLevel(0L, 2);
        answers.put("setLevel", "void");
        MacWindowCalls.setAlpha(0L, 0.5f);
        answers.put("setAlpha", "void");
        answers.put("setBackground", MacWindowCalls.setBackground(0L, 0.25f, 0.5f, 0.75f));
        MacWindowCalls.setEnabled(0L, true);
        answers.put("setEnabled", "void");
        answers.put("setMinimumSize", MacWindowCalls.setMinimumSize(0L, 1, 2));
        answers.put("setMaximumSize", MacWindowCalls.setMaximumSize(0L, 1, 2));
        MacWindowCalls.setIcon(0L, ByteBuffer.allocateDirect(4), 1, 1);
        answers.put("setIcon", "void");
        MacWindowCalls.setDarkFrame(0L, true);
        answers.put("setDarkFrame", "void");
        MacWindowCalls.toFront(0L);
        answers.put("toFront", "void");
        MacWindowCalls.toBack(0L);
        answers.put("toBack", "void");
        answers.put("grabFocus", MacWindowCalls.grabFocus(0L));
        MacWindowCalls.ungrabFocus(0L);
        answers.put("ungrabFocus", "void");
        MacWindowCalls.performDrag(0L);
        answers.put("performDrag", "void");
        MacWindowCalls.performTitleBarDoubleClickAction(0L);
        answers.put("performTitleBarDoubleClickAction", "void");
        MacWindowCalls.setButtonStyle(0L, 1, true);
        answers.put("setButtonStyle", "void");
        MacWindowCalls.setButtonAppearance(0L, true);
        answers.put("setButtonAppearance", "void");
        return answers;
    }

    /**
     * The frame of {@code MacWindowCalls}' value-returning calls, with {@code downcall}, given the window's address,
     * where the export would be.
     */
    public static int windowCall(long window, LongToIntFunction downcall) {
        return MacWindowCalls.call(window, w -> downcall.applyAsInt(w.address()));
    }

    /** The frame of the calls that answer nothing, with {@code downcall} where the export would be. */
    public static void windowRun(long window, LongConsumer downcall) {
        MacWindowCalls.run(window, w -> {
            downcall.accept(w.address());
        });
    }

    /** The frame of {@code _toFront}, which had no assertion, with {@code downcall} where the export would be. */
    public static void windowRunUnasserted(long window, LongConsumer downcall) {
        MacWindowCalls.runUnasserted(window, w -> {
            downcall.accept(w.address());
        });
    }

    /** {@code MacWindow._isRightToLeftLayoutDirection}, with the detached assertion of its JNI site. */
    public static boolean isRightToLeftLayoutDirection() {
        return MacWindowCalls.isRightToLeftLayoutDirection();
    }

    /** The CoreFoundation chain that replaced the JNI's {@code NSLocale} messages: a CFLocaleLanguageDirection. */
    public static long preferredLanguageDirection() {
        return MacWindowCalls.preferredLanguageDirection();
    }

    public static long languageDirectionRightToLeft() {
        return MacWindowCalls.LANGUAGE_DIRECTION_RIGHT_TO_LEFT;
    }

    /** {@code gmac_test_nslocale_language_direction}: the JNI's {@code NSLocale} body, answering the direction. */
    public static int nslocaleLanguageDirection() {
        try {
            return (int) Hooks.NSLOCALE_LANGUAGE_DIRECTION.invokeExact();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** Installs the production view and gesture tables, as MacView's initialization does. */
    public static void installViewCallbacks() {
        MacViewCallbacks.install();
    }

    /** {@code gmac_view_install_callbacks(table)}; NULL uninstalls the table. */
    public static int installViewTable(MemorySegment table) {
        try {
            return (int) MacGlassNative.GMAC_VIEW_INSTALL_CALLBACKS.invokeExact(table);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code gmac_gesture_install_callbacks(table)}; NULL uninstalls the table. */
    public static int installGestureTable(MemorySegment table) {
        try {
            return (int) MacGlassNative.GMAC_GESTURE_INSTALL_CALLBACKS.invokeExact(table);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    public static List<String> viewSlotNames() {
        return MacViewCallbacks.VIEW_SLOT_NAMES;
    }

    public static List<String> gestureSlotNames() {
        return MacViewCallbacks.GESTURE_SLOT_NAMES;
    }

    /** The upcall stub of a production view slot. */
    public static MemorySegment viewCallbackStub(int slot) {
        return MacViewCallbacks.viewStub(slot);
    }

    /** Files {@code recorder} as {@code _create} files a view before {@code gmac_view_create}; its id. */
    public static long registerView(ViewCallbackRecorder recorder) {
        return MacViewCallbacks.register(recorder);
    }

    public static boolean isViewRegistered(long viewId) {
        return MacViewCallbacks.isRegistered(viewId);
    }

    /**
     * {@code gmac_test_fire_view_callback}: dials slot {@code slot} of the installed table for {@code viewId}, with
     * the out-parameters of slots 5, 7, 8-10 and 13 in {@code out}, 24 bytes, 8-aligned.
     */
    public static int fireViewCallback(int slot, long viewId, MemorySegment out) {
        try {
            return (int) Hooks.FIRE_VIEW_CALLBACK.invokeExact(slot, viewId, out);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code gmac_test_fire_gesture_callback}: dials slot {@code slot} of the installed gesture table. */
    public static int fireGestureCallback(int slot, long viewId) {
        try {
            return (int) Hooks.FIRE_GESTURE_CALLBACK.invokeExact(slot, viewId);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code gmac_test_fire_key_event}: dials {@code notify_key} {@code dials} times as one key event. */
    public static KeyEventFire fireKeyEvent(long viewId, int dials) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment consumed = arena.allocate(JAVA_INT);
            consumed.set(JAVA_INT, 0, Integer.MIN_VALUE);
            int status = (int) Hooks.FIRE_KEY_EVENT.invokeExact(viewId, dials, consumed);
            return new KeyEventFire(status, consumed.get(JAVA_INT, 0));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code gmac_test_key_chars_utf16} for a key-down event of the Mac key code {@code keyCode} whose characters are
     * the code units of {@code chars}, with {@code capacity}, into a native copy of {@code out}, or into NULL when
     * {@code out} is null.
     */
    public static KeyChars keyCharsUtf16(char keyCode, String chars, int capacity, char[] out) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment outUnits = out == null ? MemorySegment.NULL : arena.allocateFrom(JAVA_CHAR, out);
            int answer = (int) Hooks.KEY_CHARS_UTF16.invokeExact(keyCode, MacGlassNative.utf16(arena, chars),
                    chars.length(), outUnits, capacity);
            return new KeyChars(answer, out == null ? null : outUnits.toArray(JAVA_CHAR));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code gmac_test_fire_key_chars}: dials {@code notify_key} once, as one key event, with the units the views pass
     * for {@code textChar} and a key-down event of the Mac key code {@code keyCode} whose characters are the code
     * units of {@code chars}.
     */
    public static KeyEventFire fireKeyChars(long viewId, char keyCode, String chars, char textChar) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment consumed = arena.allocate(JAVA_INT);
            consumed.set(JAVA_INT, 0, Integer.MIN_VALUE);
            int status = (int) Hooks.FIRE_KEY_CHARS.invokeExact(viewId, keyCode, MacGlassNative.utf16(arena, chars),
                    chars.length(), textChar, consumed);
            return new KeyEventFire(status, consumed.get(JAVA_INT, 0));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code gmac_test_fire_input_method}: dials {@code notify_input_method} through the views' code for the code
     * units of {@code text}, in an {@code NSAttributedString} when {@code attributed}.
     */
    public static int fireInputMethod(long viewId, String text, boolean attributed, int attr, int length,
                                      int cursor, long selectionLocation, long selectionLength) {
        try (Arena arena = Arena.ofConfined()) {
            return (int) Hooks.FIRE_INPUT_METHOD.invokeExact(viewId, MacGlassNative.utf16(arena, text),
                    text.length(), attributed ? 1 : 0, attr, length, cursor, selectionLocation, selectionLength);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code gmac_test_default_view_caps}: the bytes of the caps {@code gmac_view_create} uses for NULL. */
    public static byte[] defaultViewCaps() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment caps = arena.allocate(MacViewCalls.GMAC_VIEW_CAPS_LAYOUT);
            caps.fill((byte) 0x5A);
            Hooks.DEFAULT_VIEW_CAPS.invokeExact(caps);
            return caps.toArray(JAVA_BYTE);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** The bytes of the {@code GmacViewCaps} {@code _create} builds from {@code caps}; null for NULL. */
    public static byte[] viewCaps(Map<?, ?> caps) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment struct = MacViewCalls.viewCaps(arena, caps);
            return struct.address() == 0 ? null : struct.toArray(JAVA_BYTE);
        }
    }

    /**
     * {@code MacView._create} for {@code recorder}, with {@code factory} where {@code gmac_view_create} would be: the
     * view's address.
     */
    public static long createView(ViewCallbackRecorder recorder, Map<?, ?> caps, ViewFactory factory) {
        return MacViewCalls.create(recorder, caps, factory::create);
    }

    /**
     * Initializes {@code MacView}'s nested holder of the {@code MacGestureSupport} handles, which its gesture slots
     * invoke, and answers the method each of its handles reveals. Initializing a nested class does not initialize the
     * class that encloses it, so {@code MacView} stays uninitialized; {@code findStatic} and {@code revealDirect} only
     * load {@code MacGestureSupport}.
     */
    public static List<MethodHandleInfo> gestureSupportMethods() {
        try {
            Class<?> holder = Class.forName(MacView.class.getName() + "$GestureSupportMethods", true,
                    MacGlassNativeShim.class.getClassLoader());
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            List<MethodHandleInfo> methods = new ArrayList<>();
            for (Field field : holder.getDeclaredFields()) {
                if (field.getType() == MethodHandle.class) {
                    field.setAccessible(true);
                    methods.add(lookup.revealDirect((MethodHandle) field.get(null)));
                }
            }
            return methods;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** The interface through which the view and gesture slots reach a view: {@code MacView}'s adapter implements it. */
    public static Class<?> viewReceiverInterface() {
        return MacViewCallbacks.Receiver.class;
    }

    /**
     * Every {@code MacViewCalls} call that takes a view but {@code create}, given the NULL view, and what each
     * answered: each export returns before anything else for it, and the render-thread calls return before their
     * downcall. {@code setParent} has no export. Those that answer nothing appear as {@code "void"}.
     */
    public static Map<String, Object> viewCallsOnNullView() {
        Map<String, Object> answers = new LinkedHashMap<>();
        answers.put("getNativeFrameBuffer", MacViewCalls.getNativeFrameBuffer(0L));
        answers.put("getX", MacViewCalls.getX(0L));
        answers.put("getY", MacViewCalls.getY(0L));
        MacViewCalls.setParent(0L);
        answers.put("setParent", "void");
        answers.put("close", MacViewCalls.close(0L));
        MacViewCalls.begin(0L);
        answers.put("begin", "void");
        MacViewCalls.end(0L);
        answers.put("end", "void");
        MacViewCalls.scheduleRepaint(0L);
        answers.put("scheduleRepaint", "void");
        answers.put("enterFullscreen", MacViewCalls.enterFullscreen(0L, true, true, true));
        MacViewCalls.exitFullscreen(0L, true);
        answers.put("exitFullscreen", "void");
        MacViewCalls.uploadPixelsDirect(0L, ByteBuffer.allocateDirect(16), 2, 2, 1.0f, 1.0f);
        answers.put("uploadPixelsDirect", "void");
        MacViewCalls.uploadPixelsBytes(0L, new byte[16], 0, 2, 2, 1.0f, 1.0f);
        answers.put("uploadPixelsBytes", "void");
        MacViewCalls.uploadPixelsInts(0L, new int[4], 0, 2, 2, 1.0f, 1.0f);
        answers.put("uploadPixelsInts", "void");
        MacViewCalls.enableInputMethodEvents(0L, true);
        answers.put("enableInputMethodEvents", "void");
        MacViewCalls.finishInputMethodComposition(0L);
        answers.put("finishInputMethodComposition", "void");
        return answers;
    }

    /** The uploads given a null buffer or array, which return before their downcall: the view may be any address. */
    public static void uploadNothing(long view) {
        MacViewCalls.uploadPixelsDirect(view, null, 2, 2, 1.0f, 1.0f);
        MacViewCalls.uploadPixelsBytes(view, null, 0, 2, 2, 1.0f, 1.0f);
        MacViewCalls.uploadPixelsInts(view, null, 0, 2, 2, 1.0f, 1.0f);
    }

    /** The frame of {@code _getNativeFrameBuffer}, with {@code downcall} where the export would be. */
    public static long viewFrameBuffer(long view, LongUnaryOperator downcall) {
        return MacViewCalls.getNativeFrameBuffer(view, v -> downcall.applyAsLong(v.address()));
    }

    /** The frame of {@code _begin} and {@code _end}, with {@code downcall} where the export would be. */
    public static void viewRunThrowingIfDetached(long view, LongConsumer downcall) {
        MacViewCalls.throwIfDetachedAfter(view, v -> downcall.accept(v.address()));
    }

    /**
     * The frame of the view calls whose native closed with {@code GLASS_CHECK_EXCEPTION} and returned a value, with
     * {@code downcall}, given the view's address, where the export would be.
     */
    public static int viewCallReportingIfDetached(long view, LongToIntFunction downcall) {
        return MacViewCalls.reportIfDetachedAfter(view, v -> downcall.applyAsInt(v.address()));
    }

    /** The frame of those that returned nothing, with {@code downcall} where the export would be. */
    public static void viewRunReportingIfDetached(long view, LongConsumer downcall) {
        MacViewCalls.runReportingIfDetached(view, v -> downcall.accept(v.address()));
    }

    /** The frame of the uploads, with {@code downcall} where the export would be. */
    public static void viewUploadThrowingIfDetached(long view, boolean asserts, LongConsumer downcall) {
        MacViewCalls.throwIfDetachedAfter(view, asserts, v -> downcall.accept(v.address()));
    }

    /** {@code MacView._setParent} of the view {@code view}, which has no export. */
    public static void setViewParent(long view) {
        MacViewCalls.setParent(view);
    }

    /** {@code _uploadPixelsDirect}, its export included, with no scale. */
    public static void uploadViewPixelsDirect(long view, Buffer pixels, int width, int height) {
        MacViewCalls.uploadPixelsDirect(view, pixels, width, height, 1.0f, 1.0f);
    }

    /** {@code _uploadPixelsByteArray}, its export included, with no scale. */
    public static void uploadViewPixelsBytes(long view, byte[] pixels, int offset, int width, int height) {
        MacViewCalls.uploadPixelsBytes(view, pixels, offset, width, height, 1.0f, 1.0f);
    }

    /** {@code _uploadPixelsIntArray}, its export included, with no scale. */
    public static void uploadViewPixelsInts(long view, int[] pixels, int offset, int width, int height) {
        MacViewCalls.uploadPixelsInts(view, pixels, offset, width, height, 1.0f, 1.0f);
    }

    /** {@code _getMultiClickTime_impl}, with {@code query} where {@code gmac_view_multi_click_time_ms} would be. */
    public static long multiClickTime(LongSupplier query) {
        return MacViewCalls.multiClickTime(query::getAsLong);
    }

    /** The address {@code _uploadPixelsDirect} gives {@code gmac_view_upload_pixels_direct} for {@code pixels}. */
    public static long viewPixelsAddress(Buffer pixels) {
        return MacViewCalls.baseAddress(pixels).address();
    }

    /** The device-details keys {@code _create} reads, by {@code GmacViewCaps} member. */
    public static Map<String, Object> viewCapsKeys() {
        Map<String, Object> keys = new LinkedHashMap<>();
        keys.put("mtl_command_queue", MacViewCalls.MTL_COMMAND_QUEUE_KEY);
        keys.put("share_context", MacViewCalls.SHARE_CONTEXT_KEY);
        keys.put("context", MacViewCalls.CONTEXT_KEY);
        keys.put("depth_bits", View.Capability.k3dDepthKey);
        keys.put("hidpi_aware", View.Capability.kHiDPIAwareKey);
        keys.put("mtl_for_sw", MacViewCalls.MTL_FOR_SW_KEY);
        keys.put("vsync_enabled", MacViewCalls.VSYNC_ENABLED_KEY);
        return keys;
    }

    /** What a {@code gmac_pasteboard_*} export answers through its out-parameters, for a stand-in of the library. */
    public record BlockAnswer(int status, byte[] block, int length) {
    }

    /** {@code MacPasteboard.getName} with {@code answer} where {@code gmac_pasteboard_get_name} would be. */
    public static String pasteboardName(BlockAnswer answer) {
        return MacPasteboardCalls.name(standIn(answer));
    }

    /** {@code MacPasteboard.getUTFs} with {@code answer} where {@code gmac_pasteboard_get_utis} would be. */
    public static String[][] pasteboardUtis(BlockAnswer answer) {
        return MacPasteboardCalls.utis(standIn(answer));
    }

    /** {@code MacPasteboard.getItemAsRawImage} with {@code answer} where its export would be. */
    public static byte[] pasteboardRawImage(BlockAnswer answer) {
        return MacPasteboardCalls.rawImage(standIn(answer));
    }

    /** {@code MacPasteboard.getItemStringForUTF} with {@code answer} where its export would be. */
    public static String pasteboardItemString(BlockAnswer answer) {
        return MacPasteboardCalls.itemString(standIn(answer));
    }

    /** {@code MacPasteboard.getItemBytesForUTF} with {@code answer} where its export would be. */
    public static byte[] pasteboardItemBytes(BlockAnswer answer) {
        return MacPasteboardCalls.itemBytes(standIn(answer));
    }

    /**
     * {@code MacPasteboard.getUTFs} with {@code gmac_test_pasteboard_utis_block} where {@code gmac_pasteboard_get_utis}
     * would be: item {@code i} holds the UTIs of {@code types[i]}, none for null.
     */
    public static String[][] pasteboardUtisOf(String[][] types) {
        return MacPasteboardCalls.utis((allocator, outBlock, outLength) -> {
            List<String> all = new ArrayList<>();
            MemorySegment counts = allocator.allocate(JAVA_INT, Math.max(1, types.length));
            for (int i = 0; i < types.length; i++) {
                String[] item = types[i] != null ? types[i] : new String[0];
                counts.setAtIndex(JAVA_INT, i, item.length);
                all.addAll(List.of(item));
            }
            String joined = String.join("", all);
            MemorySegment units = allocator.allocate(JAVA_CHAR, Math.max(1, joined.length()));
            MemorySegment.copy(joined.toCharArray(), 0, units, JAVA_CHAR, 0, joined.length());
            MemorySegment lengths = allocator.allocate(JAVA_INT, Math.max(1, all.size()));
            for (int k = 0; k < all.size(); k++) {
                lengths.setAtIndex(JAVA_INT, k, all.get(k).length());
            }
            return (int) Hooks.PASTEBOARD_UTIS_BLOCK.invokeExact(units, lengths, counts, types.length, outBlock,
                    outLength);
        });
    }

    /**
     * {@code MacPasteboard.getItemAsRawImage} with {@code gmac_test_pasteboard_raw_image} of {@code data}, an item's
     * TIFF, PNG or PDF data, where {@code gmac_pasteboard_get_item_as_raw_image} would be.
     */
    public static byte[] pasteboardRawImageOf(byte[] data) {
        return MacPasteboardCalls.rawImage((allocator, outBlock, outLength) ->
                rawImageHook(allocator, data, outBlock, outLength));
    }

    /** The block {@code gmac_test_pasteboard_raw_image} answers for {@code data}, read straight; null for none. */
    public static byte[] rawImageBlock(byte[] data) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment outBlock = arena.allocate(ADDRESS);
            MemorySegment outLength = arena.allocate(JAVA_INT);
            int status = rawImageHook(arena, data, outBlock, outLength);
            MemorySegment block = outBlock.get(ADDRESS, 0);
            try {
                return status == MacGlassNative.GMAC_OK
                        ? block.reinterpret(outLength.get(JAVA_INT, 0)).toArray(JAVA_BYTE) : null;
            } finally {
                MacGlassNative.free(block);
            }
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code gmac_test_pasteboard_raw_image_bounds}: bit 0 when the pixels fit, bit 1 when their block does. */
    public static int rawImageBounds(long width, long height) {
        try {
            return (int) Hooks.PASTEBOARD_RAW_IMAGE_BOUNDS.invokeExact(width, height);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * What {@code MacPasteboard.putItemsFromArray} makes of {@code items}, with
     * {@code gmac_test_pasteboard_describe_items} where {@code gmac_pasteboard_put_items} would be: the hook's lines
     * for the {@code NSPasteboardItem}s the export would make.
     */
    public static String describeItems(Object[] items) {
        String[] description = new String[1];
        MacPasteboardCalls.putItems(items, (array, itemCount) -> {
            description[0] = describe(array, itemCount);
            return 0L;
        });
        return description[0];
    }

    /** The frame of {@code MacPasteboard}'s scalar calls, with {@code downcall} where the export would be. */
    public static long pasteboardCall(LongSupplier downcall) {
        return MacPasteboardCalls.call(allocator -> downcall.getAsLong());
    }

    /** A 1x1 {@code Pixels} whose {@code attachData} throws {@code failure} and writes nothing. */
    public static Pixels throwingPixels(RuntimeException failure) {
        return new Pixels(1, 1, IntBuffer.allocate(1)) {
            @Override
            protected void _fillDirectByteBuffer(ByteBuffer bb) {
            }

            @Override
            protected void _attachInt(long ptr, int w, int h, IntBuffer ints, int[] array, int offset) {
                throw failure;
            }

            @Override
            protected void _attachByte(long ptr, int w, int h, ByteBuffer bytes, byte[] array, int offset) {
                throw failure;
            }
        };
    }

    /** {@code FormatEncoder}'s {@code _convertMIMEtoUTI}, as {@code MacSystemClipboard} calls it. */
    public static String mimeToUti(String mime) {
        return MacClipboardCalls.mimeToUti(mime);
    }

    /** {@code FormatEncoder}'s {@code _convertUTItoMIME}, as {@code MacSystemClipboard} calls it. */
    public static String utiToMime(String uti) {
        return MacClipboardCalls.utiToMime(uti);
    }

    /** {@code MacSystemClipboard._convertFileReferencePath}, as {@code MacSystemClipboard} calls it. */
    public static String fileReferencePath(String path) {
        return MacClipboardCalls.fileReferencePath(path);
    }

    /**
     * The body both {@code FormatEncoder} conversions share, pool included, with {@code conversion}, given the
     * converted string, where the UTType function would be.
     */
    public static String clipboardConvert(String input, UnaryOperator<MemorySegment> conversion) {
        return MacClipboardCalls.convert(input, conversion::apply);
    }

    /** {@code gmac_test_clipboard_mime_to_uti}, the JNI body of {@code _convertMIMEtoUTI}, decoded as the JNI did. */
    public static String oracleMimeToUti(String mime) {
        return clipboardHook(Hooks.CLIPBOARD_MIME_TO_UTI, mime);
    }

    /** {@code gmac_test_clipboard_uti_to_mime}, the JNI body of {@code _convertUTItoMIME}, decoded as the JNI did. */
    public static String oracleUtiToMime(String uti) {
        return clipboardHook(Hooks.CLIPBOARD_UTI_TO_MIME, uti);
    }

    /**
     * The JNI native {@code name} of {@code MacSystemClipboard} or of its {@code FormatEncoder}, one of
     * {@link #CLIPBOARD_NATIVES}, called with {@code argument}, while the class still declares it: only the capture
     * of the clipboard golden calls it.
     */
    public static String clipboardJniNative(String name, String argument) {
        try {
            Method method = clipboardNative(name);
            method.setAccessible(true);
            return (String) method.invoke(null, argument);
        } catch (InvocationTargetException e) {
            throw unexpected(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The JNI natives of {@code MacSystemClipboard} and its {@code FormatEncoder} before slice 7. */
    public static final List<String> CLIPBOARD_NATIVES = List.of("_convertMIMEtoUTI", "_convertUTItoMIME",
            "_convertFileReferencePath");

    /** Whether {@code MacSystemClipboard} and its {@code FormatEncoder} still declare every JNI native of theirs. */
    public static boolean clipboardIsNative() {
        for (String name : CLIPBOARD_NATIVES) {
            try {
                if (!Modifier.isNative(clipboardNative(name).getModifiers())) {
                    return false;
                }
            } catch (NoSuchMethodException e) {
                return false;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        return true;
    }

    /** What a {@code gmac_dialog_*} export would have answered: the URLs it retained for the caller, and the index. */
    public record PanelAnswer(long[] urls, long selected) {
    }

    /**
     * Stands where a {@code gmac_dialog_*} export would be, given what {@code gmac_test_dialog_describe_filters} makes
     * of the filters Java passed it, {@code ""} for none, and the loop id.
     */
    @FunctionalInterface
    public interface PanelStandIn {

        PanelAnswer run(String filters, long loopId);
    }

    /** What {@code gmac_url_create_from_bookmark} would have answered: its status and the URL it retained. */
    public record Resolution(int status, long url) {
    }

    /** Stands where {@code gmac_url_create_from_bookmark} would be, given the address and bytes of Java's copy. */
    @FunctionalInterface
    public interface ResolutionStandIn {

        Resolution resolve(long address, byte[] data);
    }

    /** What a dialog passes its export of the filters, as the hook describes them, and what was left pending. */
    public record FilterDescription(String lines, Throwable pending) {
    }

    public static void installDialogCallbacks() {
        MacDialogCallbacks.install();
    }

    public static List<String> dialogSlotNames() {
        return MacDialogCallbacks.SLOT_NAMES;
    }

    /** A loop id whose two slots run {@code onEnter} and {@code onLeave}, as a dialog registers its EventLoop. */
    public static long registerDialogLoop(Runnable onEnter, Runnable onLeave) {
        return MacDialogCallbacks.register(new MacDialogCallbacks.Receiver() {
            @Override
            public void enter() {
                onEnter.run();
            }

            @Override
            public void leave() {
                onLeave.run();
            }
        });
    }

    public static void unregisterDialogLoop(long loopId) {
        MacDialogCallbacks.unregister(loopId);
    }

    public static boolean isDialogLoopRegistered(long loopId) {
        return MacDialogCallbacks.isRegistered(loopId);
    }

    /** {@code gmac_test_fire_dialog_callback}: dials {@code slot} of the installed table, on the calling thread. */
    public static int fireDialogCallback(int slot, long loopId) {
        try {
            return (int) Hooks.FIRE_DIALOG_CALLBACK.invokeExact(slot, loopId);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code ExtensionFilter}s made as the event thread ({@link #asEventThread}); a null description gives null. */
    public static ExtensionFilter[] extensionFilters(String[] descriptions, List<List<String>> extensions) {
        return asEventThread(() -> {
            ExtensionFilter[] filters = new ExtensionFilter[descriptions.length];
            for (int i = 0; i < descriptions.length; i++) {
                filters[i] = descriptions[i] == null ? null : new ExtensionFilter(descriptions[i], extensions.get(i));
            }
            return filters;
        });
    }

    /** The {@code GmacFileFilter}s a dialog makes of {@code filters}, with {@code pending} pending before them. */
    public static FilterDescription describeFilters(ExtensionFilter[] filters, Throwable pending) {
        try (Arena arena = Arena.ofConfined()) {
            MacDialogCalls.FileFilters fileFilters = MacDialogCalls.fileFilters(arena, filters, pending);
            return new FilterDescription(describeFilters(fileFilters.array(), fileFilters.count()),
                    fileFilters.pending());
        }
    }

    /** {@code MacCommonDialogs}' open and save choosers, with {@code panel} where the export would be. */
    public static FileChooserResult fileChooser(ExtensionFilter[] filters, PanelStandIn panel) {
        return MacDialogCalls.fileChooser(filters, panel(panel));
    }

    /** {@code MacCommonDialogs}' folder chooser, with {@code panel} where the export would be. */
    public static File folderChooser(PanelStandIn panel) {
        return MacDialogCalls.folderChooser(panel(panel));
    }

    /** The {@code convertNSURLtoFile} of the dialogs and bookmarks, of a URL retained for it. */
    public static File urlToFile(long url) {
        return MacDialogCalls.toFile(url);
    }

    /** {@code gmac_url_copy_path}, decoded as the JNI's {@code NewString} decoded it. */
    public static String urlPath(long url) {
        return MacDialogCalls.path(url);
    }

    /** {@code MacFileNSURL}'s release, {@code objc_release} through the facade's binding. */
    public static void releaseUrl(long url) {
        MacDialogCalls.release(url);
    }

    public static boolean startAccessingSecurityScopedResource(long url) {
        return MacDialogCalls.startAccessingSecurityScopedResource(url);
    }

    public static void stopAccessingSecurityScopedResource(long url) {
        MacDialogCalls.stopAccessingSecurityScopedResource(url);
    }

    /** {@code MacFileNSURL.getBookmark} with {@code answer} where {@code gmac_url_get_bookmark} would be. */
    public static byte[] bookmark(BlockAnswer answer) {
        MacPasteboardCalls.BlockQuery query = standIn(answer);
        return MacDialogCalls.bookmark((outBytes, outLength) -> query.query(null, outBytes, outLength));
    }

    /** {@code MacFileNSURL.createFromBookmark} with {@code resolution} where the export would be. */
    public static MacFileNSURL fromBookmark(byte[] data, ResolutionStandIn resolution) {
        return MacDialogCalls.fromBookmark(data, (bytes, length, outUrl) -> {
            Resolution answer = resolution.resolve(bytes.address(), bytes.asSlice(0, length).toArray(JAVA_BYTE));
            outUrl.set(ADDRESS, 0, MemorySegment.ofAddress(answer.url()));
            return answer.status();
        });
    }

    /** The {@code NSURL*} a {@code MacFileNSURL} holds, 0 once disposed. */
    public static long fileNSURLPointer(MacFileNSURL url) {
        try {
            Field ptr = MacFileNSURL.class.getDeclaredField("ptr");
            ptr.setAccessible(true);
            return ptr.getLong(url);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Runs {@code body} with the calling thread installed as Glass's event thread, and the previous one restored
     * afterwards. A unit-test JVM has none, so {@code Application.checkEventThread()} throws in
     * {@code ExtensionFilter}, {@code EventLoop} and {@code MacFileNSURL} everywhere else.
     */
    public static <T> T asEventThread(Supplier<T> body) {
        Thread previous = EventThread.get();
        EventThread.set(Thread.currentThread());
        try {
            return body.get();
        } finally {
            EventThread.set(previous);
        }
    }

    /** A CoreFoundation file URL of {@code path}, +1: an {@code NSURL*} made without {@code libglass}. */
    public static long createFileUrl(String path) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment chars = arena.allocateFrom(JAVA_CHAR, path.toCharArray());
            MemorySegment string = (MemorySegment) CoreFoundation.CF_STRING_CREATE_WITH_CHARACTERS.invokeExact(
                    MemorySegment.NULL, chars, (long) path.length());
            try {
                return ((MemorySegment) CoreFoundation.CF_URL_CREATE_WITH_FILE_SYSTEM_PATH.invokeExact(
                        MemorySegment.NULL, string, CF_URL_POSIX_PATH_STYLE, false)).address();
            } finally {
                CoreFoundation.CF_RELEASE.invokeExact(string);
            }
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    public static long cfRetainCount(long object) {
        try {
            return (long) CoreFoundation.CF_GET_RETAIN_COUNT.invokeExact(MemorySegment.ofAddress(object));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code CFRetain}: the object, retained once more. */
    public static long cfRetain(long object) {
        try {
            return ((MemorySegment) CoreFoundation.CF_RETAIN.invokeExact(MemorySegment.ofAddress(object))).address();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    public static void cfRelease(long object) {
        try {
            CoreFoundation.CF_RELEASE.invokeExact(MemorySegment.ofAddress(object));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** The CoreFoundation twin of {@code -[NSURL startAccessingSecurityScopedResource]}. */
    public static boolean cfStartAccessingSecurityScopedResource(long url) {
        try {
            return (boolean) CoreFoundation.CF_URL_START_ACCESSING_SECURITY_SCOPED_RESOURCE.invokeExact(
                    MemorySegment.ofAddress(url));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    public static void cfStopAccessingSecurityScopedResource(long url) {
        try {
            CoreFoundation.CF_URL_STOP_ACCESSING_SECURITY_SCOPED_RESOURCE.invokeExact(MemorySegment.ofAddress(url));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** A {@code GmacMenuImage} as the export reads it. */
    public record MenuIcon(long image, int width, int height, float scaleX, float scaleY) {
    }

    /**
     * Stands where {@code gmac_menu_create} would be, given the title's code units, {@code null} for NULL, and the
     * length that came with them.
     */
    @FunctionalInterface
    public interface MenuStandIn {

        long create(long menuId, String title, int titleLength, int enabled);
    }

    /**
     * Stands where {@code gmac_menu_item_create} would be, given the title as {@link MenuStandIn} is, and the icon,
     * {@code null} for NULL.
     */
    @FunctionalInterface
    public interface MenuItemStandIn {

        long create(long menuId, String title, int titleLength, char shortcut, int modifiers, MenuIcon icon,
                    int enabled, int checked);
    }

    /** Stands where {@code gmac_menu_set_image} would be, given the icon, {@code null} for NULL. */
    @FunctionalInterface
    public interface MenuImageStandIn {

        void set(long menu, MenuIcon icon);
    }

    /** What {@code gmac_test_menu_key_equivalent} answered: the key equivalent's length, its copied units, the mask. */
    public record KeyEquivalent(int length, String copied, long modifierMask) {
    }

    /** Installs the production menu table, as {@code MacMenuDelegate}'s initialization does. */
    public static void installMenuCallbacks() {
        MacMenuCallbacks.install();
    }

    /** {@code gmac_menu_install_callbacks(table)}; NULL uninstalls the table. */
    public static int installMenuTable(MemorySegment table) {
        try {
            return (int) MacGlassNative.GMAC_MENU_INSTALL_CALLBACKS.invokeExact(table);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    public static List<String> menuSlotNames() {
        return MacMenuCallbacks.SLOT_NAMES;
    }

    /** The descriptor of every production menu slot's stub. */
    public static FunctionDescriptor menuSlotDescriptor() {
        return MacMenuCallbacks.SLOT_DESCRIPTOR;
    }

    /** The upcall stub of a production menu slot. */
    public static MemorySegment menuCallbackStub(int slot) {
        return MacMenuCallbacks.stub(slot);
    }

    /** Files {@code recorder} as the menu and {@code callback} as a {@code MacMenuDelegate} files itself; the id. */
    public static long registerMenu(MenuRecorder recorder, Callback callback) {
        return MacMenuCallbacks.register(recorder, callback);
    }

    /**
     * Files a new {@code MacMenuDelegate} of a menu item, whose menu is null, with {@code callback}, as the delegate
     * files itself before its create export; the id.
     */
    public static long registerMenuItemDelegate(Callback callback) {
        return MacMenuCallbacks.register(new MacMenuDelegate(), callback);
    }

    public static boolean isMenuRegistered(long menuId) {
        return MacMenuCallbacks.isRegistered(menuId);
    }

    /** {@code gmac_test_fire_menu_callback}: dials slot {@code slot} of the installed table for {@code menuId}. */
    public static int fireMenuCallback(int slot, long menuId) {
        try {
            return (int) Hooks.FIRE_MENU_CALLBACK.invokeExact(slot, menuId);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code gmac_test_menu_key_equivalent}, with room for {@code capacity} units. */
    public static KeyEquivalent menuKeyEquivalent(char shortcut, int modifiers, int capacity) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(JAVA_CHAR, Math.max(1, capacity));
            MemorySegment mask = arena.allocate(JAVA_LONG);
            int length = (int) Hooks.MENU_KEY_EQUIVALENT.invokeExact(shortcut, modifiers, out, capacity, mask);
            int copied = Math.min(Math.max(length, 0), capacity);
            String units = new String(out.asSlice(0, JAVA_CHAR.byteSize() * copied).toArray(JAVA_CHAR));
            return new KeyEquivalent(length, units, mask.get(JAVA_LONG, 0));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** The {@code Menu} methods a delegate's menu slots invoke, as their handles reveal them. */
    public static List<MethodHandleInfo> menuMethods() {
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(Menu.class, MethodHandles.lookup());
            List<MethodHandleInfo> methods = new ArrayList<>();
            for (Field field : MacMenuCallbacks.class.getDeclaredFields()) {
                if (field.getType() == MethodHandle.class) {
                    field.setAccessible(true);
                    methods.add(lookup.revealDirect((MethodHandle) field.get(null)));
                }
            }
            return methods;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** {@code MacMenuDelegate._createMenu} for the filed {@code menuId}, with {@code standIn} for the export. */
    public static long createMenu(long menuId, String title, boolean enabled, MenuStandIn standIn) {
        return MacMenuCalls.createMenu(menuId, title, enabled,
                (id, units, length, flag) -> standIn.create(id, menuTitle(units, length), length, flag));
    }

    /** {@code MacMenuDelegate._createMenuItem} for the filed {@code menuId}, with {@code standIn} for the export. */
    public static long createMenuItem(long menuId, String title, char shortcut, int modifiers, Pixels icon,
                                      boolean enabled, boolean checked, MenuItemStandIn standIn) {
        return MacMenuCalls.createMenuItem(menuId, title, shortcut, modifiers, icon, enabled, checked,
                (id, units, length, key, mask, image, enabledFlag, checkedFlag) -> standIn.create(id,
                        menuTitle(units, length), length, key, mask, menuIcon(image), enabledFlag, checkedFlag));
    }

    /** {@code MacMenuDelegate._setPixels} with {@code standIn} where {@code gmac_menu_set_image} would be. */
    public static void setMenuPixels(long menu, Pixels pixels, MenuImageStandIn standIn) {
        MacMenuCalls.setPixels(menu, pixels, (target, icon) -> standIn.set(target.address(), menuIcon(icon)));
    }

    /** {@code MacMenuDelegate._setPixels} through {@code gmac_menu_set_image}. */
    public static void setMenuPixels(long menu, Pixels pixels) {
        MacMenuCalls.setPixels(menu, pixels);
    }

    /** {@code MacMenuDelegate._setShortcut} through {@code gmac_menu_set_shortcut}. */
    public static void setMenuShortcut(long menu, char shortcut, int modifiers) {
        MacMenuCalls.setShortcut(menu, shortcut, modifiers);
    }

    /** {@code MacMenuDelegate._setChecked} through {@code gmac_menu_set_checked}. */
    public static void setMenuChecked(long menu, boolean checked) {
        MacMenuCalls.setChecked(menu, checked);
    }

    /** {@code MacMenuDelegate._setCallback} of the {@code GlassMenu} at {@code menu}, filed as {@code menuId}. */
    public static void setMenuCallback(long menuId, long menu, Callback callback) {
        MacMenuCalls.setCallback(menuId, menu, callback);
    }

    /**
     * A {@code MacVariant} but for its unused {@code stringArray}, as a value: a null list stands for a null array,
     * and a null element of {@code variantArray} for a null element.
     */
    public record Variant(int type, int int1, int int2, int location, int length, float float1, float float2,
                          float float3, float float4, long long1, double double1, long key, List<Long> longArray,
                          List<Integer> intArray, String string, List<Variant> variantArray) {

        public static Variant of(int type) {
            return new Variant(type, 0, 0, 0, 0, 0f, 0f, 0f, 0f, 0L, 0.0, 0L, null, null, null, null);
        }

        public Variant withInts(int newInt1, int newInt2) {
            return new Variant(type, newInt1, newInt2, location, length, float1, float2, float3, float4, long1,
                    double1, key, longArray, intArray, string, variantArray);
        }

        public Variant withFloats(float newFloat1, float newFloat2, float newFloat3, float newFloat4) {
            return new Variant(type, int1, int2, location, length, newFloat1, newFloat2, newFloat3, newFloat4, long1,
                    double1, key, longArray, intArray, string, variantArray);
        }

        public Variant withLong1(long newLong1) {
            return new Variant(type, int1, int2, location, length, float1, float2, float3, float4, newLong1,
                    double1, key, longArray, intArray, string, variantArray);
        }

        public Variant withDouble1(double newDouble1) {
            return new Variant(type, int1, int2, location, length, float1, float2, float3, float4, long1,
                    newDouble1, key, longArray, intArray, string, variantArray);
        }

        /** The attribute name and range of a style of an {@code NSAttributedString}. */
        public Variant withStyle(long newKey, int newLocation, int newLength) {
            return new Variant(type, int1, int2, newLocation, newLength, float1, float2, float3, float4, long1,
                    double1, newKey, longArray, intArray, string, variantArray);
        }

        public Variant withLongArray(List<Long> newLongArray) {
            return new Variant(type, int1, int2, location, length, float1, float2, float3, float4, long1,
                    double1, key, newLongArray, intArray, string, variantArray);
        }

        public Variant withIntArray(List<Integer> newIntArray) {
            return new Variant(type, int1, int2, location, length, float1, float2, float3, float4, long1,
                    double1, key, longArray, newIntArray, string, variantArray);
        }

        public Variant withString(String newString) {
            return new Variant(type, int1, int2, location, length, float1, float2, float3, float4, long1,
                    double1, key, longArray, intArray, newString, variantArray);
        }

        public Variant withVariantArray(List<Variant> newVariantArray) {
            return new Variant(type, int1, int2, location, length, float1, float2, float3, float4, long1,
                    double1, key, longArray, intArray, string, newVariantArray);
        }
    }

    /** The {@code static final int} type constants of {@code MacVariant}, by field name. */
    public static Map<String, Integer> variantTypes() {
        Map<String, Integer> types = new LinkedHashMap<>();
        for (Field field : MacVariant.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == int.class) {
                try {
                    field.setAccessible(true);
                    types.put(field.getName(), field.getInt(null));
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        return types;
    }

    /** Installs the production accessibility table, as {@code MacAccessible}'s initialization does. */
    public static void installAccessibleCallbacks() {
        MacAccessibleCallbacks.install();
    }

    /** {@code gmac_a11y_install_callbacks(table)}; NULL uninstalls the table. */
    public static int installAccessibleTable(MemorySegment table) {
        try {
            return (int) MacGlassNative.GMAC_A11Y_INSTALL_CALLBACKS.invokeExact(table);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    public static List<String> accessibleSlotNames() {
        return MacAccessibleCallbacks.SLOT_NAMES;
    }

    /** The descriptor of each production accessibility slot's stub, in slot order. */
    public static List<FunctionDescriptor> accessibleSlotDescriptors() {
        return MacAccessibleCallbacks.SLOT_DESCRIPTORS;
    }

    /** The upcall stub of a production accessibility slot. */
    public static MemorySegment accessibleCallbackStub(int slot) {
        return MacAccessibleCallbacks.stub(slot);
    }

    /** Files {@code recorder} as {@code gmac_a11y_create_peer}'s caller files a {@code MacAccessible}; the id. */
    public static long registerAccessible(AccessibleRecorder recorder) {
        return MacAccessibleCallbacks.register(recorder);
    }

    public static boolean isAccessibleRegistered(long peerId) {
        return MacAccessibleCallbacks.isRegistered(peerId);
    }

    /**
     * {@code gmac_test_fire_accessible_callback}: dials slot {@code slot} of the installed table for {@code peerId},
     * with its out-parameters in {@code out}, which holds 120 bytes, 8-aligned.
     */
    public static int fireAccessibleCallback(int slot, long peerId, MemorySegment out) {
        try {
            return (int) Hooks.FIRE_ACCESSIBLE_CALLBACK.invokeExact(slot, peerId, out);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** The ids of a block a slot handed the C, which this frees, as the C does. */
    public static List<Long> takeIdBlock(long block, int count) {
        try {
            MemorySegment ids = MemorySegment.ofAddress(block).reinterpret(JAVA_LONG.byteSize() * count);
            List<Long> values = Arrays.stream(ids.toArray(JAVA_LONG)).boxed().toList();
            LibSystem.FREE.invokeExact(MemorySegment.ofAddress(block));
            return values;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** The UTF-16 units of a block a slot handed the C, which this frees, as the C does. */
    public static String takeCharBlock(long block, int length) {
        try {
            MemorySegment units = MemorySegment.ofAddress(block).reinterpret(JAVA_CHAR.byteSize() * length);
            String value = new String(units.toArray(JAVA_CHAR));
            LibSystem.FREE.invokeExact(MemorySegment.ofAddress(block));
            return value;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * The {@code GmacVariant} at {@code variant} as a slot wrote it, read as the C reads it, a NULL block of a count
     * of -1 and NULL units as null; frees its blocks, as the C does. Throws when the variant carries a string to Java,
     * which no slot writes, or a block a count needs is NULL.
     */
    public static Variant takeWrittenVariant(MemorySegment variant) {
        try {
            return takeWritten(variant.reinterpret(MacAccessibleCallbacks.VARIANT_SIZE));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** What {@code gmac_test_a11y_variant_to_object} answered: its status, and the object, retained, 0 for nil. */
    public record VariantObject(int status, long object) {
    }

    /** {@code gmac_test_a11y_variant_to_object}: the object the peers make of {@code variant}, which it frees. */
    public static VariantObject variantToObject(MemorySegment variant) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment outObject = arena.allocate(ADDRESS);
            int status = (int) Hooks.A11Y_VARIANT_TO_OBJECT.invokeExact(variant, outObject);
            return new VariantObject(status, outObject.get(ADDRESS, 0).address());
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code MacAccessible.idToMacVariant} through {@code gmac_a11y_id_to_variant}. */
    public static Variant idToMacVariant(long object, int type) {
        return variant(MacAccessibilityCalls.idToMacVariant(object, type));
    }

    /** {@code MacAccessible.idToMacVariant} with a stand-in that writes nothing and answers {@code status}. */
    public static Variant idToMacVariant(long object, int type, int status) {
        return variant(MacAccessibilityCalls.idToMacVariant(object, type, (target, readType, out) -> status));
    }

    /** What a stand-in of {@code gmac_a11y_unignored_children} answers: the status, and the ids, null for none. */
    public record ChildrenAnswer(int status, long[] ids) {
    }

    /**
     * Stands where {@code gmac_a11y_unignored_children} would be, given the ids Java passed, null for NULL, and their
     * count.
     */
    @FunctionalInterface
    public interface ChildrenStandIn {

        ChildrenAnswer answer(List<Long> ids, int count);
    }

    /** {@code MacAccessible.NSAccessibilityUnignoredChildren} through the export: only null reaches no AppKit. */
    public static long[] unignoredChildren(long[] children) {
        return MacAccessibilityCalls.unignoredChildren(children);
    }

    /** {@code MacAccessible.NSAccessibilityUnignoredChildren} with {@code standIn} where the export would be. */
    public static long[] unignoredChildren(long[] children, ChildrenStandIn standIn) {
        return MacAccessibilityCalls.unignoredChildren(children, (ids, count, outIds, outCount) -> {
            List<Long> passed = ids.address() == 0 ? null
                    : Arrays.stream(ids.reinterpret(JAVA_LONG.byteSize() * count).toArray(JAVA_LONG)).boxed().toList();
            ChildrenAnswer answer = standIn.answer(passed, count);
            if (answer.ids() != null && answer.ids().length > 0) {
                long size = JAVA_LONG.byteSize() * answer.ids().length;
                MemorySegment block = ((MemorySegment) LibSystem.MALLOC.invokeExact(size)).reinterpret(size);
                MemorySegment.copy(answer.ids(), 0, block, JAVA_LONG, 0, answer.ids().length);
                outIds.set(ADDRESS, 0, block);
            }
            outCount.set(JAVA_INT, 0, answer.ids() != null ? answer.ids().length : 0);
            return answer.status();
        });
    }

    /** A new {@code MacAccessible}, opaque to the test; making the first one initializes the class. */
    public static Object newMacAccessible() {
        return new MacAccessible();
    }

    /** Stands where {@code gmac_a11y_create_peer} would be, given the role's code units, null for NULL. */
    @FunctionalInterface
    public interface PeerStandIn {

        long create(String role, int roleLength, long peerId);
    }

    /** {@code MacAccessible._createAccessiblePeer} of {@code accessible}, with {@code standIn} for the export. */
    public static long createAccessiblePeer(Object accessible, String role, PeerStandIn standIn) {
        return MacAccessibilityCalls.createAccessiblePeer((MacAccessible) accessible, role,
                (units, length, peerId) -> standIn.create(menuTitle(units, length), length, peerId));
    }

    /** {@code MacAccessible.GlassAccessibleToMacAccessible} through {@code gmac_a11y_peer_id}. */
    public static Object glassAccessibleToMacAccessible(long object) {
        return MacAccessibilityCalls.glassAccessibleToMacAccessible(object);
    }

    /** {@code GlassAccessibleToMacAccessible} with {@code peerIdOf} where {@code gmac_a11y_peer_id} would be. */
    public static Object glassAccessibleToMacAccessible(long object, LongUnaryOperator peerIdOf) {
        return MacAccessibilityCalls.glassAccessibleToMacAccessible(object,
                target -> peerIdOf.applyAsLong(target.address()));
    }

    /** {@code MacAccessible._destroyAccessiblePeer}: {@code objc_release} of {@code peer}. */
    public static void destroyAccessiblePeer(long peer) {
        MacAccessibilityCalls.destroyAccessiblePeer(peer);
    }

    /** {@code MacAccessible.isEqualToString}: {@code CFEqual}, guarded against NULL. */
    public static boolean isEqualToString(long string1, long string2) {
        return MacAccessibilityCalls.isEqualToString(string1, string2);
    }

    /** {@code MacAccessible.NSAccessibilityRoleDescription}, in a pool of the calling thread. */
    public static String roleDescription(long role, long subrole) {
        return inPool(() -> MacAccessibilityCalls.roleDescription(role, subrole));
    }

    /** {@code MacAccessible.NSAccessibilityActionDescription}, in a pool of the calling thread. */
    public static String actionDescription(long action) {
        return inPool(() -> MacAccessibilityCalls.actionDescription(action));
    }

    /** The UTF-8 extraction of the two descriptions: {@code -UTF8String} as {@code NewStringUTF} read it. */
    public static String utf8String(long string) {
        return MacClipboardCalls.utf8String(MemorySegment.ofAddress(string));
    }

    /** What {@code _initEnum} stores for a constant called {@code name}, null where it answers false. */
    public static Long enumPointer(String name) {
        OptionalLong pointer = MacAccessibilityCalls.enumPointer(name);
        return pointer.isPresent() ? pointer.getAsLong() : null;
    }

    /**
     * Constants whose names {@code _initEnum} handles three ways: an AppKit data symbol, an "AX" name, a name the
     * bundle lacks, and an AppKit data symbol after it, which {@code _initEnum} never reaches.
     */
    public enum InitEnumProbe {
        NSAccessibilityRoleAttribute,
        AXJFXProbe,
        NSAccessibilityNoSuchProbeAttribute,
        NSAccessibilityTitleAttribute
    }

    /** What {@code _initEnum} answered for an enum, and the {@code ptr} of each constant afterwards, 0 if unset. */
    public record InitEnumAnswer(boolean answer, List<Long> pointers) {
    }

    /** {@code MacAccessible}'s {@code _initEnum}, through {@code MacAccessibilityCalls}, of {@link InitEnumProbe}. */
    public static InitEnumAnswer initEnumProbe() {
        long[] pointers = new long[InitEnumProbe.values().length];
        boolean answer = MacAccessibilityCalls.initEnum(InitEnumProbe.values(),
                (constant, pointer) -> pointers[constant.ordinal()] = pointer);
        return new InitEnumAnswer(answer, Arrays.stream(pointers).boxed().toList());
    }

    /** {@code _initEnum} of an enum without constants. */
    public static boolean initEnumOfNoConstants() {
        return MacAccessibilityCalls.initEnum(new InitEnumProbe[0], (constant, pointer) -> {
            throw new AssertionError("no constant to store into");
        });
    }

    /** The names of the constants of {@code MacAccessible}'s nested enum {@code enumName}, in declaration order. */
    public static List<String> accessibleEnumNames(String enumName) {
        List<String> names = new ArrayList<>();
        for (Object constant : accessibleEnum(enumName).getEnumConstants()) {
            names.add(constant.toString());
        }
        return names;
    }

    /**
     * The {@code ptr} of each constant of {@code MacAccessible}'s nested enum {@code enumName}, as the initialization
     * of {@code MacAccessible}, which this runs if it has not run yet, stored them.
     */
    public static List<Long> accessibleEnumPointers(String enumName) {
        try {
            MethodHandles.lookup().ensureInitialized(MacAccessible.class);
            Class<?> type = accessibleEnum(enumName);
            Field ptr = type.getDeclaredField("ptr");
            ptr.setAccessible(true);
            List<Long> pointers = new ArrayList<>();
            for (Object constant : type.getEnumConstants()) {
                pointers.add(ptr.getLong(constant));
            }
            return pointers;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The JNI natives of {@code MacAccessible} that the slice-10 golden records, while the class declares them. */
    public static final List<String> ACCESSIBLE_GOLDEN_NATIVES = List.of("getString", "isEqualToString",
            "NSAccessibilityRoleDescription", "NSAccessibilityActionDescription", "_initEnum");

    /** Whether {@code MacAccessible} still declares every native of {@link #ACCESSIBLE_GOLDEN_NATIVES}. */
    public static boolean accessibleGoldenNativesExist() {
        for (String name : ACCESSIBLE_GOLDEN_NATIVES) {
            boolean found = false;
            for (Method method : MacAccessible.class.getDeclaredMethods()) {
                if (method.getName().equals(name) && Modifier.isNative(method.getModifiers())) {
                    found = true;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    /** The names of the methods {@code MacAccessible} declares {@code native}. */
    public static List<String> accessibleNatives() {
        List<String> names = new ArrayList<>();
        for (Method method : MacAccessible.class.getDeclaredMethods()) {
            if (Modifier.isNative(method.getModifiers())) {
                names.add(method.getName());
            }
        }
        return names;
    }

    /** The JNI {@code getString}: {@code NewStringUTF([string UTF8String])}. Only the golden capture calls it. */
    public static String jniGetString(long string) {
        return (String) accessibleJniNative("getString", new Class<?>[] {long.class}, string);
    }

    /** The JNI {@code isEqualToString}. Only the golden capture calls it. */
    public static boolean jniIsEqualToString(long string1, long string2) {
        return (Boolean) accessibleJniNative("isEqualToString", new Class<?>[] {long.class, long.class}, string1,
                string2);
    }

    /** The JNI {@code NSAccessibilityRoleDescription}, in a pool. Only the golden capture calls it. */
    public static String jniRoleDescription(long role, long subrole) {
        return inPool(() -> (String) accessibleJniNative("NSAccessibilityRoleDescription",
                new Class<?>[] {long.class, long.class}, role, subrole));
    }

    /** The JNI {@code NSAccessibilityActionDescription}, in a pool. Only the golden capture calls it. */
    public static String jniActionDescription(long action) {
        return inPool(() -> (String) accessibleJniNative("NSAccessibilityActionDescription",
                new Class<?>[] {long.class}, action));
    }

    /** {@code gmac_test_a11y_utf8_string}: the {@code [string UTF8String]} of {@code getString}, decoded. */
    public static String oracleUtf8String(long string) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment outUtf8 = arena.allocate(ADDRESS);
            MemorySegment outLength = arena.allocate(JAVA_INT);
            int status = (int) Hooks.A11Y_UTF8_STRING.invokeExact(MemorySegment.ofAddress(string), outUtf8,
                    outLength);
            MemorySegment utf8 = outUtf8.get(ADDRESS, 0);
            try {
                return status == MacGlassNative.GMAC_OK
                        ? MacGlassNative.newStringUtf(utf8, outLength.get(JAVA_INT, 0)) : null;
            } finally {
                MacGlassNative.free(utf8);
            }
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code NSAccessibilityRoleDescription} read by the C of the JNI's {@code getString}, in a pool: the oracle of
     * the extraction {@link #roleDescription} makes.
     */
    public static String oracleRoleDescription(long role, long subrole) {
        return inPool(() -> {
            try {
                long description = ((MemorySegment) AppKit.NS_ACCESSIBILITY_ROLE_DESCRIPTION.invokeExact(
                        MemorySegment.ofAddress(role), MemorySegment.ofAddress(subrole))).address();
                return description != 0 ? oracleUtf8String(description) : null;
            } catch (Throwable t) {
                throw unexpected(t);
            }
        });
    }

    /** {@code NSAccessibilityActionDescription} read as {@link #oracleRoleDescription} reads its description. */
    public static String oracleActionDescription(long action) {
        return inPool(() -> {
            try {
                long description = ((MemorySegment) AppKit.NS_ACCESSIBILITY_ACTION_DESCRIPTION.invokeExact(
                        MemorySegment.ofAddress(action))).address();
                return description != 0 ? oracleUtf8String(description) : null;
            } catch (Throwable t) {
                throw unexpected(t);
            }
        });
    }

    /** The user's preferred languages, which choose the AppKit localization the two descriptions come from. */
    public static List<String> preferredLanguages() {
        try {
            MemorySegment languages = (MemorySegment) CoreFoundation.CF_LOCALE_COPY_PREFERRED_LANGUAGES.invokeExact();
            try {
                long count = (long) CoreFoundation.CF_ARRAY_GET_COUNT.invokeExact(languages);
                List<String> names = new ArrayList<>();
                for (long index = 0; index < count; index++) {
                    names.add(utf8String(((MemorySegment) CoreFoundation.CF_ARRAY_GET_VALUE_AT_INDEX.invokeExact(
                            languages, index)).address()));
                }
                return names;
            } finally {
                CoreFoundation.CF_RELEASE.invokeExact(languages);
            }
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code gmac_test_a11y_is_equal_to_string}: {@code [string1 isEqualToString: string2]}. */
    public static boolean oracleIsEqualToString(long string1, long string2) {
        try {
            return (int) Hooks.A11Y_IS_EQUAL_TO_STRING.invokeExact(MemorySegment.ofAddress(string1),
                    MemorySegment.ofAddress(string2)) != 0;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code gmac_test_a11y_enum_pointer}: what {@code _initEnum}'s C stores for {@code name}, null for false. */
    public static Long oracleEnumPointer(String name) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment outPointer = arena.allocate(ADDRESS);
            int found = (int) Hooks.A11Y_ENUM_POINTER.invokeExact(MacGlassNative.utf16(arena, name),
                    name != null ? name.length() : 0, outPointer);
            return found != 0 ? outPointer.get(ADDRESS, 0).address() : null;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** A CoreFoundation string of every unit of {@code s}, +1: an {@code NSString*} made without {@code libglass}. */
    public static long cfString(String s) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment chars = arena.allocateFrom(JAVA_CHAR, s.toCharArray());
            return ((MemorySegment) CoreFoundation.CF_STRING_CREATE_WITH_CHARACTERS.invokeExact(MemorySegment.NULL,
                    chars, (long) s.length())).address();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** A mutable CoreFoundation copy of {@code s}, +1: an {@code NSMutableString*}. */
    public static long cfMutableString(String s) {
        long string = cfString(s);
        try {
            return ((MemorySegment) CoreFoundation.CF_STRING_CREATE_MUTABLE_COPY.invokeExact(MemorySegment.NULL, 0L,
                    MemorySegment.ofAddress(string))).address();
        } catch (Throwable t) {
            throw unexpected(t);
        } finally {
            cfRelease(string);
        }
    }

    /** The value of AppKit's data symbol {@code symbol}, such as an {@code NSAccessibility*} string constant. */
    public static long appKitConstant(String symbol) {
        MemorySegment address = AppKit.LOOKUP.findOrThrow(symbol).reinterpret(ADDRESS.byteSize());
        return address.get(ADDRESS, 0).address();
    }

    /** Whether AppKit exports {@code symbol}: a lookup, which neither calls nor reads it. */
    public static boolean appKitExports(String symbol) {
        return AppKit.LOOKUP.find(symbol).isPresent();
    }

    /** A CoreFoundation number of {@code value}, +1: an {@code NSNumber*} of an {@code int}. */
    public static long cfNumber(int value) {
        try (Arena arena = Arena.ofConfined()) {
            return ((MemorySegment) CoreFoundation.CF_NUMBER_CREATE.invokeExact(MemorySegment.NULL,
                    CF_NUMBER_SINT32_TYPE, arena.allocateFrom(JAVA_INT, value))).address();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** A CoreFoundation number of {@code value}, +1: an {@code NSNumber*} of a {@code double}. */
    public static long cfNumber(double value) {
        try (Arena arena = Arena.ofConfined()) {
            return ((MemorySegment) CoreFoundation.CF_NUMBER_CREATE.invokeExact(MemorySegment.NULL,
                    CF_NUMBER_DOUBLE_TYPE, arena.allocateFrom(JAVA_DOUBLE, value))).address();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code kCFBooleanTrue} or {@code kCFBooleanFalse}: an immortal {@code NSNumber*} of a {@code BOOL}. */
    public static long cfBoolean(boolean value) {
        MemorySegment address = CoreFoundation.LOOKUP.findOrThrow(value ? "kCFBooleanTrue" : "kCFBooleanFalse")
                .reinterpret(ADDRESS.byteSize());
        return address.get(ADDRESS, 0).address();
    }

    /** A CoreFoundation array of the objects {@code values}, retained, +1: an {@code NSArray*}. */
    public static long cfArray(long... values) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment objects = arena.allocate(ADDRESS, Math.max(1, values.length));
            for (int index = 0; index < values.length; index++) {
                objects.setAtIndex(ADDRESS, index, MemorySegment.ofAddress(values[index]));
            }
            return ((MemorySegment) CoreFoundation.CF_ARRAY_CREATE.invokeExact(MemorySegment.NULL, objects,
                    (long) values.length, CoreFoundation.LOOKUP.findOrThrow("kCFTypeArrayCallBacks"))).address();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** The seconds since 1970 of a CoreFoundation date: what {@code dateWithTimeIntervalSince1970:} was given. */
    public static double cfDateSince1970(long date) {
        try {
            return (double) CoreFoundation.CF_DATE_GET_ABSOLUTE_TIME.invokeExact(MemorySegment.ofAddress(date))
                    + CF_ABSOLUTE_TIME_INTERVAL_SINCE_1970;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    public static long cfDictionaryCount(long dictionary) {
        try {
            return (long) CoreFoundation.CF_DICTIONARY_GET_COUNT.invokeExact(MemorySegment.ofAddress(dictionary));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** The value of {@code key} in a CoreFoundation dictionary, borrowed, 0 for none. */
    public static long cfDictionaryValue(long dictionary, long key) {
        try {
            return ((MemorySegment) CoreFoundation.CF_DICTIONARY_GET_VALUE.invokeExact(
                    MemorySegment.ofAddress(dictionary), MemorySegment.ofAddress(key))).address();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** The text of an attributed string, read as {@link #utf8String} reads a string. */
    public static String attributedStringText(long attributedString) {
        try {
            return utf8String(((MemorySegment) CoreFoundation.CF_ATTRIBUTED_STRING_GET_STRING.invokeExact(
                    MemorySegment.ofAddress(attributedString))).address());
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** The value of an attribute of an attributed string at {@code location}, and the range it covers. */
    public record StyleRun(long value, long location, long length) {
    }

    public static StyleRun attributedStringAttribute(long attributedString, long location, long attribute) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment range = arena.allocate(JAVA_LONG, 2);
            long value = ((MemorySegment) CoreFoundation.CF_ATTRIBUTED_STRING_GET_ATTRIBUTE.invokeExact(
                    MemorySegment.ofAddress(attributedString), location, MemorySegment.ofAddress(attribute), range))
                    .address();
            return new StyleRun(value, range.getAtIndex(JAVA_LONG, 0), range.getAtIndex(JAVA_LONG, 1));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static Class<?> accessibleEnum(String enumName) {
        try {
            return Class.forName(MacAccessible.class.getName() + "$" + enumName, true,
                    MacAccessible.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object accessibleJniNative(String name, Class<?>[] parameterTypes, Object... arguments) {
        try {
            Method method = MacAccessible.class.getDeclaredMethod(name, parameterTypes);
            if (!Modifier.isNative(method.getModifiers())) {
                throw new IllegalStateException("MacAccessible." + name + " is no longer the JNI native");
            }
            method.setAccessible(true);
            return method.invoke(null, arguments);
        } catch (InvocationTargetException e) {
            throw unexpected(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Runs {@code body} between {@code gmac_pool_push} and {@code gmac_pool_pop}, for what AppKit autoreleases. */
    private static <T> T inPool(Supplier<T> body) {
        try {
            MacGlassNative.GMAC_POOL_PUSH.invokeExact();
            try {
                return body.get();
            } finally {
                MacGlassNative.GMAC_POOL_POP.invokeExact();
            }
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static MacVariant macVariant(Variant variant) {
        if (variant == null) {
            return null;
        }
        MacVariant macVariant = new MacVariant();
        macVariant.type = variant.type();
        macVariant.int1 = variant.int1();
        macVariant.int2 = variant.int2();
        macVariant.location = variant.location();
        macVariant.length = variant.length();
        macVariant.float1 = variant.float1();
        macVariant.float2 = variant.float2();
        macVariant.float3 = variant.float3();
        macVariant.float4 = variant.float4();
        macVariant.long1 = variant.long1();
        macVariant.double1 = variant.double1();
        macVariant.key = variant.key();
        if (variant.longArray() != null) {
            macVariant.longArray = variant.longArray().stream().mapToLong(Long::longValue).toArray();
        }
        if (variant.intArray() != null) {
            macVariant.intArray = variant.intArray().stream().mapToInt(Integer::intValue).toArray();
        }
        macVariant.string = variant.string();
        if (variant.variantArray() != null) {
            macVariant.variantArray = variant.variantArray().stream().map(MacGlassNativeShim::macVariant)
                    .toArray(MacVariant[]::new);
        }
        return macVariant;
    }

    private static Variant variant(MacVariant macVariant) {
        if (macVariant == null) {
            return null;
        }
        List<Variant> elements = null;
        if (macVariant.variantArray != null) {
            elements = new ArrayList<>();
            for (MacVariant element : macVariant.variantArray) {
                elements.add(variant(element));
            }
        }
        return new Variant(macVariant.type, macVariant.int1, macVariant.int2, macVariant.location, macVariant.length,
                macVariant.float1, macVariant.float2, macVariant.float3, macVariant.float4, macVariant.long1,
                macVariant.double1, macVariant.key,
                macVariant.longArray != null ? Arrays.stream(macVariant.longArray).boxed().toList() : null,
                macVariant.intArray != null ? Arrays.stream(macVariant.intArray).boxed().toList() : null,
                macVariant.string, elements);
    }

    private static Variant takeWritten(MemorySegment value) throws Throwable {
        if (value.get(ADDRESS, MacAccessibleCallbacks.UTF8).address() != 0
                || value.get(JAVA_INT, MacAccessibleCallbacks.UTF8_LENGTH) != 0) {
            throw new IllegalStateException("a slot wrote a string to Java into a GmacVariant");
        }
        List<Long> longArray = null;
        int longCount = value.get(JAVA_INT, MacAccessibleCallbacks.LONG_ARRAY_COUNT);
        if (longCount >= 0) {
            longArray = takeIdBlock(requiredBlock(value, MacAccessibleCallbacks.LONG_ARRAY, longCount), longCount);
        }
        List<Integer> intArray = null;
        int intCount = value.get(JAVA_INT, MacAccessibleCallbacks.INT_ARRAY_COUNT);
        if (intCount >= 0) {
            long block = requiredBlock(value, MacAccessibleCallbacks.INT_ARRAY, intCount);
            MemorySegment ints = MemorySegment.ofAddress(block).reinterpret(JAVA_INT.byteSize() * intCount);
            intArray = Arrays.stream(ints.toArray(JAVA_INT)).boxed().toList();
            LibSystem.FREE.invokeExact(MemorySegment.ofAddress(block));
        }
        String string = null;
        long chars = value.get(ADDRESS, MacAccessibleCallbacks.CHARS).address();
        if (chars != 0) {
            string = takeCharBlock(chars, value.get(JAVA_INT, MacAccessibleCallbacks.CHARS_LENGTH));
        }
        List<Variant> elements = null;
        int elementCount = value.get(JAVA_INT, MacAccessibleCallbacks.VARIANT_ARRAY_COUNT);
        if (elementCount >= 0) {
            long block = requiredBlock(value, MacAccessibleCallbacks.VARIANT_ARRAY, elementCount);
            MemorySegment array = MemorySegment.ofAddress(block)
                    .reinterpret(MacAccessibleCallbacks.VARIANT_SIZE * elementCount);
            elements = new ArrayList<>();
            for (int index = 0; index < elementCount; index++) {
                MemorySegment element = array.asSlice(index * MacAccessibleCallbacks.VARIANT_SIZE,
                        MacAccessibleCallbacks.VARIANT_SIZE);
                elements.add(element.get(JAVA_INT, MacAccessibleCallbacks.TYPE) == 0 ? nullVariant(element)
                        : takeWritten(element));
            }
            LibSystem.FREE.invokeExact(MemorySegment.ofAddress(block));
        }
        return new Variant(value.get(JAVA_INT, MacAccessibleCallbacks.TYPE),
                value.get(JAVA_INT, MacAccessibleCallbacks.INT1), value.get(JAVA_INT, MacAccessibleCallbacks.INT2),
                value.get(JAVA_INT, MacAccessibleCallbacks.LOCATION),
                value.get(JAVA_INT, MacAccessibleCallbacks.LENGTH),
                value.get(JAVA_FLOAT, MacAccessibleCallbacks.FLOAT1),
                value.get(JAVA_FLOAT, MacAccessibleCallbacks.FLOAT2),
                value.get(JAVA_FLOAT, MacAccessibleCallbacks.FLOAT3),
                value.get(JAVA_FLOAT, MacAccessibleCallbacks.FLOAT4),
                value.get(JAVA_LONG, MacAccessibleCallbacks.LONG1),
                value.get(JAVA_DOUBLE, MacAccessibleCallbacks.DOUBLE1),
                value.get(ADDRESS, MacAccessibleCallbacks.KEY).address(), longArray, intArray, string, elements);
    }

    /** A null element as the C's {@code nullVariant} wrote it, of which it makes nil: null, after checking that. */
    private static Variant nullVariant(MemorySegment element) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment expected = arena.allocate(MacAccessibleCallbacks.GMAC_VARIANT_LAYOUT);
            expected.set(JAVA_INT, MacAccessibleCallbacks.LONG_ARRAY_COUNT, -1);
            expected.set(JAVA_INT, MacAccessibleCallbacks.INT_ARRAY_COUNT, -1);
            expected.set(JAVA_INT, MacAccessibleCallbacks.VARIANT_ARRAY_COUNT, -1);
            if (element.mismatch(expected) != -1) {
                throw new IllegalStateException("a null element is not the C's null variant: "
                        + Arrays.toString(element.toArray(JAVA_BYTE)));
            }
        }
        return null;
    }

    private static long requiredBlock(MemorySegment value, long offset, int count) {
        long block = value.get(ADDRESS, offset).address();
        if (count > 0 && block == 0) {
            throw new IllegalStateException("a block of " + count + " elements at offset " + offset + " is NULL");
        }
        return block;
    }

    private static MacDialogCalls.Panel panel(PanelStandIn standIn) {
        return (allocator, filters, filterCount, loopId) -> {
            PanelAnswer answer = standIn.run(describeFilters(filters, filterCount), loopId);
            return new MacDialogCalls.Chosen(answer.urls(), answer.selected());
        };
    }

    private static String describeFilters(MemorySegment filters, int filterCount) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment outUtf8 = arena.allocate(ADDRESS);
            MemorySegment outLength = arena.allocate(JAVA_INT);
            int status = (int) Hooks.DIALOG_DESCRIBE_FILTERS.invokeExact(filters, filterCount, outUtf8, outLength);
            MemorySegment utf8 = outUtf8.get(ADDRESS, 0);
            try {
                if (status != MacGlassNative.GMAC_OK) {
                    throw new IllegalStateException("gmac_test_dialog_describe_filters answered " + status);
                }
                return MacGlassNative.newStringUtf(utf8, outLength.get(JAVA_INT, 0));
            } finally {
                MacGlassNative.free(utf8);
            }
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static String menuTitle(MemorySegment units, int length) {
        if (units.address() == 0) {
            return null;
        }
        return new String(units.asSlice(0, JAVA_CHAR.byteSize() * length).toArray(JAVA_CHAR));
    }

    private static MenuIcon menuIcon(MemorySegment icon) {
        if (icon.address() == 0) {
            return null;
        }
        StructLayout layout = MacMenuCalls.GMAC_MENU_IMAGE_LAYOUT;
        return new MenuIcon(icon.get(ADDRESS, layout.byteOffset(PathElement.groupElement("image"))).address(),
                icon.get(JAVA_INT, layout.byteOffset(PathElement.groupElement("width"))),
                icon.get(JAVA_INT, layout.byteOffset(PathElement.groupElement("height"))),
                icon.get(JAVA_FLOAT, layout.byteOffset(PathElement.groupElement("scale_x"))),
                icon.get(JAVA_FLOAT, layout.byteOffset(PathElement.groupElement("scale_y"))));
    }

    private static Method clipboardNative(String name) throws ReflectiveOperationException {
        Class<?> owner = name.equals("_convertFileReferencePath") ? MacSystemClipboard.class
                : Class.forName(MacSystemClipboard.class.getName() + "$FormatEncoder");
        return owner.getDeclaredMethod(name, String.class);
    }

    private static String clipboardHook(MethodHandle hook, String value) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment outUtf8 = arena.allocate(ADDRESS);
            MemorySegment outLength = arena.allocate(JAVA_INT);
            int status = (int) hook.invokeExact(MacGlassNative.utf16(arena, value),
                    value != null ? value.length() : 0, outUtf8, outLength);
            MemorySegment utf8 = outUtf8.get(ADDRESS, 0);
            try {
                return status == MacGlassNative.GMAC_OK ? MacGlassNative.newStringUtf(utf8,
                        outLength.get(JAVA_INT, 0)) : null;
            } finally {
                MacGlassNative.free(utf8);
            }
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static int rawImageHook(SegmentAllocator allocator, byte[] data, MemorySegment outBlock,
                                    MemorySegment outLength) throws Throwable {
        MemorySegment bytes = allocator.allocate(Math.max(1, data.length));
        MemorySegment.copy(data, 0, bytes, JAVA_BYTE, 0, data.length);
        return (int) Hooks.PASTEBOARD_RAW_IMAGE.invokeExact(bytes, data.length, outBlock, outLength);
    }

    private static String describe(MemorySegment items, int itemCount) throws Throwable {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment outUtf8 = arena.allocate(ADDRESS);
            MemorySegment outLength = arena.allocate(JAVA_INT);
            int status = (int) Hooks.PASTEBOARD_DESCRIBE_ITEMS.invokeExact(items, itemCount, outUtf8, outLength);
            MemorySegment utf8 = outUtf8.get(ADDRESS, 0);
            try {
                if (status != MacGlassNative.GMAC_OK) {
                    throw new IllegalStateException("gmac_test_pasteboard_describe_items answered " + status);
                }
                return MacGlassNative.newStringUtf(utf8, outLength.get(JAVA_INT, 0));
            } finally {
                MacGlassNative.free(utf8);
            }
        }
    }

    /** A stand-in that answers what {@code answer} says, its block a malloc'd copy with a NUL past it. */
    private static MacPasteboardCalls.BlockQuery standIn(BlockAnswer answer) {
        return (allocator, outBlock, outLength) -> {
            if (answer.block() != null) {
                MemorySegment block = ((MemorySegment) LibSystem.MALLOC.invokeExact(answer.block().length + 1L))
                        .reinterpret(answer.block().length + 1L);
                MemorySegment.copy(answer.block(), 0, block, JAVA_BYTE, 0, answer.block().length);
                block.set(JAVA_BYTE, answer.block().length, (byte) 0);
                outBlock.set(ADDRESS, 0, block);
            }
            outLength.set(JAVA_INT, 0, answer.length());
            return answer.status();
        };
    }

    /**
     * Stands where a delegate's menu receives the menu slots: adds each call to {@code events}, then, when
     * {@code failing}, throws an {@link IllegalStateException} naming it.
     */
    public static final class MenuRecorder implements MacMenuCallbacks.MenuReceiver {

        private final List<String> events;
        private final boolean failing;

        public MenuRecorder(List<String> events, boolean failing) {
            this.events = events;
            this.failing = failing;
        }

        @Override
        public void notifyMenuOpening() {
            record("notifyMenuOpening()");
        }

        @Override
        public void notifyMenuClosed() {
            record("notifyMenuClosed()");
        }

        private void record(String call) {
            events.add(call);
            if (failing) {
                throw new IllegalStateException(call);
            }
        }
    }

    /**
     * Stands where a {@code MacAccessible} receives the slots of its peer: adds each call to {@code events}, pointers
     * in hexadecimal, then, when {@code failing}, throws an {@link IllegalStateException} naming it, else answers what
     * {@code answers} holds under the method's name: a {@code List<Long>} for a {@code long[]}, a {@link Variant}, a
     * {@code Boolean}, {@code Integer}, {@code Long} or {@code String}. A name it lacks answers null, false or 0.
     */
    public static final class AccessibleRecorder implements MacAccessibleCallbacks.Receiver {

        private final List<String> events;
        private final Map<String, Object> answers;
        private final boolean failing;

        public AccessibleRecorder(List<String> events, Map<String, Object> answers, boolean failing) {
            this.events = events;
            this.answers = answers;
            this.failing = failing;
        }

        @Override
        public long[] accessibilityAttributeNames() {
            return ids(record("accessibilityAttributeNames", ""));
        }

        @Override
        public MacVariant accessibilityAttributeValue(long attribute) {
            return macVariant((Variant) record("accessibilityAttributeValue", hex(attribute)));
        }

        @Override
        public boolean accessibilityIsAttributeSettable(long attribute) {
            return Boolean.TRUE.equals(record("accessibilityIsAttributeSettable", hex(attribute)));
        }

        @Override
        public void accessibilitySetValue(long value, long attribute) {
            record("accessibilitySetValue", hex(value) + ", " + hex(attribute));
        }

        @Override
        public long accessibilityIndexOfChild(long child) {
            Object index = record("accessibilityIndexOfChild", hex(child));
            return index != null ? (Long) index : 0L;
        }

        @Override
        public int accessibilityArrayAttributeCount(long attribute) {
            Object count = record("accessibilityArrayAttributeCount", hex(attribute));
            return count != null ? (Integer) count : 0;
        }

        @Override
        public long[] accessibilityArrayAttributeValues(long attribute, int index, int maxCount) {
            return ids(record("accessibilityArrayAttributeValues", hex(attribute) + ", " + index + ", " + maxCount));
        }

        @Override
        public long[] accessibilityParameterizedAttributeNames() {
            return ids(record("accessibilityParameterizedAttributeNames", ""));
        }

        @Override
        public MacVariant accessibilityAttributeValueForParameter(long attribute, long parameter) {
            return macVariant((Variant) record("accessibilityAttributeValueForParameter",
                    hex(attribute) + ", " + hex(parameter)));
        }

        @Override
        public long[] accessibilityActionNames() {
            return ids(record("accessibilityActionNames", ""));
        }

        @Override
        public String accessibilityActionDescription(long action) {
            return (String) record("accessibilityActionDescription", hex(action));
        }

        @Override
        public void accessibilityPerformAction(long action) {
            record("accessibilityPerformAction", hex(action));
        }

        @Override
        public boolean accessibilityIsIgnored() {
            return Boolean.TRUE.equals(record("accessibilityIsIgnored", ""));
        }

        @Override
        public long accessibilityHitTest(float x, float y) {
            Object element = record("accessibilityHitTest", x + ", " + y);
            return element != null ? (Long) element : 0L;
        }

        @Override
        public long accessibilityFocusedUIElement() {
            Object element = record("accessibilityFocusedUIElement", "");
            return element != null ? (Long) element : 0L;
        }

        private Object record(String method, String arguments) {
            String call = method + "(" + arguments + ")";
            events.add(call);
            if (failing) {
                throw new IllegalStateException(call);
            }
            return answers.get(method);
        }

        @SuppressWarnings("unchecked")
        private static long[] ids(Object answer) {
            return answer != null ? ((List<Long>) answer).stream().mapToLong(Long::longValue).toArray() : null;
        }

        private static String hex(long pointer) {
            return "0x" + Long.toHexString(pointer);
        }
    }

    /**
     * Stands where a {@code MacWindow} receives the window slots: adds each call to {@code events}, then, when
     * {@code failing}, throws an {@link IllegalStateException} naming it. Keeps every {@code Screen} it was given.
     */
    public static final class WindowCallbackRecorder implements MacWindowCallbacks.Receiver {

        private final List<String> events;
        private final boolean failing;
        private final List<Screen> screens = new CopyOnWriteArrayList<>();

        public WindowCallbackRecorder(List<String> events, boolean failing) {
            this.events = events;
            this.failing = failing;
        }

        public List<Screen> screens() {
            return List.copyOf(screens);
        }

        @Override
        public void notifyMove(int x, int y, boolean isZoomed) {
            record("notifyMove(" + x + ", " + y + ", " + isZoomed + ")");
        }

        @Override
        public void notifyResize(int type, int width, int height) {
            record("notifyResize(" + type + ", " + width + ", " + height + ")");
        }

        @Override
        public void notifyMoveToAnotherScreen(Screen screen) {
            screens.add(screen);
            record("notifyMoveToAnotherScreen");
        }

        @Override
        public void notifyScaleChanged(float platformScaleX, float platformScaleY, float outputScaleX,
                                       float outputScaleY) {
            record("notifyScaleChanged(" + platformScaleX + ", " + platformScaleY + ", " + outputScaleX + ", "
                    + outputScaleY + ")");
        }

        @Override
        public void notifyClose() {
            record("notifyClose()");
        }

        @Override
        public void notifyFocus(int event) {
            record("notifyFocus(" + event + ")");
        }

        @Override
        public void notifyFocusUngrab() {
            record("notifyFocusUngrab()");
        }

        @Override
        public void notifyFocusDisabled() {
            record("notifyFocusDisabled()");
        }

        @Override
        public void notifyDestroy() {
            record("notifyDestroy()");
        }

        private void record(String call) {
            events.add(call);
            if (failing) {
                throw new IllegalStateException(call);
            }
        }
    }

    /**
     * Stands where a {@code MacView} receives the view and gesture slots: adds each call to {@code events}, then,
     * when {@code failing}, throws an {@link IllegalStateException} naming it, and otherwise answers what it was told
     * to. Keeps every {@code char[]} a key call was given.
     */
    public static final class ViewCallbackRecorder implements MacViewCallbacks.Receiver {

        private final List<String> events;
        private final boolean failing;
        private final List<char[]> keyChars = new CopyOnWriteArrayList<>();
        private volatile boolean consumed = true;
        private volatile int dragAction = 0x40000000;
        private volatile double[] candidatePosition = {1.5, 2.5};
        private volatile long accessible = 0x0102030405060708L;
        private volatile Runnable duringNextKey;

        public ViewCallbackRecorder(List<String> events, boolean failing) {
            this.events = events;
            this.failing = failing;
        }

        public List<char[]> keyChars() {
            return List.copyOf(keyChars);
        }

        public void answerConsumed(boolean value) {
            consumed = value;
        }

        public void answerDragAction(int value) {
            dragAction = value;
        }

        public void answerCandidatePosition(double[] value) {
            candidatePosition = value;
        }

        public void answerAccessible(long value) {
            accessible = value;
        }

        /** Runs {@code action} inside the next key call, after recording it, as a nested event would. */
        public void duringNextKey(Runnable action) {
            duringNextKey = action;
        }

        @Override
        public void notifyView(int type) {
            record("notifyView(" + type + ")");
        }

        @Override
        public void notifyResize(int width, int height) {
            record("notifyResize(" + width + ", " + height + ")");
        }

        @Override
        public void notifyRepaint(int x, int y, int width, int height) {
            record("notifyRepaint(" + x + ", " + y + ", " + width + ", " + height + ")");
        }

        @Override
        public void notifyMenu(int x, int y, int xAbs, int yAbs, boolean isKeyboardTrigger) {
            record("notifyMenu(" + x + ", " + y + ", " + xAbs + ", " + yAbs + ", " + isKeyboardTrigger + ")");
        }

        @Override
        public void notifyMouse(int type, int button, int x, int y, int xAbs, int yAbs, int modifiers,
                                boolean isPopupTrigger, boolean isSynthesized) {
            record("notifyMouse(" + type + ", " + button + ", " + x + ", " + y + ", " + xAbs + ", " + yAbs + ", "
                    + modifiers + ", " + isPopupTrigger + ", " + isSynthesized + ")");
        }

        @Override
        public boolean notifyKeyAndReturnConsumed(int type, int keyCode, char[] chars, int modifiers) {
            keyChars.add(chars);
            Runnable nested = duringNextKey;
            duringNextKey = null;
            String call = "notifyKeyAndReturnConsumed(" + type + ", " + keyCode + ", " + units(chars) + ", "
                    + modifiers + ")";
            events.add(call);
            if (nested != null) {
                nested.run();
            }
            failIfFailing(call);
            return consumed;
        }

        @Override
        public void notifyInputMethodMac(String text, int attr, int length, int cursor, int selStart,
                                         int selLength) {
            record("notifyInputMethodMac(" + units(text.toCharArray()) + ", " + attr + ", " + length + ", "
                    + cursor + ", " + selStart + ", " + selLength + ")");
        }

        @Override
        public double[] notifyInputMethodCandidatePosRequest(int pos) {
            record("notifyInputMethodCandidatePosRequest(" + pos + ")");
            return candidatePosition;
        }

        @Override
        public int notifyDragEnter(int x, int y, int xAbs, int yAbs, int recommendedAction) {
            record("notifyDragEnter(" + x + ", " + y + ", " + xAbs + ", " + yAbs + ", " + recommendedAction + ")");
            return dragAction;
        }

        @Override
        public int notifyDragOver(int x, int y, int xAbs, int yAbs, int recommendedAction) {
            record("notifyDragOver(" + x + ", " + y + ", " + xAbs + ", " + yAbs + ", " + recommendedAction + ")");
            return dragAction;
        }

        @Override
        public int notifyDragDrop(int x, int y, int xAbs, int yAbs, int recommendedAction) {
            record("notifyDragDrop(" + x + ", " + y + ", " + xAbs + ", " + yAbs + ", " + recommendedAction + ")");
            return dragAction;
        }

        @Override
        public void notifyDragLeave() {
            record("notifyDragLeave()");
        }

        @Override
        public void notifyDragEnd(int performedAction) {
            record("notifyDragEnd(" + performedAction + ")");
        }

        @Override
        public long getAccessible() {
            record("getAccessible()");
            return accessible;
        }

        @Override
        public void rotateGesturePerformed(int modifiers, int x, int y, int xAbs, int yAbs, float rotation) {
            record("rotateGesturePerformed(" + modifiers + ", " + x + ", " + y + ", " + xAbs + ", " + yAbs + ", "
                    + rotation + ")");
        }

        @Override
        public void scrollGesturePerformed(int modifiers, int sender, int x, int y, int xAbs, int yAbs, float dx,
                                           float dy) {
            record("scrollGesturePerformed(" + modifiers + ", " + sender + ", " + x + ", " + y + ", " + xAbs + ", "
                    + yAbs + ", " + dx + ", " + dy + ")");
        }

        @Override
        public void swipeGesturePerformed(int modifiers, int dir, int x, int y, int xAbs, int yAbs) {
            record("swipeGesturePerformed(" + modifiers + ", " + dir + ", " + x + ", " + y + ", " + xAbs + ", "
                    + yAbs + ")");
        }

        @Override
        public void magnifyGesturePerformed(int modifiers, int x, int y, int xAbs, int yAbs, float scale) {
            record("magnifyGesturePerformed(" + modifiers + ", " + x + ", " + y + ", " + xAbs + ", " + yAbs + ", "
                    + scale + ")");
        }

        @Override
        public void gestureFinished(int modifiers, int x, int y, int xAbs, int yAbs) {
            record("gestureFinished(" + modifiers + ", " + x + ", " + y + ", " + xAbs + ", " + yAbs + ")");
        }

        /** The UTF-16 code units, in hex, so that a lone surrogate shows as itself. */
        private static String units(char[] chars) {
            StringBuilder hex = new StringBuilder("[");
            for (int i = 0; i < chars.length; i++) {
                hex.append(i == 0 ? "" : " ").append(String.format("%04x", (int) chars[i]));
            }
            return hex.append(']').toString();
        }

        private void record(String call) {
            events.add(call);
            failIfFailing(call);
        }

        private void failIfFailing(String call) {
            if (failing) {
                throw new IllegalStateException(call);
            }
        }
    }

    /** {@code gmac_test_sdk_constant}: the SDK value of a {@code GmacSdkConstant} index, INT32_MIN outside. */
    public static int sdkConstant(int index) {
        try {
            return (int) Hooks.SDK_CONSTANT.invokeExact(index);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code gmac_test_color_space_srgb_name}: the {@code kCGColorSpaceSRGB} the library was linked against. */
    public static MemorySegment libraryColorSpaceSrgbName() {
        try {
            return (MemorySegment) Hooks.COLOR_SPACE_SRGB_NAME.invokeExact();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * The SDK values and C type sizes that {@code MacRobotCalls} and {@link #readEvent} spell out, by the
     * {@code GmacSdkConstant} entry that answers each, in the enum's order.
     */
    public static Map<String, Integer> robotSdkConstants() {
        Map<String, Integer> constants = new LinkedHashMap<>();
        constants.put("GMAC_SDK_EVENT_LEFT_MOUSE_DOWN", MacRobotCalls.kCGEventLeftMouseDown);
        constants.put("GMAC_SDK_EVENT_LEFT_MOUSE_UP", MacRobotCalls.kCGEventLeftMouseUp);
        constants.put("GMAC_SDK_EVENT_RIGHT_MOUSE_DOWN", MacRobotCalls.kCGEventRightMouseDown);
        constants.put("GMAC_SDK_EVENT_RIGHT_MOUSE_UP", MacRobotCalls.kCGEventRightMouseUp);
        constants.put("GMAC_SDK_EVENT_MOUSE_MOVED", MacRobotCalls.kCGEventMouseMoved);
        constants.put("GMAC_SDK_EVENT_LEFT_MOUSE_DRAGGED", MacRobotCalls.kCGEventLeftMouseDragged);
        constants.put("GMAC_SDK_EVENT_RIGHT_MOUSE_DRAGGED", MacRobotCalls.kCGEventRightMouseDragged);
        constants.put("GMAC_SDK_EVENT_OTHER_MOUSE_DOWN", MacRobotCalls.kCGEventOtherMouseDown);
        constants.put("GMAC_SDK_EVENT_OTHER_MOUSE_UP", MacRobotCalls.kCGEventOtherMouseUp);
        constants.put("GMAC_SDK_EVENT_OTHER_MOUSE_DRAGGED", MacRobotCalls.kCGEventOtherMouseDragged);
        constants.put("GMAC_SDK_HID_EVENT_TAP", MacRobotCalls.kCGHIDEventTap);
        constants.put("GMAC_SDK_SCROLL_EVENT_UNIT_LINE", MacRobotCalls.kCGScrollEventUnitLine);
        constants.put("GMAC_SDK_WINDOW_LIST_OPTION_ON_SCREEN_ONLY", MacRobotCalls.kCGWindowListOptionOnScreenOnly);
        constants.put("GMAC_SDK_NULL_WINDOW_ID", MacRobotCalls.kCGNullWindowID);
        constants.put("GMAC_SDK_WINDOW_IMAGE_DEFAULT", MacRobotCalls.kCGWindowImageDefault);
        constants.put("GMAC_SDK_RENDERING_INTENT_ABSOLUTE_COLORIMETRIC",
                MacRobotCalls.kCGRenderingIntentAbsoluteColorimetric);
        constants.put("GMAC_SDK_CAPTURE_BITMAP_INFO", MacRobotCalls.CAPTURE_BITMAP_INFO);
        putSize(constants, "CGFLOAT", MacRobotCalls.CG_FLOAT);
        putSize(constants, "CGPOINT", MacRobotCalls.CG_POINT);
        putSize(constants, "CGRECT", MacRobotCalls.CG_RECT);
        putSize(constants, "CGEVENTTYPE", MacRobotCalls.CG_EVENT_TYPE);
        putSize(constants, "CGMOUSEBUTTON", MacRobotCalls.CG_MOUSE_BUTTON);
        putSize(constants, "CGEVENTTAPLOCATION", MacRobotCalls.CG_EVENT_TAP_LOCATION);
        putSize(constants, "CGSCROLLEVENTUNIT", MacRobotCalls.CG_SCROLL_EVENT_UNIT);
        putSize(constants, "CGEVENTFIELD", CoreGraphics.CG_EVENT_FIELD);
        putSize(constants, "CGCHARCODE", MacRobotCalls.CG_CHAR_CODE);
        putSize(constants, "CGKEYCODE", MacRobotCalls.CG_KEY_CODE);
        putSize(constants, "BOOLEAN", MacRobotCalls.BOOLEAN);
        putSize(constants, "AXERROR", MacRobotCalls.AX_ERROR);
        putSize(constants, "CGERROR", MacRobotCalls.CG_ERROR);
        putSize(constants, "CGWINDOWLISTOPTION", MacRobotCalls.CG_WINDOW_LIST_OPTION);
        putSize(constants, "CGWINDOWID", MacRobotCalls.CG_WINDOW_ID);
        putSize(constants, "CGWINDOWIMAGEOPTION", MacRobotCalls.CG_WINDOW_IMAGE_OPTION);
        putSize(constants, "CGCOLORRENDERINGINTENT", MacRobotCalls.CG_COLOR_RENDERING_INTENT);
        putSize(constants, "CGBITMAPINFO", MacRobotCalls.CG_BITMAP_INFO);
        putSize(constants, "SIZE_T", MacRobotCalls.SIZE_T);
        return constants;
    }

    /** The {@code kCGColorSpaceSRGB} that {@code MacRobotCalls} names the captures' colour space by. */
    public static MemorySegment robotColorSpaceSrgbName() {
        return MacRobotCalls.colorSpaceSrgbName();
    }

    /** {@code gmac_pool_push}, as each robot call opens its pool. */
    public static void robotPoolPush() {
        MacRobotCalls.poolPush();
    }

    /** {@code gmac_pool_pop}, as each robot call closes its pool. */
    public static void robotPoolPop() {
        MacRobotCalls.poolPop();
    }

    /**
     * The event {@code MacRobot.mouseMove(x, y)} posts while the robot holds {@code mouseButtons}, made the same way
     * and not posted. The caller releases it ({@link #release}).
     */
    public static MemorySegment robotMouseMovedEvent(int mouseButtons, float x, float y) {
        try (Arena arena = Arena.ofConfined()) {
            return MacRobotCalls.mouseMovedEvent(mouseButtons, MacRobotCalls.cgPoint(arena, x, y));
        }
    }

    /** The event a mouse press or release posts for the button of bit {@code index}, at (x, y), not posted. */
    public static MemorySegment robotMouseButtonEvent(int index, boolean pressed, double x, double y) {
        try (Arena arena = Arena.ofConfined()) {
            return MacRobotCalls.mouseButtonEvent(index, pressed, MacRobotCalls.cgPoint(arena, x, y));
        }
    }

    /** The event {@code MacRobot.mouseWheel(wheelAmt)} posts, not posted. */
    public static MemorySegment robotScrollWheelEvent(int wheelAmt) {
        return MacRobotCalls.scrollWheelEvent(wheelAmt);
    }

    /**
     * {@code gmac_key_find_mac}, through the binding {@code MacRobotCalls} looks a key up with before posting it: the
     * Mac key code of {@code javaKeyCode}, or -1 for none. It reads the keyboard layout for a code whose US QWERTY key
     * is layout-sensitive or that its table lacks.
     */
    public static int robotFindMacKey(int javaKeyCode) {
        try {
            return (int) MacGlassNative.GMAC_KEY_FIND_MAC.invokeExact(javaKeyCode);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code CFRelease}, through {@code MacRobotCalls}' binding. */
    public static void release(MemorySegment object) {
        MacRobotCalls.CFRelease(object);
    }

    /** What CoreGraphics reads back from an event. */
    public record EventReadback(int type, double x, double y, long buttonNumber, long scrollDeltaAxis1) {
    }

    /**
     * {@code CGEventGetType}, {@code CGEventGetLocation} and {@code CGEventGetIntegerValueField} of the two fields,
     * whose numbers the caller takes from {@link #sdkConstant}.
     */
    public static EventReadback readEvent(MemorySegment event, int buttonNumberField, int scrollDeltaAxis1Field) {
        try (Arena arena = Arena.ofConfined()) {
            int type = (int) CoreGraphics.CG_EVENT_GET_TYPE.invokeExact(event);
            MemorySegment location = (MemorySegment) CoreGraphics.CG_EVENT_GET_LOCATION.invokeExact(
                    (SegmentAllocator) arena, event);
            long buttonNumber = (long) CoreGraphics.CG_EVENT_GET_INTEGER_VALUE_FIELD.invokeExact(event,
                    buttonNumberField);
            long scrollDelta = (long) CoreGraphics.CG_EVENT_GET_INTEGER_VALUE_FIELD.invokeExact(event,
                    scrollDeltaAxis1Field);
            return new EventReadback(type, location.get(JAVA_DOUBLE, 0), location.get(JAVA_DOUBLE, Double.BYTES),
                    buttonNumber, scrollDelta);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code CGEventCreateMouseEvent(NULL, type, (x, y), button)}, as the C called it, through this class's own
     * binding rather than {@code MacRobotCalls}'; not posted. The caller releases it ({@link #release}).
     */
    public static MemorySegment referenceMouseEvent(int type, double x, double y, int button) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment location = arena.allocate(MacRobotCalls.CG_POINT);
            location.set(JAVA_DOUBLE, 0, x);
            location.set(JAVA_DOUBLE, Double.BYTES, y);
            return (MemorySegment) CoreGraphics.CG_EVENT_CREATE_MOUSE_EVENT.invokeExact(MemorySegment.NULL, type,
                    location, button);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code CGEventCreateScrollWheelEvent(NULL, units, wheelCount, wheel1)}, as the C called it, through this class's
     * own binding; not posted. The caller releases it ({@link #release}).
     */
    public static MemorySegment referenceScrollWheelEvent(int units, int wheelCount, int wheel1) {
        try {
            return (MemorySegment) CoreGraphics.CG_EVENT_CREATE_SCROLL_WHEEL_EVENT.invokeExact(MemorySegment.NULL,
                    units, wheelCount, wheel1);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code CGEventCreateData}: the event as CoreGraphics serializes it, every field included, the flags and the
     * click state among them, which {@link #readEvent} does not read.
     */
    public static byte[] eventData(MemorySegment event) {
        try {
            MemorySegment data = (MemorySegment) CoreGraphics.CG_EVENT_CREATE_DATA.invokeExact(MemorySegment.NULL,
                    event);
            if (data.address() == 0) {
                throw new IllegalStateException("CoreGraphics did not serialize the event");
            }
            try {
                long length = (long) CoreFoundation.CF_DATA_GET_LENGTH.invokeExact(data);
                MemorySegment bytes = (MemorySegment) CoreFoundation.CF_DATA_GET_BYTE_PTR.invokeExact(data);
                return bytes.reinterpret(length).toArray(JAVA_BYTE);
            } finally {
                MacRobotCalls.CFRelease(data);
            }
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static void putSize(Map<String, Integer> constants, String type, MemoryLayout layout) {
        constants.put("GMAC_SDK_SIZEOF_" + type, Math.toIntExact(layout.byteSize()));
    }

    private static MacTimerCalls.TimerDowncalls adapt(TimerStandIn timers) {
        return new MacTimerCalls.TimerDowncalls() {
            @Override
            public long startPeriod(long timerId, int period) {
                return timers.startPeriod(timerId, period);
            }

            @Override
            public long startVsync(long timerId) {
                return timers.startVsync(timerId);
            }

            @Override
            public void stop(long timer) {
                timers.stop(timer);
            }

            @Override
            public void pause(long timer) {
                timers.pause(timer);
            }

            @Override
            public void resume(long timer) {
                timers.resume(timer);
            }

            @Override
            public boolean paused(long timer) {
                return timers.paused(timer);
            }
        };
    }

    /**
     * Stands where {@code MacApplication} receives the slots: records each call, then throws an
     * {@link IllegalStateException} naming the slot when the slot is one of {@code failingSlots}.
     */
    public static final class AppCallbackRecorder implements MacAppCallbacks.Receiver {

        private final Set<Integer> failingSlots;
        private final List<String> calls = new CopyOnWriteArrayList<>();
        private final List<Map<String, Object>> preferences = new CopyOnWriteArrayList<>();

        public AppCallbackRecorder(Set<Integer> failingSlots) {
            this.failingSlots = Set.copyOf(failingSlots);
        }

        public List<String> calls() {
            return List.copyOf(calls);
        }

        /** The maps {@code notifyPreferencesChanged} was given, as given. */
        public List<Map<String, Object>> preferences() {
            return List.copyOf(preferences);
        }

        @Override
        public void runRunnable(long runnableId) {
            record(MacAppCallbacks.RUN_RUNNABLE, "runRunnable(0x" + Long.toHexString(runnableId) + ")");
        }

        @Override
        public void fire(int slot) {
            record(slot, "fire(" + slot + ")");
        }

        @Override
        public void setName(String name) {
            record(MacAppCallbacks.SET_NAME, "setName(" + name + ")");
        }

        @Override
        public void notifyOpenFiles(String[] files) {
            record(MacAppCallbacks.NOTIFY_OPEN_FILES, "notifyOpenFiles(" + Arrays.toString(files) + ")");
        }

        @Override
        public void notifyPreferencesChanged(Map<String, Object> preferences) {
            this.preferences.add(preferences);
            record(MacAppCallbacks.NOTIFY_PREFERENCES_CHANGED, "notifyPreferencesChanged");
        }

        private void record(int slot, String call) {
            calls.add(call);
            if (failingSlots.contains(slot)) {
                throw new IllegalStateException("slot " + slot);
            }
        }
    }

    public static boolean isMainThread() {
        try {
            return (int) LibSystem.PTHREAD_MAIN_NP.invokeExact() != 0;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** The bytes in use in every malloc zone of the process, summed. */
    public static long mallocBytesInUse() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment statistics = arena.allocate(MALLOC_STATISTICS);
            LibSystem.MALLOC_ZONE_STATISTICS.invokeExact(MemorySegment.NULL, statistics);
            return statistics.get(JAVA_LONG, MALLOC_STATISTICS.byteOffset(PathElement.groupElement("size_in_use")));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * Whether AppKit's {@code NSApp} is nil, that is, no {@code NSApplication} exists in this process. An AppKit that
     * is not mapped has none either, and this does not map it.
     */
    public static boolean nsAppIsNil() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment handle = (MemorySegment) LibSystem.DLOPEN.invokeExact(arena.allocateFrom(APPKIT),
                    RTLD_LAZY | RTLD_NOLOAD);
            if (handle.address() == 0) {
                return true;
            }
            if ((int) LibSystem.DLCLOSE.invokeExact(handle) != 0) {
                throw new IllegalStateException("dlclose of " + APPKIT + " failed");
            }
        } catch (Throwable t) {
            throw unexpected(t);
        }
        MemorySegment nsApp = AppKit.LOOKUP.findOrThrow("NSApp").reinterpret(ADDRESS.byteSize());
        return nsApp.get(ADDRESS, 0).address() == 0;
    }

    /** Passes {@code value} as the facade passes a string to the library ({@code MacGlassNative.utf16}). */
    private static Conversion convert(MethodHandle hook, String value, int capacity) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment chars = MacGlassNative.utf16(arena, value);
            int length = value != null ? value.length() : 0;
            MemorySegment out = arena.allocate(JAVA_CHAR, Math.max(1, capacity));
            int result = (int) hook.invokeExact(chars, length, out, capacity);
            int copied = Math.min(Math.max(result, 0), capacity);
            char[] units = out.asSlice(0, JAVA_CHAR.byteSize() * copied).toArray(JAVA_CHAR);
            return new Conversion(result, new String(units));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** Not {@code MacGlassNative.unexpected}: that would initialize the facade, and load the library, on failure. */
    private static RuntimeException unexpected(Throwable t) {
        if (t instanceof RuntimeException e) {
            return e;
        }
        if (t instanceof Error e) {
            throw e;
        }
        return new IllegalStateException(t);
    }

    private static Map<String, MethodType> carrierTypes(Map<String, FunctionDescriptor> descriptors) {
        Map<String, MethodType> types = new LinkedHashMap<>();
        descriptors.forEach((name, descriptor) -> types.put(name, descriptor.toMethodType()));
        return types;
    }

    private static final class Hooks {

        static final Map<String, FunctionDescriptor> DESCRIPTORS = new LinkedHashMap<>();

        /** This class's loader is the facade's, so its lookup sees the library once the facade has loaded it. */
        static final SymbolLookup GLASS = glass();

        static final MethodHandle POOL_DEPTH = bind("gmac_test_pool_depth", FunctionDescriptor.of(JAVA_INT));
        static final MethodHandle SET_MAIN_JAVA_ATTACHED = bind("gmac_test_set_main_java_attached",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT));
        static final MethodHandle STRING_OR_EMPTY = bind("gmac_test_string_or_empty",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));
        static final MethodHandle STRING_OR_NIL = bind("gmac_test_string_or_nil",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));
        static final MethodHandle FIRE_APP_CALLBACK = bind("gmac_test_fire_app_callback",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT));
        static final MethodHandle INSPECT_AND_RELEASE_APP_DELEGATE = bind(
                "gmac_test_inspect_and_release_app_delegate", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        static final MethodHandle INSPECT_AND_RELEASE_IMAGE = bind("gmac_test_inspect_and_release_image",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT));
        static final MethodHandle FIRE_TIMER_CALLBACK = bind("gmac_test_fire_timer_callback",
                FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT));
        static final MethodHandle SDK_CONSTANT = bind("gmac_test_sdk_constant",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT));
        static final MethodHandle COLOR_SPACE_SRGB_NAME = bind("gmac_test_color_space_srgb_name",
                FunctionDescriptor.of(ADDRESS));
        static final MethodHandle FIRE_WINDOW_CALLBACK = bind("gmac_test_fire_window_callback",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_LONG));
        static final MethodHandle NSLOCALE_LANGUAGE_DIRECTION = bind("gmac_test_nslocale_language_direction",
                FunctionDescriptor.of(JAVA_INT));
        static final MethodHandle FIRE_VIEW_CALLBACK = bind("gmac_test_fire_view_callback",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_LONG, ADDRESS));
        static final MethodHandle FIRE_GESTURE_CALLBACK = bind("gmac_test_fire_gesture_callback",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_LONG));
        static final MethodHandle FIRE_KEY_EVENT = bind("gmac_test_fire_key_event",
                FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, ADDRESS));
        static final MethodHandle KEY_CHARS_UTF16 = bind("gmac_test_key_chars_utf16",
                FunctionDescriptor.of(JAVA_INT, JAVA_CHAR, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));
        static final MethodHandle FIRE_KEY_CHARS = bind("gmac_test_fire_key_chars",
                FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_CHAR, ADDRESS, JAVA_INT, JAVA_CHAR, ADDRESS));
        static final MethodHandle FIRE_INPUT_METHOD = bind("gmac_test_fire_input_method",
                FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT,
                        JAVA_LONG, JAVA_LONG));
        static final MethodHandle DEFAULT_VIEW_CAPS = bind("gmac_test_default_view_caps",
                FunctionDescriptor.ofVoid(ADDRESS));
        static final MethodHandle CLIPBOARD_MIME_TO_UTI = bind("gmac_test_clipboard_mime_to_uti",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
        static final MethodHandle CLIPBOARD_UTI_TO_MIME = bind("gmac_test_clipboard_uti_to_mime",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
        static final MethodHandle PASTEBOARD_UTIS_BLOCK = bind("gmac_test_pasteboard_utis_block",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
        static final MethodHandle PASTEBOARD_RAW_IMAGE = bind("gmac_test_pasteboard_raw_image",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
        static final MethodHandle PASTEBOARD_RAW_IMAGE_BOUNDS = bind("gmac_test_pasteboard_raw_image_bounds",
                FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG));
        static final MethodHandle PASTEBOARD_DESCRIBE_ITEMS = bind("gmac_test_pasteboard_describe_items",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
        static final MethodHandle FIRE_DIALOG_CALLBACK = bind("gmac_test_fire_dialog_callback",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_LONG));
        static final MethodHandle DIALOG_DESCRIBE_FILTERS = bind("gmac_test_dialog_describe_filters",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
        static final MethodHandle FIRE_MENU_CALLBACK = bind("gmac_test_fire_menu_callback",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_LONG));
        static final MethodHandle MENU_KEY_EQUIVALENT = bind("gmac_test_menu_key_equivalent",
                FunctionDescriptor.of(JAVA_INT, JAVA_CHAR, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        static final MethodHandle FIRE_ACCESSIBLE_CALLBACK = bind("gmac_test_fire_accessible_callback",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_LONG, ADDRESS));
        static final MethodHandle A11Y_VARIANT_TO_OBJECT = bind("gmac_test_a11y_variant_to_object",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        static final MethodHandle A11Y_UTF8_STRING = bind("gmac_test_a11y_utf8_string",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        static final MethodHandle A11Y_IS_EQUAL_TO_STRING = bind("gmac_test_a11y_is_equal_to_string",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        static final MethodHandle A11Y_ENUM_POINTER = bind("gmac_test_a11y_enum_pointer",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));

        private static SymbolLookup glass() {
            MacGlassNative.ensureLoaded();
            return SymbolLookup.loaderLookup();
        }

        private static MethodHandle bind(String name, FunctionDescriptor descriptor) {
            DESCRIPTORS.put(name, descriptor);
            return LINKER.downcallHandle(GLASS.findOrThrow(name), descriptor);
        }
    }

    /** {@code Application}'s protected event-thread accessors, which only a subclass may call. */
    private abstract static class EventThread extends Application {

        static Thread get() {
            return getEventThread();
        }

        static void set(Thread thread) {
            setEventThread(thread);
        }
    }

    private static final class LibSystem {

        static final SymbolLookup LOOKUP = LINKER.defaultLookup();

        static final MethodHandle PTHREAD_MAIN_NP = bind("pthread_main_np", FunctionDescriptor.of(JAVA_INT));
        static final MethodHandle MALLOC = bind("malloc", FunctionDescriptor.of(ADDRESS, JAVA_LONG));
        static final MethodHandle FREE = bind("free", FunctionDescriptor.ofVoid(ADDRESS));
        static final MethodHandle DLOPEN = bind("dlopen", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
        static final MethodHandle DLCLOSE = bind("dlclose", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        static final MethodHandle MALLOC_ZONE_STATISTICS = bind("malloc_zone_statistics",
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));

        private static MethodHandle bind(String name, FunctionDescriptor descriptor) {
            return LINKER.downcallHandle(LOOKUP.findOrThrow(name), descriptor);
        }
    }

    private static final class AppKit {

        static final SymbolLookup LOOKUP = SymbolLookup.libraryLookup(APPKIT, Arena.global());

        static final MethodHandle NS_ACCESSIBILITY_ROLE_DESCRIPTION = LINKER.downcallHandle(
                LOOKUP.findOrThrow("NSAccessibilityRoleDescription"),
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
        static final MethodHandle NS_ACCESSIBILITY_ACTION_DESCRIPTION = LINKER.downcallHandle(
                LOOKUP.findOrThrow("NSAccessibilityActionDescription"), FunctionDescriptor.of(ADDRESS, ADDRESS));
    }

    /**
     * The readbacks of {@link #readEvent} and {@link #eventData}, which no production code makes, and the reference
     * events, bound apart from {@code MacRobotCalls} so that a wrong descriptor there does not repeat here.
     */
    private static final class CoreGraphics {

        static final ValueLayout.OfInt CG_EVENT_FIELD = JAVA_INT;

        static final SymbolLookup LOOKUP = SymbolLookup.libraryLookup(
                "/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics", Arena.global());

        static final MethodHandle CG_EVENT_GET_TYPE = bind("CGEventGetType",
                FunctionDescriptor.of(MacRobotCalls.CG_EVENT_TYPE, ADDRESS));
        static final MethodHandle CG_EVENT_GET_LOCATION = bind("CGEventGetLocation",
                FunctionDescriptor.of(MacRobotCalls.CG_POINT, ADDRESS));
        static final MethodHandle CG_EVENT_GET_INTEGER_VALUE_FIELD = bind("CGEventGetIntegerValueField",
                FunctionDescriptor.of(JAVA_LONG, ADDRESS, CG_EVENT_FIELD));
        static final MethodHandle CG_EVENT_CREATE_DATA = bind("CGEventCreateData",
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
        static final MethodHandle CG_EVENT_CREATE_MOUSE_EVENT = bind("CGEventCreateMouseEvent",
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, MacRobotCalls.CG_POINT, JAVA_INT));
        // Variadic after wheel1, as MacRobotCalls binds it: (source, units, wheelCount, wheel1, ...).
        static final MethodHandle CG_EVENT_CREATE_SCROLL_WHEEL_EVENT = bind("CGEventCreateScrollWheelEvent",
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT),
                Linker.Option.firstVariadicArg(4));

        private static MethodHandle bind(String name, FunctionDescriptor descriptor, Linker.Option... options) {
            return LINKER.downcallHandle(LOOKUP.findOrThrow(name), descriptor, options);
        }
    }

    private static final class CoreFoundation {

        static final SymbolLookup LOOKUP = SymbolLookup.libraryLookup(
                "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation", Arena.global());

        static final MethodHandle CF_DATA_GET_LENGTH = bind("CFDataGetLength",
                FunctionDescriptor.of(JAVA_LONG, ADDRESS));
        static final MethodHandle CF_DATA_GET_BYTE_PTR = bind("CFDataGetBytePtr",
                FunctionDescriptor.of(ADDRESS, ADDRESS));
        static final MethodHandle CF_STRING_CREATE_WITH_CHARACTERS = bind("CFStringCreateWithCharacters",
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG));
        // CFURLPathStyle is a CFIndex and Boolean an unsigned char.
        static final MethodHandle CF_URL_CREATE_WITH_FILE_SYSTEM_PATH = bind("CFURLCreateWithFileSystemPath",
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, JAVA_BOOLEAN));
        static final MethodHandle CF_GET_RETAIN_COUNT = bind("CFGetRetainCount",
                FunctionDescriptor.of(JAVA_LONG, ADDRESS));
        static final MethodHandle CF_RETAIN = bind("CFRetain", FunctionDescriptor.of(ADDRESS, ADDRESS));
        static final MethodHandle CF_RELEASE = bind("CFRelease", FunctionDescriptor.ofVoid(ADDRESS));
        static final MethodHandle CF_URL_START_ACCESSING_SECURITY_SCOPED_RESOURCE = bind(
                "CFURLStartAccessingSecurityScopedResource", FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS));
        static final MethodHandle CF_URL_STOP_ACCESSING_SECURITY_SCOPED_RESOURCE = bind(
                "CFURLStopAccessingSecurityScopedResource", FunctionDescriptor.ofVoid(ADDRESS));
        static final MethodHandle CF_LOCALE_COPY_PREFERRED_LANGUAGES = bind("CFLocaleCopyPreferredLanguages",
                FunctionDescriptor.of(ADDRESS));
        static final MethodHandle CF_ARRAY_GET_COUNT = bind("CFArrayGetCount",
                FunctionDescriptor.of(JAVA_LONG, ADDRESS));
        static final MethodHandle CF_ARRAY_GET_VALUE_AT_INDEX = bind("CFArrayGetValueAtIndex",
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG));
        static final MethodHandle CF_STRING_CREATE_MUTABLE_COPY = bind("CFStringCreateMutableCopy",
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG, ADDRESS));
        // CFNumberType is a CFIndex.
        static final MethodHandle CF_NUMBER_CREATE = bind("CFNumberCreate",
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG, ADDRESS));
        static final MethodHandle CF_ARRAY_CREATE = bind("CFArrayCreate",
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS));
        static final MethodHandle CF_DATE_GET_ABSOLUTE_TIME = bind("CFDateGetAbsoluteTime",
                FunctionDescriptor.of(JAVA_DOUBLE, ADDRESS));
        static final MethodHandle CF_DICTIONARY_GET_COUNT = bind("CFDictionaryGetCount",
                FunctionDescriptor.of(JAVA_LONG, ADDRESS));
        static final MethodHandle CF_DICTIONARY_GET_VALUE = bind("CFDictionaryGetValue",
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
        static final MethodHandle CF_ATTRIBUTED_STRING_GET_STRING = bind("CFAttributedStringGetString",
                FunctionDescriptor.of(ADDRESS, ADDRESS));
        static final MethodHandle CF_ATTRIBUTED_STRING_GET_ATTRIBUTE = bind("CFAttributedStringGetAttribute",
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS));

        private static MethodHandle bind(String name, FunctionDescriptor descriptor) {
            return LINKER.downcallHandle(LOOKUP.findOrThrow(name), descriptor);
        }
    }
}
