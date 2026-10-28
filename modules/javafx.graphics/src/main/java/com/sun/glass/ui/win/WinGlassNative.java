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

package com.sun.glass.ui.win;

import com.sun.glass.ui.Pixels;
import com.sun.glass.utils.NativeLibLoader;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.nio.Buffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * The single point of contact between {@code com.sun.glass.ui.win} and native code: the {@code gwin_*}
 * C ABI of {@code glass.dll} ({@code src/main/native-glass/win/glass_win_api.h}) and the Win32
 * functions the deleted JNI bodies called. It owns the library load, the eager {@link SymbolLookup}s, the
 * {@link Linker}, the symbol list and the ABI check, and it is the only class in this package that uses a
 * restricted {@code java.lang.foreign} method in product code (the test-only shim {@code WinGlassNativeShim}
 * binds a handful of read-back symbols of its own).
 * <p>
 * The peers call this class and nothing else. Most of its methods delegate to a package-private aggregate
 * named after the peer it serves: {@code Win<Peer>Calls} holds a peer's downcalls, {@code Win<Peer>Callbacks} a
 * callback table with its descriptors, its install and its upcall targets, and {@link WinCallSupport} what
 * several of them share. Each aggregate holds its own handles and layouts, bound through {@link #bindGlass} and
 * its siblings, so this class's initializer - the library load and the ABI check - completes before any aggregate
 * binds a symbol; {@link #ensureLoaded} lists every aggregate. The one table installed from here is the clipboard
 * and drag pair, at the end of this class's initializer (see {@link WinClipboardCallbacks}).
 */
final class WinGlassNative {

    /**
     * The {@code gwin_*} ABI revision this class is written against ({@code GLASS_WIN_ABI_VERSION}). 5 adds
     * six exports, with {@code gwin_robot_pixel_color} and {@code gwin_test_screen_anchor} defined as
     * test-only - this class never bound either, and both have since been deleted without a bump.
     * <p>
     * 6 is the accessibility section: thirteen exports and two callback tables, of which this class binds
     * ten - {@code gwin_test_fire_accessible_callback}, {@code gwin_test_fire_text_range_callback} and
     * {@code gwin_test_variant_offsets} are test hooks the shim binds and this class does not. They landed
     * additive under 5, and this class binding them changed nothing a mismatched pair could not already
     * detect: a {@code glass.dll} without those exports fails in {@link #bindGlass} by name. What the bump
     * to 6 protects is the other direction, a {@code glass.dll} whose {@code Java_*} arms are gone meeting
     * a Java side that installs no tables, and that shape first existed when those arms were deleted. The
     * bump therefore travelled with that deletion: this literal and {@code GLASS_WIN_ABI_VERSION} moved in
     * one change set, because the check below is an exact equality in both directions.
     */
    static final int ABI_VERSION = 6;

    static final String LIBRARY_NAME = "glass";
    static final String USER32_NAME = "user32";
    static final String KERNEL32_NAME = "kernel32";
    static final String OLE32_NAME = "ole32";

    private static final Linker LINKER = Linker.nativeLinker();
    /**
     * Copy-on-write because the aggregates and the lazy holders bind after this class's initializer, on
     * whichever thread first uses them: such an append can otherwise race a {@link #boundSymbols()} read on
     * another thread.
     */
    private static final List<String> BOUND_SYMBOLS = new CopyOnWriteArrayList<>();

    static final SymbolLookup GLASS;

    static {
        // The library has to be loaded by this class loader before loaderLookup() can see it.
        // WinApplication loads it too; NativeLibLoader and System.loadLibrary are both idempotent.
        NativeLibLoader.loadLibrary(LIBRARY_NAME);
        GLASS = SymbolLookup.loaderLookup();
    }

    /** A system DLL the JDK does not load for us, looked up once for the life of the process. */
    static final SymbolLookup USER32 = systemLibrary(USER32_NAME + ".dll");

    /**
     * Eager, like {@code user32}: {@code kernel32} is mapped into every Win32 process before any Java code runs,
     * so there is nothing to defer and nothing that can fail here that has not failed already.
     */
    static final SymbolLookup KERNEL32 = systemLibrary(KERNEL32_NAME + ".dll");

    /**
     * {@code ole32.dll}, for {@code OleIsCurrentClipboard}. Eager, like {@code user32}: {@code glass.dll}
     * imports {@code ole32} itself, so it is mapped before this class can load, and a lookup that cannot fail
     * has nothing to defer.
     */
    static final SymbolLookup OLE32 = systemLibrary(OLE32_NAME + ".dll");

    /* Binding order matters: the ABI guard is bound and checked before any other gwin_* symbol. */
    private static final MethodHandle GWIN_ABI_VERSION = bindGlass("gwin_abi_version",
            FunctionDescriptor.of(JAVA_INT));

    static {
        checkAbiVersion();
    }

    /** {@code int32_t gwin_clipboard_set_callbacks(const GwinClipboardCallbacks* cb)} - copied by value, always OK. */
    static final MethodHandle GWIN_CLIPBOARD_SET_CALLBACKS = bindGlass("gwin_clipboard_set_callbacks",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** {@code int32_t gwin_dnd_set_callbacks(const GwinDndCallbacks* cb)} - copied by value, always OK. */
    static final MethodHandle GWIN_DND_SET_CALLBACKS = bindGlass("gwin_dnd_set_callbacks",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    private WinGlassNative() {
    }

    @SuppressWarnings("restricted")
    static SymbolLookup systemLibrary(String fileName) {
        return SymbolLookup.libraryLookup(fileName, Arena.global());
    }

    /**
     * A system DLL loaded by full path, for the life of the process: the restricted half of
     * {@code WinScreenCalls.Shcore}'s load, which takes {@code SHCore.dll} from the directory
     * {@code LoadDPIFuncs} took it from.
     */
    @SuppressWarnings("restricted")
    static SymbolLookup systemLibrary(Path path) {
        return SymbolLookup.libraryLookup(path, Arena.global());
    }

    static MethodHandle bindGlass(String name, FunctionDescriptor descriptor) {
        return bind(GLASS, LIBRARY_NAME, name, descriptor);
    }

    static MethodHandle bindUser32(String name, FunctionDescriptor descriptor) {
        return bind(USER32, USER32_NAME, name, descriptor);
    }

    static MethodHandle bindKernel32(String name, FunctionDescriptor descriptor) {
        return bind(KERNEL32, KERNEL32_NAME, name, descriptor);
    }

    static MethodHandle bindOle32(String name, FunctionDescriptor descriptor) {
        return bind(OLE32, OLE32_NAME, name, descriptor);
    }

    /**
     * No downcall is {@link Linker.Option#critical critical}: the capture blocks on GDI and the Win32 calls pass
     * no Java array (see the class documentation of {@link WinRobotCalls}).
     *
     * @throws UnsatisfiedLinkError if the library does not export {@code name}
     */
    @SuppressWarnings("restricted")
    static MethodHandle bind(SymbolLookup lookup, String library, String name,
                                     FunctionDescriptor descriptor) {
        MemorySegment symbol = lookup.find(name).orElseThrow(
                () -> new UnsatisfiedLinkError("missing native symbol: " + name + " in " + library));
        BOUND_SYMBOLS.add(library + "!" + name);
        return LINKER.downcallHandle(symbol, descriptor);
    }

    /**
     * {@link Linker#upcallStub} is restricted and a field initializer cannot carry {@code @SuppressWarnings}, so
     * the call lives in a method, exactly as {@link #systemLibrary}, {@link #bind}, {@link #bound} and
     * {@link #bounded} hold the other restricted calls.
     */
    @SuppressWarnings("restricted")
    static MemorySegment upcallStub(MethodHandle target, FunctionDescriptor fd, Arena arena) {
        return LINKER.upcallStub(target, fd, arena);
    }

    static MethodHandle upcallTarget(Class<?> owner, String name, FunctionDescriptor descriptor) {
        try {
            return MethodHandles.lookup().findStatic(owner, name, descriptor.toMethodType());
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static void checkAbiVersion() {
        int actual;
        try {
            actual = (int) GWIN_ABI_VERSION.invokeExact();
        } catch (Throwable t) {
            throw unexpected(t);
        }
        if (actual != ABI_VERSION) {
            throw new UnsatisfiedLinkError(LIBRARY_NAME + " ABI version mismatch: expected " + ABI_VERSION
                    + ", found " + actual);
        }
    }

    static Error unexpected(Throwable t) {
        if (t instanceof Error e) {
            return e;
        }
        if (t instanceof RuntimeException e) {
            throw e;
        }
        // Only linkage failures can land here: neither glass.dll nor user32 throws.
        return new AssertionError(t);
    }

    /**
     * Runs this class initializer - loads {@code glass}, checks the ABI version, installs the clipboard and drag
     * tables - and then every aggregate's, in this order, which binds every eager symbol and fixes the order of
     * {@link #boundSymbols()}. The lazy holders stay lazy.
     */
    static void ensureLoaded() {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        try {
            for (Class<?> aggregate : List.of(WinCallSupport.class, WinClipboardCallbacks.class, WinRobotCalls.class,
                    WinCursorCalls.class, WinPreferenceCalls.class, WinApplicationCalls.class, WinTimerCalls.class,
                    WinMenuCalls.class, WinViewCallbacks.class, WinViewCalls.class, WinWindowCallbacks.class,
                    WinWindowCalls.class, WinScreenCalls.class, WinClipboardCalls.class, WinAccessibilityCalls.class,
                    WinAccessibilityMarshalling.class, WinAccessibleCallbacks.class, WinTextRangeCallbacks.class)) {
                lookup.ensureInitialized(aggregate);
            }
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    /** The symbols this class and its aggregates bound, as {@code <library>!<name>}, in binding order. */
    static List<String> boundSymbols() {
        return Collections.unmodifiableList(new ArrayList<>(BOUND_SYMBOLS));
    }

    static int abiVersion() {
        try {
            return (int) GWIN_ABI_VERSION.invokeExact();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * Bounds a C pointer that arrives as a zero-length segment: the {@code void*} {@code CreateDIBSection} wrote
     * into its out-parameter ({@link WinCursorCalls#colourBitmap}) and the {@code lpszDefaultScheme} Windows wrote
     * into {@code HIGHCONTRASTW} ({@link WinPreferenceCalls#schemeName}). {@link MemorySegment#reinterpret} is
     * restricted, which is why this is a method of this class and not an expression in those two.
     */
    @SuppressWarnings("restricted")
    static MemorySegment bound(MemorySegment address, long byteSize) {
        return address.reinterpret(byteSize);
    }

    /**
     * Bounds a borrowed C pointer to {@code byteSize} bytes - the {@code JPEGNative.bounded} pattern;
     * {@code withTargetLayout} is not used for upcall parameters anywhere in this package.
     */
    @SuppressWarnings("restricted")
    static MemorySegment bounded(MemorySegment pointer, long byteSize) {
        return pointer.reinterpret(byteSize);
    }

    /**
     * What {@code SystemParametersInfo(SPI_GETHIGHCONTRAST, ...)} reported, or {@code null} when it
     * returned {@code FALSE} - which the C treated as "put neither key"
     * (the {@code SPI_GETHIGHCONTRAST} arm of {@code PlatformSupport::querySystemParameters}).
     *
     * @param on {@code dwFlags & HCF_HIGHCONTRASTON}
     * @param scheme {@code lpszDefaultScheme}, and {@code null} whenever high contrast is off,
     *               because the C put an explicit null value for the scheme in that branch of
     *               {@code PlatformSupport::querySystemParameters}
     */
    record HighContrast(boolean on, String scheme) {
    }

    /**
     * What {@code gwin_prefs_query_ui_settings} reported (see {@link WinPreferenceCalls#uiSettings}). A false
     * flag means <em>no key at all</em> for its group, never a key with the value {@code false}.
     *
     * @param available false when {@code UISettings} could not be activated or there is no live toolkit
     * @param colors the nine {@code 0xAARRGGBB} values in {@link WinPreferenceCalls#GWIN_UI_COLOR_COUNT} order;
     *               an array, so this record has no useful {@code equals} and nothing compares two of them
     */
    record UiSettings(boolean available, boolean colorsValid, int[] colors, boolean advancedEffectsValid,
                      boolean advancedEffectsEnabled, boolean autoHideValid, boolean autoHideScrollBars) {
    }

    /**
     * What {@code gwin_prefs_query_network} reported (see {@link WinPreferenceCalls#networkInfo}).
     *
     * @param available false when there is no key; true with {@code GWIN_NET_COST_UNKNOWN} is the key "Unknown"
     * @param costType one of the four {@code GWIN_NET_COST_*} values, this ABI's numbering and not WinRT's
     */
    record NetworkInfo(boolean available, int costType) {
    }

    /**
     * What {@code gwin_dialog_file} answered: {@code files} is {@code null} when the paths could not
     * be read (the caller returns a null result), empty for cancel, else the full paths;
     * {@code filterIndex} is the C's {@code (1-based selected index) - 1}, untouched - {@code -1}
     * when the query failed.
     */
    record FileDialogResult(String[] files, int filterIndex) {
    }

    /*
     * The install of the clipboard and drag tables (WinClipboardCallbacks), from THIS class's initializer (the
     * header's installer comment): after both handles they need, before any peer can exist. The last static
     * initializer of the class, so that no stub target can read a field of it that is not set yet.
     */
    static {
        installClipboardCallbacks();
        installDndCallbacks();
    }

    /* The entry points whose bodies are in an aggregate, in the order of ensureLoaded's list. */

    static void installClipboardCallbacks() {
        WinClipboardCallbacks.installClipboardCallbacks();
    }

    static void installDndCallbacks() {
        WinClipboardCallbacks.installDndCallbacks();
    }

    static boolean clipboardCallbacksInstalled() {
        return WinClipboardCallbacks.clipboardCallbacksInstalled();
    }

    static boolean dndCallbacksInstalled() {
        return WinClipboardCallbacks.dndCallbacksInstalled();
    }

    static void reinstallClipboardCallbacks() {
        WinClipboardCallbacks.reinstallClipboardCallbacks();
    }

    static void reinstallDndCallbacks() {
        WinClipboardCallbacks.reinstallDndCallbacks();
    }

    static List<MemorySegment> installedClipboardCallbackStubs() {
        return WinClipboardCallbacks.installedClipboardCallbackStubs();
    }

    static List<MemorySegment> installedDndCallbackStubs() {
        return WinClipboardCallbacks.installedDndCallbackStubs();
    }

    static int keyCodeForChar(short c, int hint) {
        return WinRobotCalls.keyCodeForChar(c, hint);
    }

    static int keyLockState(int keyCode) {
        return WinRobotCalls.keyLockState(keyCode);
    }

    static int getSystemMetrics(int index) {
        return WinRobotCalls.getSystemMetrics(index);
    }

    static void robotKeyPress(int javaKeyCode) {
        WinRobotCalls.robotKeyPress(javaKeyCode);
    }

    static void robotKeyRelease(int javaKeyCode) {
        WinRobotCalls.robotKeyRelease(javaKeyCode);
    }

    static void robotMouseMove(float windowsX, float windowsY) {
        WinRobotCalls.robotMouseMove(windowsX, windowsY);
    }

    static void robotMousePress(int buttons) {
        WinRobotCalls.robotMousePress(buttons);
    }

    static void robotMouseRelease(int buttons) {
        WinRobotCalls.robotMouseRelease(buttons);
    }

    static void robotMouseWheel(int wheelAmt) {
        WinRobotCalls.robotMouseWheel(wheelAmt);
    }

    static int[] cursorPosition() {
        return WinRobotCalls.cursorPosition();
    }

    static int robotCapture(int x, int y, int width, int height, int[] data) {
        return WinRobotCalls.robotCapture(x, y, width, height, data);
    }

    static int robotPixelColorAt(int windowsX, int windowsY) {
        return WinRobotCalls.robotPixelColorAt(windowsX, windowsY);
    }

    static int showCursor(boolean show) {
        return WinCursorCalls.showCursor(show);
    }

    static long cursorCreateCustom(int width, int height, Buffer pixels, int hotspotX, int hotspotY) {
        return WinCursorCalls.cursorCreateCustom(width, height, pixels, hotspotX, hotspotY);
    }

    static long iconCreate(int width, int height, Buffer pixels) {
        return WinCursorCalls.iconCreate(width, height, pixels);
    }

    static long loadSystemCursor(int ordinal) {
        return WinCursorCalls.loadSystemCursor(ordinal);
    }

    static long loadSystemCursor(String name) {
        return WinCursorCalls.loadSystemCursor(name);
    }

    static int sysColor(int index) {
        return WinPreferenceCalls.sysColor(index);
    }

    static int sysColorRed(int colorRef) {
        return WinPreferenceCalls.sysColorRed(colorRef);
    }

    static int sysColorGreen(int colorRef) {
        return WinPreferenceCalls.sysColorGreen(colorRef);
    }

    static int sysColorBlue(int colorRef) {
        return WinPreferenceCalls.sysColorBlue(colorRef);
    }

    static HighContrast highContrast() {
        return WinPreferenceCalls.highContrast();
    }

    static Boolean clientAreaAnimation() {
        return WinPreferenceCalls.clientAreaAnimation();
    }

    static UiSettings uiSettings() {
        return WinPreferenceCalls.uiSettings();
    }

    static NetworkInfo networkInfo() {
        return WinPreferenceCalls.networkInfo();
    }

    static void installPreferencesCallback(Arena stubArena) {
        WinPreferenceCalls.installPreferencesCallback(stubArena);
    }

    static void clearPreferencesCallback() {
        WinPreferenceCalls.clearPreferencesCallback();
    }

    static boolean isWindowsVersionAtLeast(int major, int minor) {
        return WinApplicationCalls.isWindowsVersionAtLeast(major, minor);
    }

    static long appCreate() {
        return WinApplicationCalls.appCreate();
    }

    static String defaultBrowser() {
        return WinApplicationCalls.defaultBrowser();
    }

    static void installApplicationCallback() {
        WinApplicationCalls.installApplicationCallback();
    }

    static void runLoop(Runnable launchable) {
        WinApplicationCalls.runLoop(launchable);
    }

    static void terminateLoop() {
        WinApplicationCalls.terminateLoop();
    }

    static void enterNestedEventLoop() {
        WinApplicationCalls.enterNestedEventLoop();
    }

    static void leaveNestedEventLoop() {
        WinApplicationCalls.leaveNestedEventLoop();
    }

    static void invokeAndWait(Runnable runnable) {
        WinApplicationCalls.invokeAndWait(runnable);
    }

    static void submitForLaterInvocation(Runnable runnable) {
        WinApplicationCalls.submitForLaterInvocation(runnable);
    }

    static int timerMinPeriod() {
        return WinTimerCalls.timerMinPeriod();
    }

    static int timerMaxPeriod() {
        return WinTimerCalls.timerMaxPeriod();
    }

    static long timerStart(Runnable runnable, int period) {
        return WinTimerCalls.timerStart(runnable, period);
    }

    static void timerStop(long id) {
        WinTimerCalls.timerStop(id);
    }

    static long menuCreate() {
        return WinMenuCalls.menuCreate();
    }

    static void menuDestroy(long hMenu) {
        WinMenuCalls.menuDestroy(hMenu);
    }

    static boolean menuInsertItem(long hMenu, int pos, int cmdID, String title,
                                  boolean enabled, boolean checked) {
        return WinMenuCalls.menuInsertItem(hMenu, pos, cmdID, title, enabled, checked);
    }

    static boolean menuInsertSubmenu(long hMenu, int pos, long hSubmenu, String title,
                                     boolean enabled) {
        return WinMenuCalls.menuInsertSubmenu(hMenu, pos, hSubmenu, title, enabled);
    }

    static boolean menuInsertSeparator(long hMenu, int pos) {
        return WinMenuCalls.menuInsertSeparator(hMenu, pos);
    }

    static boolean menuRemoveAtPos(long hMenu, int pos) {
        return WinMenuCalls.menuRemoveAtPos(hMenu, pos);
    }

    static boolean menuSetItemTitle(long hMenu, int cmdID, String title) {
        return WinMenuCalls.menuSetItemTitle(hMenu, cmdID, title);
    }

    static boolean menuSetSubmenuTitle(long hMenu, long hSubmenu, String title) {
        return WinMenuCalls.menuSetSubmenuTitle(hMenu, hSubmenu, title);
    }

    static boolean menuEnableItem(long hMenu, int cmdID, boolean enable) {
        return WinMenuCalls.menuEnableItem(hMenu, cmdID, enable);
    }

    static boolean menuEnableSubmenu(long hMenu, long hSubmenu, boolean enable) {
        return WinMenuCalls.menuEnableSubmenu(hMenu, hSubmenu, enable);
    }

    static boolean menuCheckItem(long hMenu, int cmdID, boolean check) {
        return WinMenuCalls.menuCheckItem(hMenu, cmdID, check);
    }

    static void installMenuCallback() {
        WinMenuCalls.installMenuCallback();
    }

    static void installViewCallbacks() {
        WinViewCallbacks.installViewCallbacks();
    }

    static long viewCreate(long viewId) {
        return WinViewCalls.viewCreate(viewId);
    }

    static boolean viewClose(long view) {
        return WinViewCalls.viewClose(view);
    }

    static long viewGetNativeView(long view) {
        return WinViewCalls.viewGetNativeView(view);
    }

    static void viewSetParent(long view, long parentHwnd) {
        WinViewCalls.viewSetParent(view, parentHwnd);
    }

    static int viewGetX(long view) {
        return WinViewCalls.viewGetX(view);
    }

    static int viewGetY(long view) {
        return WinViewCalls.viewGetY(view);
    }

    static void viewScheduleRepaint(long view) {
        WinViewCalls.viewScheduleRepaint(view);
    }

    static void viewUploadPixels(long view, Pixels pixels) {
        WinViewCalls.viewUploadPixels(view, pixels);
    }

    static boolean viewEnterFullscreen(long view, boolean animate, boolean keepRatio, boolean hideCursor) {
        return WinViewCalls.viewEnterFullscreen(view, animate, keepRatio, hideCursor);
    }

    static void viewExitFullscreen(long view, boolean animate) {
        WinViewCalls.viewExitFullscreen(view, animate);
    }

    static void viewEnableIme(long view, boolean enable) {
        WinViewCalls.viewEnableIme(view, enable);
    }

    static void viewFinishImeComposition(long view) {
        WinViewCalls.viewFinishImeComposition(view);
    }

    static long getDoubleClickTime() {
        return WinViewCalls.getDoubleClickTime();
    }

    static void installWindowCallbacks() {
        WinWindowCallbacks.installWindowCallbacks();
    }

    static long windowCreate(long ownerPtr, long monitor, int mask, long windowId) {
        return WinWindowCalls.windowCreate(ownerPtr, monitor, mask, windowId);
    }

    static boolean windowClose(long hwnd) {
        return WinWindowCalls.windowClose(hwnd);
    }

    static boolean windowSetView(long hwnd, long view) {
        return WinWindowCalls.windowSetView(hwnd, view);
    }

    static void windowUpdateViewSize(long hwnd) {
        WinWindowCalls.windowUpdateViewSize(hwnd);
    }

    static boolean windowSetMenubar(long hwnd, long hmenu) {
        return WinWindowCalls.windowSetMenubar(hwnd, hmenu);
    }

    static void windowSetLevel(long hwnd, int level) {
        WinWindowCalls.windowSetLevel(hwnd, level);
    }

    static void windowSetFocusable(long hwnd, boolean focusable) {
        WinWindowCalls.windowSetFocusable(hwnd, focusable);
    }

    static void windowSetEnabled(long hwnd, boolean enabled) {
        WinWindowCalls.windowSetEnabled(hwnd, enabled);
    }

    static void windowSetAlpha(long hwnd, float alpha) {
        WinWindowCalls.windowSetAlpha(hwnd, alpha);
    }

    static void windowSetDarkFrame(long hwnd, boolean dark) {
        WinWindowCalls.windowSetDarkFrame(hwnd, dark);
    }

    static long windowGetInsets(long hwnd) {
        return WinWindowCalls.windowGetInsets(hwnd);
    }

    static void windowSetBounds(long hwnd, int x, int y, boolean xSet, boolean ySet, int w, int h, int cw, int ch,
                                float xGravity, float yGravity) {
        WinWindowCalls.windowSetBounds(hwnd, x, y, xSet, ySet, w, h, cw, ch, xGravity, yGravity);
    }

    static boolean windowSetTitle(long hwnd, String title) {
        return WinWindowCalls.windowSetTitle(hwnd, title);
    }

    static boolean windowSetResizable(long hwnd, boolean resizable) {
        return WinWindowCalls.windowSetResizable(hwnd, resizable);
    }

    static boolean windowSetVisible(long hwnd, boolean visible) {
        return WinWindowCalls.windowSetVisible(hwnd, visible);
    }

    static boolean windowRequestFocus(long hwnd, int event) {
        return WinWindowCalls.windowRequestFocus(hwnd, event);
    }

    static boolean windowGrabFocus(long hwnd) {
        return WinWindowCalls.windowGrabFocus(hwnd);
    }

    static void windowUngrabFocus(long hwnd) {
        WinWindowCalls.windowUngrabFocus(hwnd);
    }

    static boolean windowMinimize(long hwnd, boolean minimize) {
        return WinWindowCalls.windowMinimize(hwnd, minimize);
    }

    static boolean windowMaximize(long hwnd, boolean maximize, boolean wasMaximized) {
        return WinWindowCalls.windowMaximize(hwnd, maximize, wasMaximized);
    }

    static boolean windowSetMinSize(long hwnd, int width, int height) {
        return WinWindowCalls.windowSetMinSize(hwnd, width, height);
    }

    static boolean windowSetMaxSize(long hwnd, int width, int height) {
        return WinWindowCalls.windowSetMaxSize(hwnd, width, height);
    }

    static void windowSetIcon(long hwnd, long hicon) {
        WinWindowCalls.windowSetIcon(hwnd, hicon);
    }

    static void windowToFront(long hwnd) {
        WinWindowCalls.windowToFront(hwnd);
    }

    static void windowToBack(long hwnd) {
        WinWindowCalls.windowToBack(hwnd);
    }

    static void windowSetCursor(long hwnd, long hcursor) {
        WinWindowCalls.windowSetCursor(hwnd, hcursor);
    }

    static void windowShowSystemMenu(long hwnd, int x, int y) {
        WinWindowCalls.windowShowSystemMenu(hwnd, x, y);
    }

    static long windowAnchor(long hwnd) {
        return WinWindowCalls.windowAnchor(hwnd);
    }

    static void loadDpiFuncs(int awarenessRequested) {
        WinScreenCalls.loadDpiFuncs(awarenessRequested);
    }

    static List<WinScreenLayout.MonitorSettings> collectMonitors() {
        return WinScreenCalls.collectMonitors();
    }

    static void installScreenCallbacks() {
        WinScreenCalls.installScreenCallbacks();
    }

    static int clipboardRegisterViewer(long clipboardId) {
        return WinClipboardCalls.clipboardRegisterViewer(clipboardId);
    }

    static void clipboardDispose(MemorySegment dataObject) {
        WinClipboardCalls.clipboardDispose(dataObject);
    }

    static int clipboardPush(MemorySegment old, long clipboardId, Object[] keys, int supportedActions) {
        return WinClipboardCalls.clipboardPush(old, clipboardId, keys, supportedActions);
    }

    static MemorySegment clipboardPop(MemorySegment old) {
        return WinClipboardCalls.clipboardPop(old);
    }

    static byte[] clipboardPopBytes(MemorySegment dataObject, String mime, long lindex) {
        return WinClipboardCalls.clipboardPopBytes(dataObject, mime, lindex);
    }

    static String[] clipboardPopMimes(MemorySegment dataObject) {
        return WinClipboardCalls.clipboardPopMimes(dataObject);
    }

    static void clipboardPushTargetAction(MemorySegment dataObject, int actionDone) {
        WinClipboardCalls.clipboardPushTargetAction(dataObject, actionDone);
    }

    static int clipboardPopSupportedActions(MemorySegment dataObject) {
        return WinClipboardCalls.clipboardPopSupportedActions(dataObject);
    }

    static int dndPush(MemorySegment old, long clipboardId, Object[] keys, int supportedActions) {
        return WinClipboardCalls.dndPush(old, clipboardId, keys, supportedActions);
    }

    static void dndDispose(MemorySegment dataObject) {
        WinClipboardCalls.dndDispose(dataObject);
    }

    static boolean oleIsCurrentClipboard(MemorySegment dataObject) {
        return WinClipboardCalls.oleIsCurrentClipboard(dataObject);
    }

    static FileDialogResult dialogFile(long owner, String folder, String filename, String title, int type,
                                       boolean multiple, String[] filterDescriptions, String[] filterExtensions,
                                       int defaultFilterIndex) {
        return WinClipboardCalls.dialogFile(owner, folder, filename, title, type, multiple, filterDescriptions,
                filterExtensions, defaultFilterIndex);
    }

    static String dialogFolder(long owner, String folder, String title) {
        return WinClipboardCalls.dialogFolder(owner, folder, title);
    }

    static void installAccessibilityCallbacks() {
        WinAccessibilityCalls.installAccessibilityCallbacks();
    }

    static long createAccessible(long accessibleId) {
        return WinAccessibilityCalls.createAccessible(accessibleId);
    }

    static void destroyAccessible(long accessible) {
        WinAccessibilityCalls.destroyAccessible(accessible);
    }

    static long createTextRange(long accessible, long rangeId) {
        return WinAccessibilityCalls.createTextRange(accessible, rangeId);
    }

    static void destroyTextRange(long range) {
        WinAccessibilityCalls.destroyTextRange(range);
    }

    static long raiseAutomationPropertyChangedEvent(long provider, int propertyId, WinVariant oldValue,
                                                    WinVariant newValue) {
        return WinAccessibilityCalls.raiseAutomationPropertyChangedEvent(provider, propertyId, oldValue, newValue);
    }

    static long raiseAutomationEvent(long provider, int eventId) {
        return WinAccessibilityCalls.raiseAutomationEvent(provider, eventId);
    }

    static boolean uiaClientsAreListening() {
        return WinAccessibilityCalls.uiaClientsAreListening();
    }
}
