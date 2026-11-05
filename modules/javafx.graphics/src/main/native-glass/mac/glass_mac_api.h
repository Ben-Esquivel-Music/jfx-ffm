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

/*
 * glass_mac_api.h - the flat C ABI of the macOS Glass library (libglass.dylib), bound by
 * com.sun.glass.ui.mac.MacGlassNative through java.lang.foreign.
 *
 * Arguments and results are <stdint.h> scalars, opaque pointers and caller-owned buffers; nothing here names a JVM
 * type. Objective-C BOOL never crosses: it is bool on arm64 but signed char on x86_64, so a boolean is an int32_t,
 * 0 for false and anything else for true.
 *
 * Strings into the library are UTF-16 code units, (const uint16_t *chars, int32_t len). chars == NULL with len 0
 * stands for a Java null. A non-null empty string needs a non-NULL chars, because the conversions that tell null
 * from empty answer nil for NULL; len 0 reads nothing from it.
 *
 * Strings out of the library are the bytes -[NSString UTF8String] gave the JNI's NewStringUTF, NUL-terminated, with
 * their length; Java decodes them as NewStringUTF did. A NULL string stands for a Java null.
 *
 * Blocks the library allocates for the caller are freed with gmac_free.
 *
 * gmac_init must run before any function that opens an autorelease pool (GLASS_POOL_ENTER): before it, the
 * thread-data key is 0, and on Darwin thread-specific slot 0 belongs to libpthread.
 *
 * critical(true) is legal only on gmac_abi_version, gmac_abi_constant, gmac_main_thread_detached, gmac_timer_paused
 * and the gmac_sizeof_* and gmac_offsetof_* probes. gmac_init can wait for another thread inside pthread_once, and
 * everything else allocates, runs AppKit or CoreVideo, makes a thread, waits or calls back into Java.
 *
 * SHIM-ONLY TEST HOOKS: the gmac_test_* functions ship in the production library because the tests run against it,
 * and MacGlassNative does not bind them. A change to them does not move GLASS_MAC_ABI_VERSION.
 */

#ifndef GLASS_MAC_API_H
#define GLASS_MAC_API_H

#include <stdint.h>

#define GMAC_EXPORT __attribute__((visibility("default")))

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Bumped for a removal, or a prototype, struct-layout or constant change, in anything MacGlassNative binds. An export
 * added together with its Java binding rides the current version. Java calls gmac_abi_version first and refuses the
 * library on a mismatch.
 */
#define GLASS_MAC_ABI_VERSION 1

GMAC_EXPORT int32_t gmac_abi_version(void);

#define GMAC_OK                      0
#define GMAC_ERR_NO_DATA           (-1)  /* the JNI native returned null */
#define GMAC_ERR_UNSUPPORTED_OS    (-2)  /* the JNI threw RuntimeException("Unsupported macOS version") */
#define GMAC_ERR_NOT_ATTACHED      (-3)  /* on the main thread with Java detached the JNI dropped the request */
#define GMAC_ERR_DETACHED_OFF_MAIN (-4)  /* GLASS_ASSERT_MAIN_JAVA_THREAD fired: the JNI threw or reported
                                            RuntimeException("Main Java thread is detached."), per site */

#define GMAC_UPCALL_OK    0
#define GMAC_UPCALL_THREW 1

/* GmacPref.kind */
#define GMAC_PREF_COLOR       0
#define GMAC_PREF_COLOR_ARRAY 1
#define GMAC_PREF_STRING      2
#define GMAC_PREF_BOOLEAN     3

/* NSEventModifierFlagCapsLock, the bit of gmac_current_modifier_flags that _isKeyLocked tested. */
#define GMAC_MODIFIER_FLAG_CAPS_LOCK 65536

/* GmacPasteboardRep.kind: what Java passes for the value of a representation */
#define GMAC_PASTEBOARD_REP_BAD    (-1)  /* a representation array that is not {UTI, value} */
#define GMAC_PASTEBOARD_REP_NONE     0   /* a value of no kind below */
#define GMAC_PASTEBOARD_REP_STRING   1
#define GMAC_PASTEBOARD_REP_BYTES    2
#define GMAC_PASTEBOARD_REP_IMAGE    3

/* GmacPasteboardItem.rep_count of a null item */
#define GMAC_PASTEBOARD_ITEM_NULL (-1)

/* Indices of gmac_abi_constant, which answers INT32_MIN outside [0, GMAC_ABI_CONSTANT_COUNT). */
enum GmacAbiConstant {
    GMAC_ABI_OK = 0,
    GMAC_ABI_ERR_NO_DATA,
    GMAC_ABI_ERR_UNSUPPORTED_OS,
    GMAC_ABI_ERR_NOT_ATTACHED,
    GMAC_ABI_ERR_DETACHED_OFF_MAIN,
    GMAC_ABI_UPCALL_OK,
    GMAC_ABI_UPCALL_THREW,
    GMAC_ABI_PREF_COLOR,
    GMAC_ABI_PREF_COLOR_ARRAY,
    GMAC_ABI_PREF_STRING,
    GMAC_ABI_PREF_BOOLEAN,
    GMAC_ABI_MODIFIER_FLAG_CAPS_LOCK,
    GMAC_ABI_PASTEBOARD_REP_BAD,
    GMAC_ABI_PASTEBOARD_REP_NONE,
    GMAC_ABI_PASTEBOARD_REP_STRING,
    GMAC_ABI_PASTEBOARD_REP_BYTES,
    GMAC_ABI_PASTEBOARD_REP_IMAGE,
    GMAC_ABI_PASTEBOARD_ITEM_NULL,
    GMAC_ABI_CONSTANT_COUNT
};

GMAC_EXPORT int32_t gmac_abi_constant(int32_t index);

/*
 * pthread_key_create(&GlassThreadDataKey), run once per process however often it is called, as the JNI build's library
 * initializer ran it. Answers GMAC_OK, or the error number pthread_key_create returned. The JNI build carried on
 * after a failure, so a caller must too.
 */
GMAC_EXPORT int32_t gmac_init(void);

GMAC_EXPORT void gmac_free(void *block);

/*
 * The condition of GLASS_ASSERT_MAIN_JAVA_THREAD: 1 when called off the main thread while the main thread is not
 * attached to Java (before runLoop: attaches it, or after [NSApp run] returned), else 0. Any thread.
 */
GMAC_EXPORT int32_t gmac_main_thread_detached(void);

/* ---- layouts ---- */

/* The 20 arguments of the com.sun.glass.ui.Screen constructor, in order. */
typedef struct GmacScreenInfo {
    void   *screen;            /*  0 NSScreen*, retained by every query and never released, as the JNI did */
    int32_t depth;             /*  8 */
    int32_t x;                 /* 12 frame, with y measured down from the top of the first screen */
    int32_t y;                 /* 16 */
    int32_t width;             /* 20 */
    int32_t height;            /* 24 */
    int32_t platform_x;        /* 28 the frame again */
    int32_t platform_y;        /* 32 */
    int32_t platform_width;    /* 36 */
    int32_t platform_height;   /* 40 */
    int32_t visible_x;         /* 44 visibleFrame, flipped the same way */
    int32_t visible_y;         /* 48 */
    int32_t visible_width;     /* 52 */
    int32_t visible_height;    /* 56 */
    int32_t resolution_x;      /* 60 */
    int32_t resolution_y;      /* 64 */
    float   platform_scale_x;  /* 68 1.0f */
    float   platform_scale_y;  /* 72 1.0f */
    float   output_scale_x;    /* 76 */
    float   output_scale_y;    /* 80 */
} GmacScreenInfo;              /* 88 bytes */

/*
 * The arguments the JNI gave Color.rgb(int, int, int, double): (int)(component * 255.0f) of the colour in sRGB, and
 * all zero for a colour that has no sRGB form. Color.rgb throws for a component outside 0-255.
 */
typedef struct GmacColor {
    int32_t r;         /*  0 */
    int32_t g;         /*  4 */
    int32_t b;         /*  8 */
    int32_t reserved;  /* 12 0 */
    double  a;         /* 16 */
} GmacColor;           /* 24 bytes */

/*
 * One entry of the platform preferences map; the entries come in the order the JNI put them into its HashMap. key
 * and a string value are static NUL-terminated ASCII.
 */
typedef struct GmacPref {
    const char *key;    /*  0 */
    int32_t     kind;   /*  8 GMAC_PREF_* */
    int32_t     count;  /* 12 COLOR_ARRAY: the colours. STRING: the bytes of value. BOOLEAN: 0 or 1 */
    GmacColor   color;  /* 16 COLOR */
    const void *value;  /* 40 COLOR_ARRAY: const GmacColor[count], NULL when count is 0.
                                 STRING: const char *, NULL for a null value */
} GmacPref;             /* 48 bytes */

/*
 * ---- GmacAppCallbacks: the upcalls of MacApplication's native side ----
 *
 * Every slot runs on the AppKit main thread once runLoop: has attached it to Java, which for a delegate
 * gmac_app_create_delegate made is slot 2's upcall, and answers GMAC_UPCALL_OK or GMAC_UPCALL_THREW. No Java
 * exception may escape a slot: its Java target catches Throwable and disposes of it as the JNI site did:
 *   REPORT    Application.reportException(t), and what that throws is swallowed (GLASS_CHECK_EXCEPTION). Where
 *             the JNI reported after the site's autorelease pool drained (slots 4-15), the target reports inside it.
 *   DESCRIBE  what JNI ExceptionDescribe printed: "Exception in thread \"<name>\" " written to fd 2, then
 *             t.printStackTrace(). The Java target prints it; the C prints nothing and only reads the status.
 *   NONE      the JNI left the exception pending for a later GLASS_CHECK_EXCEPTION on the main thread to report; the
 *             target reports it at once.
 *
 * A slot is dialled while the table is installed and Java is attached to the main thread; a NULL slot is skipped, and
 * the C carries on as after GMAC_UPCALL_OK. Where the JNI printed "Java has been detached already, but someone is
 * still trying to use it" (GET_MAIN_JENV), before slots 1 and 4-15, 17 and 18, the C prints it and skips the slot.
 * Slots 0 and 19 are skipped silently while Java is detached; slots 2, 3 and 16 run inside runLoop: while it is
 * attached, and slot 20 is dialled whether or not it is, as the JNI made its call. The number before each slot is its
 * position, which gmac_test_fire_app_callback takes.
 */
typedef struct GmacAppCallbacks {
    /*  0 REPORT. Runs what gmac_app_invoke_later or gmac_app_invoke_and_wait posted, unless Java detached the main
     *    thread before it ran: then it never runs, as a JNI runnable never did. */
    int32_t (*run_runnable)(int64_t runnable_id);
    /*  1 DESCRIBE. The launchable of a delegate gmac_app_create_delegate was told of. On THREW the C does not observe
     *    screen parameter changes, so notify_screen_settings_changed never fires. */
    int32_t (*run_launchable)(void);
    /*  2 REPORT. MacApplication.setEventThread(), from runLoop:. It is the first slot dialled on the main thread, and
     *    its upcall attaches the thread to Java as a daemon "Thread-N", which the JNI's AttachCurrentThreadAsDaemon
     *    did just before this point; the thread stays attached. */
    int32_t (*set_event_thread)(void);
    /*  3 REPORT. Application.setName(String) with the bundle's display name, valid for the call. */
    int32_t (*set_name)(const char *utf8, int32_t length);
    /*  4-14 REPORT. Application.notifyWillFinishLaunching() ... notifyWillQuit(). */
    int32_t (*notify_will_finish_launching)(void);
    int32_t (*notify_did_finish_launching)(void);
    int32_t (*notify_will_become_active)(void);
    int32_t (*notify_did_become_active)(void);
    int32_t (*notify_will_resign_active)(void);
    int32_t (*notify_did_resign_active)(void);
    int32_t (*notify_will_hide)(void);
    int32_t (*notify_did_hide)(void);
    int32_t (*notify_will_unhide)(void);
    int32_t (*notify_did_unhide)(void);
    int32_t (*notify_will_quit)(void);
    /* 15 REPORT. Application.notifyOpenFiles(String[]): one string per file, a NULL one standing for a null element;
     *    valid for the call. OK once the String[] exists, whether or not notifyOpenFiles then threw. THREW when
     *    building the String[] threw: the C then prints the message of a failed NewObjectArray and replies
     *    NSApplicationDelegateReplyFailure, where it otherwise replies Success. */
    int32_t (*notify_open_files)(const char *const *utf8, const int32_t *lengths, int32_t count);
    /* 16 REPORT. MacApplication.notifyApplicationDidTerminate(), after [NSApp run] returned. */
    int32_t (*notify_did_terminate)(void);
    /* 17 DESCRIBE. static Application.enterNestedEventLoop(); returns when the loop is left. */
    int32_t (*enter_nested_event_loop)(void);
    /* 18 NONE. static Application.leaveNestedEventLoop(null). */
    int32_t (*leave_nested_event_loop)(void);
    /* 19 REPORT. static Screen.notifySettingsChanged(). */
    int32_t (*notify_screen_settings_changed)(void);
    /* 20 REPORT at each step. The entries gmac_app_collect_preferences answers, valid for the call. Java builds the
     *    map from them, compares it with the map it kept from the last call (null at first), and when they differ
     *    keeps the new one and calls MacApplication.notifyPreferencesChanged with an unmodifiable view of it. */
    int32_t (*notify_preferences_changed)(const GmacPref *entries, int32_t count);
} GmacAppCallbacks;  /* 21 pointers */

/*
 * ---- GmacTimerCallbacks: the upcall of MacTimer's native side ----
 *
 * The timers gmac_timer_start_period and gmac_timer_start_vsync made dial it. It runs on a thread no Java code
 * created, which the upcall attaches to Java as a daemon "Thread-N" and detaches only when the thread ends. It
 * answers GMAC_UPCALL_OK or GMAC_UPCALL_THREW, which the C ignores, and no Java exception may escape it.
 */
typedef struct GmacTimerCallbacks {
    /*
     * 0 Runs the Runnable of timer_id. Like the JNI's global reference, the id is in place only once the start
     *   export has made the thread or set the display link's context, so a tick that races the start runs nothing.
     *   Period timer: on its "Glass Timer Thread" pthread, inside an autorelease pool. REPORT: the JNI attached the
     *   thread, unnamed ("Thread-N"), before its first tick and reported after that pool drained; the target reports
     *   inside it.
     *   Vsync timer: on the display link's thread, inside the callback's pool. NONE: the JNI attached the thread as
     *   "PulseTimer-CVDisplayLink thread" and left a Throwable pending. A tick that did not throw kept it, and a tick
     *   that threw replaced it. After a tick that left the timer paused (gmac_timer_paused) the JNI detached the
     *   thread, and HotSpot then passed what was pending to the thread's uncaught-exception handler.
     */
    int32_t (*tick)(int64_t timer_id);
} GmacTimerCallbacks;  /* 1 pointer */

/*
 * ---- GmacWindowCallbacks: the upcalls of MacWindow's native side ----
 *
 * Every slot runs on the AppKit main thread for the window_id gmac_window_create was given, often inside a
 * gmac_window_* call or a nested event loop. No Java exception may escape a slot. The JNI left what slots 0-8 threw
 * pending (NONE, as GmacAppCallbacks calls it) for a later GLASS_CHECK_EXCEPTION on the main thread; the target
 * reports it at once through Application.reportException. Slot 9 stands where the JNI deleted its global reference
 * and then ran GLASS_CHECK_EXCEPTION: REPORT.
 *
 * A slot answers GMAC_UPCALL_THREW when it raised, and reported, a new exception, else GMAC_UPCALL_OK; the C reads no
 * status.
 *
 * A slot is dialled while a table is installed and Java is attached to the main thread; a NULL slot is skipped. Where
 * the JNI printed "Java has been detached already, but someone is still trying to use it" and went on with a NULL JNI
 * environment (GET_MAIN_JENV), the C prints it and skips the slot. Every pointer a slot is given is valid for the
 * call only. The number before each slot is its position, which gmac_test_fire_window_callback takes.
 */
typedef struct GmacWindowCallbacks {
    /* 0 MacWindow.notifyMove(int, int, boolean): the frame's origin, truncated, with y measured down from the top of
     *   the first screen, and 1 when the window is zoomed. */
    int32_t (*notify_move)(int64_t window_id, int32_t x, int32_t y, int32_t is_zoomed);
    /* 1 MacWindow.notifyResize(int, int, int): a WindowEvent type, which is MINIMIZE while the window is miniaturized
     *   and else MAXIMIZE while it is zoomed, and the frame's size, truncated. */
    int32_t (*notify_resize)(int64_t window_id, int32_t type, int32_t width, int32_t height);
    /* 2 Window.notifyMoveToAnotherScreen(Screen), after slot 0 or 1, when the window is on a screen other than the one
     *   it was created on or last moved to. The JNI built the Screen from these values; when that failed, it reported
     *   the failure and passed null. */
    int32_t (*notify_move_to_another_screen)(int64_t window_id, const GmacScreenInfo *screen);
    /* 3 Window.notifyScaleChanged(float, float, float, float): 1.0f, 1.0f, then the backing scale factor of the
     *   window's screen, narrowed to float, twice. */
    int32_t (*notify_scale_changed)(int64_t window_id, float platform_scale_x, float platform_scale_y,
                                    float output_scale_x, float output_scale_y);
    /* 4 Window.notifyClose(), when the user asks an enabled window to close. */
    int32_t (*notify_close)(int64_t window_id);
    /* 5 Window.notifyFocus(int): WindowEvent.FOCUS_GAINED or FOCUS_LOST. */
    int32_t (*notify_focus)(int64_t window_id, int32_t event);
    /* 6 Window.notifyFocusUngrab(), when the window that holds the grab releases it. */
    int32_t (*notify_focus_ungrab)(int64_t window_id);
    /* 7 Window.notifyFocusDisabled(), after NSBeep, when a disabled window became key. */
    int32_t (*notify_focus_disabled)(int64_t window_id);
    /* 8 Window.notifyDestroy(), when the window will close, after the windows it owns closed. */
    int32_t (*notify_destroy)(int64_t window_id);
    /* 9 The window's GlassWindow was deallocated, after its NSWindow: no slot runs for window_id after this one, so
     *   it is the only place where Java may forget the id. Skipped while Java is detached, as the JNI then kept its
     *   global reference. */
    int32_t (*notify_dispose)(int64_t window_id);
} GmacWindowCallbacks;  /* 10 pointers */

/*
 * What MacView._create read from Application.getDeviceDetails() to choose and set up the view's layer, one member per
 * key. A member keeps its default, given last, when the map is null, has no entry for the key or maps it to null; a
 * NULL caps stands for every default. A Metal layer is made when mtl_command_queue is not NULL or mtl_for_sw is not 0,
 * else an OpenGL one, and each reads only the members marked for it.
 */
typedef struct GmacViewCaps {
    void   *mtl_command_queue;  /*  0 "mtlCommandQueue", a Long: Prism's id<MTLCommandQueue>, as a raw address. NULL */
    void   *share_context;      /*  8 "shareContextPtr", a Long: an NSOpenGLContext*. OpenGL. NULL */
    void   *context;            /* 16 "contextPtr", a Long: an NSOpenGLContext*. OpenGL. NULL */
    int32_t depth_bits;         /* 24 View.Capability.k3dDepthKey, an Integer. OpenGL. 0 */
    int32_t hidpi_aware;        /* 28 View.Capability.kHiDPIAwareKey, a Boolean, 1 for TRUE. OpenGL: a Metal view is
                                      always HiDPI-aware. 0 */
    int32_t mtl_for_sw;         /* 32 "useMTLInGlassForSW", a Boolean, 1 for TRUE. 0 */
    int32_t vsync_enabled;      /* 36 "isVsyncEnabled", a Long: 1 when it is 1L, else 0. Metal. 1 */
} GmacViewCaps;                 /* 40 bytes */

/*
 * ---- GmacViewCallbacks: the upcalls of MacView's native side ----
 *
 * Every slot runs on the AppKit main thread for the view_id gmac_view_create was given, often inside a gmac_window_*
 * or gmac_view_* call or a nested event loop. No Java exception may escape a slot. Each JNI site but the Caps Lock pair
 * of slot 5 checked for an exception at once, so the target reports what it throws through
 * Application.reportException (REPORT); the C reads no status.
 *
 * A slot writes its out-parameters only when it returns normally. The C sets them first to what the JNI read after an
 * exception, which is also what it reads when the slot is NULL or skipped: 0, or NULL for a pointer.
 *
 * A slot is dialled while a table is installed and Java is attached to the main thread; a NULL slot is skipped. Where
 * the JNI printed "Java has been detached already, but someone is still trying to use it" and went on with a NULL JNI
 * environment (GET_MAIN_JENV), the C prints it and skips the slot. Every pointer a slot is given is valid for the
 * call only. The number before each slot is its position, which gmac_test_fire_view_callback takes.
 */
typedef struct GmacViewCallbacks {
    /*  0 View.notifyView(int): ViewEvent.ADD when the view moved into a window, REMOVE when it left one, and
     *    FULLSCREEN_ENTER or FULLSCREEN_EXIT when it entered or left full screen. */
    int32_t (*notify_view)(int64_t view_id, int32_t type);
    /*  1 MacView.notifyResize(int, int): the view's new frame size, truncated. */
    int32_t (*notify_resize)(int64_t view_id, int32_t width, int32_t height);
    /*  2 View.notifyRepaint(int, int, int, int): the view's frame, truncated, from -drawRect:. */
    int32_t (*notify_repaint)(int64_t view_id, int32_t x, int32_t y, int32_t width, int32_t height);
    /*  3 View.notifyMenu(int, int, int, int, boolean), after a popup-trigger press: its location in the view and on
     *    the screen, truncated, and is_keyboard_trigger, always 0. */
    int32_t (*notify_menu)(int64_t view_id, int32_t x, int32_t y, int32_t x_abs, int32_t y_abs,
                           int32_t is_keyboard_trigger);
    /*  4 MacView.notifyMouse(int, int, int, int, int, int, int, boolean, boolean), for every mouse event but a wheel
     *    event, which takes slot 1 of GmacGestureCallbacks: a MouseEvent type and button, the location in the view
     *    and on the screen, truncated, the KeyEvent.MODIFIER_* mask, 1 for a popup trigger, and is_synthesized, always
     *    0. */
    int32_t (*notify_mouse)(int64_t view_id, int32_t type, int32_t button, int32_t x, int32_t y, int32_t x_abs,
                            int32_t y_abs, int32_t modifiers, int32_t is_popup_trigger, int32_t is_synthesized);
    /*  5 View.notifyKeyAndReturnConsumed(int, int, char[], int): a KeyEvent type, the key code, 0 for TYPED, the
     *    char_count UTF-16 units at chars (never NULL) and the modifiers; *out_consumed takes 1 when Java answered
     *    true. A key event dials this up to three times and a modifier change up to six, and the JNI passed every
     *    dial of one event the same char[]. same_chars_as_previous is 1 when the same event made the previous dial of
     *    this slot: Java then passes the char[] it passed that dial. Else it is 0, also for a dial that follows a
     *    nested event's, and Java passes a new char[] of the units. What the Caps Lock pair of a modifier change threw
     *    the JNI left pending for the check after the pair (NONE, as GmacAppCallbacks calls it); the target reports
     *    it at once. */
    int32_t (*notify_key)(int64_t view_id, int32_t type, int32_t key_code, const uint16_t *chars, int32_t char_count,
                          int32_t modifiers, int32_t same_chars_as_previous, int32_t *out_consumed);
    /*  6 MacView.notifyInputMethodMac(String, int, int, int, int, int): the text_len UTF-16 units at text, "" for
     *    text_len 0, when text may be NULL; the attribute, 1 for marked and 4 for committed text; the length; the
     *    cursor; and the selected range's location and length, narrowed from NSUInteger, so NSNotFound arrives as -1.
     *    Not dialled where the JNI made no String: committed attributed text with a length of 0 or less, or which
     *    cannot be copied. Main thread only: the C drops a call made elsewhere. */
    int32_t (*notify_input_method)(int64_t view_id, const uint16_t *text, int32_t text_len, int32_t attr,
                                   int32_t length, int32_t cursor, int32_t sel_start, int32_t sel_length);
    /*  7 View.notifyInputMethodCandidatePosRequest(int), pos 0: when the double[] Java answers has 2 elements, Java
     *    writes them to out_xy[0] and out_xy[1] and 1 to *out_valid, and the C makes them the origin of the candidate
     *    window. Main thread only, as slot 6. */
    int32_t (*notify_input_method_candidate_pos_request)(int64_t view_id, int32_t pos, double *out_xy,
                                                         int32_t *out_valid);
    /*  8 View.notifyDragEnter(int, int, int, int, int), 9 notifyDragOver and 10 notifyDragDrop, the same: the drag's
     *    location in the view and on the screen, truncated, and the recommended Clipboard.ACTION_*; *out_action takes
     *    the action Java answers. */
    int32_t (*notify_drag_enter)(int64_t view_id, int32_t x, int32_t y, int32_t x_abs, int32_t y_abs,
                                 int32_t recommended_action, int32_t *out_action);
    int32_t (*notify_drag_over)(int64_t view_id, int32_t x, int32_t y, int32_t x_abs, int32_t y_abs,
                                int32_t recommended_action, int32_t *out_action);
    int32_t (*notify_drag_drop)(int64_t view_id, int32_t x, int32_t y, int32_t x_abs, int32_t y_abs,
                                int32_t recommended_action, int32_t *out_action);
    /* 11 View.notifyDragLeave(). */
    int32_t (*notify_drag_leave)(int64_t view_id);
    /* 12 View.notifyDragEnd(int): the Clipboard.ACTION_* that the drag this view started ended with. */
    int32_t (*notify_drag_end)(int64_t view_id, int32_t performed_action);
    /* 13 View.getAccessible(), on each accessibility query AppKit makes of the view: *out_accessible takes the
     *    GlassAccessible* Java answers, which MacAccessible owns and the C does not retain. */
    int32_t (*get_accessible)(int64_t view_id, void **out_accessible);
    /* 14 The view's GlassViewDelegate was deallocated, which View.close causes under the render lock: no slot runs for
     *    view_id after this one, so it is the only place, for a view that was made, where Java may forget the id.
     *    Skipped while Java is detached, as the JNI then kept its global reference. */
    int32_t (*notify_dispose)(int64_t view_id);
} GmacViewCallbacks;  /* 15 pointers */

/*
 * ---- GmacGestureCallbacks: the upcalls of MacGestureSupport's native side ----
 *
 * The static methods of MacGestureSupport, which the view's trackpad gestures and wheel events call with the View of
 * view_id, under the rules of GmacViewCallbacks: REPORT, and the location is in the view and on the screen, truncated.
 * The JNI looked MacGestureSupport up with Class.forName on every event, which initialized it on the first; nothing
 * but the first dial may initialize it now. The number before each slot is its position, which
 * gmac_test_fire_gesture_callback takes.
 */
typedef struct GmacGestureCallbacks {
    /* 0 rotateGesturePerformed(View, int, int, int, int, int, float): the modifiers and [NSEvent rotation]. */
    int32_t (*rotate_gesture_performed)(int64_t view_id, int32_t modifiers, int32_t x, int32_t y, int32_t x_abs,
                                        int32_t y_abs, float rotation);
    /* 1 scrollGesturePerformed(View, int, int, int, int, int, int, float, float), for a wheel event: the modifiers,
     *   the MacGestureSupport.SCROLL_SRC_* sender, and the scrolling deltas times 0.1 where they are precise, else the
     *   deltas, computed in double and narrowed to float. */
    int32_t (*scroll_gesture_performed)(int64_t view_id, int32_t modifiers, int32_t sender, int32_t x, int32_t y,
                                        int32_t x_abs, int32_t y_abs, float delta_x, float delta_y);
    /* 2 swipeGesturePerformed(View, int, int, int, int, int, int): the modifiers and a SwipeGesture.DIR_*, or 0. */
    int32_t (*swipe_gesture_performed)(int64_t view_id, int32_t modifiers, int32_t direction, int32_t x, int32_t y,
                                       int32_t x_abs, int32_t y_abs);
    /* 3 magnifyGesturePerformed(View, int, int, int, int, int, float): the modifiers and [NSEvent magnification],
     *   narrowed to float. */
    int32_t (*magnify_gesture_performed)(int64_t view_id, int32_t modifiers, int32_t x, int32_t y, int32_t x_abs,
                                         int32_t y_abs, float magnification);
    /* 4 gestureFinished(View, int, int, int, int, int): the modifiers, when the last gesture in progress ended or was
     *   cancelled. */
    int32_t (*gesture_finished)(int64_t view_id, int32_t modifiers, int32_t x, int32_t y, int32_t x_abs,
                                int32_t y_abs);
} GmacGestureCallbacks;  /* 5 pointers */

/*
 * One representation of a MacPasteboard item, the Object[] {UTI, value} at MacPasteboard.UtfIndex and ObjectIndex,
 * which the C reads as the JNI read it, by its UTI: the value of a string UTI as a String, that of the raw or the
 * drag image UTI as a Pixels, and any other as a byte[]. A value of another kind, which the JNI read as that kind
 * all the same, sets nothing. The pointers are read during the call only; an image is released whatever its UTI.
 */
typedef struct GmacPasteboardRep {
    const uint16_t *uti;   /*  0 the UTI's UTF-16 units */
    int32_t uti_length;    /*  8 0 makes the UTI nil, as an empty String did */
    int32_t kind;          /* 12 GMAC_PASTEBOARD_REP_* */
    const void *data;      /* 16 STRING: the String's UTF-16 units. BYTES: the byte[]'s bytes, at least 8 readable, as
                                 the drag image offset's UTI reads 8 whatever data_length is (latent bug), so Java
                                 pads a shorter byte[] with zeros where the JNI read past it. IMAGE: the +1 NSImage*
                                 Pixels.attachData wrote, or NULL. NONE, BAD: unused */
    int32_t data_length;   /* 24 STRING: the units. BYTES: the bytes of the byte[]. BAD: the array's length, which the
                                 C logs with NSLog */
    int32_t reserved;      /* 28 0 */
} GmacPasteboardRep;       /* 32 bytes */

/* One MacPasteboard item, the Object[] of its representations. */
typedef struct GmacPasteboardItem {
    const GmacPasteboardRep *reps;  /*  0 */
    int32_t rep_count;              /*  8 GMAC_PASTEBOARD_ITEM_NULL for a null Object[], which makes no item */
    int32_t reserved;               /* 12 0 */
} GmacPasteboardItem;               /* 16 bytes */

/*
 * One CommonDialogs.ExtensionFilter of a file dialog: what getDescription() and extensionsToArray() answered, each
 * string taken as "" when it is null or empty, as the JNI's nsStringWithJavaString took it. The C copies them before
 * the panel runs and keeps the path extension of each extension, "txt" for "*.txt".
 */
typedef struct GmacFileFilter {
    const uint16_t *description;        /*  0 */
    int32_t description_length;         /*  8 */
    int32_t extension_count;            /* 12 */
    const uint16_t *const *extensions;  /* 16 extension_count strings, NULL for a null element */
    const int32_t *extension_lengths;   /* 24 their lengths */
} GmacFileFilter;                       /* 32 bytes */

/*
 * ---- GmacDialogCallbacks: the upcalls of MacCommonDialogs' native side ----
 *
 * Both slots run on the AppKit main thread for the loop_id a gmac_dialog_* call was given. No Java exception may
 * escape a slot. The JNI checked for one at once after each, so the target reports what it throws through
 * Application.reportException (REPORT); the C reads no status.
 *
 * A slot is dialled while the table is installed and Java is attached to the main thread; a NULL slot is skipped.
 * Without a table the dialogs do not begin their panel. The number before each slot is its position, which
 * gmac_test_fire_dialog_callback takes.
 */
typedef struct GmacDialogCallbacks {
    /* 0 EventLoop.enter() of the loop, once the panel began: it returns when slot 1 has made the nested loop leave. */
    int32_t (*event_loop_enter)(int64_t loop_id);
    /* 1 EventLoop.leave(null) of the loop, from the panel's completion handler, once the C recorded the panel's
     *   result. It can come after slot 0 returned, when enter() threw at once: the JNI then called through the global
     *   reference it had deleted (latent bug), so Java ignores a loop_id it no longer holds. */
    int32_t (*event_loop_leave)(int64_t loop_id);
} GmacDialogCallbacks;  /* 2 pointers */

/*
 * The icon of a menu item, a Pixels that is not null: what Pixels.attachData wrote and the Pixels' size and scales,
 * which the JNI read only when attachData wrote an image. The image is released by the call it is passed to.
 */
typedef struct GmacMenuImage {
    void   *image;    /*  0 the +1 NSImage*, or NULL, which leaves the item's image as it is */
    int32_t width;    /*  8 Pixels.width */
    int32_t height;   /* 12 Pixels.height */
    float   scale_x;  /* 16 Pixels.scalex: above 1 together with scale_y, and with a positive size, the image is
                             sized to width / scale_x by height / scale_y points */
    float   scale_y;  /* 20 Pixels.scaley */
} GmacMenuImage;      /* 24 bytes */

/*
 * ---- GmacMenuCallbacks: the upcalls of MacMenuDelegate's native side ----
 *
 * Every slot runs on the AppKit main thread for the menu_id gmac_menu_create or gmac_menu_item_create was given:
 * slots 0, 2 and 3 from AppKit's event dispatch and menu tracking, slot 1 there too and inside gmac_menubar_insert,
 * gmac_menubar_remove and gmac_menu_remove, whose [[NSApp mainMenu] update] validates the menu bar's items, and slot
 * 4 inside gmac_menubar_remove and gmac_menu_remove. No Java exception may escape a slot. The JNI checked for none
 * after slots 0-3 and left what they threw pending (NONE, as GmacAppCallbacks calls it) for a later
 * GLASS_CHECK_EXCEPTION on the main thread; the target reports it at once through Application.reportException. Slot 4
 * stands where the JNI deleted its global references, which ran no Java; the target reports what it throws (REPORT).
 * The C reads no status.
 *
 * A slot is dialled while the table is installed and Java is attached to the main thread; a NULL slot is skipped,
 * and the C carries on as after GMAC_UPCALL_OK. Where the JNI printed "Java has been detached already, but someone is
 * still trying to use it" (GET_MAIN_JENV), the C prints it and skips the slot. Without a table no slot runs and
 * validation answers YES. The number before each slot is its position, which gmac_test_fire_menu_callback takes.
 */
typedef struct GmacMenuCallbacks {
    /* 0 MenuItem.Callback.action() of the item's callback, when the item was chosen; Java returns when the item has
     *   no callback, as the JNI made no call. The C prints the detached message for an item without a callback too,
     *   where the JNI printed it only for one with a callback. */
    int32_t (*action)(int64_t menu_id);
    /* 1 MenuItem.Callback.validate() of the item's callback, when AppKit validates an item whose target is this
     *   GlassMenu; the C then answers whether the target's item is enabled, which validate() may have changed, and
     *   YES while Java is detached. The JNI called through a null callback all the same (latent bug), which left a
     *   NullPointerException pending. */
    int32_t (*validate)(int64_t menu_id);
    /* 2 Menu.notifyMenuOpening() of the delegate's menu, when its menu will open and no window holds the grab: when
     *   one holds it, the C cancels the tracking and releases the grab instead (slot 6 of GmacWindowCallbacks). The
     *   JNI called through a null menu all the same, which left a NullPointerException pending. */
    int32_t (*menu_opening)(int64_t menu_id);
    /* 3 Menu.notifyMenuClosed() of the delegate's menu, when its menu closed, under the same rule. */
    int32_t (*menu_closed)(int64_t menu_id);
    /* 4 The GlassMenu was deallocated, when gmac_menubar_remove or gmac_menu_remove released it: no slot runs for
     *   menu_id after this one, so it is the only place where Java may forget the id. Skipped while Java is
     *   detached, as the JNI then kept its global references. A GlassMenu never removed is never deallocated
     *   (latent bug), and its id stays. */
    int32_t (*menu_disposed)(int64_t menu_id);
} GmacMenuCallbacks;  /* 5 pointers */

/*
 * One MacVariant but for its unused stringArray: what slots 1 and 8 of GmacAccessibleCallbacks answer, and what
 * gmac_a11y_id_to_variant reads an object as. type is MacVariant.type, a com_sun_glass_ui_mac_MacVariant_* constant;
 * 0, which is none of them, stands for a null MacVariant. Every other member is the MacVariant field of its name. An
 * array is a block and its count, -1 for a null array, whose block is NULL then. A string from Java is chars and
 * chars_length, its UTF-16 units, with NULL chars for a null string; a string to Java is utf8 and utf8_length, in the
 * strings-out convention.
 */
typedef struct GmacVariant {
    int32_t type;                       /*   0 */
    int32_t int1;                       /*   4 */
    int32_t int2;                       /*   8 */
    int32_t location;                   /*  12 a style's range in an NSAttributedString */
    int32_t length;                     /*  16 */
    float float1;                       /*  20 */
    float float2;                       /*  24 */
    float float3;                       /*  28 */
    float float4;                       /*  32 */
    int32_t long_array_count;           /*  36 */
    int64_t long1;                      /*  40 an id for NSObject, seconds since 1970 for NSDate */
    double double1;                     /*  48 */
    void *key;                          /*  56 a style's NSString* attribute name, borrowed */
    void **long_array;                  /*  64 ids, borrowed: NSArray_id's, and NSDictionary's keys */
    int32_t *int_array;                 /*  72 */
    int32_t int_array_count;            /*  80 */
    int32_t chars_length;               /*  84 */
    uint16_t *chars;                    /*  88 Java to C */
    char *utf8;                         /*  96 C to Java */
    struct GmacVariant *variant_array;  /* 104 NSDictionary's values, NSAttributedString's styles */
    int32_t variant_array_count;        /* 112 */
    int32_t utf8_length;                /* 116 */
} GmacVariant;                          /* 120 bytes */

/*
 * ---- GmacAccessibleCallbacks: the upcalls of MacAccessible's native side ----
 *
 * Slots 0-14 run on the AppKit main thread for the peer_id gmac_a11y_create_peer was given, when AppKit or an
 * assistive application queries the peer, often inside a downcall a slot's Java made. Slot 15 runs on whichever
 * thread released the peer last. No Java exception may escape a slot. The JNI checked for one at once after each, so
 * the target reports what it throws through Application.reportException (REPORT) and answers GMAC_UPCALL_THREW, else
 * GMAC_UPCALL_OK; the C reads the status only where slot 11 says.
 *
 * A slot writes its out-parameters only when it returns normally. The C sets them first to what the JNI read after an
 * exception, which is also what it reads when the slot is NULL: a null variant, a NULL block with a count of -1, NULL
 * chars, 0, or NULL. The C then takes over every block a slot wrote and frees it with free(), so Java allocates them
 * with libSystem's malloc: the ids of an array, the UTF-16 units of a string, at least one byte for an empty one, and
 * the blocks of a GmacVariant, its variant_array and those of its elements. An NSException the C raises while it makes
 * an object of them, as for a 0 id (latent bug), leaves them allocated.
 *
 * attribute, value, child, parameter and action are borrowed for the call, and the ids a slot answers are borrowed;
 * the C retains none of them, as the JNI did not.
 *
 * Slots 0-14 are dialled while the table is installed and Java is attached to the main thread. Where the JNI printed
 * "Java has been detached already, but someone is still trying to use it" (GET_MAIN_JENV), the C prints it and
 * answers what the JNI answered without a call. The number before each slot is its position, which
 * gmac_test_fire_accessible_callback takes.
 */
typedef struct GmacAccessibleCallbacks {
    /*  0 MacAccessible.accessibilityAttributeNames(): *out_ids takes the long[]'s ids, *out_count its length. */
    int32_t (*attribute_names)(int64_t peer_id, void ***out_ids, int32_t *out_count);
    /*  1 accessibilityAttributeValue(long), for GlassAccessible's -accessibilityAttributeValue: and for
     *    AccessibleBase's -requestNodeAttribute:, which serves its getters and those of the JFX*Accessibility classes:
     *    *out_value takes the MacVariant. */
    int32_t (*attribute_value)(int64_t peer_id, void *attribute, GmacVariant *out_value);
    /*  2 accessibilityIsAttributeSettable(long), for -accessibilityIsAttributeSettable: and AccessibleBase's
     *    -isNodeAttributeSettable:, whose *out_settable takes 1 for true. */
    int32_t (*is_attribute_settable)(int64_t peer_id, void *attribute, int32_t *out_settable);
    /*  3 accessibilitySetValue(long, long), for -accessibilitySetValue:forAttribute: and AccessibleBase's
     *    -setNodeAttribute:forAttribute:. */
    int32_t (*set_value)(int64_t peer_id, void *value, void *attribute);
    /*  4 accessibilityIndexOfChild(long): *out_index takes the long; -1 makes GlassAccessible answer NSObject's. */
    int32_t (*index_of_child)(int64_t peer_id, void *child, int64_t *out_index);
    /*  5 accessibilityArrayAttributeCount(long): *out_count takes the int; -1 makes GlassAccessible answer NSObject's.
     *    While Java is detached GlassAccessible answers NSUIntegerMax (latent bug). */
    int32_t (*array_attribute_count)(int64_t peer_id, void *attribute, int32_t *out_count);
    /*  6 accessibilityArrayAttributeValues(long, int, int), index and max_count narrowed from NSUInteger: as slot 0.
     *    A null long[] makes GlassAccessible answer NSObject's; while Java is detached it answers nil (latent bug). */
    int32_t (*array_attribute_values)(int64_t peer_id, void *attribute, int32_t index, int32_t max_count,
                                      void ***out_ids, int32_t *out_count);
    /*  7 accessibilityParameterizedAttributeNames(): as slot 0. */
    int32_t (*parameterized_attribute_names)(int64_t peer_id, void ***out_ids, int32_t *out_count);
    /*  8 accessibilityAttributeValueForParameter(long, long), for -accessibilityAttributeValue:forParameter: and
     *    AccessibleBase's -requestNodeAttribute:forParameter:, as slot 1. */
    int32_t (*attribute_value_for_parameter)(int64_t peer_id, void *attribute, void *parameter,
                                             GmacVariant *out_value);
    /*  9 accessibilityActionNames(): as slot 0. */
    int32_t (*action_names)(int64_t peer_id, void ***out_ids, int32_t *out_count);
    /* 10 accessibilityActionDescription(long): *out_chars takes the String's UTF-16 units, *out_length their count. */
    int32_t (*action_description)(int64_t peer_id, void *action, uint16_t **out_chars, int32_t *out_length);
    /* 11 accessibilityPerformAction(long), for GlassAccessible's -accessibilityPerformAction: and AccessibleBase's
     *    -performAccessibleAction:, which answers NO when the slot answers GMAC_UPCALL_THREW, else YES, and NO while
     *    Java is detached. */
    int32_t (*perform_action)(int64_t peer_id, void *action);
    /* 12 accessibilityIsIgnored(): *out_ignored takes 1 for true. */
    int32_t (*is_ignored)(int64_t peer_id, int32_t *out_ignored);
    /* 13 accessibilityHitTest(float, float): the point AppKit gave, narrowed to float; *out_element takes the long. */
    int32_t (*hit_test)(int64_t peer_id, float x, float y, void **out_element);
    /* 14 accessibilityFocusedUIElement(): *out_element takes the long. */
    int32_t (*focused_ui_element)(int64_t peer_id, void **out_element);
    /* 15 The peer was deallocated: no slot runs for peer_id after this one, so it is the only place where Java may
     *    forget the id, and its target may touch only what is safe on any thread. Skipped while Java is detached
     *    from the main thread, as the JNI then kept its global reference. */
    int32_t (*peer_released)(int64_t peer_id);
} GmacAccessibleCallbacks;  /* 16 pointers */

GMAC_EXPORT int32_t gmac_sizeof_app_callbacks(void);  /* 168 */
GMAC_EXPORT int32_t gmac_sizeof_screen_info(void);    /* 88 */
GMAC_EXPORT int32_t gmac_sizeof_color(void);          /* 24 */
GMAC_EXPORT int32_t gmac_sizeof_pref(void);           /* 48 */
GMAC_EXPORT int32_t gmac_sizeof_timer_callbacks(void);  /* 8 */
GMAC_EXPORT int32_t gmac_sizeof_window_callbacks(void);  /* 80 */
GMAC_EXPORT int32_t gmac_sizeof_view_caps(void);  /* 40 */
GMAC_EXPORT int32_t gmac_sizeof_view_callbacks(void);  /* 120 */
GMAC_EXPORT int32_t gmac_sizeof_gesture_callbacks(void);  /* 40 */
GMAC_EXPORT int32_t gmac_sizeof_pasteboard_rep(void);  /* 32 */
GMAC_EXPORT int32_t gmac_sizeof_pasteboard_item(void);  /* 16 */
GMAC_EXPORT int32_t gmac_sizeof_file_filter(void);  /* 32 */
GMAC_EXPORT int32_t gmac_sizeof_dialog_callbacks(void);  /* 16 */
GMAC_EXPORT int32_t gmac_sizeof_menu_image(void);  /* 24 */
GMAC_EXPORT int32_t gmac_sizeof_menu_callbacks(void);  /* 40 */
GMAC_EXPORT int32_t gmac_sizeof_variant(void);  /* 120 */
GMAC_EXPORT int32_t gmac_sizeof_accessible_callbacks(void);  /* 128 */

/* The offset of a struct member, counted from 0 in declaration order; -1 for a member the struct does not have. */
GMAC_EXPORT int32_t gmac_offsetof_screen_info(int32_t member);
GMAC_EXPORT int32_t gmac_offsetof_color(int32_t member);
GMAC_EXPORT int32_t gmac_offsetof_pref(int32_t member);
GMAC_EXPORT int32_t gmac_offsetof_view_caps(int32_t member);
GMAC_EXPORT int32_t gmac_offsetof_pasteboard_rep(int32_t member);
GMAC_EXPORT int32_t gmac_offsetof_pasteboard_item(int32_t member);
GMAC_EXPORT int32_t gmac_offsetof_file_filter(int32_t member);
GMAC_EXPORT int32_t gmac_offsetof_menu_image(int32_t member);
GMAC_EXPORT int32_t gmac_offsetof_variant(int32_t member);

/*
 * Copies the table; NULL uninstalls it. Install it once, before gmac_app_create_delegate creates the delegate:
 * nothing orders a second install against the main thread's reads of the copy. Always GMAC_OK.
 */
GMAC_EXPORT int32_t gmac_app_install_callbacks(const GmacAppCallbacks *callbacks);

/*
 * Copies the table; NULL uninstalls it. Install it once, from MacTimer's class initialization, before the first
 * gmac_timer_start_*: nothing orders a second install against the timer threads' reads of the copy. Always GMAC_OK.
 */
GMAC_EXPORT int32_t gmac_timer_install_callbacks(const GmacTimerCallbacks *callbacks);

/*
 * Copies the table; NULL uninstalls it. Install it once, from MacWindow's class initialization, before the first
 * gmac_window_create: nothing orders a second install against the main thread's reads of the copy. Always GMAC_OK.
 */
GMAC_EXPORT int32_t gmac_window_install_callbacks(const GmacWindowCallbacks *callbacks);

/*
 * Copies the table; NULL uninstalls it. Install both once, from MacView's class initialization, before the first
 * gmac_view_create: nothing orders a second install against the main thread's reads of the copy. Always GMAC_OK.
 */
GMAC_EXPORT int32_t gmac_view_install_callbacks(const GmacViewCallbacks *callbacks);
GMAC_EXPORT int32_t gmac_gesture_install_callbacks(const GmacGestureCallbacks *callbacks);

/*
 * Copies the table; NULL uninstalls it. Install it once, from MacCommonDialogs' class initialization, before the first
 * gmac_dialog_*: nothing orders a second install against the main thread's reads of the copy. Always GMAC_OK.
 */
GMAC_EXPORT int32_t gmac_dialog_install_callbacks(const GmacDialogCallbacks *callbacks);

/*
 * Copies the table; NULL uninstalls it. Install it once, from MacMenuDelegate's class initialization, before the first
 * gmac_menu_create or gmac_menu_item_create: nothing orders a second install against the main thread's reads of the
 * copy. Always GMAC_OK.
 */
GMAC_EXPORT int32_t gmac_menu_install_callbacks(const GmacMenuCallbacks *callbacks);

/*
 * Copies the table; NULL uninstalls it. Install it once, from MacAccessible's class initialization, before the first
 * gmac_a11y_create_peer: nothing orders a second install against the reads of the copy, on the main thread and, for
 * slot 15, on others. Always GMAC_OK.
 */
GMAC_EXPORT int32_t gmac_a11y_install_callbacks(const GmacAccessibleCallbacks *callbacks);

/*
 * ---- MacApplication ----
 *
 * app is the GlassApplication* gmac_app_create_delegate answered, which MacApplication keeps as delegateHandle. "FX"
 * is the AppKit main thread, which is the FX thread. None of these may be critical.
 */

/*
 * MacApplication's class initialization, on that thread. Answers GMAC_ERR_UNSUPPORTED_OS, after an NSLog naming both
 * versions, when this macOS is older than the minimum the library was built for (10.16 counts as 11.0); Java then
 * throws RuntimeException("Unsupported macOS version"), as the JNI did, and nothing is recorded. Otherwise records
 * disable_sync_rendering, != 0 to disable, which the OpenGL frame buffer reads before its glFinish, and answers
 * GMAC_OK. Like the JNI, it opens no pool, so what it autoreleases goes to the calling thread's.
 */
GMAC_EXPORT int32_t gmac_app_init(int32_t disable_sync_rendering);

/*
 * Launcher thread, after gmac_app_install_callbacks. Answers a +1 GlassApplication*, which
 * gmac_app_finish_terminating releases, or NULL when it cannot be made. taskbar_application == 1 makes runLoop: turn
 * the process into a regular application with a Dock icon, anything else into a UI element, as the JNI compared with
 * JNI_TRUE. has_launchable != 0 says Java holds a launchable: then applicationWillFinishLaunching: dials
 * run_launchable, and observes screen parameter changes unless it answered GMAC_UPCALL_THREW.
 *
 * MacApplication._initDelegate also took the Java application, the launchable and Glass's class loader, for its
 * upcalls and their Class.forName lookups. The C needs none of them: every site dials its slot, and Java resolves
 * the targets. Nor does runLoop: attach the main thread to Java: the first upcall, set_event_thread, does, where the
 * JNI attached it. Like the JNI, it opens no pool.
 */
GMAC_EXPORT void *gmac_app_create_delegate(int32_t taskbar_application, int32_t has_launchable);

/*
 * Launcher thread. Performs runLoop: on the main thread and waits, polling every 10 ms, until AppKit's run loop has
 * started; under -XstartOnFirstThread it runs runLoop: inline and returns only when the application terminated.
 */
GMAC_EXPORT void gmac_app_run_loop(void *app);

/* FX. Releases app (NULL is allowed), then, unless embedded, stops NSApp and wakes its run loop. */
GMAC_EXPORT void gmac_app_finish_terminating(void *app);

/* FX. Runs events until gmac_app_leave_nested_loop; re-entrant, and calls back into Java. The value is Java's. */
GMAC_EXPORT void gmac_app_enter_nested_loop(void);

/* FX. Makes the innermost gmac_app_enter_nested_loop return. */
GMAC_EXPORT void gmac_app_leave_nested_loop(void);

/*
 * Post run_runnable(runnable_id) to the main thread: invoke_later returns at once, invoke_and_wait (any thread but
 * FX) once the slot returned. The C never interprets the id. GMAC_OK when posted; when Java is detached from the
 * main thread the request is dropped, as the JNI dropped it, with GMAC_ERR_NOT_ATTACHED on the main thread and
 * GMAC_ERR_DETACHED_OFF_MAIN elsewhere, the case where the JNI threw "Main Java thread is detached.".
 */
GMAC_EXPORT int32_t gmac_app_invoke_later(int64_t runnable_id);
GMAC_EXPORT int32_t gmac_app_invoke_and_wait(int64_t runnable_id);

/* FX. 1 when the delegate hides and reactivates the application on its first activation, else 0. */
GMAC_EXPORT int32_t gmac_app_is_trigger_reactivation(void);

/* FX. [NSApp hide:NSApp], [NSApp hideOtherApplications:NSApp], [NSApp unhideAllApplications:NSApp]. */
GMAC_EXPORT void gmac_app_hide(void);
GMAC_EXPORT void gmac_app_hide_other_applications(void);
GMAC_EXPORT void gmac_app_unhide_all_applications(void);

/* FX. The refresh period, in ms, of the display link MacTimer created; 0.0 before it exists. */
GMAC_EXPORT double gmac_screen_video_refresh_period(void);

/*
 * FX. One entry per +[NSScreen screens], in order, as a gmac_free block (NULL when there is no screen), and the
 * largest frame recorded for window sizing. GMAC_OK, or GMAC_ERR_NO_DATA with NULL and 0 when the block cannot be
 * allocated.
 */
GMAC_EXPORT int32_t gmac_screens_query(GmacScreenInfo **out_screens, int32_t *out_count);

/*
 * Strings out: on GMAC_OK *out_utf8 is a gmac_free block of *out_length bytes and a NUL. GMAC_ERR_NO_DATA, with
 * NULL and 0, where the JNI answered null.
 * data_directory, FX: the last NSApplicationSupportDirectory of NSUserDomainMask, tilde expanded.
 * class_name, FX: the class of +[NSApplicationFX sharedApplication], which creates NSApp when there is none.
 */
GMAC_EXPORT int32_t gmac_app_data_directory(char **out_utf8, int32_t *out_length);
GMAC_EXPORT int32_t gmac_app_class_name(char **out_utf8, int32_t *out_length);

/*
 * FX. The platform preferences as one gmac_free block of entries, the colour array included. GMAC_ERR_NO_DATA, with
 * NULL and 0, when app is NULL, before applicationWillFinishLaunching or when the block cannot be allocated.
 */
GMAC_EXPORT int32_t gmac_app_collect_preferences(void *app, GmacPref **out_entries, int32_t *out_count);

/* FX. The Mac key code of a KeyEvent.VK_* code, & 0xFFFF; 0 when there is none, which is also kVK_ANSI_A. */
GMAC_EXPORT int32_t gmac_key_java_to_mac(int32_t java_key_code);

/*
 * Any thread, reading the main thread's map without a lock, as the JNI did. The KeyEvent.VK_* code of a key that has
 * typed the character or prints it, or KeyEvent.VK_UNDEFINED.
 */
GMAC_EXPORT int32_t gmac_key_code_for_char(uint16_t character);

/* FX. +[NSEvent modifierFlags]. */
GMAC_EXPORT uint64_t gmac_current_modifier_flags(void);

/*
 * Any thread; waits up to 1 s. Opens uri (NULL for a Java null, which raises an Objective-C exception, as the JNI
 * did) with the default browser and answers the OSStatus of the attempt, or -1 when no default browser is set.
 * *out_completed is 1 where the JNI went on to its closing GLASS_CHECK_EXCEPTION and 0 at that -1: so, when
 * gmac_main_thread_detached answered 1 before the call, Java reports the RuntimeException on 1 and throws it on 0.
 */
GMAC_EXPORT int32_t gmac_app_open_uri(const uint16_t *uri, int32_t length, int32_t *out_completed);

/*
 * ---- MacPixels and MacCursor ----
 *
 * None of these may be critical: gmac_image_create allocates by the caller's size and the others run AppKit. None
 * reaches a JNI upcall site: their natives' closing GLASS_CHECK_EXCEPTION covered only the detached assertion and, in
 * _createCursor, the Pixels.attachData upcall, both of which are Java's now.
 */

/*
 * FX. Writes to *out_image a +1 NSImage*, width x height points in sRGB, holding a copy of the width * height pixels
 * read from pixels during the call: rows without padding, 4 bytes of premultiplied BGRA (Pixels.Format.BYTE_BGRA_PRE)
 * each, pixels pointing at the first, past any array or buffer offset. Writes NULL when pixels is NULL, width or
 * height is not positive, or the image cannot be made. out_image must not be NULL. The image is the caller's:
 * gmac_cursor_create takes it, and a C caller of Pixels.attachData releases what its slot holds afterwards.
 */
GMAC_EXPORT void gmac_image_create(const uint8_t *pixels, int32_t width, int32_t height, void **out_image);

/*
 * Once, from MacCursor's class initialization: builds the cursors gmac_cursor_set picks from. Like the JNI, it opens
 * no pool, so what it autoreleases goes to the calling thread's.
 */
GMAC_EXPORT void gmac_cursor_init(void);

/*
 * FX. A +1 NSCursor* (Cursor.ptr, never released) showing image with its hot spot at (x, y), or NULL when image is
 * NULL. Releases image, a +1 NSImage* from gmac_image_create, once the cursor holds it.
 */
GMAC_EXPORT void *gmac_cursor_create(void *image, int32_t x, int32_t y);

/*
 * FX. Sets the cursor of a Cursor.CURSOR_* type; MacCursor passes CURSOR_DEFAULT (1) to CURSOR_MAX (19), and 0 is
 * the arrow. Any other type raises an Objective-C NSRangeException, as the JNI did.
 */
GMAC_EXPORT void gmac_cursor_set(int32_t type);

/* FX. Sets cursor, an NSCursor* from gmac_cursor_create; NULL does nothing. */
GMAC_EXPORT void gmac_cursor_set_custom(void *cursor);

/* FX. [NSCursor unhide] when visible is not 0, else [NSCursor hide]; AppKit counts them, so callers balance them. */
GMAC_EXPORT void gmac_cursor_set_visible(int32_t visible);

/*
 * FX. The size MacCursor reports for a requested width x height: a positive dimension is kept, and any other becomes
 * the arrow cursor image's, truncated toward zero, or 32 when the arrow has no image.
 */
GMAC_EXPORT void gmac_cursor_best_size(int32_t width, int32_t height, int32_t *out_width, int32_t *out_height);

/*
 * ---- MacTimer ----
 *
 * Any thread: QuantumToolkit starts and stops its pulse timer on the FX thread, pauses it from its tick, on the
 * timer's own thread, and resumes it from any thread. timer is the GlassTimer* a start export answered (Timer.ptr).
 * A vsync timer is never freed. A period timer's thread frees it after its loop has seen gmac_timer_stop, so timer
 * is dead once stop returns. None of these may be critical but gmac_timer_paused: the starts make a thread or set a
 * CoreVideo callback, and the others start and stop the display link.
 *
 * The former JNI natives of the start, stop, pause and resume exports opened with GLASS_ASSERT_MAIN_JAVA_THREAD,
 * ran the body all the same, with no JNI call between that could raise, and reported the RuntimeException at their
 * closing GLASS_CHECK_EXCEPTION. _getMinPeriod and _getMaxPeriod did the same around the constants 0 and 1000000 ms.
 *
 * The JNI released a period timer's Runnable when its thread ended, which can be one tick after gmac_timer_stop
 * returned, and never released a vsync timer's; the C reports neither end. A vsync timer ticks while it is the
 * display link's context, until the next gmac_timer_start_vsync replaces it.
 */

/*
 * Once, from MacTimer's class initialization, which must come before gmac_screen_video_refresh_period: unless it
 * exists, creates the display link of the active displays, on the main display, with the callback and a NULL
 * context, and starts it. A failing step is logged with NSLog and the next one runs. Opens no pool, as the JNI's
 * _initIDs did not.
 */
GMAC_EXPORT void gmac_timer_init(void);

/*
 * Starts the "Glass Timer Thread" (SCHED_FIFO, second highest priority), which, until gmac_timer_stop, ticks
 * timer_id unless paused, then sleeps period ms less the tick's time, or yields when the tick took longer. NULL,
 * after an NSLog, when the thread cannot be created.
 */
GMAC_EXPORT void *gmac_timer_start_period(int64_t timer_id, int32_t period);

/*
 * Makes a timer the display link's context, so that the link's thread ticks timer_id at each refresh while the link
 * runs. When CVDisplayLinkSetOutputCallback fails it logs, the link keeps its previous context and the timer never
 * ticks; the timer is answered all the same, never NULL.
 */
GMAC_EXPORT void *gmac_timer_start_vsync(int64_t timer_id);

/*
 * Period timer: clears the flag its loop checks before each tick, so one more tick can begin after this returns.
 * Vsync timer: stops the display link and releases it, when it runs, without clearing GlassDisplayLink (latent bug:
 * a later display-link call reads the released link).
 */
GMAC_EXPORT void gmac_timer_stop(void *timer);

/*
 * Period timer: sets or clears the paused flag. Vsync timer: stops or starts the display link and sets or clears the
 * flag when that succeeds. pause logs when the link is NULL, not running or will not stop; resume logs only when the
 * link will not start.
 */
GMAC_EXPORT void gmac_timer_pause(void *timer);
GMAC_EXPORT void gmac_timer_resume(void *timer);

/* Any thread, without a lock, as the display link's callback read it after a tick: 1 when paused, else 0. */
GMAC_EXPORT int32_t gmac_timer_paused(void *timer);

/*
 * ---- MacRobot ----
 *
 * FX: MacRobot runs on the event thread only. Java posts the robot's events through CoreGraphics, HIServices and
 * CoreFoundation itself and calls these for what is Objective-C or libglass's own state. None of them may be critical:
 * the pool exports allocate and drain, gmac_key_find_mac reads the keyboard layout and the last one runs AppKit.
 *
 * Each former robot native opened with GLASS_ASSERT_MAIN_JAVA_THREAD and ran its whole body inside one
 * GLASS_POOL_ENTER/EXIT pair, these calls included.
 */

/*
 * GLASS_POOL_PUSH and GLASS_POOL_POP: GLASS_POOL_ENTER and GLASS_POOL_EXIT split across two calls, as MacView's _begin
 * and _end use them. The calling thread's first push allocates its pool data. The push from depth 0 creates an
 * NSAutoreleasePool and the pop back to depth 0 drains it, so a nested pair only counts. A pop must follow a push on
 * the same thread. A push never popped leaves the depth raised for good, and the thread's later pools then never
 * drain: the latent bug of the JNI's early returns inside a pool, which a caller carries by not popping there.
 */
GMAC_EXPORT void gmac_pool_push(void);
GMAC_EXPORT void gmac_pool_pop(void);

/*
 * FX. The Mac key code GetMacKey finds for a KeyEvent.VK_* code, 0 to 0xFFFF, or -1 when it finds none, so that
 * kVK_ANSI_A (0) is told from no key, which gmac_key_java_to_mac cannot do. A key that the US QWERTY table places in
 * the layout-sensitive area is searched for again in the current keyboard layout.
 */
GMAC_EXPORT int32_t gmac_key_find_mac(int32_t java_key_code);

/*
 * FX. +[NSEvent mouseLocation], in points, with y measured down from the top of the first screen. With no screen it
 * raises an Objective-C NSRangeException, as the JNI did. Opens no pool, as the former -[GlassRobot getMousePosFlipped]
 * did not: the natives called it inside theirs.
 */
GMAC_EXPORT void gmac_mouse_location_flipped(double *out_x, double *out_y);

/*
 * ---- MacWindow ----
 *
 * FX: Window calls each of these on the event thread. window is the NSWindow* gmac_window_create answered
 * (Window.ptr), whose delegate is the window's GlassWindow. A NULL window returns at once, answering 0, as the former
 * natives returned before their GLASS_ASSERT_MAIN_JAVA_THREAD. None of these may be critical: they run AppKit, which
 * calls back into Java through the callback tables and in nested event loops.
 *
 * Each former native opened with GLASS_ASSERT_MAIN_JAVA_THREAD, but for _toFront, which had none, ran its body all
 * the same and closed with GLASS_CHECK_EXCEPTION, which reported what a JNI upcall had left pending. No upcall leaves
 * one pending now, so the exports have no closing check.
 *
 * MacWindow._isRightToLeftLayoutDirection has no export. Its NSLocale messages and CFLocaleCopyPreferredLanguages,
 * CFArrayGetValueAtIndex(list, 0), CFLocaleCreate, CFLocaleGetValue(kCFLocaleLanguageCode) and
 * CFLocaleGetLanguageCharacterDirection run the same implementations, so Java calls those, unguarded: on an empty
 * list both trap alike. gmac_test_nslocale_language_direction is the oracle.
 */

/*
 * Makes the GlassWindow of window_id for style_mask, the Window.TITLED to DARK_FRAME bits, on screen (an NSScreen* of
 * a GmacScreenInfo, or NULL) and owned by owner (an NSWindow* this function answered, or NULL), and answers its
 * NSWindow*, an NSPanel for a popup or an opaque utility window. Slots can run for window_id before it returns, so
 * Java registers the id first. Latent bug: when the window cannot be made, it dereferences NULL.
 */
GMAC_EXPORT void *gmac_window_create(void *owner, void *screen, int32_t style_mask, int64_t window_id);

/* Closes the window on a later pass of the main run loop, without asking windowShouldClose:; answers 1. */
GMAC_EXPORT int32_t gmac_window_close(void *window);

/*
 * The opening of the former _setView: a window in native full screen leaves it, and waits in a nested event loop
 * until it has. That loop runs Java, which can close the view, so Java reads View.ptr for gmac_window_set_view only
 * after this returns, where the JNI read it.
 */
GMAC_EXPORT void gmac_window_leave_native_full_screen(void *window);

/*
 * The rest of the former _setView, after gmac_window_leave_native_full_screen: makes view, a GlassView3D* (View.ptr),
 * or NULL for none, the window's content; answers 1.
 */
GMAC_EXPORT int32_t gmac_window_set_view(void *window, void *view);

/*
 * Sets menubar, a GlassMenubar* (MacMenuBarDelegate.ptr), as the window's menu bar; answers 1. Latent bug: a NULL
 * menubar is dereferenced unless the application is embedded and the window is not key.
 */
GMAC_EXPORT int32_t gmac_window_set_menubar(void *window, void *menubar);

/* Miniaturizes the window when minimize is not 0, else deminiaturizes it; answers 1. */
GMAC_EXPORT int32_t gmac_window_minimize(void *window, int32_t minimize);

/*
 * Zooms the window when maximize is not 0 and is_zoomed is 0, unzooms it when maximize is 0 and is_zoomed is not 0,
 * and, when its frame changed, dials slots 0 and 1 for the move and the resize; answers 1.
 */
GMAC_EXPORT int32_t gmac_window_maximize(void *window, int32_t maximize, int32_t is_zoomed);

/*
 * Window._setBounds in points: x and y where x_set and y_set are not 0, the frame's width and height where positive,
 * else the content's where positive. The gravities are ignored, as they were.
 */
GMAC_EXPORT void gmac_window_set_bounds(void *window, int32_t x, int32_t y, int32_t x_set, int32_t y_set,
                                        int32_t width, int32_t height, int32_t content_width,
                                        int32_t content_height, float x_gravity, float y_gravity);

/* Shows the window when visible is not 0, else hides it; answers 1 when it is visible afterwards, else 0. */
GMAC_EXPORT int32_t gmac_window_set_visible(void *window, int32_t visible);

/* Records whether the window is resizable and, for a decorated window, applies it; answers 1. */
GMAC_EXPORT int32_t gmac_window_set_resizable(void *window, int32_t resizable);

/* Brings a visible window to the front as the main and key window; answers 1 when it is key afterwards, else 0. */
GMAC_EXPORT int32_t gmac_window_request_focus(void *window);

GMAC_EXPORT void gmac_window_set_focusable(void *window, int32_t focusable);

/* title is UTF-16, read during the call; a null or empty title sets @""; answers 1. */
GMAC_EXPORT int32_t gmac_window_set_title(void *window, const uint16_t *title, int32_t length);

/* level is a Window.Level constant: FLOATING and TOPMOST raise the window, every other value is NORMAL. */
GMAC_EXPORT void gmac_window_set_level(void *window, int32_t level);

GMAC_EXPORT void gmac_window_set_alpha(void *window, float alpha);

/* An opaque sRGB colour, which a transparent window replaces with the clear colour; answers 1. */
GMAC_EXPORT int32_t gmac_window_set_background(void *window, float red, float green, float blue);

GMAC_EXPORT void gmac_window_set_enabled(void *window, int32_t enabled);

/* Both answer 1. set_maximum_size takes -1 for no limit in a dimension. */
GMAC_EXPORT int32_t gmac_window_set_minimum_size(void *window, int32_t width, int32_t height);
GMAC_EXPORT int32_t gmac_window_set_maximum_size(void *window, int32_t width, int32_t height);

/*
 * Shows width x height pixels of premultiplied BGRA (Pixels.Format.BYTE_BGRA_PRE), read during the call from pixels,
 * the base address of the Pixels' direct buffer, as the title-bar icon; NULL removes the icon. Latent bug: the image
 * is made height x width.
 */
GMAC_EXPORT void gmac_window_set_icon(void *window, const uint8_t *pixels, int32_t width, int32_t height);

/* The dark or the light (Aqua) appearance for the window's frame. */
GMAC_EXPORT void gmac_window_set_dark_frame(void *window, int32_t dark);

GMAC_EXPORT void gmac_window_to_front(void *window);
GMAC_EXPORT void gmac_window_to_back(void *window);

/* Makes the window hold the grab, releasing any other window's through its slot 6; answers 1. */
GMAC_EXPORT int32_t gmac_window_grab_focus(void *window);

/* Releases the grab when the window holds it, through slot 6. */
GMAC_EXPORT void gmac_window_ungrab_focus(void *window);

/* Moves the window with the mouse, from the current event, as a drag of its title bar does. */
GMAC_EXPORT void gmac_window_perform_drag(void *window);

/* What a double click on the title bar does by the AppleActionOnDoubleClick default: minimize, zoom or nothing. */
GMAC_EXPORT void gmac_window_perform_title_bar_double_click_action(void *window);

/* An NSWindowToolbarStyle, and whether the close, miniaturize and zoom buttons show (buttons_visible not 0). */
GMAC_EXPORT void gmac_window_set_button_style(void *window, int32_t toolbar_style, int32_t buttons_visible);

/* The dark or the light (Aqua) appearance for the close, miniaturize and zoom buttons. */
GMAC_EXPORT void gmac_window_set_button_appearance(void *window, int32_t dark);

/*
 * ---- MacView ----
 *
 * view is the GlassView3D* gmac_view_create answered (View.ptr). A NULL view returns at once, answering 0, as the
 * former natives returned before their GLASS_ASSERT_MAIN_JAVA_THREAD. "FX" is the event thread View checks, the AppKit
 * main thread; "render" is the thread of View.lock, unlock and getNativeFrameBuffer. gmac_view_begin, gmac_view_end
 * and gmac_view_get_native_frame_buffer, which those call, and gmac_view_multi_click_time_ms, which MacView's
 * initializer calls on the thread that initializes the class, are the only exports that can run off the main
 * thread. None of these may be critical: they run AppKit, CGL or Metal, which call back into Java through the
 * callback tables, run nested event loops or wait for the GPU.
 *
 * Each former native opened with GLASS_ASSERT_MAIN_JAVA_THREAD and ran its body all the same. Those of the FX
 * exports but the uploads closed with GLASS_CHECK_EXCEPTION, which also reported what a JNI upcall had left pending;
 * no upcall leaves one pending now, so the exports have no closing check.
 *
 * Off the main thread while that thread was detached, the assertion left its RuntimeException pending and the
 * native's end raised it. The exports do not evaluate the assertion. Java evaluates gmac_main_thread_detached before
 * each call whose native asserted, for any view but NULL, and before gmac_view_multi_click_time_ms, which takes no
 * view; it makes the call all the same and then raises the RuntimeException as the native's end did. It reports it
 * once after the exports whose native closed with GLASS_CHECK_EXCEPTION, gmac_view_get_native_frame_buffer among
 * them, and throws it after gmac_view_begin, gmac_view_end, gmac_view_multi_click_time_ms and the uploads, whose
 * natives had no closing check. An upload throws only when its native got past the checks it made before the
 * assertion, which the export repeats. This holds for the FX exports too, which only glass.disableThreadChecks lets
 * View call off the main thread. gmac_view_create is the exception: Java evaluates nothing around it, as its native
 * crashed on the main thread's NULL JNI environment in the GlassViewDelegate's initializer, before its closing check.
 *
 * MacView._getMultiClickMaxX_impl and _getMultiClickMaxY_impl answered the literal 3; their assertion could fire only
 * if the main thread detached between those calls in MacView's initializer, after _getMultiClickTime_impl's found it
 * attached, a race this does not reproduce (PB7). _setParent only asserted. So they have no export, and Java's
 * _setParent throws the RuntimeException itself, for any view but NULL, when gmac_main_thread_detached answers 1.
 */

/* FX. Makes the view of view_id for caps (NULL for every default) inside a GlassHostView of its own, so that the view
 * can move between windows, and answers its GlassView3D*. Java registers the id before the call; it forgets the id
 * when the call answers NULL, which makes no GlassViewDelegate. */
GMAC_EXPORT void *gmac_view_create(int64_t view_id, const GmacViewCaps *caps);

/* Render, between gmac_view_begin and gmac_view_end. The painter offscreen's framebuffer: an OpenGL framebuffer name,
 * or an id<MTLTexture>. */
GMAC_EXPORT int64_t gmac_view_get_native_frame_buffer(void *view);

/* FX. The content's offset in the window's frame, x from the left and y from the top, taking the bottom border as
 * empty; 0 for a view in no window. */
GMAC_EXPORT int32_t gmac_view_get_x(void *view);
GMAC_EXPORT int32_t gmac_view_get_y(void *view);

/* FX. Removes the view from its host view and releases both; answers 1. */
GMAC_EXPORT int32_t gmac_view_close(void *view);

/*
 * Render. gmac_view_begin pushes the thread's autorelease pool (gmac_pool_push), retains the view and binds its layer
 * for the view's size, in pixels when the view is HiDPI-aware; gmac_view_end, on the same thread, flushes the layer,
 * releases the view and pops the pool, so that the pool spans the painting between them.
 */
GMAC_EXPORT void gmac_view_begin(void *view);
GMAC_EXPORT void gmac_view_end(void *view);

/* FX. Marks the view as needing display. */
GMAC_EXPORT void gmac_view_schedule_repaint(void *view);

/* FX. Toggles the window into full screen and waits in a nested event loop until it is; the flags are ignored, as
 * they were. Answers 1. */
GMAC_EXPORT int32_t gmac_view_enter_fullscreen(void *view, int32_t animate, int32_t keep_ratio, int32_t hide_cursor);

/* FX. Leaves native full screen and waits in a nested event loop until it has; a view not in native full screen gets
 * notify_view(FULLSCREEN_EXIT) at once. animate is ignored, as it was. */
GMAC_EXPORT void gmac_view_exit_fullscreen(void *view, int32_t animate);

/*
 * FX, between View.lock and unlock. Shows width x height premultiplied BGRA pixels, read during the call, scaled by
 * scale_x and scale_y; nothing is shown unless width and height are positive. Java returns before the call for a null
 * buffer or array, as the natives did.
 * _direct: pixels is the base address of the Pixels' direct buffer, whatever its position, and is passed on even when
 * NULL, as GetDirectBufferAddress's NULL for a buffer that is not direct was. Latent bug: there is no capacity check.
 * _bytes, _ints: array holds array_length bytes or ints, NULL for none; nothing is shown for a negative offset, or
 * when the image, offset elements into array, does not fit below INT_MAX or in array_length.
 */
GMAC_EXPORT void gmac_view_upload_pixels_direct(void *view, const void *pixels, int32_t width, int32_t height,
                                                float scale_x, float scale_y);
GMAC_EXPORT void gmac_view_upload_pixels_bytes(void *view, const uint8_t *array, int32_t array_length, int32_t offset,
                                               int32_t width, int32_t height, float scale_x, float scale_y);
GMAC_EXPORT void gmac_view_upload_pixels_ints(void *view, const uint32_t *array, int32_t array_length, int32_t offset,
                                              int32_t width, int32_t height, float scale_x, float scale_y);

/* FX. Enables input method events when enable is not 0, else disables them, discarding marked text when it changes. */
GMAC_EXPORT void gmac_view_enable_input_method_events(void *view, int32_t enable);

/* FX. Commits the marked text through slot 6, then discards it when the view's input context is the current one. */
GMAC_EXPORT void gmac_view_finish_input_method_composition(void *view);

/* Any thread, without a pool: +[NSEvent doubleClickInterval] in ms, truncated from the interval times 1000.0f. */
GMAC_EXPORT int64_t gmac_view_multi_click_time_ms(void);

/*
 * ---- MacPasteboard and MacSystemClipboard ----
 *
 * pasteboard is the NSPasteboard* gmac_pasteboard_create_system or _create_user answered (MacPasteboard.ptr). FX:
 * MacPasteboard runs on the event thread, the AppKit main thread. None of these may be critical: they talk to the
 * pasteboard server, decode images or begin a drag. None reaches a JNI upcall site: the closing GLASS_CHECK_EXCEPTION
 * of their natives covered the detached assertion and JNI calls that are Java's now, Pixels.attachData among them.
 *
 * Each former MacPasteboard native but _initIDs opened with GLASS_ASSERT_MAIN_JAVA_THREAD, ran its body all the same,
 * and reported the RuntimeException at the first GLASS_CHECK_EXCEPTION it reached, then answered as usual. In
 * _putItemsFromArray that check came before the first Pixels.attachData.
 *
 * A pasteboard string (gmac_pasteboard_get_utis, gmac_pasteboard_get_item_string) does not follow the strings-out
 * rule: it is what the JNI's createUTF gave new String(byte[], "UTF-8"), the first lengthOfBytesUsingEncoding:
 * bytes of -[NSString UTF8String], none for a string UTF-8 cannot hold, such as one with a lone surrogate. Java
 * decodes it as UTF-8. A length that is negative as a jsize, where the JNI crashed, answers GMAC_ERR_NO_DATA. Every
 * block these answer has a NUL past its length, so that an empty one is not NULL; GMAC_ERR_NO_DATA comes with NULL
 * and 0 where the JNI answered null.
 */

/* +0, never released: NSGeneralPboard for MacPasteboard.General, NSDragPboard for DragAndDrop, else NULL. */
GMAC_EXPORT void *gmac_pasteboard_create_system(int32_t type);

/* +1, never balanced (latent bug: releaseGlobally does not release it). A null or empty name makes the name nil. */
GMAC_EXPORT void *gmac_pasteboard_create_user(const uint16_t *name, int32_t length);

/* The pasteboard's name, in the strings-out convention. */
GMAC_EXPORT int32_t gmac_pasteboard_get_name(void *pasteboard, char **out_utf8, int32_t *out_length);

/*
 * The UTIs of every item, GMAC_ERR_NO_DATA when there is none, as one block of native-endian int32_t, unaligned: the
 * item count, then per item the count of its UTIs, 0 for an item that has none, which was a null String[], then per
 * UTI its byte count and its bytes.
 */
GMAC_EXPORT int32_t gmac_pasteboard_get_utis(void *pasteboard, uint8_t **out_block, int32_t *out_length);

/*
 * The former _getItemAsRawImage's byte[] for item index, a negative index answering GMAC_ERR_NO_DATA: the image of its
 * TIFF, PNG or PDF data, or of the URL or file URL it holds, drawn in sRGB as width x height premultiplied BGRA
 * pixels after 8 bytes of width and height, each the bytes 3 to 0 of a size_t (latent bug: big-endian on a
 * little-endian host only). GMAC_ERR_NO_DATA when there is no image, or it fails either of two different size bounds
 * (latent bug). An index past the last item raises an Objective-C NSRangeException, as the JNI did.
 */
GMAC_EXPORT int32_t gmac_pasteboard_get_item_as_raw_image(void *pasteboard, int32_t index, uint8_t **out_block,
                                                          int32_t *out_length);

/*
 * The string item index holds for uti, as a pasteboard string; GMAC_ERR_NO_DATA for a negative index, an empty uti or
 * when it holds none. An index past the last item raises an Objective-C NSRangeException, as the JNI did.
 */
GMAC_EXPORT int32_t gmac_pasteboard_get_item_string(void *pasteboard, int32_t index, const uint16_t *uti,
                                                    int32_t uti_length, char **out_utf8, int32_t *out_length);

/*
 * The bytes item index holds for uti; GMAC_ERR_NO_DATA for a negative index, an empty uti or when it holds none. Also
 * GMAC_ERR_NO_DATA, with the length in *out_length, when the length is negative as a jsize: the JNI's NewByteArray then
 * threw a NegativeArraySizeException with the length as its message, which it reported, and it answered null. An index
 * past the last item raises an Objective-C NSRangeException, as the JNI did.
 */
GMAC_EXPORT int32_t gmac_pasteboard_get_item_bytes(void *pasteboard, int32_t index, const uint16_t *uti,
                                                   int32_t uti_length, uint8_t **out_bytes, int32_t *out_length);

/*
 * Clears the pasteboard, makes an NSPasteboardItem of every item but the null ones, then begins a drag of them from
 * the view that holds the mouse drag, with the Clipboard.ACTION_* in supported_actions, when pasteboard is the drag
 * pasteboard and such a view exists, else writes them to the pasteboard. Answers the change count clearContents gave.
 */
GMAC_EXPORT int64_t gmac_pasteboard_put_items(void *pasteboard, const GmacPasteboardItem *items, int32_t item_count,
                                              int32_t supported_actions);

/* Clears the pasteboard and answers the change count clearContents gave. */
GMAC_EXPORT int64_t gmac_pasteboard_clear(void *pasteboard);

/* -[NSPasteboard changeCount]. */
GMAC_EXPORT int64_t gmac_pasteboard_get_seed(void *pasteboard);

/* -[NSPasteboard releaseGlobally]. */
GMAC_EXPORT void gmac_pasteboard_release(void *pasteboard);

/*
 * The Clipboard.ACTION_* a drop may perform: what the drag source of the drag pasteboard supports, and COPY for any
 * other pasteboard.
 */
GMAC_EXPORT int32_t gmac_pasteboard_get_allowed_operation(void *pasteboard);

/*
 * Any thread, as the JNI asserted no thread. The path of the file URL that path, parsed as a URL, refers to, such as a
 * file reference URL; path itself when it is no URL or refers to no file. A null path is taken as "". The strings-out
 * convention.
 */
GMAC_EXPORT int32_t gmac_clipboard_file_reference_path(const uint16_t *path, int32_t length, char **out_utf8,
                                                       int32_t *out_length);

/*
 * ---- MacFileNSURL and MacCommonDialogs ----
 *
 * url is an NSURL*: MacFileNSURL.ptr, which holds a reference until dispose, or one that a dialog export or
 * gmac_url_create_from_bookmark retained for the caller, who keeps it in a MacFileNSURL or releases it.
 * MacFileNSURL._dispose has no export: its [url release] compiles to a tail call of objc_release(url). FX: the callers
 * check the event thread, but for gmac_url_copy_path. None of these may be critical: the dialogs run a panel in a
 * nested event loop that calls back into Java, the bookmark exports reach the file system and the others message an
 * NSURL.
 *
 * The three dialog natives opened with GLASS_ASSERT_MAIN_JAVA_THREAD, ran their body all the same and closed with
 * GLASS_CHECK_EXCEPTION, which also reported what a JNI upcall had left pending. The exports do not evaluate the
 * assertion, and no upcall leaves an exception pending now, so they have no closing check. The MacFileNSURL natives
 * asserted nothing.
 */

/* [url startAccessingSecurityScopedResource]: 1 when it answered YES, else 0. */
GMAC_EXPORT int32_t gmac_url_start_accessing_security_scoped_resource(void *url);

/* [url stopAccessingSecurityScopedResource]. */
GMAC_EXPORT void gmac_url_stop_accessing_security_scoped_resource(void *url);

/*
 * The security-scoped bookmark data of url relative to base_url, NULL for none, in a gmac_free block of *out_length
 * bytes and a NUL, so that an empty one is not NULL. GMAC_ERR_NO_DATA, with NULL and 0, when the NSError out-parameter
 * was set, which is logged with NSLog, or the block cannot be allocated. Also GMAC_ERR_NO_DATA, with the length in
 * *out_length, when the length is negative as a jsize: the JNI's NewByteArray then threw a NegativeArraySizeException
 * with the length as its message, which it reported, and it answered null.
 */
GMAC_EXPORT int32_t gmac_url_get_bookmark(void *url, void *base_url, uint8_t **out_bytes, int32_t *out_length);

/*
 * Resolves the length bytes at data as a bookmark, without UI and with its security scope, relative to base_url, NULL
 * for none, and writes the URL to *out_url, retained, or NULL when the resolution answered nil without an error. A
 * stale bookmark is logged with NSLog first. GMAC_ERR_NO_DATA, with NULL, when the NSError out-parameter was set, which
 * is logged with NSLog, or when data is NULL: Java passes a non-NULL data even for length 0, as the JNI's
 * GetPrimitiveArrayCritical answered for an empty byte[], whose NULL left the native with nothing to resolve.
 */
GMAC_EXPORT int32_t gmac_url_create_from_bookmark(const uint8_t *data, int32_t length, void *base_url, void **out_url);

/*
 * Any thread. [[url path] dataUsingEncoding:NSUTF16LittleEndianStringEncoding], the UTF-16 units the JNI gave
 * NewString, in a gmac_free block of *out_length units and a NUL, so that an empty one is not NULL; a NULL url, like
 * nil, has the empty path. GMAC_ERR_NO_DATA, with NULL and 0, when the block cannot be allocated.
 */
GMAC_EXPORT int32_t gmac_url_copy_path(void *url, uint16_t **out_chars, int32_t *out_length);

/*
 * The former _showFileOpenChooser, _showFileSaveChooser and _showFolderChooser, but for what was Java's: configure an
 * NSOpenPanel or an NSSavePanel as they did, then, on the main thread, begin it as a sheet of owner, an NSWindow*
 * (Window.ptr), or on its own when owner is NULL, and dial slot 0 of GmacDialogCallbacks with loop_id, which returns
 * once the completion handler's slot 1 has made the nested loop leave.
 *
 * The strings are taken as "" when null or empty: title becomes the panel's title, a folder that is not empty its
 * directory, and a filename that is not empty its name, without its path extension. filters, filter_count of them,
 * make the popup of file types when filter_count is above 0, with default_index selected; Java passes 0 for a null
 * or an empty array alike. multiple is the open panel's multiple selection.
 *
 * The panel is not begun, and the dialog answers as cancelled, when loop_id is 0, which Java passes when its
 * new EventLoop() threw, where the JNI returned with the exception pending, when no GmacDialogCallbacks table is
 * installed, or when Java is detached from the main thread, after the message GET_MAIN_JENV printed, where the JNI
 * crashed on the NULL JNI environment.
 *
 * A panel that ended with NSModalResponseOK answers its URLs, each retained for the caller: gmac_dialog_open those of
 * [panel URLs] in a gmac_free block of *out_url_count pointers, gmac_dialog_save [panel URL] and gmac_dialog_folder
 * the first of [panel URLs]. Otherwise NULL and 0. When filter_count is above 0, *out_selected takes the popup's
 * indexOfSelectedItem, -1 when nothing is selected, and else -1. gmac_dialog_open answers GMAC_OK, or GMAC_ERR_NO_DATA
 * when the block cannot be allocated, which retains no URL.
 */
GMAC_EXPORT int32_t gmac_dialog_open(void *owner, const uint16_t *folder, int32_t folder_length, const uint16_t *title,
                                     int32_t title_length, int32_t multiple, const GmacFileFilter *filters,
                                     int32_t filter_count, int32_t default_index, int64_t loop_id, void ***out_urls,
                                     int32_t *out_url_count, int64_t *out_selected);
GMAC_EXPORT void gmac_dialog_save(void *owner, const uint16_t *folder, int32_t folder_length,
                                  const uint16_t *filename, int32_t filename_length, const uint16_t *title,
                                  int32_t title_length, const GmacFileFilter *filters, int32_t filter_count,
                                  int32_t default_index, int64_t loop_id, void **out_url, int64_t *out_selected);
GMAC_EXPORT void gmac_dialog_folder(void *owner, const uint16_t *folder, int32_t folder_length, const uint16_t *title,
                                    int32_t title_length, int64_t loop_id, void **out_url);

/*
 * ---- MacMenuBarDelegate and MacMenuDelegate ----
 *
 * menubar is the GlassMenubar* gmac_menubar_create answered (MacMenuBarDelegate.ptr), which MacWindow passes to
 * gmac_window_set_menubar. menu and submenu are the GlassMenu* gmac_menu_create or gmac_menu_item_create answered
 * (MacMenuDelegate.ptr): an NSMenuItem and, for a menu, the NSMenu it opens. FX: Menu and MenuItem check the event
 * thread. A title is taken as "" when null or empty. None of these may be critical: they run AppKit, and the insert
 * and remove exports dial the GmacMenuCallbacks slots.
 *
 * MacMenuDelegate._setCallback has no export: its body only replaced the global reference to the item's callback,
 * which Java keeps with the id. _initIDs has none either.
 *
 * The former natives opened with GLASS_ASSERT_MAIN_JAVA_THREAD, ran their body all the same and closed with
 * GLASS_CHECK_EXCEPTION, which also reported what a JNI upcall had left pending. The exports do not evaluate the
 * assertion, and no upcall leaves an exception pending now, so they have no closing check. With Java detached from
 * the main thread they print the detached message where the JNI's GET_MAIN_JENV printed it: when they make a
 * GlassMenu, set an item's image, or none, and validate an item. Where the JNI then crashed on the NULL JNI
 * environment, after making a GlassMenu or before setting an image, they carry on.
 *
 * A NULL menubar or menu is dereferenced (latent bug), as the former natives did, but where an export says otherwise.
 */

/* A GlassMenubar holding an empty NSMenu titled "Menubar", +1. Neither is ever released (latent bug). */
GMAC_EXPORT void *gmac_menubar_create(void);

/*
 * Inserts menu's item into menubar's NSMenu at pos, with menu's NSMenu as its submenu, which then enables its items
 * automatically; makes the item the application menu (setAppleMenu:) when its title is "Apple"; then updates the main
 * menu, which validates its items (slot 1).
 */
GMAC_EXPORT void gmac_menubar_insert(void *menubar, void *menu, int32_t pos);

/*
 * When menubar's NSMenu holds menu's item, removes it and releases menu, which deallocates it (slot 4); then updates
 * the main menu, which validates its items (slot 1). pos is ignored.
 */
GMAC_EXPORT void gmac_menubar_remove(void *menubar, void *menu, int32_t pos);

/*
 * A menu: an NSMenuItem titled title, enabled when enabled is not 0, with no action, and an NSMenu of that title. A
 * menu item: an NSMenuItem titled title whose action is slot 0, with the key equivalent of gmac_menu_set_shortcut
 * unless shortcut is 0, enabled when enabled is not 0, checked as gmac_menu_set_checked checks it, and with icon's
 * image as gmac_menu_set_image sets it unless icon is NULL. Each answers its GlassMenu*, +1, which is the item's
 * target and the NSMenu's delegate, and which the slots identify by menu_id. No slot runs for menu_id before it
 * returns.
 */
GMAC_EXPORT void *gmac_menu_create(int64_t menu_id, const uint16_t *title, int32_t title_length, int32_t enabled);
GMAC_EXPORT void *gmac_menu_item_create(int64_t menu_id, const uint16_t *title, int32_t title_length,
                                        uint16_t shortcut, int32_t modifiers, const GmacMenuImage *icon,
                                        int32_t enabled, int32_t checked);

/*
 * Inserts submenu's item into menu's NSMenu at pos, with submenu's NSMenu, none for a menu item, as its submenu, which
 * then enables its items automatically. A NULL submenu adds a separator at the end instead.
 */
GMAC_EXPORT void gmac_menu_insert(void *menu, void *submenu, int32_t pos);

/*
 * When menu's NSMenu holds submenu's item, removes it and releases submenu, which deallocates it (slot 4); a NULL
 * submenu removes the item at pos instead, which raises an NSException when there is none (latent bug). Then updates
 * the main menu, which validates its items (slot 1).
 */
GMAC_EXPORT void gmac_menu_remove(void *menu, void *submenu, int32_t pos);

/*
 * Sets title as the title of the submenu of the main menu's item that is menu's item, when the main menu holds it,
 * else as the title of menu's item.
 */
GMAC_EXPORT void gmac_menu_set_title(void *menu, const uint16_t *title, int32_t title_length);

/*
 * menu's item's key equivalent: shortcut, a KeyEvent.VK_* code, as GlassKey's GetStringForJavaKey spells it, or the
 * function key of VK_F1 to VK_F12 or VK_F13 to VK_F24 when modifiers has KeyEvent.MODIFIER_FUNCTION; with the NSEvent
 * modifier flags of modifiers' MODIFIER_COMMAND, MODIFIER_SHIFT, MODIFIER_CONTROL and MODIFIER_OPTION bits. A NULL
 * menu does nothing.
 */
GMAC_EXPORT void gmac_menu_set_shortcut(void *menu, uint16_t shortcut, int32_t modifiers);

/* menu's item's image: icon's, consumed, or none for a NULL icon. A NULL menu only releases icon's image. */
GMAC_EXPORT void gmac_menu_set_image(void *menu, const GmacMenuImage *icon);

GMAC_EXPORT void gmac_menu_set_enabled(void *menu, int32_t enabled);

/* NSOnState when checked is not 0, else NSOffState. A NULL menu does nothing. */
GMAC_EXPORT void gmac_menu_set_checked(void *menu, int32_t checked);

/*
 * ---- MacAccessible ----
 *
 * peer is the GlassAccessible* or AccessibleBase* gmac_a11y_create_peer answered (MacAccessible.peer). FX: Java calls
 * these on the AppKit main thread, which MacAccessible checks, most of them inside a GmacAccessibleCallbacks slot. The
 * former natives checked neither the thread nor for an exception. None of these may be critical: they message objects
 * AppKit passed, and gmac_a11y_unignored_children dials slot 12.
 *
 * The other natives have no export, because each body was one AppKit, CoreFoundation or Objective-C runtime call
 * around marshalling: _initEnum (CFBundleGetBundleWithIdentifier, CFBundleGetDataPointerForName, and for an "AX" name
 * -[NSString initWithString:]), NSAccessibilityPostNotification and NSAccessibilityUnignoredAncestor (a tail call of
 * the AppKit function), NSAccessibilityRoleDescription and NSAccessibilityActionDescription (the AppKit function, then
 * -UTF8String for NewStringUTF), isEqualToString (-isEqualToString:), _destroyAccessiblePeer (objc_release, after
 * objc_opt_respondsToSelector for release, which every peer answers YES) and the unused getString (-UTF8String). The
 * gmac_test_a11y_* hooks keep the C their replacements must match. _initIDs has none either: its JNI IDs give way to
 * gmac_a11y_install_callbacks.
 */

/*
 * A peer for an accessible of role, the AccessibleRole name, NULL for null: a JFX*Accessibility for each role
 * AccessibleBase maps to one, which keeps a copy of role it never releases (latent bug), else a GlassAccessible.
 * Answers it +1, for MacAccessible to release; the slots identify it by peer_id. No slot runs for peer_id before it
 * returns.
 */
GMAC_EXPORT void *gmac_a11y_create_peer(const uint16_t *role, int32_t role_length, int64_t peer_id);

/* Makes an AccessibleBase peer forget the parent it cached; any other peer, and NULL, is left as it is. */
GMAC_EXPORT void gmac_a11y_invalidate_parent(void *peer);

/*
 * The peer_id of object, a peer, and 0 for NULL. Any other object raises an NSException for the unrecognized
 * selector, as the JNI's GlassAccessibleToMacAccessible did.
 */
GMAC_EXPORT int64_t gmac_a11y_peer_id(void *object);

/*
 * Reads object, borrowed, as idToMacVariant read it as type, into *out: type, the members type reads, and the rest
 * as in a null variant. NSNumber_Boolean and NSNumber_Int read -boolValue and -intValue into int1, NSNumber_Double
 * -doubleValue into double1, NSString -UTF8String into utf8, NSArray_id the ids of -objectAtIndex: into long_array,
 * NSArray_int their -intValue into int_array, NSValue_point and NSValue_size the two CGFloats into float1 and float2
 * and NSValue_rectangle the four into float1-float4, narrowed to float, and NSValue_range the location and length
 * into int1 and int2, narrowed to int32_t. Any other type reads nothing. The blocks it writes are freed with
 * gmac_free; an empty array has none. Answers GMAC_OK, or GMAC_ERR_NO_DATA when a block could not be allocated, where
 * the JNI's NewStringUTF, NewLongArray or NewIntArray threw OutOfMemoryError.
 */
GMAC_EXPORT int32_t gmac_a11y_id_to_variant(void *object, int32_t type, GmacVariant *out);

/*
 * NSAccessibilityUnignoredChildren of an NSMutableArray of the count ids, which dials slot 12 of the children AppKit
 * asks: *out_ids takes a block of the ids it answered, NULL for none, freed with gmac_free, and *out_count their
 * count. A 0 id raises NSInvalidArgumentException (latent bug). Answers GMAC_OK, else GMAC_ERR_NO_DATA: for a count
 * below 0, a null long[], of which the JNI answered null, and when the block could not be allocated, where its
 * NewLongArray threw OutOfMemoryError.
 */
GMAC_EXPORT int32_t gmac_a11y_unignored_children(void *const *ids, int32_t count, void ***out_ids,
                                                 int32_t *out_count);

/* ---- shim-only test hooks ---- */

/* The pool depth (GlassThreadData.counter) of the calling thread, or -1 before its first GLASS_POOL_ENTER. */
GMAC_EXPORT int32_t gmac_test_pool_depth(void);

/* Sets what gmac_main_thread_detached reads as "the main thread is attached"; answers the previous value. */
GMAC_EXPORT int32_t gmac_test_set_main_java_attached(int32_t attached);

/*
 * Convert (chars, len) as the library's two UTF-16 conversions do, inside a GLASS_POOL_ENTER/EXIT pair, and copy
 * min(length, capacity) code units of the result to out. Answer -1 for nil, else the result's length.
 * _or_empty: @"" for null and for empty, as GlassHelper's nsStringWithJavaString answered.
 * _or_nil: nil for null, @"" for empty, as GlassAccessible's jStringToNSString answered.
 */
GMAC_EXPORT int32_t gmac_test_string_or_empty(const uint16_t *chars, int32_t len, uint16_t *out, int32_t capacity);
GMAC_EXPORT int32_t gmac_test_string_or_nil(const uint16_t *chars, int32_t len, uint16_t *out, int32_t capacity);

/*
 * Dials slot `slot` of the installed GmacAppCallbacks, on the calling thread, with the fixed arguments below, and
 * answers its status: GMAC_ERR_NO_DATA when no table is installed or the slot is NULL, INT32_MIN outside 0-20. Every
 * string is static and NUL-terminated; a NUL is not counted in a length.
 *    0 run_runnable(0x0102030405060708)
 *    3 set_name(41 F0 9F 98 80 42, 6): "A", U+1F600 and "B" in standard UTF-8
 *   15 notify_open_files({"/a", NULL, C3 A9 F0 9F 98 80}, {2, 0, 6}, 3): U+00E9 and U+1F600 in the third
 *   20 notify_preferences_changed(these 9 entries, 9)
 *        "test.color"      COLOR        {255, 128, 0, 0, 0.5}
 *        "test.badColor"   COLOR        {256, 0, 0, 0, 1.0}
 *        "test.colors"     COLOR_ARRAY  2: {1, 2, 3, 0, 0.25}, {4, 5, 6, 0, 0.75}
 *        "test.badColors"  COLOR_ARRAY  2: {7, 8, 9, 0, 1.0}, {-1, 0, 0, 0, 1.0}
 *        "test.noColors"   COLOR_ARRAY  0, NULL
 *        "test.string"     STRING       "string", 6
 *        "test.nullString" STRING       NULL, 0
 *        "test.true"       BOOLEAN      1
 *        "test.false"      BOOLEAN      0
 *   every other slot takes no argument
 */
GMAC_EXPORT int32_t gmac_test_fire_app_callback(int32_t slot);

/*
 * Reads back app, a delegate from gmac_app_create_delegate that no run loop has used, then releases it; NULL
 * answers -1. Otherwise answers a mask: 1 when runLoop: would make a regular application, 2 when
 * applicationWillFinishLaunching: would dial run_launchable.
 */
GMAC_EXPORT int32_t gmac_test_inspect_and_release_app_delegate(void *app);

/*
 * Reads back image, a +1 NSImage* from gmac_image_create, then releases it; NULL answers -1 and writes nothing. out
 * takes 9 values: [0] and [1] the image's size in points, truncated; [2] to [7] the width, height, bits per component,
 * bits per pixel, bytes per row and bitmap info of its CGImage; [8] 1 when that CGImage is in sRGB, else 0. Copies
 * min(n, capacity) bytes of the CGImage's pixel data to pixels and answers n, the byte count of that data; -1 when
 * there is no CGImage, leaving out[2] to out[8] 0, or no data.
 */
GMAC_EXPORT int32_t gmac_test_inspect_and_release_image(void *image, int32_t *out, uint8_t *pixels, int32_t capacity);

/*
 * Dials tick(timer_id) of the installed GmacTimerCallbacks count times, on the calling thread when on_new_thread is
 * 0, else on one new pthread the call joins, and answers the status of the last dial: GMAC_ERR_NO_DATA when no table
 * is installed or the slot is NULL, INT32_MIN when count is below 1 or the thread cannot be created.
 */
GMAC_EXPORT int32_t gmac_test_fire_timer_callback(int64_t timer_id, int32_t count, int32_t on_new_thread);

/*
 * Indices of gmac_test_sdk_constant: the CoreGraphics and HIServices values the former robot natives passed, as this
 * SDK defines them, then the sizes of the types they passed by value or read back.
 */
enum GmacSdkConstant {
    GMAC_SDK_EVENT_LEFT_MOUSE_DOWN = 0,              /* kCGEventLeftMouseDown */
    GMAC_SDK_EVENT_LEFT_MOUSE_UP,                    /* kCGEventLeftMouseUp */
    GMAC_SDK_EVENT_RIGHT_MOUSE_DOWN,                 /* kCGEventRightMouseDown */
    GMAC_SDK_EVENT_RIGHT_MOUSE_UP,                   /* kCGEventRightMouseUp */
    GMAC_SDK_EVENT_MOUSE_MOVED,                      /* kCGEventMouseMoved */
    GMAC_SDK_EVENT_LEFT_MOUSE_DRAGGED,               /* kCGEventLeftMouseDragged */
    GMAC_SDK_EVENT_RIGHT_MOUSE_DRAGGED,              /* kCGEventRightMouseDragged */
    GMAC_SDK_EVENT_SCROLL_WHEEL,                     /* kCGEventScrollWheel */
    GMAC_SDK_EVENT_OTHER_MOUSE_DOWN,                 /* kCGEventOtherMouseDown */
    GMAC_SDK_EVENT_OTHER_MOUSE_UP,                   /* kCGEventOtherMouseUp */
    GMAC_SDK_EVENT_OTHER_MOUSE_DRAGGED,              /* kCGEventOtherMouseDragged */
    GMAC_SDK_HID_EVENT_TAP,                          /* kCGHIDEventTap */
    GMAC_SDK_SCROLL_EVENT_UNIT_LINE,                 /* kCGScrollEventUnitLine */
    GMAC_SDK_MOUSE_EVENT_BUTTON_NUMBER,              /* kCGMouseEventButtonNumber */
    GMAC_SDK_SCROLL_WHEEL_EVENT_DELTA_AXIS_1,        /* kCGScrollWheelEventDeltaAxis1 */
    GMAC_SDK_WINDOW_LIST_OPTION_ON_SCREEN_ONLY,      /* kCGWindowListOptionOnScreenOnly */
    GMAC_SDK_NULL_WINDOW_ID,                         /* kCGNullWindowID */
    GMAC_SDK_WINDOW_IMAGE_DEFAULT,                   /* kCGWindowImageDefault */
    GMAC_SDK_RENDERING_INTENT_ABSOLUTE_COLORIMETRIC, /* kCGRenderingIntentAbsoluteColorimetric */
    GMAC_SDK_CAPTURE_BITMAP_INFO,                    /* kCGBitmapByteOrder32Host | kCGImageAlphaPremultipliedFirst */
    GMAC_SDK_SIZEOF_CGFLOAT,
    GMAC_SDK_SIZEOF_CGPOINT,
    GMAC_SDK_SIZEOF_CGRECT,
    GMAC_SDK_SIZEOF_CGEVENTTYPE,
    GMAC_SDK_SIZEOF_CGMOUSEBUTTON,
    GMAC_SDK_SIZEOF_CGEVENTTAPLOCATION,
    GMAC_SDK_SIZEOF_CGSCROLLEVENTUNIT,
    GMAC_SDK_SIZEOF_CGEVENTFIELD,
    GMAC_SDK_SIZEOF_CGCHARCODE,
    GMAC_SDK_SIZEOF_CGKEYCODE,
    GMAC_SDK_SIZEOF_BOOLEAN,
    GMAC_SDK_SIZEOF_AXERROR,
    GMAC_SDK_SIZEOF_CGERROR,
    GMAC_SDK_SIZEOF_CGWINDOWLISTOPTION,
    GMAC_SDK_SIZEOF_CGWINDOWID,
    GMAC_SDK_SIZEOF_CGWINDOWIMAGEOPTION,
    GMAC_SDK_SIZEOF_CGCOLORRENDERINGINTENT,
    GMAC_SDK_SIZEOF_CGBITMAPINFO,
    GMAC_SDK_SIZEOF_SIZE_T,
    GMAC_SDK_CONSTANT_COUNT
};

/* The value of an enum GmacSdkConstant index, or INT32_MIN outside [0, GMAC_SDK_CONSTANT_COUNT). */
GMAC_EXPORT int32_t gmac_test_sdk_constant(int32_t index);

/* kCGColorSpaceSRGB: the CFStringRef that _getPixelColor and _getScreenCapture gave CGColorSpaceCreateWithName. */
GMAC_EXPORT const void *gmac_test_color_space_srgb_name(void);

/*
 * Dials slot `slot` of the installed GmacWindowCallbacks, on the calling thread, for window_id with the fixed
 * arguments below, and answers its status: GMAC_ERR_NO_DATA when no table is installed or the slot is NULL, INT32_MIN
 * outside 0-9.
 *   0 notify_move(window_id, 1001, 1002, 1)
 *   1 notify_resize(window_id, 1001, 1002, 1003)
 *   2 notify_move_to_another_screen(window_id, {(void *)0x0102030405060708, 1001, 1002, ... 1015, 1.5f, 2.5f, 3.5f,
 *     4.5f}): the 15 int32_t members, depth to resolution_y, are 1001 to 1015 in order
 *   3 notify_scale_changed(window_id, 1.5f, 2.5f, 3.5f, 4.5f)
 *   5 notify_focus(window_id, 1001)
 *   every other slot takes window_id only
 */
GMAC_EXPORT int32_t gmac_test_fire_window_callback(int32_t slot, int64_t window_id);

/*
 * The body of the former MacWindow._isRightToLeftLayoutDirection, inside a GLASS_POOL_ENTER/EXIT pair, answering
 * the NSLocaleLanguageDirection NSLocale gives the language of the first preferred language, where the native
 * answered whether it is NSLocaleLanguageDirectionRightToLeft (2): the oracle of the CoreFoundation chain that
 * replaced it. With no preferred language it traps, as that body did.
 */
GMAC_EXPORT int32_t gmac_test_nslocale_language_direction(void);

/*
 * Dials slot `slot` of the installed GmacViewCallbacks, on the calling thread, for view_id with the fixed arguments
 * below, and answers its status: GMAC_ERR_NO_DATA when no table is installed or the slot is NULL, INT32_MIN outside
 * 0-14. The out-parameters of slots 5, 7, 8-10 and 13 point into out, which the caller fills and which holds 24 bytes,
 * 8-aligned, and are not written by the hook; text and chars are static.
 *    0 notify_view(view_id, 1001)
 *    1 notify_resize(view_id, 1001, 1002)
 *    2 notify_repaint(view_id, 1001, 1002, 1003, 1004)
 *    3 notify_menu(view_id, 1001, 1002, 1003, 1004, 1)
 *    4 notify_mouse(view_id, 1001, 1002, 1003, 1004, 1005, 1006, 1007, 1, 0)
 *    5 notify_key(view_id, 1001, 1002, {0x0061, 0x00E9, 0xD83D, 0xDE00, 0xDC00}, 5, 1003, 0, (int32_t *)out): "a",
 *      U+00E9, U+1F600 and a lone low surrogate
 *    6 notify_input_method(view_id, {0x0062, 0x65E5, 0xD83D, 0xDE00}, 4, 1001, 1002, 1003, -1, 1004)
 *    7 notify_input_method_candidate_pos_request(view_id, 1001, (double *)out, (int32_t *)(out + 16 bytes))
 *    8 notify_drag_enter(view_id, 1001, 1002, 1003, 1004, 1005, (int32_t *)out), and 9 and 10 alike
 *   12 notify_drag_end(view_id, 1001)
 *   13 get_accessible(view_id, (void **)out)
 *   every other slot takes view_id only
 */
GMAC_EXPORT int32_t gmac_test_fire_view_callback(int32_t slot, int64_t view_id, void *out);

/*
 * Dials slot `slot` of the installed GmacGestureCallbacks, on the calling thread, for view_id with the fixed arguments
 * below, and answers its status: GMAC_ERR_NO_DATA when no table is installed or the slot is NULL, INT32_MIN outside
 * 0-4.
 *   0 rotate_gesture_performed(view_id, 1001, 1002, 1003, 1004, 1005, 1.5f)
 *   1 scroll_gesture_performed(view_id, 1001, 1002, 1003, 1004, 1005, 1006, 1.5f, 2.5f)
 *   2 swipe_gesture_performed(view_id, 1001, 1002, 1003, 1004, 1005, 1006)
 *   3 magnify_gesture_performed(view_id, 1001, 1002, 1003, 1004, 1005, 1.5f)
 *   4 gesture_finished(view_id, 1001, 1002, 1003, 1004, 1005)
 */
GMAC_EXPORT int32_t gmac_test_fire_gesture_callback(int32_t slot, int64_t view_id);

/*
 * Dials notify_key of the installed GmacViewCallbacks `dials` times on the calling thread as one key event, through the
 * code the views use, which sets same_chars_as_previous: dial i, from 0, passes type 1001 + i, key code 2001 + i, the
 * units {0x0061, 0x00E9} and modifiers 3001. *out_consumed takes what the first dial wrote, 0 when it wrote nothing.
 * Answers the status of the last dial, GMAC_ERR_NO_DATA when no table is installed or the slot is NULL, and INT32_MIN
 * when dials is below 1. A slot may call the hook again during a dial, as a nested event would dial the slot.
 */
GMAC_EXPORT int32_t gmac_test_fire_key_event(int64_t view_id, int32_t dials, int32_t *out_consumed);

/*
 * What GetJavaKeyCharsUtf16, the views' helper, does for a key-down NSEvent with the Mac key code key_code whose
 * characters are the len units at chars, made inside a GLASS_POOL_ENTER/EXIT pair. For a key that sends its characters
 * it copies min(len, capacity) of the units to out, none for a capacity below 1, writes nothing else there, and
 * answers len. For a key code whose key sends none, such as 0x7B (the left arrow) or one the key map lacks, it writes
 * nothing and answers -1. 0x00, kVK_ANSI_A, sends them.
 */
GMAC_EXPORT int32_t gmac_test_key_chars_utf16(uint16_t key_code, const uint16_t *chars, int32_t len, uint16_t *out,
                                              int32_t capacity);

/*
 * Dials notify_key of the installed GmacViewCallbacks once on the calling thread, inside a GLASS_POOL_ENTER/EXIT pair,
 * with type 1001, key code 2001, modifiers 3001 and the units the views' code passes for text_char and a key-down
 * NSEvent made as gmac_test_key_chars_utf16 makes it: text_char alone when it is not 0, else every unit of the event's
 * characters, beyond 16 too, and none for a key that sends none. *out_consumed takes what the dial wrote, 0 when it
 * wrote nothing. Answers the dial's status, GMAC_ERR_NO_DATA when no table is installed or the slot is NULL.
 */
GMAC_EXPORT int32_t gmac_test_fire_key_chars(int64_t view_id, uint16_t key_code, const uint16_t *chars, int32_t len,
                                             uint16_t text_char, int32_t *out_consumed);

/*
 * Dials notify_input_method of the installed GmacViewCallbacks on the calling thread through the code the views use,
 * inside a GLASS_POOL_ENTER/EXIT pair, for an NSString of the len units at chars, wrapped in an NSAttributedString
 * when attributed is not 0, and attr, length, cursor and the range (sel_location, sel_length). Answers the slot's
 * status, GMAC_ERR_NO_DATA when no table is installed or the slot is NULL, and INT32_MIN where the JNI made no String
 * and so made no call.
 */
GMAC_EXPORT int32_t gmac_test_fire_input_method(int64_t view_id, const uint16_t *chars, int32_t len, int32_t attributed,
                                                int32_t attr, int32_t length, int32_t cursor, uint64_t sel_location,
                                                uint64_t sel_length);

/* Writes to *out the GmacViewCaps that gmac_view_create uses for a NULL caps: every member's default. */
GMAC_EXPORT void gmac_test_default_view_caps(GmacViewCaps *out);

/*
 * The bodies of the former MacSystemClipboard.FormatEncoder._convertMIMEtoUTI and _convertUTItoMIME, inside a
 * GLASS_POOL_ENTER/EXIT pair, answering the [NSString UTF8String] they gave NewStringUTF in the strings-out
 * convention: mime_to_uti the identifier UTTypeCreatePreferredIdentifierForTag answers for the MIME type, conforming to
 * "public.mime-type", and uti_to_mime the MIME type tag UTTypeCopyPreferredTagWithClass answers for the UTI. A null
 * string is taken as "". The oracle of the UTType and CoreFoundation calls that replace them.
 */
GMAC_EXPORT int32_t gmac_test_clipboard_mime_to_uti(const uint16_t *mime, int32_t length, char **out_utf8,
                                                    int32_t *out_length);
GMAC_EXPORT int32_t gmac_test_clipboard_uti_to_mime(const uint16_t *uti, int32_t length, char **out_utf8,
                                                    int32_t *out_length);

/*
 * The block gmac_pasteboard_get_utis answers for item_count items, none answering GMAC_ERR_NO_DATA, whose UTIs are
 * NSStrings of the units at units: item i has type_counts[i] of them, which take lengths[k] consecutive units each.
 */
GMAC_EXPORT int32_t gmac_test_pasteboard_utis_block(const uint16_t *units, const int32_t *lengths,
                                                    const int32_t *type_counts, int32_t item_count,
                                                    uint8_t **out_block, int32_t *out_length);

/*
 * What gmac_pasteboard_get_item_as_raw_image answers for an item whose TIFF, PNG or PDF data are the length bytes at
 * data, inside a GLASS_POOL_ENTER/EXIT pair.
 */
GMAC_EXPORT int32_t gmac_test_pasteboard_raw_image(const uint8_t *data, int32_t length, uint8_t **out_block,
                                                   int32_t *out_length);

/*
 * The two size bounds of gmac_pasteboard_get_item_as_raw_image for a width x height CGImage: bit 0 when it allocates
 * the pixels, bit 1 when it makes the block of them.
 */
GMAC_EXPORT int32_t gmac_test_pasteboard_raw_image_bounds(uint64_t width, uint64_t height);

/*
 * Runs what gmac_pasteboard_put_items does to make its NSPasteboardItems, inside a GLASS_POOL_ENTER/EXIT pair, on
 * recording items, and answers in the strings-out convention one line per item it makes, "item", each followed by one
 * line per setter it calls: "string <type> <string>" or "data <type> <data>". A string is "nil", or its unit count, a
 * colon and its units as 4 lowercase hex digits each; data is its byte count, a colon and at most its first 64 bytes
 * as 2 lowercase hex digits each. Every line ends with "\n". Images are released as the export releases them, and
 * a BAD representation is logged as the export logs it.
 */
GMAC_EXPORT int32_t gmac_test_pasteboard_describe_items(const GmacPasteboardItem *items, int32_t item_count,
                                                        char **out_utf8, int32_t *out_length);

/*
 * Dials slot `slot` of the installed GmacDialogCallbacks, on the calling thread, with loop_id, and answers its status:
 * GMAC_ERR_NO_DATA when no table is installed or the slot is NULL, INT32_MIN outside 0-1.
 */
GMAC_EXPORT int32_t gmac_test_fire_dialog_callback(int32_t slot, int64_t loop_id);

/*
 * Makes, inside a GLASS_POOL_ENTER/EXIT pair, the popup titles and file types the dialog exports make of filters, and
 * answers them in the strings-out convention: per filter a line "filter <string>", then per extension a line
 * "extension <string>", where a string is its unit count, a colon and its units as 4 lowercase hex digits each,
 * after a space. Every line ends with "\n". A filter_count of 0 or less, which makes no popup, answers "".
 */
GMAC_EXPORT int32_t gmac_test_dialog_describe_filters(const GmacFileFilter *filters, int32_t filter_count,
                                                      char **out_utf8, int32_t *out_length);

/*
 * Dials slot `slot` of the installed GmacMenuCallbacks, on the calling thread, with menu_id, and answers its status:
 * GMAC_ERR_NO_DATA when no table is installed or the slot is NULL, INT32_MIN outside 0-4.
 */
GMAC_EXPORT int32_t gmac_test_fire_menu_callback(int32_t slot, int64_t menu_id);

/*
 * The key equivalent and modifier mask gmac_menu_set_shortcut gives an item for shortcut and modifiers, made inside a
 * GLASS_POOL_ENTER/EXIT pair: copies min(length, capacity) of the key equivalent's UTF-16 units to out, answers its
 * length and writes the NSEventModifierFlags to *out_mask.
 */
GMAC_EXPORT int32_t gmac_test_menu_key_equivalent(uint16_t shortcut, int32_t modifiers, uint16_t *out,
                                                  int32_t capacity, uint64_t *out_mask);

/*
 * Dials slot `slot` of the installed GmacAccessibleCallbacks, on the calling thread, for peer_id with the fixed
 * arguments below, and answers its status: GMAC_ERR_NO_DATA when no table is installed or the slot is NULL, INT32_MIN
 * outside 0-15. ATTRIBUTE is (void *)0x0102030405060701, VALUE, CHILD, PARAMETER and ACTION the same but for their
 * last digit, 2, 3, 4 and 5: no objects. The out-parameters point into out, which the caller fills and which holds
 * 120 bytes, 8-aligned; the hook neither reads nor frees what a slot writes there.
 *    0 attribute_names(peer_id, (void ***)out, (int32_t *)(out + 8 bytes)), and 7 and 9 alike
 *    1 attribute_value(peer_id, ATTRIBUTE, (GmacVariant *)out)
 *    2 is_attribute_settable(peer_id, ATTRIBUTE, (int32_t *)out)
 *    3 set_value(peer_id, VALUE, ATTRIBUTE)
 *    4 index_of_child(peer_id, CHILD, (int64_t *)out)
 *    5 array_attribute_count(peer_id, ATTRIBUTE, (int32_t *)out)
 *    6 array_attribute_values(peer_id, ATTRIBUTE, 1001, 1002, (void ***)out, (int32_t *)(out + 8 bytes))
 *    8 attribute_value_for_parameter(peer_id, ATTRIBUTE, PARAMETER, (GmacVariant *)out)
 *   10 action_description(peer_id, ACTION, (uint16_t **)out, (int32_t *)(out + 8 bytes))
 *   11 perform_action(peer_id, ACTION)
 *   12 is_ignored(peer_id, (int32_t *)out)
 *   13 hit_test(peer_id, 1.5f, 2.5f, (void **)out)
 *   14 focused_ui_element(peer_id, (void **)out)
 *   15 peer_released(peer_id)
 */
GMAC_EXPORT int32_t gmac_test_fire_accessible_callback(int32_t slot, int64_t peer_id, void *out);

/*
 * What the peers make of a variant as slot 1 or 8 wrote it, inside a GLASS_POOL_ENTER/EXIT pair: *out_object takes
 * the object, retained, NULL for nil, and the variant's blocks are freed as the peers free them. Answers GMAC_OK, or
 * GMAC_ERR_NO_DATA for nil. The long1 of an NSObject variant must be an object or 0, as the hook retains it.
 */
GMAC_EXPORT int32_t gmac_test_a11y_variant_to_object(GmacVariant *variant, void **out_object);

/*
 * The C of the former getString, isEqualToString and _initEnum, inside a GLASS_POOL_ENTER/EXIT pair: the oracle of
 * the calls that replace them.
 * _utf8_string: the [string UTF8String] getString gave NewStringUTF, in the strings-out convention.
 * _is_equal_to_string: [string1 isEqualToString:string2], 1 for YES.
 * _enum_pointer: what _initEnum stored in the ptr of the enum constant whose name is the length UTF-16 units at name,
 *   NULL for null: a new NSString of the name, +1, when it begins with "AX", which _initEnum never released and the
 *   caller may, else the value of the com.apple.Cocoa bundle's data symbol of that name. Answers 1, or 0 with NULL
 *   where _initEnum answered false: a null name, no such bundle, or no such symbol.
 */
GMAC_EXPORT int32_t gmac_test_a11y_utf8_string(void *string, char **out_utf8, int32_t *out_length);
GMAC_EXPORT int32_t gmac_test_a11y_is_equal_to_string(void *string1, void *string2);
GMAC_EXPORT int32_t gmac_test_a11y_enum_pointer(const uint16_t *name, int32_t length, void **out_pointer);

#ifdef __cplusplus
}
#endif

#endif /* GLASS_MAC_API_H */
