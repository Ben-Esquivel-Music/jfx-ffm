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

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import static com.sun.glass.ui.win.WinCallSupport.GWIN_ERR_NO_TOOLKIT;
import static com.sun.glass.ui.win.WinCallSupport.GWIN_OK;
import static com.sun.glass.ui.win.WinCallSupport.codeUnitsToNul;
import static com.sun.glass.ui.win.WinCallSupport.reportUpcallFailure;
import static com.sun.glass.ui.win.WinCallSupport.requireOk;
import static com.sun.glass.ui.win.WinCallSupport.wideString;
import static com.sun.glass.ui.win.WinGlassNative.bind;
import static com.sun.glass.ui.win.WinGlassNative.bindGlass;
import static com.sun.glass.ui.win.WinGlassNative.bindKernel32;
import static com.sun.glass.ui.win.WinGlassNative.systemLibrary;
import static com.sun.glass.ui.win.WinGlassNative.unexpected;
import static com.sun.glass.ui.win.WinGlassNative.upcallStub;
import static com.sun.glass.ui.win.WinGlassNative.upcallTarget;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_CHAR;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The {@link WinGlassNative} calls of {@code WinApplication}: the two {@code WRAPPER}s of
 * {@code GlassApplication.cpp}, the toolkit constructor, the Win32 message pump and its nested twin, and the two
 * ways of getting a {@code Runnable} onto the Glass toolkit thread.
 * <p>
 * <b>{@code GlassApplication.cpp}</b> (ABI 3). Of the thirteen natives {@code WinApplication}
 * declared, exactly two were {@code WRAPPER}s ({@link #isWindowsVersionAtLeast}, {@link #defaultBrowser()}).
 * Six of the other eleven are the {@code OS-CALL} family that {@code GLASS_WIN_ABI_VERSION} 3 exists for:
 * {@link #runLoop}, {@link #enterNestedEventLoop}, {@link #leaveNestedEventLoop}, {@link #terminateLoop},
 * {@link #invokeAndWait} and {@link #submitForLaterInvocation}. They moved out of the JNI entry points into
 * {@code gwin_*} exports statement for statement. The invoke pair has changed since: a post, and a synchronous
 * call from any thread but the toolkit thread, go through the publication guard of the toolkit window - a
 * {@code PostMessage}, and a {@code SendNotifyMessage} the caller waits for - so each reaches that window or
 * is refused (see {@link #invokeAndWait}).
 * <p>
 * <b>What this deliberately does not move is the identity of the toolkit thread.</b>
 * {@code WinApplication.runLoop} still creates {@code WindowsNativeRunloopThread} and still calls
 * {@code setEventThread} on it before starting it; the thread body is now one FFM downcall where a
 * JNI native method used to be, on the same thread, so every {@code Application.checkEventThread()}
 * passes and fails in exactly the same cases. No helper thread may be introduced anywhere on this path.
 * <p>
 * None of these downcalls is {@link Linker.Option#critical critical}. The exports that run arbitrary Java
 * (through {@code run_runnable}, or {@code dispose_peer} in {@link #terminateLoop}) or pump a Win32 message
 * loop forbid it outright rather than merely not needing it, and {@code gwin_run_loop} does both for the
 * entire life of the application.
 * <p>
 * Line numbers into the {@code native-glass/win} C++ sources ({@code GlassApplication.cpp},
 * {@code GlassApplication.h}, {@code Utils.h}, {@code Utils.cpp}, bare {@code :N} and {@code LN} forms included)
 * refer to those files at commit {@code 8492cb03b0}
 * ({@code git show 8492cb03b0:modules/javafx.graphics/src/main/native-glass/win/<file>}); a bare number belongs
 * to the C file named last before it.
 */
final class WinApplicationCalls {

    /*
     * shlwapi.h: the three values Java_..._1getDefaultBrowser passed to AssocQueryStringW
     * (GlassApplication.cpp:541-547). MAX_PATH (minwindef.h) is the size of the WCHAR buffer the C put on
     * its stack, in CHARACTERS; see assocQueryCommand.
     */
    static final int ASSOCF_NONE = 0;
    static final int ASSOCSTR_COMMAND = 1;
    static final int MAX_PATH = 260;

    /**
     * {@code GwinAppCallbacks} ({@code glass_win_api.h}): one function pointer, which is all three of
     * {@code GlassApplication.cpp}'s former {@code Runnable.run} upcalls at once - the launchable
     * ({@code :398}), {@code _invokeAndWait} ({@code :469}) and {@code _submitForLaterInvocation}
     * ({@code :489}) all went through the single cached {@code javaIDs.Runnable.run} ({@code :344}).
     * <p>
     * It is a second table rather than a slot appended to {@link WinPreferenceCalls#GWIN_PREFS_CALLBACKS_LAYOUT}: the
     * preferences table lives beside the WinRT state it serves, this one beside the message pump, and
     * two tables mean neither struct ever changes shape.
     */
    static final StructLayout GWIN_APP_CALLBACKS_LAYOUT = MemoryLayout.structLayout(
            ADDRESS.withName("run_runnable"));

    private static final long RUN_RUNNABLE_OFFSET =
            GWIN_APP_CALLBACKS_LAYOUT.byteOffset(PathElement.groupElement("run_runnable"));

    private static final MethodHandle GWIN_SIZEOF_APP_CALLBACKS = bindGlass("gwin_sizeof_app_callbacks",
            FunctionDescriptor.of(JAVA_INT));

    private static final MethodHandle GWIN_APP_SET_CALLBACKS = bindGlass("gwin_app_set_callbacks",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    private static final MethodHandle GWIN_APP_CREATE = bindGlass("gwin_app_create",
            FunctionDescriptor.of(ADDRESS));

    private static final MethodHandle GWIN_RUN_LOOP = bindGlass("gwin_run_loop",
            FunctionDescriptor.ofVoid(JAVA_LONG));

    private static final MethodHandle GWIN_TERMINATE_LOOP = bindGlass("gwin_terminate_loop",
            FunctionDescriptor.ofVoid());

    private static final MethodHandle GWIN_ENTER_NESTED_EVENT_LOOP =
            bindGlass("gwin_enter_nested_event_loop", FunctionDescriptor.ofVoid());

    private static final MethodHandle GWIN_LEAVE_NESTED_EVENT_LOOP =
            bindGlass("gwin_leave_nested_event_loop", FunctionDescriptor.ofVoid());

    private static final MethodHandle GWIN_INVOKE_AND_WAIT = bindGlass("gwin_invoke_and_wait",
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG));

    private static final MethodHandle GWIN_INVOKE_LATER = bindGlass("gwin_invoke_later",
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG));

    /**
     * {@code GetVersion} is deprecated and, for a process whose manifest does not claim a newer
     * Windows, reports 6.2 rather than the true build. That lie is per <em>process</em>, so this
     * facade is told the same one the C was told, in the same process, on the same thread:
     * {@link #isWindowsVersionAtLeast} is byte-for-byte the answer {@code IS_WINVISTA} gave. Do not
     * "fix" it to {@code RtlGetVersion}, {@code VerifyVersionInfo} or {@code IsWindows8OrGreater} -
     * that would change what {@code Application.supportsUnifiedWindows()} returns on some machines,
     * which is a behaviour change and not a migration.
     */
    private static final MethodHandle GET_VERSION = bindKernel32("GetVersion",
            FunctionDescriptor.of(JAVA_INT));

    /**
     * {@code shlwapi.dll} and the one function of it {@code GlassApplication.cpp} called, bound on
     * the first {@link #defaultBrowser()} rather than in this class's initializer.
     * <p>
     * The laziness is behaviour parity, exactly as it is for {@link WinTimerCalls.Winmm}: {@code glass.dll}
     * delay-loaded {@code shlwapi} ({@code win.cmake:175}, {@code /DELAYLOAD:shlwapi.dll}) and
     * {@code AssocQueryStringW} was its only use of that DLL in the whole library
     * ({@code GlassApplication.cpp:28,545}), so a process that never called
     * {@code Application.showDocument} never loaded it. {@code shlwapi} in turn pulls in the shell
     * association machinery, so this is not a free load. A holder class keeps the load point where
     * the JNI had it, and keeps a {@code shlwapi} that cannot be resolved from failing
     * {@link WinRobot}, {@link WinTimer} and {@link WinCursor} with it.
     * <p>
     * The cost, again as for {@link WinTimerCalls.Winmm}: {@link WinGlassNative#boundSymbols()} grows when the first
     * {@code showDocument} happens, which is why that list is a {@link CopyOnWriteArrayList} and why
     * the tests that assert it force this holder in their {@code @BeforeAll}.
     */
    static final class Shlwapi {

        static final String NAME = "shlwapi";

        /** A system DLL the JDK does not load for us; the lookup lives for the life of the process. */
        static final SymbolLookup LOOKUP = systemLibrary(NAME + ".dll");

        /**
         * {@code HRESULT AssocQueryStringW(ASSOCF flags, ASSOCSTR str, LPCWSTR pszAssoc,
         * LPCWSTR pszExtra, LPWSTR pszOut, DWORD *pcchOut)} - {@code GlassApplication.cpp:545-552}.
         * Both enums are 32-bit, {@code pszExtra} was {@code NULL} there, and {@code pcchOut} is an
         * in/out count in <em>characters</em>.
         */
        private static final MethodHandle ASSOC_QUERY_STRING_W = bindShlwapi("AssocQueryStringW",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));

        private Shlwapi() {
        }

        private static MethodHandle bindShlwapi(String name, FunctionDescriptor descriptor) {
            return bind(LOOKUP, NAME, name, descriptor);
        }
    }

    private WinApplicationCalls() {
    }

    /**
     * {@code kernel32!GetVersion} as the unsigned 32-bit value it is: the low word is the version
     * (low byte major, high byte minor), the high word the build number - or 0 when the process runs
     * under a compatibility shim. Package-private so a test can check the decoding of
     * {@link #isWindowsVersionAtLeast} against the raw value instead of against a platform.
     */
    static int windowsVersion() {
        try {
            return (int) GET_VERSION.invokeExact();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code Utils.h}'s {@code IS_WINVER_ATLEAST(major, minor)} (L61-64) - and therefore the whole of
     * {@code WinApplication._supportsUnifiedWindows}, whose C body was {@code return (IS_WINVISTA);}
     * ({@code GlassApplication.cpp:504-508}), {@code IS_WINVISTA} being {@code IS_WINVER_ATLEAST(6, 0)}
     * ({@code Utils.h:66}).
     * <p>
     * The arithmetic is the macro's, including its operator precedence: {@code &&} binds tighter than
     * {@code ||}, so the C reads {@code a > maj || (a == maj && b >= min)}.
     * <p>
     * What this deliberately does <em>not</em> do is tell the truth about the platform; see
     * {@link #GET_VERSION}.
     */
    static boolean isWindowsVersionAtLeast(int major, int minor) {
        int version = windowsVersion();
        int actualMajor = version & 0xFF;
        int actualMinor = (version >>> 8) & 0xFF;
        return actualMajor > major || actualMajor == major && actualMinor >= minor;
    }

    /**
     * {@code gwin_app_create}: what {@code WinApplication._init} did after its DPI step
     * ({@code GlassApplication.cpp}) - {@code new GlassApplication()}, which registers the window class,
     * creates the hidden toolkit window whose {@code WM_CREATE} publishes the instance every other export
     * reads, and initializes WinRT ({@code RoInitialize(RO_INIT_SINGLETHREADED)}, {@code UISettings},
     * {@code NetworkInformation}) on <b>this</b> thread; the object is deleted again if no window resulted.
     * <p>
     * Call it on the thread that will call {@link #runLoop}, before it, and after {@link WinScreenCalls#loadDpiFuncs}
     * with the requested awareness: a window created before the process is DPI-aware stays unaware. Never
     * from a test JVM that does not own the toolkit: a second call leaks the first instance, as
     * {@code _init} did.
     *
     * @return the toolkit HWND as a {@code long}, or 0; the caller discards it, as it discarded
     *         {@code _init}'s {@code jlong} - {@code gwin_terminate_loop} reads the live instance, and an
     *         HWND value can be recycled
     */
    static long appCreate() {
        try {
            MemorySegment hwnd = (MemorySegment) GWIN_APP_CREATE.invokeExact();
            return hwnd.address();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code WinApplication._getDefaultBrowser}, which was {@code GlassApplication.cpp:537-559}: the
     * command line Windows has registered for the {@code https} scheme, or {@code null} when the
     * association cannot be read. {@code WinApplication._showDocument} parses it - a leading quoted
     * path, then {@code %1} substitution - so what has to be preserved here is the raw string,
     * including its quotes and its {@code %1}.
     */
    static String defaultBrowser() {
        return assocQueryCommand("https");
    }

    /**
     * {@code AssocQueryStringW(ASSOCF_NONE, ASSOCSTR_COMMAND, association, NULL, buffer, &cch)},
     * which is {@code GlassApplication.cpp:545-552} with its literal {@code L"https"} ({@code :540}) made
     * a parameter so that a test can drive the failure arm on an association it controls. The
     * association crosses as its code units and a NUL ({@link WinCallSupport#wideString}), which for {@code "https"}
     * are the literal's own {@code WCHAR}s; a charset encoder would have written U+FFFD for an unpaired
     * surrogate in a test's association.
     * <p>
     * <b>The buffer is 260 characters, not 260 bytes.</b> The C declared
     * {@code WCHAR defaultBrowser_c[MAX_PATH]} - 520 bytes - and passed {@code cchBuffer = MAX_PATH}
     * (:541-542), a count in characters. Allocating 260 <em>bytes</em> and still claiming 260
     * characters would let {@code shlwapi} write 520 bytes into it, past the end of the segment and
     * into the arena's neighbours: a native-heap corruption FFM cannot bounds-check, because the
     * write happens inside {@code shlwapi.dll}, and one that only shows on a machine whose browser
     * command line is longer than 129 characters - which is the normal case.
     * <p>
     * {@code FAILED(hr)}, i.e. {@code hr < 0}, is {@code null}, exactly as at :554-556; that is also
     * what a command line longer than the buffer gives ({@code E_POINTER}), and the C had no
     * retry-with-a-bigger-buffer either. The string is read to its first NUL as code units
     * ({@link WinCallSupport#codeUnitsToNul}), which is what {@code CreateJString} did with {@code NewString} and
     * {@code wcslen} ({@code Utils.h:279-285}), and it is read <em>inside</em> the arena, so the
     * {@code String} copy exists before the segment dies.
     * <p>
     * One deliberate difference, the same one {@link WinRobotCalls#robotCapture} makes with its buffer: the arena
     * zero-fills, so a call that reports success without writing anything yields {@code ""} where the
     * C read an uninitialised stack buffer and made a {@code String} out of whatever was there.
     */
    static String assocQueryCommand(String association) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment assoc = wideString(arena, association);
            MemorySegment buffer = arena.allocate(JAVA_CHAR, MAX_PATH);
            MemorySegment count = arena.allocateFrom(JAVA_INT, MAX_PATH);
            int hr = (int) Shlwapi.ASSOC_QUERY_STRING_W.invokeExact(ASSOCF_NONE, ASSOCSTR_COMMAND,
                    assoc, MemorySegment.NULL, buffer, count);
            if (hr < 0) {
                return null;
            }
            return codeUnitsToNul(buffer);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static final FunctionDescriptor RUN_RUNNABLE_FD = FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG);

    /**
     * The runnables the library has been told about but has not yet run, keyed by the id that crosses
     * the ABI where the JNI passed a {@code jobject}. {@code glass.dll} holds no Java reference at all
     * now, so there is nothing for it to add, delete or leak.
     * <p>
     * The references are strong, exactly as the {@code JGlobalRef} the {@code Action} held was
     * ({@code GlassApplication.h:61-62}): a runnable submitted and then dropped by its submitter could
     * not be collected before it ran, and must still not be. {@code JPEGNative}'s weak registry is the
     * wrong model here for the same reason it was for {@link WinTimerCalls#TIMERS}.
     * <p>
     * <b>There is no sweeper, and that is deliberate.</b> An entry is removed by whoever can prove the
     * callback will not arrive: {@link #onRunRunnable} when it runs one (one-shot),
     * {@link #invokeAndWait} in a {@code finally} because the downcall does not return until the
     * runnable has run or has been refused, {@link #runLoop} in a {@code finally} because the JNI's
     * launchable was a parameter whose local ref died with the call, and
     * {@link #submitForLaterInvocation} when the downcall answers {@link WinCallSupport#GWIN_ERR_NO_TOOLKIT}, which
     * is the case where nothing was posted. The last three remove through
     * {@link #unregisterRunnable}, which is also how {@code WinGlassNativeShim}'s toolkit drivers drop
     * their ids, by the same rules. What is left over is a runnable posted to the
     * toolkit window whose message was still on the queue at shutdown, and that leaked its
     * {@code JGlobalRef} in precisely the same way. Reproducing a pre-existing leak is the job;
     * inventing cleanup for it is not.
     */
    private static final Map<Long, Runnable> RUNNABLES = new ConcurrentHashMap<>();

    /**
     * Ids run from 1, because {@code gwin_run_loop} spells "no launchable" as 0 - which is what
     * {@code jLaunchable != NULL} tested ({@code GlassApplication.cpp:397}). They are monotone and
     * never reused, so a late or duplicate callback for an id that has already been removed is a
     * no-op rather than a call on the wrong runnable.
     */
    private static final AtomicLong NEXT_RUNNABLE_ID = new AtomicLong(1L);

    private static boolean applicationCallbackInstalled;

    /** {@code sizeof(GwinAppCallbacks)} as the C compiler saw it; {@link #GWIN_APP_CALLBACKS_LAYOUT} must agree. */
    static int sizeOfAppCallbacks() {
        try {
            return (int) GWIN_SIZEOF_APP_CALLBACKS.invokeExact();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * <b>Call this exactly once, from {@code WinApplication}'s static initializer</b>, beside
     * {@link WinPreferenceCalls#installPreferencesCallback}: that runs on the launcher thread, after the library is
     * loaded and before {@code Application.run()} creates the toolkit window, so the table is
     * installed before the first {@code WM_DO_ACTION} and is never written from a second thread.
     * Installing it lazily would drop the launchable itself, which is the first thing
     * {@code gwin_run_loop} does.
     * <p>
     * The table is copied by the library, so the confined arena that holds it is closed here. The stub
     * inside it is in {@link Arena#global()} and stays there: a message posted by
     * {@code gwin_invoke_later} can be dispatched at any time up to the last {@code GetMessage}, and
     * unlike the preferences stub ({@link WinPreferenceCalls#clearPreferencesCallback}) nothing frees this one after
     * the pump, so it is one stub for the life of the process.
     */
    static synchronized void installApplicationCallback() {
        if (applicationCallbackInstalled) {
            return;
        }
        MemorySegment callback = upcallStub(upcallTarget(WinApplicationCalls.class, "onRunRunnable", RUN_RUNNABLE_FD),
                RUN_RUNNABLE_FD, Arena.global());
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment table = arena.allocate(GWIN_APP_CALLBACKS_LAYOUT);
            table.set(ADDRESS, RUN_RUNNABLE_OFFSET, callback);
            int status = (int) GWIN_APP_SET_CALLBACKS.invokeExact(table, MemorySegment.NULL);
            requireOk(status, "gwin_app_set_callbacks");
        } catch (Throwable t) {
            throw unexpected(t);
        }
        applicationCallbackInstalled = true;
    }

    static synchronized boolean applicationCallbackInstalled() {
        return applicationCallbackInstalled;
    }

    /**
     * The {@code GwinAppCallbacks.run_runnable} body. {@code user} is the pointer
     * {@link #installApplicationCallback} passed, which is {@code NULL} - the registry the C needed a
     * {@code this} for is a static field here.
     * <p>
     * <b>Thread.</b> Always the Glass toolkit thread, in three situations: inside
     * {@code gwin_run_loop} before the pump starts and inside the OLE apartment it created, for the
     * launchable; in the arms of {@code GlassApplication::WindowProc} for {@link #invokeAndWait} - the
     * {@code WM_DO_ACTION} arm, inline in a {@code SendMessage} made on the toolkit thread, and the
     * {@code WM_DO_ACTION_NOTIFY} arm, where the toolkit thread's message retrieval hands it a call that
     * another thread queued with {@code SendNotifyMessage}; and inside {@code DispatchMessage} for the
     * {@code WM_DO_ACTION_LATER} arm. Every one of those messages runs the window procedure on the
     * window's owning thread, so no foreign thread can reach this.
     * <p>
     * <b>Re-entrant.</b> The runnable may call {@link #invokeAndWait}, {@link #submitForLaterInvocation}
     * or {@link #enterNestedEventLoop}, and a nested pump dispatches further {@code WM_DO_ACTION_NOTIFY}
     * and {@code WM_DO_ACTION_LATER} messages on top of this frame. Nothing here holds a lock; the C
     * holds none while it runs.
     * <p>
     * <b>It must not throw, and it returns nothing.</b> An exception escaping an upcall stub terminates
     * the JVM, so {@code Throwable} is caught and reported exactly as {@code CheckAndClearException}
     * did ({@code Utils.cpp:50-71}). None of the three {@code CallVoidMethod} sites of
     * {@code GlassApplication.cpp} consumed a return value and the
     * {@code WM_DO_ACTION} arms answer {@code return 0} whatever the runnable did, so this slot is
     * {@code void} - unlike {@link WinPreferenceCalls#onPreferencesChanged}, whose return really does choose between
     * {@code return 0} and {@code DefWindowProc}.
     * <p>
     * A callback for an id that has already been removed is dropped, where the C would have called
     * {@code CallVoidMethod} on a released global ref.
     */
    static void onRunRunnable(MemorySegment user, long runnableId) {
        try {
            // One-shot, and the local is what keeps the runnable reachable across run().
            Runnable runnable = RUNNABLES.remove(runnableId);
            if (runnable != null) {
                runnable.run();
            }
        } catch (Throwable t) {
            reportUpcallFailure(t);
        }
    }

    /**
     * {@code Java_com_sun_glass_ui_win_WinApplication__1runLoop} ({@code GlassApplication.cpp:393-421}):
     * {@code OleInitialize}, run the launchable inside that apartment, then pump until the
     * {@code GlassApplication} instance is destroyed. <b>Blocks for the life of the application</b> and
     * must be called on the thread that is to be the Glass toolkit thread (see the class documentation).
     * <p>
     * The launchable is registered rather than passed, and removed in a {@code finally} because the
     * JNI's {@code jLaunchable} was a parameter whose local ref died when the entry point returned.
     * In practice {@link #onRunRunnable} has already removed it, before the pump even started.
     *
     * @param launchable what to run before the first {@code GetMessage}, or {@code null} for nothing -
     *                   which is the {@code jLaunchable != NULL} test, spelled as the id 0
     */
    static void runLoop(Runnable launchable) {
        long id = launchable == null ? 0L : registerRunnable(launchable);
        try {
            GWIN_RUN_LOOP.invokeExact(id);
        } catch (Throwable t) {
            throw unexpected(t);
        } finally {
            unregisterRunnable(id);
        }
    }

    /**
     * {@code Java_com_sun_glass_ui_win_WinApplication__1terminateLoop} ({@code :428-435}):
     * {@code DestroyWindow} on the toolkit window if it is still a window. Idempotent - the handle is
     * {@code NULL} once there is no instance and {@code IsWindow(NULL)} is false - and callable only
     * from the thread that created the window, which is where
     * {@code Application.finishTerminating()} runs.
     * <p>
     * What happens inside it is worth knowing: {@code WM_DESTROY} runs
     * {@code RegisterClipboardViewerId(0)}, which dials the {@code dispose_peer} slot of the clipboard
     * table for a registered clipboard peer ({@code GlassApplication::DisposeRegisteredClipboard}), so
     * this downcall can re-enter Java through an upcall on this thread; then {@code WM_NCDESTROY} clears
     * the instance and the pump in {@link #runLoop} returns.
     */
    static void terminateLoop() {
        try {
            GWIN_TERMINATE_LOOP.invokeExact();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code GlassApplication::EnterNestedEventLoop} ({@code :248-270}) without its {@code jobject}
     * half: pump until {@code gwin_leave_nested_event_loop} raises the flag or the instance goes away.
     * <b>Blocks and dispatches</b>, so {@link #onRunRunnable} can be re-entered on top of it. The
     * return value never left Java in the first place and {@code WinApplication} holds it now.
     */
    static void enterNestedEventLoop() {
        try {
            GWIN_ENTER_NESTED_EVENT_LOOP.invokeExact();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code GlassApplication::LeaveNestedEventLoop} ({@code :273-277}) minus the value: raise the
     * flag. Toolkit thread only, which is why the flag needs no barrier. The caller stores its return
     * value <em>before</em> calling this, in the order the C used.
     */
    static void leaveNestedEventLoop() {
        try {
            GWIN_LEAVE_NESTED_EVENT_LOOP.invokeExact();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code Java_com_sun_glass_ui_win_WinApplication__1invokeAndWait} ({@code :464-477}): wrap the
     * runnable in an {@code Action} and hand it to {@code GlassApplication::ExecAction}. <b>Blocks</b>
     * until {@link #onRunRunnable} has returned on the toolkit thread, or until the call is refused.
     * <p>
     * From any thread but the toolkit thread, {@code ExecAction} links the call into a pending list and
     * queues it with {@code SendNotifyMessage(toolkit HWND, WM_DO_ACTION_NOTIFY)}, inside the shared side
     * of the toolkit window's publication guard, then waits outside the guard, handling the messages
     * other threads send to it meanwhile, as the JNI's {@code SendMessage} did. The toolkit thread
     * handles the call where it handled that {@code SendMessage}, ahead of posted messages, so the order
     * against pending {@link #submitForLaterInvocation} runnables is unchanged. A call still queued when
     * the window is destroyed is released by its {@code WM_NCDESTROY} arm, unrun.
     * <p>
     * <b>{@link WinCallSupport#GWIN_ERR_NO_TOOLKIT} is ignored, on purpose.</b> There was no toolkit window, or it was
     * destroyed before the runnable started, so the runnable did not run and no callback will arrive -
     * and that is what the JNI did, because {@code ExecAction} returned early and silently
     * ({@code :229-235}). Throwing here, or blocking, would be a new behaviour on a path that runs during
     * shutdown.
     * <p>
     * If the caller is itself the toolkit thread, {@code SendMessage(toolkit HWND, WM_DO_ACTION)} runs the
     * window procedure inline on this stack, with no lock held; {@code Application.invokeAndWait}
     * short-circuits that case in Java first ({@code Application.java:463-465}), as it always did.
     */
    static void invokeAndWait(Runnable runnable) {
        long id = registerRunnable(runnable);
        try {
            execAction(id);
        } finally {
            unregisterRunnable(id);
        }
    }

    /**
     * {@code Java_com_sun_glass_ui_win_WinApplication__1submitForLaterInvocation} ({@code :484-497}):
     * {@code PostMessage(toolkit HWND, WM_DO_ACTION_LATER, action, 0)}; the window procedure runs the
     * action and then deletes it. Returns as soon as the message is posted.
     * <p>
     * <b>Callable from any thread</b> - Glass's {@code InvokeLaterDispatcher} thread is the usual one
     * ({@code InvokeLaterDispatcher.java:122}, and {@code :143} through its own
     * {@code invokeAndWait}).
     * <p>
     * On {@link WinCallSupport#GWIN_ERR_NO_TOOLKIT} nothing was posted (an action the library allocated has been
     * destroyed), so no callback will ever arrive for this id and the registry entry is dropped here.
     * The runnable is not run and nothing is thrown; with no toolkit window that is what
     * {@code ExecActionLater} did ({@code :238-245}). Where the JNI's {@code new} threw
     * {@code std::bad_alloc} through the call, the library now answers {@link WinCallSupport#GWIN_ERR_NO_TOOLKIT}
     * and the runnable does not run.
     */
    static void submitForLaterInvocation(Runnable runnable) {
        long id = registerRunnable(runnable);
        if (!postAction(id)) {
            unregisterRunnable(id);
        }
    }

    /**
     * Takes the next id and publishes the runnable under it before any downcall can carry it, which is
     * the ordering {@link #onRunRunnable} needs: the callback can arrive on the toolkit thread while
     * the submitting thread is still inside {@code PostMessage}. Package-private, with
     * {@link #onRunRunnable}, so that {@code WinGlassNativeShim} can drive the target's one-shot and
     * {@code Throwable}-swallowing arms without a pump.
     */
    static long registerRunnable(Runnable runnable) {
        long id = NEXT_RUNNABLE_ID.getAndIncrement();
        RUNNABLES.put(id, runnable);
        return id;
    }

    /**
     * For a caller that can prove no callback will arrive for {@code id} - the rules {@link #RUNNABLES}
     * lists. Removing an id that is not there (0, or one {@link #onRunRunnable} has already removed) is a
     * no-op.
     */
    static void unregisterRunnable(long id) {
        RUNNABLES.remove(id);
    }

    /** The status is discarded - see {@link #invokeAndWait}. */
    private static void execAction(long id) {
        try {
            int ignoredStatus = (int) GWIN_INVOKE_AND_WAIT.invokeExact(id);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * Whether the action was posted: false is {@link WinCallSupport#GWIN_ERR_NO_TOOLKIT}, after which no
     * callback will arrive.
     */
    private static boolean postAction(long id) {
        try {
            return scheduled((int) GWIN_INVOKE_LATER.invokeExact(id), "gwin_invoke_later");
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@link WinCallSupport#GWIN_OK} and {@link WinCallSupport#GWIN_ERR_NO_TOOLKIT} are the only two statuses either
     * scheduling export can answer, so anything else is a facade bug rather than a platform condition - the same
     * reasoning as {@link WinCallSupport#requireOk}.
     */
    private static boolean scheduled(int status, String function) {
        if (status == GWIN_OK) {
            return true;
        }
        if (status == GWIN_ERR_NO_TOOLKIT) {
            return false;
        }
        throw new AssertionError(function + " returned " + status);
    }

    /**
     * How many runnables this class is holding for {@code glass.dll}; for
     * {@code WinApplicationNativeTest}. It is the FFM counterpart of the {@code JGlobalRef}s the
     * {@code Action}s used to hold, and the only externally visible evidence that they are released.
     */
    static int runnableRegistrySize() {
        return RUNNABLES.size();
    }
}
