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

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import static com.sun.glass.ui.gtk.GtkGlassNative.LIB_C;
import static com.sun.glass.ui.gtk.GtkGlassNative.LIB_X11;
import static com.sun.glass.ui.gtk.GtkGlassNative.Loader.BOUND;
import static com.sun.glass.ui.gtk.GtkGlassNative.Loader.LINKER;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Which glass GTK library the toolkit must load, and what has to happen to the process before it can:
 * {@code Java_com_sun_glass_ui_gtk_GtkApplication__1queryLibrary} of {@code launcher.c} - the whole of
 * {@code libglass.so} at commit {@code 033187ad90} - and the copy of it in {@code GlassApplication.cpp} that
 * answered when a {@code libglassgtk3.so} build had been renamed to {@code libglass.so}.
 * <p>
 * A class of its own, initialized by the first query and by nothing else: it opens libc and
 * {@code libX11.so.6} and no more, so that a machine with no display still fails with the
 * {@code UnsupportedOperationException} of a display that will not open, where initializing {@link GtkGlassNative} -
 * which opens the GTK, GDK, GLib, GIO and XTest libraries - would fail first and differently. That class is
 * initialized where it always was, by {@code GtkApplication} after this query ({@link GtkGlassNative#link}); that is
 * why the query has lookups, a linker and a symbol census of its own and reads no field and calls no method of
 * {@code GtkGlassNative} or of its aggregates. {@code GtkApplication} reaches it through
 * {@link GtkGlassNative.Loader}, which holds the query's linker and census and makes its two restricted calls, and
 * whose initializer reaches only JDK classes.
 * <p>
 * {@code dlopen} is called through libc and not through {@link SymbolLookup#libraryLookup}, because the two
 * flags that carry the behaviour cannot be expressed there: {@code RTLD_NOLOAD}, which asks whether a library
 * is already in the process without loading it, and {@code RTLD_GLOBAL}, which puts the GTK library and its
 * dependencies into the global scope - where the {@code dlsym(RTLD_DEFAULT, ...)} of {@code wrapped.c} looks
 * for {@code gdk_x11_display_set_window_scale} and the four GSettings functions.
 */
final class GtkLibraryLoader {

    /** {@code RTLD_LAZY} of {@code bits/dlfcn.h}: resolve a symbol when it is first called. */
    private static final int RTLD_LAZY = 0x00001;

    private static final int RTLD_NOLOAD = 0x00004;

    private static final int RTLD_GLOBAL = 0x00100;

    /**
     * The answers of {@link #queryLibrary}, the {@code GGTK_QUERY_*} of
     * {@code native-glass/gtk/glass_gtk_api.h}: no GTK 3 library could be loaded, or GTK 2 is already in the
     * process.
     */
    static final int QUERY_ERROR = -2;

    /** {@code XOpenDisplay(NULL)} failed. */
    static final int QUERY_NO_DISPLAY = -1;

    /** The library loaded as {@code glass} is itself the glass GTK library; load none. */
    static final int QUERY_USE_CURRENT = 1;

    /** A GTK 3 library is in the process; load {@code libglassgtk3.so}. */
    static final int QUERY_LOAD_GTK3 = 3;

    /** {@code gtk3_lib_options} of {@code launcher.c}, in its order. */
    private static final String[] GTK3_LIB_OPTIONS = {"libgtk-3.so.0", "libgtk-3.so"};

    /** {@code gtk2_lib_options} of {@code launcher.c}: a GTK 2 already in the process is an error. */
    private static final String[] GTK2_LIB_OPTIONS = {"libgtk-x11-2.0.so.0", "libgtk-x11-2.0.so"};

    /** The version of the GTK 3 chain, {@code gtk3_versioned[0]} of {@code launcher.c}. */
    private static final String GTK3_VERSION = "3";

    /** {@code void *dlopen(const char *file, int mode)}. */
    private static final MethodHandle DLOPEN = bind(LINKER.defaultLookup(), LIB_C, "dlopen",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));

    /** {@code int putenv(char *string)}. */
    private static final MethodHandle PUTENV = bind(LINKER.defaultLookup(), LIB_C, "putenv",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    private static final SymbolLookup X11 = x11();

    /** {@code Display *XOpenDisplay(const char *display_name)}. */
    private static final MethodHandle X_OPEN_DISPLAY = bind(X11, LIB_X11, "XOpenDisplay",
            FunctionDescriptor.of(ADDRESS, ADDRESS));

    /** {@code int XCloseDisplay(Display *display)}. */
    private static final MethodHandle X_CLOSE_DISPLAY = bind(X11, LIB_X11, "XCloseDisplay",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /**
     * {@code putenv} keeps the pointer it is given - the C passed a string literal of {@code libglass.so} - so this
     * block is allocated in {@link Arena#global()} and never freed.
     */
    private static final MemorySegment GDK_BACKEND_X11 = Arena.global().allocateFrom("GDK_BACKEND=x11");

    /**
     * Whether the library the class loader loaded as {@code glass} is itself a glass GTK library: the
     * deployment {@code GlassApplication.cpp} described as "renaming the gtk versioned native libraries to be
     * libglass.so", whose {@code _queryLibrary} answered {@code QUERY_USE_CURRENT} because the library that
     * answered was the one to use. Decided once, when this class initializes - which is the toolkit's query,
     * before it loads {@code glassgtk3} - because every process that has loaded a glass GTK library exports
     * these symbols afterwards.
     */
    private static final boolean GLASS_IS_THE_GTK_LIBRARY =
            SymbolLookup.loaderLookup().find("ggtk_abi_version").isPresent();

    private GtkLibraryLoader() {
    }

    /**
     * {@code Java_com_sun_glass_ui_gtk_GtkApplication__1queryLibrary}, the two of them: the copy in
     * {@code GlassApplication.cpp} when the glass library the process loaded is a glass GTK library itself -
     * which only opens and closes the display, sets no environment variable and prints nothing - and
     * otherwise the launcher's, which sets {@code GDK_BACKEND=x11} on every system, checks the display and
     * then looks for a GTK library.
     *
     * @param version the GTK version asked for, {@code jdk.gtk.version}
     * @param verbose {@code jdk.gtk.verbose}: print what the launcher printed at each step
     * @return one of {@link #QUERY_ERROR}, {@link #QUERY_NO_DISPLAY}, {@link #QUERY_USE_CURRENT},
     *         {@link #QUERY_LOAD_GTK3}
     */
    static int queryLibrary(int version, boolean verbose) {
        if (GLASS_IS_THE_GTK_LIBRARY) {
            return displayOpens() ? QUERY_USE_CURRENT : QUERY_NO_DISPLAY;
        }
        try {
            int ignored = (int) PUTENV.invokeExact(GDK_BACKEND_X11);
        } catch (Throwable t) {
            throw unexpected(t);
        }
        if (!displayOpens()) {
            return QUERY_NO_DISPLAY;
        }
        return sniffLibs(version, verbose, GTK3_LIB_OPTIONS, GTK2_LIB_OPTIONS) == '3'
                ? QUERY_LOAD_GTK3 : QUERY_ERROR;
    }

    private static boolean displayOpens() {
        try {
            MemorySegment display = (MemorySegment) X_OPEN_DISPLAY.invokeExact(MemorySegment.NULL);
            if (display.address() == 0) {
                return false;
            }
            int ignored = (int) X_CLOSE_DISPLAY.invokeExact(display);
            return true;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code sniffLibs} of {@code launcher.c}: first whether a GTK 3 library of {@code gtk3} is already in the
     * process, then whether a GTK 2 library of {@code gtk2} is - which is the one failure this answers - and
     * only then an attempt to load the GTK 3 libraries in order.
     * <p>
     * The two chains are parameters, where the C read its two file-static arrays, so that a test can drive
     * every branch of this table on a machine that has no GTK 2 library to load.
     *
     * @return the first character of the version of the chain that was found, {@code '3'}, or -1
     */
    static int sniffLibs(int wantVersion, boolean verbose, String[] gtk3, String[] gtk2) {
        if (verbose) {
            printLine("checking GTK version " + wantVersion);
        }
        boolean found = false;
        int i;
        for (i = 0; i < gtk3.length && !found; i++) {
            found = tryLibrariesNoload(gtk3[i]);
            if (found && verbose) {
                printLine("found already loaded GTK library " + gtk3[i]);
            }
        }
        if (!found) {
            for (i = 0; i < gtk2.length && !found; i++) {
                found = tryLibrariesNoload(gtk2[i]);
                if (found && verbose) {
                    printLine("found already loaded unsupported GTK library " + gtk2[i]);
                }
            }
            if (found) {
                return -1;
            }
            if (wantVersion != 0 && wantVersion != 3) {
                // Note, this should never happen, java should be protecting us
                if (verbose) {
                    printLine("bad GTK version specified, assuming 3");
                }
                // the C corrected its own local here and never read it again; kept so the branch reads as it did
                wantVersion = 3;
            }
            for (i = 0; i < gtk3.length && !found; i++) {
                if (verbose) {
                    printLine("trying GTK library " + gtk3[i]);
                }
                found = tryOpeningLibraries(gtk3[i]);
            }
        }
        if (found) {
            if (verbose) {
                i--;
                printLine("using GTK library version " + GTK3_VERSION + " set " + gtk3[i]);
            }
            return GTK3_VERSION.charAt(0);
        }
        return -1;
    }

    /**
     * {@code try_opening_libraries} of {@code launcher.c}: {@code dlopen(name, RTLD_LAZY | RTLD_GLOBAL)}. The
     * handle is dropped without {@code dlclose}, as the C dropped it: the library stays for the process.
     */
    static boolean tryOpeningLibraries(String name) {
        return dlopen(name, RTLD_LAZY | RTLD_GLOBAL);
    }

    /** {@code try_libraries_noload} of {@code launcher.c}: {@code dlopen(name, RTLD_LAZY | RTLD_NOLOAD)}. */
    static boolean tryLibrariesNoload(String name) {
        return dlopen(name, RTLD_LAZY | RTLD_NOLOAD);
    }

    private static boolean dlopen(String name, int flags) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment handle = (MemorySegment) DLOPEN.invokeExact(arena.allocateFrom(name), flags);
            return handle.address() != 0;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * One {@code printf} of {@code launcher.c} under {@code gtk_versionDebug}. The newline is the C's; the
     * stream flushes it, where the C flushed {@code stdout} once {@code sniffLibs} was done. It is
     * {@link System#out}, the field, so a {@code System.setOut} takes these lines where the C always wrote
     * file descriptor 1 - the stream {@code GtkApplication}'s own "Glass GTK library to load is" line has
     * always used.
     */
    private static void printLine(String line) {
        System.out.print(line + "\n");
    }

    private static SymbolLookup x11() {
        try {
            return GtkGlassNative.Loader.libraryLookup(LIB_X11, Arena.global());
        } catch (IllegalArgumentException e) {
            UnsatisfiedLinkError error = new UnsatisfiedLinkError("cannot open " + LIB_X11 + ": "
                    + e.getMessage());
            error.initCause(e);
            throw error;
        }
    }

    private static MethodHandle bind(SymbolLookup lookup, String library, String name,
                                     FunctionDescriptor descriptor) {
        MemorySegment symbol = lookup.find(name).orElseThrow(
                () -> new UnsatisfiedLinkError(library + " does not export " + name));
        BOUND.put(library + "!" + name, symbol.address());
        return GtkGlassNative.Loader.downcallHandle(LINKER, symbol, descriptor);
    }

    private static RuntimeException unexpected(Throwable t) {
        if (t instanceof RuntimeException e) {
            return e;
        }
        if (t instanceof Error e) {
            throw e;
        }
        return new IllegalStateException(t);
    }
}
