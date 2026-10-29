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

import java.lang.foreign.AddressLayout;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;
import static com.sun.glass.ui.gtk.GtkGlassNative.ABI_VERSION;
import static com.sun.glass.ui.gtk.GtkGlassNative.LIB_GLASS;
import static com.sun.glass.ui.gtk.GtkGlassNative.bind;
import static com.sun.glass.ui.gtk.GtkGlassNative.pointerTo;
import static com.sun.glass.ui.gtk.GtkGlassNative.unexpected;
import static com.sun.glass.ui.gtk.GtkGlassNative.upcallStub;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_CHAR;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The calls of the window, view, drag-and-drop and application C of {@code libglassgtk3.so} into Java - the
 * {@code Call*Method} / {@code NewObject} sites of {@code glass_window.cpp}, {@code glass_window_ime.cpp},
 * {@code GlassView.cpp}, {@code glass_dnd.cpp}, {@code glass_screen.cpp} and {@code glass_general.cpp} at commit
 * {@code 033187ad90}, which reached Java through the {@code jmethodID}s {@code glass_general.cpp}'s {@code JNI_OnLoad}
 * cached and {@code mainEnv} - as the four callback tables of its flat C ABI
 * ({@code native-glass/gtk/glass_gtk_api.h}): the values both sides share, the table and data layouts, the slot
 * descriptors, the install, and {@link Glass}, the holder of the library's {@code ggtk_*} exports.
 * <p>
 * Java calls that C through one {@code ggtk_*} function per former JNI native of {@code GtkWindow}, {@code GtkView},
 * {@code GtkDnDClipboard} and {@code GtkApplication} (all of them but {@code _queryLibrary}), and the C calls Java
 * through the four callback tables, which {@link #installCallbacks} fills with upcall stubs of
 * {@link GtkCallbackTargets} once, at toolkit start. Windows and views cross as {@code long} ids that
 * {@code GtkWindow} and {@code GtkView} assign and resolve; the stubs report exceptions as
 * {@link GtkGlassNative#reportException} does and hand the C a status, on which it takes the branch it took after
 * a pending exception, and where a JNI native threw, its replacement throws the same exception when the downcall has
 * returned.
 */
final class GtkCallbackTables {

    /**
     * {@code GGTK_ERR_NOT_IN_DRAG}: no drag has entered a Glass window, where the JNI threw
     * {@link #NOT_IN_DRAG_MESSAGE} as an {@code IllegalStateException} ({@code ggtk_dnd_target_*}).
     */
    static final int ERR_NOT_IN_DRAG = -3;

    /**
     * {@code GGTK_ERR_GTK_VERSION}: GTK is older than the build's minimum, where the JNI threw
     * {@code UnsupportedOperationException} ({@code ggtk_application_init_gtk}).
     */
    static final int ERR_GTK_VERSION = -4;

    /** The message of the {@code IllegalStateException} of {@code check_state_in_drag} ({@code glass_dnd.cpp}). */
    static final String NOT_IN_DRAG_MESSAGE =
            "Cannot get supported actions. Drag pointer haven't entered the application window";

    /** {@code GGTK_UPCALL_OK}: the Java target returned normally. */
    static final int UPCALL_OK = 0;

    /** {@code GGTK_UPCALL_THREW}: the Java target threw, and the exception was reported as the C reported it. */
    static final int UPCALL_THREW = 1;

    /** {@code GGTK_DND_DATA_*}, the {@code kind} of a {@code GgtkDndData}. */
    static final int DND_DATA_NONE = 0;
    static final int DND_DATA_STRING = 1;
    static final int DND_DATA_BYTES = 2;
    static final int DND_DATA_PIXBUF = 3;
    static final int DND_DATA_STRINGS = 4;

    /** {@code GGTK_DND_AS_*}, the conversion {@code source_get_data} applies to the value of the key. */
    static final int DND_AS_STRING = 1;
    static final int DND_AS_BYTES = 2;
    static final int DND_AS_PIXBUF = 3;
    static final int DND_AS_STRINGS = 4;
    static final int DND_AS_RAW = 5;

    static final StructLayout GGTK_APP_CALLBACKS_LAYOUT = MemoryLayout.structLayout(
            ADDRESS.withName("notify_screen_settings_changed"),
            ADDRESS.withName("get_application_name"));

    static final StructLayout GGTK_WINDOW_CALLBACKS_LAYOUT = MemoryLayout.structLayout(
            ADDRESS.withName("is_enabled"),
            ADDRESS.withName("notify_state_changed"),
            ADDRESS.withName("notify_focus"),
            ADDRESS.withName("notify_focus_disabled"),
            ADDRESS.withName("notify_focus_ungrab"),
            ADDRESS.withName("notify_destroy"),
            ADDRESS.withName("notify_close"),
            ADDRESS.withName("notify_resize"),
            ADDRESS.withName("notify_move"),
            ADDRESS.withName("notify_move_to_another_screen"),
            ADDRESS.withName("notify_level_changed"),
            ADDRESS.withName("non_client_hit_test"));

    static final StructLayout GGTK_VIEW_CALLBACKS_LAYOUT = MemoryLayout.structLayout(
            ADDRESS.withName("notify_view"),
            ADDRESS.withName("notify_resize"),
            ADDRESS.withName("notify_repaint"),
            ADDRESS.withName("notify_mouse"),
            ADDRESS.withName("notify_menu"),
            ADDRESS.withName("notify_scroll"),
            ADDRESS.withName("notify_key"),
            ADDRESS.withName("notify_input_method_preedit"),
            ADDRESS.withName("notify_input_method_commit"),
            ADDRESS.withName("notify_input_method_candidate_pos_request"));

    static final StructLayout GGTK_DND_CALLBACKS_LAYOUT = MemoryLayout.structLayout(
            ADDRESS.withName("notify_drag_enter"),
            ADDRESS.withName("notify_drag_over"),
            ADDRESS.withName("notify_drag_drop"),
            ADDRESS.withName("notify_drag_leave"),
            ADDRESS.withName("source_get_data"));

    /** {@code GgtkDndData}: {@code int32_t kind, count; void *data, *pixbuf} - 24 bytes on LP64. */
    static final StructLayout GGTK_DND_DATA_LAYOUT = MemoryLayout.structLayout(
            JAVA_INT.withName("kind"),
            JAVA_INT.withName("count"),
            ADDRESS.withName("data"),
            ADDRESS.withName("pixbuf"));

    static final long DND_DATA_KIND_OFFSET = dndDataOffset("kind");
    static final long DND_DATA_COUNT_OFFSET = dndDataOffset("count");
    static final long DND_DATA_DATA_OFFSET = dndDataOffset("data");
    static final long DND_DATA_PIXBUF_OFFSET = dndDataOffset("pixbuf");

    /**
     * {@code GgtkDndTargetValue}: {@code int32_t kind, count; void *data; int32_t width, height, records} and the
     * padding to the pointer's alignment - 32 bytes on LP64, which {@link #installCallbacks} checks against the C.
     */
    static final StructLayout GGTK_DND_TARGET_VALUE_LAYOUT = MemoryLayout.structLayout(
            JAVA_INT.withName("kind"),
            JAVA_INT.withName("count"),
            ADDRESS.withName("data"),
            JAVA_INT.withName("width"),
            JAVA_INT.withName("height"),
            JAVA_INT.withName("records"),
            MemoryLayout.paddingLayout(4));

    /** {@code GGTK_DND_VALUE_*}, the {@code kind} of a {@code GgtkDndTargetValue}. */
    static final int DND_VALUE_NONE = 0;
    static final int DND_VALUE_STRING = 1;
    static final int DND_VALUE_BYTES = 2;
    static final int DND_VALUE_IMAGE = 3;
    static final int DND_VALUE_FILES = 4;

    /*
     * The out-parameters of the slots, as pointers whose target is sized: the segment an upcall receives for them
     * covers exactly the C object, so a target writes it without widening the pointer itself.
     */
    private static final AddressLayout INT_OUT = pointerTo(JAVA_INT);
    private static final AddressLayout ADDRESS_OUT = pointerTo(ADDRESS);
    private static final AddressLayout XY_OUT = pointerTo(MemoryLayout.sequenceLayout(2, JAVA_DOUBLE));
    private static final AddressLayout DND_DATA_OUT = pointerTo(GGTK_DND_DATA_LAYOUT);

    /* The slot prototypes of glass_gtk_api.h, parameter by parameter; every slot returns the int32_t status. */
    static final FunctionDescriptor NOTIFY_SCREEN_SETTINGS_CHANGED_FD = FunctionDescriptor.of(JAVA_INT);
    static final FunctionDescriptor GET_APPLICATION_NAME_FD = FunctionDescriptor.of(JAVA_INT, ADDRESS_OUT);

    static final FunctionDescriptor IS_ENABLED_FD = FunctionDescriptor.of(JAVA_INT, JAVA_LONG, INT_OUT);
    static final FunctionDescriptor NOTIFY_STATE_CHANGED_FD = FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT);
    static final FunctionDescriptor NOTIFY_FOCUS_FD = FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT);
    static final FunctionDescriptor NOTIFY_FOCUS_DISABLED_FD = FunctionDescriptor.of(JAVA_INT, JAVA_LONG);
    static final FunctionDescriptor NOTIFY_FOCUS_UNGRAB_FD = FunctionDescriptor.of(JAVA_INT, JAVA_LONG);
    static final FunctionDescriptor NOTIFY_DESTROY_FD = FunctionDescriptor.of(JAVA_INT, JAVA_LONG);
    static final FunctionDescriptor NOTIFY_CLOSE_FD = FunctionDescriptor.of(JAVA_INT, JAVA_LONG);
    static final FunctionDescriptor NOTIFY_WINDOW_RESIZE_FD = FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT,
            JAVA_INT, JAVA_INT);
    static final FunctionDescriptor NOTIFY_MOVE_FD = FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT);
    static final FunctionDescriptor NOTIFY_MOVE_TO_ANOTHER_SCREEN_FD = FunctionDescriptor.of(JAVA_INT, JAVA_LONG,
            JAVA_INT);
    static final FunctionDescriptor NOTIFY_LEVEL_CHANGED_FD = FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT);
    static final FunctionDescriptor NON_CLIENT_HIT_TEST_FD = FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT,
            JAVA_INT, INT_OUT);

    static final FunctionDescriptor NOTIFY_VIEW_FD = FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT);
    static final FunctionDescriptor NOTIFY_VIEW_RESIZE_FD = FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT,
            JAVA_INT);
    static final FunctionDescriptor NOTIFY_REPAINT_FD = FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT,
            JAVA_INT, JAVA_INT);
    static final FunctionDescriptor NOTIFY_MOUSE_FD = FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT,
            JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);
    static final FunctionDescriptor NOTIFY_MENU_FD = FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT,
            JAVA_INT, JAVA_INT, JAVA_INT);
    static final FunctionDescriptor NOTIFY_SCROLL_FD = FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT,
            JAVA_INT, JAVA_INT, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT,
            JAVA_DOUBLE, JAVA_DOUBLE);
    static final FunctionDescriptor NOTIFY_KEY_FD = FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT,
            ADDRESS, JAVA_INT, JAVA_INT);
    static final FunctionDescriptor NOTIFY_INPUT_METHOD_PREEDIT_FD = FunctionDescriptor.of(JAVA_INT, JAVA_LONG,
            ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT);
    static final FunctionDescriptor NOTIFY_INPUT_METHOD_COMMIT_FD = FunctionDescriptor.of(JAVA_INT, JAVA_LONG,
            ADDRESS, JAVA_INT);
    static final FunctionDescriptor NOTIFY_INPUT_METHOD_CANDIDATE_POS_REQUEST_FD = FunctionDescriptor.of(JAVA_INT,
            JAVA_LONG, JAVA_INT, XY_OUT, INT_OUT);

    static final FunctionDescriptor NOTIFY_DRAG_ENTER_FD = FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT,
            JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, INT_OUT);
    static final FunctionDescriptor NOTIFY_DRAG_OVER_FD = NOTIFY_DRAG_ENTER_FD;
    static final FunctionDescriptor NOTIFY_DRAG_DROP_FD = FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT,
            JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);
    static final FunctionDescriptor NOTIFY_DRAG_LEAVE_FD = FunctionDescriptor.of(JAVA_INT, JAVA_LONG);
    static final FunctionDescriptor SOURCE_GET_DATA_FD = FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT,
            DND_DATA_OUT);

    /**
     * One callback table: the struct layout, its slot names in declaration order (the order the tables are written
     * in and {@code ggtk_test_fire_callback} numbers them), the target methods of {@link GtkCallbackTargets} and the
     * descriptors, parallel to the names.
     */
    record CallbackTable(StructLayout layout, List<String> slots, List<String> targets,
                         List<FunctionDescriptor> descriptors) {
    }

    static final CallbackTable APP_TABLE = new CallbackTable(GGTK_APP_CALLBACKS_LAYOUT,
            List.of("notify_screen_settings_changed", "get_application_name"),
            List.of("onNotifyScreenSettingsChanged", "onGetApplicationName"),
            List.of(NOTIFY_SCREEN_SETTINGS_CHANGED_FD, GET_APPLICATION_NAME_FD));

    static final CallbackTable WINDOW_TABLE = new CallbackTable(GGTK_WINDOW_CALLBACKS_LAYOUT,
            List.of("is_enabled", "notify_state_changed", "notify_focus", "notify_focus_disabled",
                    "notify_focus_ungrab", "notify_destroy", "notify_close", "notify_resize", "notify_move",
                    "notify_move_to_another_screen", "notify_level_changed", "non_client_hit_test"),
            List.of("onIsEnabled", "onNotifyStateChanged", "onNotifyFocus", "onNotifyFocusDisabled",
                    "onNotifyFocusUngrab", "onNotifyDestroy", "onNotifyClose", "onNotifyWindowResize",
                    "onNotifyMove", "onNotifyMoveToAnotherScreen", "onNotifyLevelChanged", "onNonClientHitTest"),
            List.of(IS_ENABLED_FD, NOTIFY_STATE_CHANGED_FD, NOTIFY_FOCUS_FD, NOTIFY_FOCUS_DISABLED_FD,
                    NOTIFY_FOCUS_UNGRAB_FD, NOTIFY_DESTROY_FD, NOTIFY_CLOSE_FD, NOTIFY_WINDOW_RESIZE_FD,
                    NOTIFY_MOVE_FD, NOTIFY_MOVE_TO_ANOTHER_SCREEN_FD, NOTIFY_LEVEL_CHANGED_FD,
                    NON_CLIENT_HIT_TEST_FD));

    static final CallbackTable VIEW_TABLE = new CallbackTable(GGTK_VIEW_CALLBACKS_LAYOUT,
            List.of("notify_view", "notify_resize", "notify_repaint", "notify_mouse", "notify_menu", "notify_scroll",
                    "notify_key", "notify_input_method_preedit", "notify_input_method_commit",
                    "notify_input_method_candidate_pos_request"),
            List.of("onNotifyView", "onNotifyViewResize", "onNotifyRepaint", "onNotifyMouse", "onNotifyMenu",
                    "onNotifyScroll", "onNotifyKey", "onNotifyInputMethodPreedit", "onNotifyInputMethodCommit",
                    "onNotifyInputMethodCandidatePosRequest"),
            List.of(NOTIFY_VIEW_FD, NOTIFY_VIEW_RESIZE_FD, NOTIFY_REPAINT_FD, NOTIFY_MOUSE_FD, NOTIFY_MENU_FD,
                    NOTIFY_SCROLL_FD, NOTIFY_KEY_FD, NOTIFY_INPUT_METHOD_PREEDIT_FD, NOTIFY_INPUT_METHOD_COMMIT_FD,
                    NOTIFY_INPUT_METHOD_CANDIDATE_POS_REQUEST_FD));

    static final CallbackTable DND_TABLE = new CallbackTable(GGTK_DND_CALLBACKS_LAYOUT,
            List.of("notify_drag_enter", "notify_drag_over", "notify_drag_drop", "notify_drag_leave",
                    "source_get_data"),
            List.of("onNotifyDragEnter", "onNotifyDragOver", "onNotifyDragDrop", "onNotifyDragLeave",
                    "onSourceGetData"),
            List.of(NOTIFY_DRAG_ENTER_FD, NOTIFY_DRAG_OVER_FD, NOTIFY_DRAG_DROP_FD, NOTIFY_DRAG_LEAVE_FD,
                    SOURCE_GET_DATA_FD));

    static final List<CallbackTable> CALLBACK_TABLES = List.of(APP_TABLE, WINDOW_TABLE, VIEW_TABLE, DND_TABLE);

    /**
     * The production stubs, one list per table of {@link #CALLBACK_TABLES}, created once in {@link Arena#global()}
     * by {@link #installCallbacks} and never replaced: the library may call a stub it has been given at any later
     * point, so every one of them must outlive the process.
     */
    private static List<MemorySegment[]> callbackStubs;

    /**
     * The exports of {@code glass_gtk_api.h} the binding calls, in {@code libglassgtk3.so} as the class loader loaded
     * it ({@code NativeLibLoader.loadLibrary("glassgtk3")} in {@code GtkApplication}, or - {@code QUERY_USE_CURRENT}
     * - a {@code glassgtk3} build loaded as the {@code glass} library itself). A holder of its own: the
     * rest of the binding binds system libraries only and initializes before, or without, {@code glassgtk3} (a
     * headless test does). {@code ggtk_abi_version} is bound and checked before anything else of the library.
     */
    static final class Glass {

        private static final SymbolLookup LOOKUP = SymbolLookup.loaderLookup();

        static final MethodHandle ABI = bind(LOOKUP, LIB_GLASS, "ggtk_abi_version", FunctionDescriptor.of(JAVA_INT));

        static {
            int actual;
            try {
                actual = (int) ABI.invokeExact();
            } catch (Throwable t) {
                throw unexpected(t);
            }
            if (actual != ABI_VERSION) {
                throw new UnsatisfiedLinkError(LIB_GLASS + " ABI version mismatch: expected " + ABI_VERSION
                        + ", found " + actual);
            }
        }

        static final MethodHandle SIZEOF_APP = bind(LOOKUP, LIB_GLASS, "ggtk_sizeof_app_callbacks",
                FunctionDescriptor.of(JAVA_INT));
        static final MethodHandle SIZEOF_WINDOW = bind(LOOKUP, LIB_GLASS, "ggtk_sizeof_window_callbacks",
                FunctionDescriptor.of(JAVA_INT));
        static final MethodHandle SIZEOF_VIEW = bind(LOOKUP, LIB_GLASS, "ggtk_sizeof_view_callbacks",
                FunctionDescriptor.of(JAVA_INT));
        static final MethodHandle SIZEOF_DND = bind(LOOKUP, LIB_GLASS, "ggtk_sizeof_dnd_callbacks",
                FunctionDescriptor.of(JAVA_INT));
        static final MethodHandle SIZEOF_DND_DATA = bind(LOOKUP, LIB_GLASS, "ggtk_sizeof_dnd_data",
                FunctionDescriptor.of(JAVA_INT));
        static final MethodHandle SIZEOF_DND_TARGET_VALUE = bind(LOOKUP, LIB_GLASS, "ggtk_sizeof_dnd_target_value",
                FunctionDescriptor.of(JAVA_INT));

        /** {@code int32_t ggtk_*_set_callbacks(const Ggtk*Callbacks *cb)}. */
        static final MethodHandle APP_SET_CALLBACKS = bind(LOOKUP, LIB_GLASS, "ggtk_app_set_callbacks",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        static final MethodHandle WINDOW_SET_CALLBACKS = bind(LOOKUP, LIB_GLASS, "ggtk_window_set_callbacks",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        static final MethodHandle VIEW_SET_CALLBACKS = bind(LOOKUP, LIB_GLASS, "ggtk_view_set_callbacks",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        static final MethodHandle DND_SET_CALLBACKS = bind(LOOKUP, LIB_GLASS, "ggtk_dnd_set_callbacks",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));

        /*
         * THE NATIVES of glass_gtk_api.h, in its order: one function per former JNI native of GtkApplication,
         * GtkWindow, GtkView and GtkDnDClipboard whose body reads or changes the C's state. Four natives have no
         * function here: _terminateLoop, whose body was gtk_main_quit (GtkMainLoopCalls), and _getNativeView,
         * _scheduleRepaint and pushTargetActionToSystem, whose bodies were a constant or nothing - their Java
         * callers do that themselves. ggtk_window_t / ggtk_view_t / void* are ADDRESS, int32_t JAVA_INT, int64_t
         * JAVA_LONG, float JAVA_FLOAT, uint16_t JAVA_CHAR.
         */

        /* int32_t ggtk_application_init_gtk(int32_t version, int32_t verbose, float ui_scale, char **out_error) */
        static final MethodHandle APPLICATION_INIT_GTK = bind(LOOKUP, LIB_GLASS, "ggtk_application_init_gtk",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_FLOAT, ADDRESS));
        /* void ggtk_application_init(int64_t event_handler, int32_t disable_grab) */
        static final MethodHandle APPLICATION_INIT = bind(LOOKUP, LIB_GLASS, "ggtk_application_init",
                FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_INT));
        /* void ggtk_application_run_loop(int32_t no_error_trap) */
        static final MethodHandle APPLICATION_RUN_LOOP = bind(LOOKUP, LIB_GLASS, "ggtk_application_run_loop",
                FunctionDescriptor.ofVoid(JAVA_INT));
        /* int32_t ggtk_application_get_key_code_for_char(uint16_t character, int32_t hint) */
        static final MethodHandle APPLICATION_GET_KEY_CODE_FOR_CHAR = bind(LOOKUP, LIB_GLASS,
                "ggtk_application_get_key_code_for_char", FunctionDescriptor.of(JAVA_INT, JAVA_CHAR, JAVA_INT));

        /*
         * ggtk_window_t ggtk_window_create(ggtk_window_t owner, int64_t screen, int32_t mask, int64_t visual_id,
         * int64_t window_id)
         */
        static final MethodHandle WINDOW_CREATE = bind(LOOKUP, LIB_GLASS, "ggtk_window_create",
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG));
        /* int32_t ggtk_window_close(ggtk_window_t window) */
        static final MethodHandle WINDOW_CLOSE = bind(LOOKUP, LIB_GLASS, "ggtk_window_close",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        /* int32_t ggtk_window_set_view(ggtk_window_t window, ggtk_view_t view, int64_t *out_view_id) */
        static final MethodHandle WINDOW_SET_VIEW = bind(LOOKUP, LIB_GLASS, "ggtk_window_set_view",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        /* void ggtk_window_update_view_size(ggtk_window_t window) */
        static final MethodHandle WINDOW_UPDATE_VIEW_SIZE = bind(LOOKUP, LIB_GLASS, "ggtk_window_update_view_size",
                FunctionDescriptor.ofVoid(ADDRESS));
        /* void ggtk_window_minimize(ggtk_window_t window, int32_t minimize) */
        static final MethodHandle WINDOW_MINIMIZE = bind(LOOKUP, LIB_GLASS, "ggtk_window_minimize",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
        /* void ggtk_window_maximize(ggtk_window_t window, int32_t maximize, int32_t was_maximized) */
        static final MethodHandle WINDOW_MAXIMIZE = bind(LOOKUP, LIB_GLASS, "ggtk_window_maximize",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT));
        /*
         * void ggtk_window_set_bounds(ggtk_window_t window, int32_t x, int32_t y, int32_t x_set, int32_t y_set,
         * int32_t w, int32_t h, int32_t cw, int32_t ch, float x_gravity, float y_gravity)
         */
        static final MethodHandle WINDOW_SET_BOUNDS = bind(LOOKUP, LIB_GLASS, "ggtk_window_set_bounds",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT,
                        JAVA_INT, JAVA_INT, JAVA_FLOAT, JAVA_FLOAT));
        /* void ggtk_window_set_visible(ggtk_window_t window, int32_t visible) */
        static final MethodHandle WINDOW_SET_VISIBLE = bind(LOOKUP, LIB_GLASS, "ggtk_window_set_visible",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
        /* int32_t ggtk_window_set_resizable(ggtk_window_t window, int32_t resizable) */
        static final MethodHandle WINDOW_SET_RESIZABLE = bind(LOOKUP, LIB_GLASS, "ggtk_window_set_resizable",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        /* int32_t ggtk_window_request_focus(ggtk_window_t window, int32_t event) */
        static final MethodHandle WINDOW_REQUEST_FOCUS = bind(LOOKUP, LIB_GLASS, "ggtk_window_request_focus",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        /* void ggtk_window_set_focusable(ggtk_window_t window, int32_t focusable) */
        static final MethodHandle WINDOW_SET_FOCUSABLE = bind(LOOKUP, LIB_GLASS, "ggtk_window_set_focusable",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
        /* int32_t ggtk_window_grab_focus(ggtk_window_t window) */
        static final MethodHandle WINDOW_GRAB_FOCUS = bind(LOOKUP, LIB_GLASS, "ggtk_window_grab_focus",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        /* void ggtk_window_ungrab_focus(ggtk_window_t window) */
        static final MethodHandle WINDOW_UNGRAB_FOCUS = bind(LOOKUP, LIB_GLASS, "ggtk_window_ungrab_focus",
                FunctionDescriptor.ofVoid(ADDRESS));
        /* int32_t ggtk_window_set_title(ggtk_window_t window, const uint16_t *title, int32_t title_len) */
        static final MethodHandle WINDOW_SET_TITLE = bind(LOOKUP, LIB_GLASS, "ggtk_window_set_title",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
        /* void ggtk_window_set_level(ggtk_window_t window, int32_t level) */
        static final MethodHandle WINDOW_SET_LEVEL = bind(LOOKUP, LIB_GLASS, "ggtk_window_set_level",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
        /* void ggtk_window_set_alpha(ggtk_window_t window, float alpha) */
        static final MethodHandle WINDOW_SET_ALPHA = bind(LOOKUP, LIB_GLASS, "ggtk_window_set_alpha",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_FLOAT));
        /* int32_t ggtk_window_set_background(ggtk_window_t window, float r, float g, float b) */
        static final MethodHandle WINDOW_SET_BACKGROUND = bind(LOOKUP, LIB_GLASS, "ggtk_window_set_background",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT));
        /* void ggtk_window_set_enabled(ggtk_window_t window, int32_t enabled) */
        static final MethodHandle WINDOW_SET_ENABLED = bind(LOOKUP, LIB_GLASS, "ggtk_window_set_enabled",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
        /* int32_t ggtk_window_set_minimum_size(ggtk_window_t window, int32_t w, int32_t h) */
        static final MethodHandle WINDOW_SET_MINIMUM_SIZE = bind(LOOKUP, LIB_GLASS, "ggtk_window_set_minimum_size",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
        /* int32_t ggtk_window_set_system_minimum_size(ggtk_window_t window, int32_t w, int32_t h) */
        static final MethodHandle WINDOW_SET_SYSTEM_MINIMUM_SIZE = bind(LOOKUP, LIB_GLASS,
                "ggtk_window_set_system_minimum_size", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
        /* int32_t ggtk_window_set_maximum_size(ggtk_window_t window, int32_t w, int32_t h) */
        static final MethodHandle WINDOW_SET_MAXIMUM_SIZE = bind(LOOKUP, LIB_GLASS, "ggtk_window_set_maximum_size",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
        /* void ggtk_window_set_icon(ggtk_window_t window, void *pixbuf, int32_t attach_threw) */
        static final MethodHandle WINDOW_SET_ICON = bind(LOOKUP, LIB_GLASS, "ggtk_window_set_icon",
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT));
        /* void ggtk_window_to_front(ggtk_window_t window) */
        static final MethodHandle WINDOW_TO_FRONT = bind(LOOKUP, LIB_GLASS, "ggtk_window_to_front",
                FunctionDescriptor.ofVoid(ADDRESS));
        /* void ggtk_window_to_back(ggtk_window_t window) */
        static final MethodHandle WINDOW_TO_BACK = bind(LOOKUP, LIB_GLASS, "ggtk_window_to_back",
                FunctionDescriptor.ofVoid(ADDRESS));
        /* void ggtk_window_set_cursor_type(ggtk_window_t window, int32_t type) */
        static final MethodHandle WINDOW_SET_CURSOR_TYPE = bind(LOOKUP, LIB_GLASS, "ggtk_window_set_cursor_type",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
        /* void ggtk_window_set_cursor(ggtk_window_t window, void *cursor) */
        static final MethodHandle WINDOW_SET_CURSOR = bind(LOOKUP, LIB_GLASS, "ggtk_window_set_cursor",
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
        /* void ggtk_window_show_system_menu(ggtk_window_t window, int32_t x, int32_t y) */
        static final MethodHandle WINDOW_SHOW_SYSTEM_MENU = bind(LOOKUP, LIB_GLASS, "ggtk_window_show_system_menu",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT));
        /* int32_t ggtk_window_is_visible(ggtk_window_t window) */
        static final MethodHandle WINDOW_IS_VISIBLE = bind(LOOKUP, LIB_GLASS, "ggtk_window_is_visible",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        /* int64_t ggtk_window_get_native_window(ggtk_window_t window) */
        static final MethodHandle WINDOW_GET_NATIVE_WINDOW = bind(LOOKUP, LIB_GLASS, "ggtk_window_get_native_window",
                FunctionDescriptor.of(JAVA_LONG, ADDRESS));

        /* void ggtk_view_enable_input_method_events(ggtk_view_t view, int32_t enable) */
        static final MethodHandle VIEW_ENABLE_INPUT_METHOD_EVENTS = bind(LOOKUP, LIB_GLASS,
                "ggtk_view_enable_input_method_events", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
        /* ggtk_view_t ggtk_view_create(int64_t view_id) */
        static final MethodHandle VIEW_CREATE = bind(LOOKUP, LIB_GLASS, "ggtk_view_create",
                FunctionDescriptor.of(ADDRESS, JAVA_LONG));
        /* int32_t ggtk_view_get_x(ggtk_view_t view) */
        static final MethodHandle VIEW_GET_X = bind(LOOKUP, LIB_GLASS, "ggtk_view_get_x",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        /* int32_t ggtk_view_get_y(ggtk_view_t view) */
        static final MethodHandle VIEW_GET_Y = bind(LOOKUP, LIB_GLASS, "ggtk_view_get_y",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        /* void ggtk_view_set_parent(ggtk_view_t view, ggtk_window_t parent) */
        static final MethodHandle VIEW_SET_PARENT = bind(LOOKUP, LIB_GLASS, "ggtk_view_set_parent",
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
        /* int32_t ggtk_view_close(ggtk_view_t view) */
        static final MethodHandle VIEW_CLOSE = bind(LOOKUP, LIB_GLASS, "ggtk_view_close",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        /* void ggtk_view_upload_pixels_direct(ggtk_view_t view, void *data, int32_t width, int32_t height) */
        static final MethodHandle VIEW_UPLOAD_PIXELS_DIRECT = bind(LOOKUP, LIB_GLASS,
                "ggtk_view_upload_pixels_direct", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT));
        /*
         * void ggtk_view_upload_pixels_int(ggtk_view_t view, const int32_t *pixels, int32_t pixels_len,
         * int32_t offset, int32_t width, int32_t height) - with a native copy of the frame (viewUploadPixelsInt)
         */
        static final MethodHandle VIEW_UPLOAD_PIXELS_INT = bind(LOOKUP, LIB_GLASS, "ggtk_view_upload_pixels_int",
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
        /*
         * void ggtk_view_upload_pixels_byte(ggtk_view_t view, const uint8_t *pixels, int32_t pixels_len,
         * int32_t offset, int32_t width, int32_t height) - with a native copy of the frame (viewUploadPixelsByte)
         */
        static final MethodHandle VIEW_UPLOAD_PIXELS_BYTE = bind(LOOKUP, LIB_GLASS, "ggtk_view_upload_pixels_byte",
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
        /*
         * int32_t ggtk_view_enter_fullscreen(ggtk_view_t view, int32_t animate, int32_t keep_ratio,
         * int32_t hide_cursor)
         */
        static final MethodHandle VIEW_ENTER_FULLSCREEN = bind(LOOKUP, LIB_GLASS, "ggtk_view_enter_fullscreen",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT));
        /* void ggtk_view_exit_fullscreen(ggtk_view_t view, int32_t animate) */
        static final MethodHandle VIEW_EXIT_FULLSCREEN = bind(LOOKUP, LIB_GLASS, "ggtk_view_exit_fullscreen",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));

        static final MethodHandle DND_IS_OWNER = bind(LOOKUP, LIB_GLASS, "ggtk_dnd_is_owner",
                FunctionDescriptor.of(JAVA_INT));
        /* int32_t ggtk_dnd_push_to_system(const char *keys, int32_t key_count, int32_t supported) */
        static final MethodHandle DND_PUSH_TO_SYSTEM = bind(LOOKUP, LIB_GLASS, "ggtk_dnd_push_to_system",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
        /* int32_t ggtk_dnd_target_get_supported_actions(int32_t *out_actions) */
        static final MethodHandle DND_TARGET_GET_SUPPORTED_ACTIONS = bind(LOOKUP, LIB_GLASS,
                "ggtk_dnd_target_get_supported_actions", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        /* int32_t ggtk_dnd_target_get_mimes(char **out_strings, int32_t *out_count, int64_t *out_generation) */
        static final MethodHandle DND_TARGET_GET_MIMES = bind(LOOKUP, LIB_GLASS, "ggtk_dnd_target_get_mimes",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        /* int32_t ggtk_dnd_target_get_data(const char *mime, GgtkDndTargetValue *out) */
        static final MethodHandle DND_TARGET_GET_DATA = bind(LOOKUP, LIB_GLASS, "ggtk_dnd_target_get_data",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

        private Glass() {
        }
    }

    /**
     * Every slot must be installed before the first of the natives' functions ({@link Glass}) runs: the library makes
     * no call for a {@code NULL} slot and goes on as after a normal return, with the out-parameters at the values it
     * initialized them to ({@code glass_gtk_api.h}, NULL SLOTS) - an event would reach no {@code Window} or
     * {@code View}, and a window without {@code is_enabled} counts as disabled and drops its input.
     * <p>
     * <b>Called once, by the {@code GtkApplication} constructor, right after {@link GtkGlassNative#link}</b>, whichever
     * glass GTK library {@code _queryLibrary} answered for: on the thread that constructs the application, before
     * {@code _initGTK} and {@code _init} and so before any GLib signal of the library is connected and before the
     * first window exists; {@code _createWindow} already dials {@code get_application_name}. The library copies each
     * table by value and reads the slots on the thread that iterates GLib's default main context, which that
     * constructor has not started yet.
     *
     * @throws UnsatisfiedLinkError if the library lacks an export, its ABI version differs from
     *         {@link GtkGlassNative#ABI_VERSION}, or a layout disagrees with the C
     */
    static synchronized void installCallbacks() {
        if (callbackStubs != null) {
            return;
        }
        checkLayouts();
        List<MemorySegment[]> stubs = new ArrayList<>();
        for (CallbackTable table : CALLBACK_TABLES) {
            MemorySegment[] tableStubs = new MemorySegment[table.slots().size()];
            for (int i = 0; i < tableStubs.length; i++) {
                tableStubs[i] = upcallStub(GtkCallbackTargets.class,
                        table.targets().get(i), table.descriptors().get(i));
            }
            stubs.add(tableStubs);
        }
        writeCallbackTables(stubs);
        callbackStubs = List.copyOf(stubs);
    }

    static synchronized boolean callbacksInstalled() {
        return callbackStubs != null;
    }

    /**
     * Writes the production tables again, with the stubs {@link #installCallbacks} created; the restore after a test
     * installed tables of its own ({@code ggtk_*_set_callbacks} copies by value, the last call wins).
     */
    static synchronized void reinstallCallbacks() {
        if (callbackStubs == null) {
            installCallbacks();
            return;
        }
        writeCallbackTables(callbackStubs);
    }

    /** The installed production stubs, table by table in the order of {@link #CALLBACK_TABLES}, for tests. */
    static synchronized List<List<MemorySegment>> installedCallbackStubs() {
        List<List<MemorySegment>> result = new ArrayList<>();
        if (callbackStubs != null) {
            for (MemorySegment[] tableStubs : callbackStubs) {
                result.add(List.of(tableStubs));
            }
        }
        return result;
    }

    /**
     * Fills the four tables with {@code stubs} - one array per table of {@link #CALLBACK_TABLES}, in slot order; a
     * {@code null} element leaves its slot {@code NULL}, which the library then never calls (see
     * {@link #installCallbacks}); only tests write such a table - and installs them.
     */
    static void writeCallbackTables(List<MemorySegment[]> stubs) {
        MethodHandle[] installers = {Glass.APP_SET_CALLBACKS, Glass.WINDOW_SET_CALLBACKS, Glass.VIEW_SET_CALLBACKS,
                Glass.DND_SET_CALLBACKS};
        try (Arena arena = Arena.ofConfined()) {
            for (int t = 0; t < CALLBACK_TABLES.size(); t++) {
                CallbackTable table = CALLBACK_TABLES.get(t);
                MemorySegment[] tableStubs = stubs.get(t);
                if (tableStubs.length != table.slots().size()) {
                    throw new IllegalArgumentException(table.slots().size() + " slots, " + tableStubs.length
                            + " stubs");
                }
                MemorySegment segment = arena.allocate(table.layout());
                for (int i = 0; i < tableStubs.length; i++) {
                    segment.set(ADDRESS, table.layout().byteOffset(PathElement.groupElement(table.slots().get(i))),
                            tableStubs[i] == null ? MemorySegment.NULL : tableStubs[i]);
                }
                int status = (int) installers[t].invokeExact(segment);
                if (status != 0) {
                    throw new IllegalStateException("ggtk_*_set_callbacks answered " + status);
                }
            }
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code sizeof} of the four tables and of {@code GgtkDndData} as the C compiler saw them, in the order of
     * {@link #CALLBACK_TABLES}, then the data.
     */
    static int[] callbackLayoutSizes() {
        try {
            return new int[] {(int) Glass.SIZEOF_APP.invokeExact(), (int) Glass.SIZEOF_WINDOW.invokeExact(),
                    (int) Glass.SIZEOF_VIEW.invokeExact(), (int) Glass.SIZEOF_DND.invokeExact(),
                    (int) Glass.SIZEOF_DND_DATA.invokeExact()};
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static void checkLayouts() {
        int[] sizes = callbackLayoutSizes();
        long[] expected = {GGTK_APP_CALLBACKS_LAYOUT.byteSize(), GGTK_WINDOW_CALLBACKS_LAYOUT.byteSize(),
                GGTK_VIEW_CALLBACKS_LAYOUT.byteSize(), GGTK_DND_CALLBACKS_LAYOUT.byteSize(),
                GGTK_DND_DATA_LAYOUT.byteSize()};
        for (int i = 0; i < sizes.length; i++) {
            if (sizes[i] != expected[i]) {
                throw new UnsatisfiedLinkError(LIB_GLASS + " layout mismatch: sizeof " + i + " is " + sizes[i]
                        + ", the layout has " + expected[i] + " bytes");
            }
        }
        int targetValue;
        try {
            targetValue = (int) Glass.SIZEOF_DND_TARGET_VALUE.invokeExact();
        } catch (Throwable t) {
            throw unexpected(t);
        }
        if (targetValue != GGTK_DND_TARGET_VALUE_LAYOUT.byteSize()) {
            throw new UnsatisfiedLinkError(LIB_GLASS + " layout mismatch: sizeof GgtkDndTargetValue is " + targetValue
                    + ", the layout has " + GGTK_DND_TARGET_VALUE_LAYOUT.byteSize() + " bytes");
        }
    }

    private static long dndDataOffset(String name) {
        return GGTK_DND_DATA_LAYOUT.byteOffset(PathElement.groupElement(name));
    }

    private GtkCallbackTables() {
    }
}
