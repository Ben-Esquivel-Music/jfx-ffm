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

package com.sun.glass.ui.gtk;

import com.sun.glass.ui.Application;
import com.sun.glass.ui.Clipboard;
import com.sun.glass.ui.CommonDialogs;
import com.sun.glass.ui.Pixels;
import com.sun.glass.ui.Screen;
import com.sun.glass.ui.Size;
import java.lang.foreign.AddressLayout;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static java.lang.foreign.ValueLayout.ADDRESS;

/**
 * The GTK, GDK and GLib calls of the Linux glass peers that used to sit behind JNI in {@code libglassgtk3.so},
 * bound from Java with {@code java.lang.foreign}. Every method of the binding replaces the body of a
 * {@code Java_com_sun_glass_ui_gtk_*} function of commit {@code 033187ad90}, either one whose C did nothing but marshal
 * around system calls or one whose body is now a function of the flat C ABI of {@code libglassgtk3.so}; its javadoc
 * names that function and the file it was in, under {@code modules/javafx.graphics/src/main/native-glass/gtk}. The
 * peers call this class and nothing else, and every restricted {@code java.lang.foreign} call of the package is in this
 * file: in this class and in its nested {@link Loader}.
 * <p>
 * Most methods delegate to a package-private aggregate named after the peer or the C file it serves
 * ({@link GtkWindowCalls}, {@link GtkClipboardCalls}, ...), which holds the bodies, their javadoc and its own
 * {@code static final} handles, made through {@link #bind}. {@link GtkCallbackTables} holds the callback tables and the
 * holder of the {@code libglassgtk3.so} exports, {@link GtkCallbackTargets} the targets of the tables' upcall stubs.
 * The library query is not an aggregate: its holder, {@link GtkLibraryLoader}, runs before this class initializes, and
 * of this file it uses only the nested {@link Loader}.
 *
 * <h2>Libraries</h2>
 * At commit {@code 033187ad90} {@code libglassgtk3.so} was linked against every soname below, and was loaded
 * only after {@code _queryLibrary} of {@code libglass.so} had {@code dlopen}ed {@code libgtk-3.so.0} with
 * {@code RTLD_GLOBAL}. This class opens the same sonames with {@link SymbolLookup#libraryLookup} in
 * {@link Arena#global()}. For the GTK, GDK, GLib, GIO and X11 libraries, which the remaining C still links, that
 * is a {@code dlopen} of a library that is already mapped: the dynamic linker hands back the instance the process
 * has, so the GTK state these calls see is the state the remaining C sees. {@code libXtst.so.6} is the exception:
 * only the robot C called it, {@code libglassgtk3.so} no longer needs it, and this class is the first to map it.
 * <p>
 * All libraries are opened when this class initializes, and every symbol but the {@code ggtk_*} exports is resolved
 * by {@link #link}, which initializes the aggregates; a missing one is an {@link UnsatisfiedLinkError} naming the
 * library or the symbol.
 * {@code GtkApplication}'s constructor calls {@link #link} right after it has called {@code _queryLibrary} and loaded
 * {@code glassgtk3}, and before {@code _initGTK}: that is where a system library missing from the machine made loading
 * {@code glassgtk3} fail before, and where it still fails the toolkit's startup. Neither this class nor an aggregate
 * may be initialized earlier.
 */
final class GtkGlassNative {

    static final String LIB_GTK = "libgtk-3.so.0";
    static final String LIB_GDK = "libgdk-3.so.0";
    static final String LIB_GDK_PIXBUF = "libgdk_pixbuf-2.0.so.0";
    static final String LIB_GOBJECT = "libgobject-2.0.so.0";
    static final String LIB_GLIB = "libglib-2.0.so.0";
    static final String LIB_GIO = "libgio-2.0.so.0";
    static final String LIB_X11 = "libX11.so.6";
    static final String LIB_XTST = "libXtst.so.6";

    /** The C library, through {@link Linker#defaultLookup()}; the name only labels its symbols. */
    static final String LIB_C = "libc.so.6";

    /**
     * {@code GLASS_GTK_ABI_VERSION} of {@code glass_gtk_api.h}, the revision this class is written against:
     * {@code ggtk_abi_version} must answer it, or the library is refused ({@link #installCallbacks}).
     */
    static final int ABI_VERSION = 1;

    /** The label of the symbols of {@code libglassgtk3.so} in {@link #boundSymbols}. */
    static final String LIB_GLASS = "libglassgtk3.so";

    private static final Linker LINKER = Linker.nativeLinker();

    /** {@code library!symbol} for every symbol bound, in binding order, with its address; read by tests. */
    private static final Map<String, Long> BOUND = Collections.synchronizedMap(new LinkedHashMap<>());

    static final SymbolLookup GTK = library(LIB_GTK);
    static final SymbolLookup GDK = library(LIB_GDK);
    static final SymbolLookup GDK_PIXBUF = library(LIB_GDK_PIXBUF);
    static final SymbolLookup GOBJECT = library(LIB_GOBJECT);
    static final SymbolLookup GLIB = library(LIB_GLIB);
    static final SymbolLookup GIO = library(LIB_GIO);
    static final SymbolLookup X11 = library(LIB_X11);
    static final SymbolLookup XTST = library(LIB_XTST);
    static final SymbolLookup LIBC = LINKER.defaultLookup();

    private GtkGlassNative() {
    }

    /**
     * Each aggregate below binds its symbols when it initializes - here, in this order, which is the order
     * {@link #boundSymbols} has always listed them in. Called by the {@code GtkApplication} constructor once the
     * glass GTK library is loaded - right after {@code NativeLibLoader.loadLibrary("glassgtk3")}, or on
     * {@code QUERY_USE_CURRENT} without it: at commit {@code 033187ad90} {@code libglassgtk3.so} needed
     * {@code libXtst.so.6}, so on a machine without it that load threw and the toolkit's startup failed there. Bound
     * lazily instead, an aggregate would fail first on the invoke-later thread or in the pulse timer, where nothing
     * reports it, and the startup would never finish. The {@code ggtk_*} exports are bound by {@link #installCallbacks}
     * ({@link GtkCallbackTables.Glass}), and the library query's holder, {@link GtkLibraryLoader}, initializes with the
     * query, before this call.
     *
     * @throws UnsatisfiedLinkError if a library or a symbol is missing
     */
    static void link() {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        try {
            for (Class<?> aggregate : List.of(GtkMainLoopCalls.class, GtkPixelsCalls.class, GtkRobotCalls.class,
                    GtkKeyCalls.class, GtkApplicationCalls.class, GtkScreenCalls.class, GtkPreferenceCalls.class,
                    GtkClipboardCalls.class, GtkClipboardReadCalls.class, GtkDialogCalls.class,
                    GtkCallbackTables.class, GtkWindowCalls.class, GtkViewCalls.class, GtkDndCalls.class,
                    GtkCallbackTargets.class)) {
                lookup.ensureInitialized(aggregate);
            }
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * The entry point of the query for the glass GTK library, which {@code GtkApplication} runs before {@link #link},
     * and the query's linker, symbol census and two restricted calls, which stay in this file; the query itself is its
     * holder {@link GtkLibraryLoader}. This class's initializer reaches only JDK classes: calling it initializes
     * neither {@code GtkGlassNative} nor a class {@link #link} lists.
     */
    static final class Loader {

        static final int QUERY_ERROR = GtkLibraryLoader.QUERY_ERROR;
        static final int QUERY_NO_DISPLAY = GtkLibraryLoader.QUERY_NO_DISPLAY;
        static final int QUERY_USE_CURRENT = GtkLibraryLoader.QUERY_USE_CURRENT;
        static final int QUERY_LOAD_GTK3 = GtkLibraryLoader.QUERY_LOAD_GTK3;

        static final Linker LINKER = Linker.nativeLinker();

        /** {@code library!symbol} for every symbol the query binds, in binding order; read by tests. */
        static final Map<String, Long> BOUND = Collections.synchronizedMap(new LinkedHashMap<>());

        private Loader() {
        }

        static int queryLibrary(int version, boolean verbose) {
            return GtkLibraryLoader.queryLibrary(version, verbose);
        }

        static List<String> boundSymbols() {
            synchronized (BOUND) {
                return new ArrayList<>(BOUND.keySet());
            }
        }

        @SuppressWarnings("restricted")
        static SymbolLookup libraryLookup(String name, Arena arena) {
            return SymbolLookup.libraryLookup(name, arena);
        }

        @SuppressWarnings("restricted")
        static MethodHandle downcallHandle(Linker linker, MemorySegment symbol, FunctionDescriptor descriptor) {
            return linker.downcallHandle(symbol, descriptor);
        }
    }

    /**
     * What {@code ggtk_window_set_view} answers: {@code set_view}'s result, and the id of the view the window holds
     * now (0 for none), which the C hands back so that nothing reads a {@code WindowContext} after the {@code EXIT}
     * handler inside the call may have destroyed it.
     */
    record SetView(boolean result, long viewId) {
    }

    /**
     * The native block a view's frames are copied into before {@code ggtk_view_upload_pixels_int} or {@code _byte}
     * paints them ({@link #viewUploadPixelsInt}): allocated at the view's first upload from a Java array, grown - never
     * shrunk - when a frame is larger, freed by {@link #release} when the view closes. The C keeps no pointer to it
     * once the paint has returned, as it kept none to the JNI's critical array. A frame is copied in pieces of at
     * most {@link #CHUNK_BYTES}, so that a safepoint requested during the copy waits for one piece at most.
     * <p>
     * Frames are uploaded on the FX thread ({@code View.uploadPixels} checks it); the uploads hold this object's
     * monitor from the copy until the paint has returned, and so does {@link #release}, so a block is never freed
     * under the C.
     */
    static final class UploadStaging {

        static final long CHUNK_BYTES = 1L << 18;

        /** The alignment of the block: more than cairo and pixman ask of image data. */
        private static final long ALIGNMENT = 64;

        private Arena arena;
        private MemorySegment block = MemorySegment.NULL;

        /** Allocating the block can throw {@link OutOfMemoryError}. */
        synchronized MemorySegment copyIn(MemorySegment source, long sourceOffset, long byteSize) {
            if (block.byteSize() < byteSize) {
                long capacity = Math.max(byteSize, block.byteSize() + block.byteSize() / 2);
                release();
                arena = Arena.ofShared();
                block = arena.allocate(capacity, ALIGNMENT);
            }
            for (long done = 0; done < byteSize; done += CHUNK_BYTES) {
                MemorySegment.copy(source, sourceOffset + done, block, done, Math.min(CHUNK_BYTES, byteSize - done));
            }
            return block;
        }

        synchronized void release() {
            if (arena != null) {
                arena.close();
                arena = null;
            }
            block = MemorySegment.NULL;
        }

        /** The size of the block in bytes, 0 when there is none. */
        synchronized long capacity() {
            return block.byteSize();
        }
    }

    /**
     * {@code check_and_clear_exception} ({@code glass_general.cpp}): {@code Application.reportException(t)} on the
     * current thread, and a second {@code ExceptionClear} that swallowed whatever the report itself threw.
     */
    static void reportException(Throwable t) {
        try {
            Application.reportException(t);
        } catch (Throwable ignored) {
            // what the C's second ExceptionClear did
        }
    }

    static List<String> boundSymbols() {
        synchronized (BOUND) {
            return new ArrayList<>(BOUND.keySet());
        }
    }

    /** The address {@code library!symbol} was bound at, or 0 if this class did not bind it. */
    static long boundAddress(String qualifiedName) {
        Long address = BOUND.get(qualifiedName);
        return address == null ? 0L : address;
    }

    @SuppressWarnings("restricted")
    private static SymbolLookup library(String soname) {
        try {
            return SymbolLookup.libraryLookup(soname, Arena.global());
        } catch (IllegalArgumentException e) {
            UnsatisfiedLinkError error = new UnsatisfiedLinkError("cannot open " + soname + ": " + e.getMessage());
            error.initCause(e);
            throw error;
        }
    }

    /** Resolves {@code name} in {@code lookup} and links it as an ordinary (never critical) downcall. */
    @SuppressWarnings("restricted")
    static MethodHandle bind(SymbolLookup lookup, String library, String name, FunctionDescriptor descriptor,
                             Linker.Option... options) {
        MemorySegment symbol = lookup.find(name).orElseThrow(
                () -> new UnsatisfiedLinkError(library + " does not export " + name));
        BOUND.put(library + "!" + name, symbol.address());
        return LINKER.downcallHandle(symbol, descriptor, options);
    }

    static MemorySegment address(SymbolLookup lookup, String library, String name) {
        MemorySegment symbol = lookup.find(name).orElseThrow(
                () -> new UnsatisfiedLinkError(library + " does not export " + name));
        BOUND.put(library + "!" + name, symbol.address());
        return symbol;
    }

    /** Resolves {@code name} in {@code lookup} as a plain address, or {@code NULL} when the library lacks it. */
    static MemorySegment optional(SymbolLookup lookup, String library, String name) {
        MemorySegment symbol = lookup.find(name).orElse(MemorySegment.NULL);
        if (symbol.address() != 0) {
            BOUND.put(library + "!" + name, symbol.address());
        }
        return symbol;
    }

    /** The {@code stderr} variable of libc, as an 8-byte segment holding the {@code FILE *}. */
    @SuppressWarnings("restricted")
    static MemorySegment stderrVariable() {
        MemorySegment symbol = LIBC.find("stderr").orElseThrow(
                () -> new UnsatisfiedLinkError(LIB_C + " does not export stderr"));
        BOUND.put(LIB_C + "!stderr", symbol.address());
        return symbol.reinterpret(ADDRESS.byteSize());
    }

    /** A downcall handle whose target address is its first argument, for symbols that may be absent. */
    @SuppressWarnings("restricted")
    static MethodHandle addresslessDowncall(FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(descriptor);
    }

    /** An upcall stub in {@link Arena#global()} for the static method {@code name} of {@code owner}. */
    @SuppressWarnings("restricted")
    static MemorySegment upcallStub(Class<?> owner, String name, FunctionDescriptor descriptor) {
        try {
            MethodHandle target = MethodHandles.lookup().findStatic(owner, name,
                    descriptor.toMethodType());
            return LINKER.upcallStub(target, descriptor, Arena.global());
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @SuppressWarnings("restricted")
    static AddressLayout pointerTo(MemoryLayout target) {
        return ADDRESS.withTargetLayout(target);
    }

    /**
     * {@code segment} resized to {@code byteSize}, for the aggregates: {@link MemorySegment#reinterpret(long)} is
     * restricted, so this file makes that call for all of them.
     */
    @SuppressWarnings("restricted")
    static MemorySegment bounded(MemorySegment segment, long byteSize) {
        return segment.reinterpret(byteSize);
    }

    /**
     * Throws {@code t} unchanged, checked or not: what a JNI native method did with an exception that native code
     * left pending when it returned.
     */
    @SuppressWarnings("unchecked")
    static <T extends Throwable> RuntimeException rethrow(Throwable t) throws T {
        throw (T) t;
    }

    /**
     * What to throw for a {@code Throwable} caught around the downcalls of the binding: a {@link RuntimeException}
     * or an {@link Error} as it is, anything else wrapped in an {@link IllegalStateException}.
     * <p>
     * Such a {@code Throwable} is a failure to link or invoke the handle, or an exception the method itself throws
     * inside its {@code try} block on purpose - the {@code IllegalStateException} of
     * {@link GtkDndCalls#dndMimesFromSystem} and {@link GtkDndCalls#dndPopFromSystem} outside a drag - which passes
     * through unchanged. Nothing else can come out of a downcall: the functions that run a nested GTK main loop or
     * call Java otherwise ({@code gtk_main}, the {@code gtk_clipboard_wait_for_*} calls, {@code gtk_native_dialog_run}
     * and the {@code ggtk_*} functions marked UPCALLS) reach Java only through upcall stubs, which let nothing out.
     * Where the C went on to free what it had allocated, the {@code finally} blocks around those calls free the same
     * things if one is thrown all the same.
     */
    static RuntimeException unexpected(Throwable t) {
        if (t instanceof RuntimeException e) {
            return e;
        }
        if (t instanceof Error e) {
            throw e;
        }
        return new IllegalStateException(t);
    }

    /* The entry points whose bodies are in an aggregate, in the order of the original binding. */

    static long timerStart(Runnable runnable, int period) {
        return GtkMainLoopCalls.timerStart(runnable, period);
    }

    static void timerStop(long timer) {
        GtkMainLoopCalls.timerStop(timer);
    }

    static void submitForLaterInvocation(Runnable runnable) {
        GtkMainLoopCalls.submitForLaterInvocation(runnable);
    }

    static void enterNestedEventLoop() {
        GtkMainLoopCalls.enterNestedEventLoop();
    }

    static void leaveNestedEventLoop() {
        GtkMainLoopCalls.leaveNestedEventLoop();
    }

    static void pixelsAttachInt(long ptr, int w, int h, IntBuffer ints, int[] array, int offset) {
        GtkPixelsCalls.pixelsAttachInt(ptr, w, h, ints, array, offset);
    }

    static void pixelsAttachByte(long ptr, int w, int h, ByteBuffer bytes, byte[] array, int offset) {
        GtkPixelsCalls.pixelsAttachByte(ptr, w, h, bytes, array, offset);
    }

    static long cursorCreate(int x, int y, Pixels pixels) {
        return GtkPixelsCalls.cursorCreate(x, y, pixels);
    }

    static Size cursorBestSize(int width, int height) {
        return GtkPixelsCalls.cursorBestSize(width, height);
    }

    static void robotKeyPress(int code) {
        GtkRobotCalls.robotKeyPress(code);
    }

    static void robotKeyRelease(int code) {
        GtkRobotCalls.robotKeyRelease(code);
    }

    static void robotMouseMove(int x, int y) {
        GtkRobotCalls.robotMouseMove(x, y);
    }

    static void robotMousePress(int buttons) {
        GtkRobotCalls.robotMousePress(buttons);
    }

    static void robotMouseRelease(int buttons) {
        GtkRobotCalls.robotMouseRelease(buttons);
    }

    static void robotMouseWheel(int amt) {
        GtkRobotCalls.robotMouseWheel(amt);
    }

    static int robotGetMouseX() {
        return GtkRobotCalls.robotGetMouseX();
    }

    static int robotGetMouseY() {
        return GtkRobotCalls.robotGetMouseY();
    }

    static void robotGetScreenCapture(int x, int y, int width, int height, int[] data) {
        GtkRobotCalls.robotGetScreenCapture(x, y, width, height, data);
    }

    static int isKeyLocked(int keyCode) {
        return GtkKeyCalls.isKeyLocked(keyCode);
    }

    static int openURI(String uri) {
        return GtkApplicationCalls.openURI(uri);
    }

    static long multiClickTime() {
        return GtkApplicationCalls.multiClickTime();
    }

    static int multiClickMaxX() {
        return GtkApplicationCalls.multiClickMaxX();
    }

    static boolean supportsTransparentWindows() {
        return GtkApplicationCalls.supportsTransparentWindows();
    }

    static Screen[] screens() {
        return GtkScreenCalls.screens();
    }

    static void platformSupportCreate(GtkApplication application) {
        GtkPreferenceCalls.platformSupportCreate(application);
    }

    static void platformSupportDestroy() {
        GtkPreferenceCalls.platformSupportDestroy();
    }

    static Map<String, Object> platformPreferences() {
        return GtkPreferenceCalls.platformPreferences();
    }

    static void clipboardInit(Clipboard peer) {
        GtkClipboardCalls.clipboardInit(peer);
    }

    static void clipboardDispose() {
        GtkClipboardCalls.clipboardDispose();
    }

    static boolean clipboardIsOwner() {
        return GtkClipboardCalls.clipboardIsOwner();
    }

    static void clipboardPush(Map<String, Object> data) {
        GtkClipboardCalls.clipboardPush(data);
    }

    static Object clipboardPop(String mime) {
        return GtkClipboardReadCalls.clipboardPop(mime);
    }

    static String[] clipboardMimes() {
        return GtkClipboardReadCalls.clipboardMimes();
    }

    static CommonDialogs.FileChooserResult showFileChooser(long parent, String folder, String name, String title,
                                                           int type, boolean multiple,
                                                           CommonDialogs.ExtensionFilter[] filters,
                                                           int defaultFilterIndex) {
        return GtkDialogCalls.showFileChooser(parent, folder, name, title, type, multiple, filters, defaultFilterIndex);
    }

    static String showFolderChooser(long parent, String folder, String title) {
        return GtkDialogCalls.showFolderChooser(parent, folder, title);
    }

    static void installCallbacks() {
        GtkCallbackTables.installCallbacks();
    }

    static boolean callbacksInstalled() {
        return GtkCallbackTables.callbacksInstalled();
    }

    static void applicationInitGtk(int version, boolean verbose, float uiScale) {
        GtkApplicationCalls.applicationInitGtk(version, verbose, uiScale);
    }

    static void applicationInit(long eventHandler, boolean disableGrab) {
        GtkApplicationCalls.applicationInit(eventHandler, disableGrab);
    }

    static void applicationRunLoop(boolean noErrorTrap) {
        GtkApplicationCalls.applicationRunLoop(noErrorTrap);
    }

    static void applicationTerminateLoop() {
        GtkApplicationCalls.applicationTerminateLoop();
    }

    static int applicationKeyCodeForChar(char c, int hint) {
        return GtkApplicationCalls.applicationKeyCodeForChar(c, hint);
    }

    static long windowCreate(long owner, long screen, int mask, long visualId, long windowId) {
        return GtkWindowCalls.windowCreate(owner, screen, mask, visualId, windowId);
    }

    static boolean windowClose(long window) {
        return GtkWindowCalls.windowClose(window);
    }

    static SetView windowSetView(long window, long view) {
        return GtkWindowCalls.windowSetView(window, view);
    }

    static void windowUpdateViewSize(long window) {
        GtkWindowCalls.windowUpdateViewSize(window);
    }

    static void windowMinimize(long window, boolean minimize) {
        GtkWindowCalls.windowMinimize(window, minimize);
    }

    static void windowMaximize(long window, boolean maximize, boolean wasMaximized) {
        GtkWindowCalls.windowMaximize(window, maximize, wasMaximized);
    }

    static void windowSetBounds(long window, int x, int y, boolean xSet, boolean ySet, int w, int h, int cw, int ch,
                                float xGravity, float yGravity) {
        GtkWindowCalls.windowSetBounds(window, x, y, xSet, ySet, w, h, cw, ch, xGravity, yGravity);
    }

    static void windowSetVisible(long window, boolean visible) {
        GtkWindowCalls.windowSetVisible(window, visible);
    }

    static boolean windowSetResizable(long window, boolean resizable) {
        return GtkWindowCalls.windowSetResizable(window, resizable);
    }

    static boolean windowRequestFocus(long window, int event) {
        return GtkWindowCalls.windowRequestFocus(window, event);
    }

    static void windowSetFocusable(long window, boolean focusable) {
        GtkWindowCalls.windowSetFocusable(window, focusable);
    }

    static boolean windowGrabFocus(long window) {
        return GtkWindowCalls.windowGrabFocus(window);
    }

    static void windowUngrabFocus(long window) {
        GtkWindowCalls.windowUngrabFocus(window);
    }

    static boolean windowSetTitle(long window, String title) {
        return GtkWindowCalls.windowSetTitle(window, title);
    }

    static void windowSetLevel(long window, int level) {
        GtkWindowCalls.windowSetLevel(window, level);
    }

    static void windowSetAlpha(long window, float alpha) {
        GtkWindowCalls.windowSetAlpha(window, alpha);
    }

    static boolean windowSetBackground(long window, float r, float g, float b) {
        return GtkWindowCalls.windowSetBackground(window, r, g, b);
    }

    static void windowSetEnabled(long window, boolean enabled) {
        GtkWindowCalls.windowSetEnabled(window, enabled);
    }

    static boolean windowSetMinimumSize(long window, int w, int h) {
        return GtkWindowCalls.windowSetMinimumSize(window, w, h);
    }

    static boolean windowSetSystemMinimumSize(long window, int w, int h) {
        return GtkWindowCalls.windowSetSystemMinimumSize(window, w, h);
    }

    static boolean windowSetMaximumSize(long window, int w, int h) {
        return GtkWindowCalls.windowSetMaximumSize(window, w, h);
    }

    static void windowSetIcon(long window, Pixels pixels) {
        GtkWindowCalls.windowSetIcon(window, pixels);
    }

    static void windowToFront(long window) {
        GtkWindowCalls.windowToFront(window);
    }

    static void windowToBack(long window) {
        GtkWindowCalls.windowToBack(window);
    }

    static void windowSetCursorType(long window, int type) {
        GtkWindowCalls.windowSetCursorType(window, type);
    }

    static void windowSetCursor(long window, long cursor) {
        GtkWindowCalls.windowSetCursor(window, cursor);
    }

    static void windowShowSystemMenu(long window, int x, int y) {
        GtkWindowCalls.windowShowSystemMenu(window, x, y);
    }

    static boolean windowIsVisible(long window) {
        return GtkWindowCalls.windowIsVisible(window);
    }

    static long windowNativeWindow(long window) {
        return GtkWindowCalls.windowNativeWindow(window);
    }

    static void viewEnableInputMethodEvents(long view, boolean enable) {
        GtkViewCalls.viewEnableInputMethodEvents(view, enable);
    }

    static long viewCreate(long viewId) {
        return GtkViewCalls.viewCreate(viewId);
    }

    static int viewX(long view) {
        return GtkViewCalls.viewX(view);
    }

    static int viewY(long view) {
        return GtkViewCalls.viewY(view);
    }

    static void viewSetParent(long view, long parent) {
        GtkViewCalls.viewSetParent(view, parent);
    }

    static boolean viewClose(long view) {
        return GtkViewCalls.viewClose(view);
    }

    static void viewUploadPixelsDirect(long view, Buffer pixels, int width, int height) {
        GtkViewCalls.viewUploadPixelsDirect(view, pixels, width, height);
    }

    static void viewUploadPixelsInt(long view, UploadStaging staging, int[] pixels, int offset, int width,
                                    int height) {
        GtkViewCalls.viewUploadPixelsInt(view, staging, pixels, offset, width, height);
    }

    static void viewUploadPixelsByte(long view, UploadStaging staging, byte[] pixels, int offset, int width,
                                     int height) {
        GtkViewCalls.viewUploadPixelsByte(view, staging, pixels, offset, width, height);
    }

    static boolean viewEnterFullscreen(long view, boolean animate, boolean keepRatio, boolean hideCursor) {
        return GtkViewCalls.viewEnterFullscreen(view, animate, keepRatio, hideCursor);
    }

    static void viewExitFullscreen(long view, boolean animate) {
        GtkViewCalls.viewExitFullscreen(view, animate);
    }

    static boolean dndIsOwner() {
        return GtkDndCalls.dndIsOwner();
    }

    static int dndPushToSystem(HashMap<String, Object> data, int supported) {
        return GtkDndCalls.dndPushToSystem(data, supported);
    }

    static int dndSupportedSourceActions() {
        return GtkDndCalls.dndSupportedSourceActions();
    }

    static String[] dndMimesFromSystem() {
        return GtkDndCalls.dndMimesFromSystem();
    }

    static Object dndPopFromSystem(String mimeType) {
        return GtkDndCalls.dndPopFromSystem(mimeType);
    }
}
