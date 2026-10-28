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

import com.sun.glass.ui.win.WinAccessibilityMarshalling.BlockAllocator;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import static com.sun.glass.ui.win.WinAccessibilityMarshalling.variantArgument;
import static com.sun.glass.ui.win.WinCallSupport.fillCallbackTable;
import static com.sun.glass.ui.win.WinCallSupport.requireOk;
import static com.sun.glass.ui.win.WinCallSupport.stubsFor;
import static com.sun.glass.ui.win.WinGlassNative.bind;
import static com.sun.glass.ui.win.WinGlassNative.bindGlass;
import static com.sun.glass.ui.win.WinGlassNative.systemLibrary;
import static com.sun.glass.ui.win.WinGlassNative.unexpected;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The {@link WinGlassNative} calls of {@code WinAccessible} and {@code WinTextRangeProvider}: the two UI
 * Automation callback tables (layouts, descriptors, install), the provider lifetime downcalls and the two
 * {@code UIAutomationCore} functions.
 * <p>
 * <b>The last JNI of {@code glass.dll}: the two UI Automation providers</b> (ABI 6). The nine native
 * methods of {@code WinAccessible} and {@code WinTextRangeProvider} are the ten exports and the two
 * callback tables of {@link #installAccessibilityCallbacks}. The provider objects themselves stay in C,
 * because they are the <em>inbound</em> edge of UI Automation - 19 COM interfaces whose vtables Windows
 * invokes, plus {@code oleaut32} {@code BSTR} and {@code SAFEARRAY} marshalling - but the 87
 * {@code Call*Method} sites in their bodies became slots keyed by an {@code int64_t} id, and the two
 * {@code _initIDs} disappeared with everything they cached. That is not a preference: {@code FindClass}
 * inside a downcall cannot see a javafx.graphics class on JDK 26 ({@code WinDowncallExceptionReportingTest}
 * names that regression), so the only safe design is one in which {@code glass.dll} looks nothing up. Two of
 * the nine get no export at all - {@code UiaRaiseAutomationEvent} and {@code UiaClientsAreListening} were
 * one-line {@code WRAPPER}s, and Java binds {@code UIAutomationCore} itself ({@link UiaCore}). The targets are
 * {@link WinAccessibleCallbacks} and {@link WinTextRangeCallbacks}; the {@code GwinVariant} and out-parameter
 * marshalling is {@link WinAccessibilityMarshalling}.
 * <p>
 * Stated behaviour difference: a slot dialled on a thread the JVM has never seen - which Windows does for the
 * two advise slots during a Narrator shutdown, and for either disposal slot - answered {@code E_FAIL}
 * under JNI, because {@code GetEnv()} returned {@code NULL} and the bodies gave up rather than
 * attaching; an FFM upcall stub attaches the caller and runs the Java target.
 */
final class WinAccessibilityCalls {

    /**
     * {@code GwinAccessibleCallbacks} ({@code glass_win_api.h}): the 69 {@code jmethodID}s of
     * {@code WinAccessible._initIDs} in that order - which is the UI Automation interface order of
     * {@code GlassAccessible.cpp} - plus {@code accessible_disposed}, which stands where
     * {@code DeleteGlobalRef} stood in {@code ~GlassAccessible}. Every slot's first parameter is the
     * {@code int64_t} id {@code WinAccessible} registered itself under; there is no table-level
     * {@code void* user}, for the reason {@link WinViewCallbacks#installViewCallbacks} gives.
     * {@code gwin_sizeof_accessible_callbacks} pins the size; {@link #ACCESSIBLE_SLOT_NAMES} is the
     * order the installer writes the stubs in, and the order
     * {@code gwin_test_fire_accessible_callback} numbers them, 0..69.
     */
    static final StructLayout GWIN_ACCESSIBLE_CALLBACKS_LAYOUT = MemoryLayout.structLayout(
            ADDRESS.withName("get_pattern_provider"),
            ADDRESS.withName("get_host_raw_element_provider"),
            ADDRESS.withName("get_property_value"),
            ADDRESS.withName("get_bounding_rectangle"),
            ADDRESS.withName("get_fragment_root"),
            ADDRESS.withName("get_embedded_fragment_roots"),
            ADDRESS.withName("get_runtime_id"),
            ADDRESS.withName("navigate"),
            ADDRESS.withName("set_focus"),
            ADDRESS.withName("element_provider_from_point"),
            ADDRESS.withName("get_focus"),
            ADDRESS.withName("advise_event_added"),
            ADDRESS.withName("advise_event_removed"),
            ADDRESS.withName("invoke"),
            ADDRESS.withName("get_selection"),
            ADDRESS.withName("get_can_select_multiple"),
            ADDRESS.withName("get_is_selection_required"),
            ADDRESS.withName("select"),
            ADDRESS.withName("add_to_selection"),
            ADDRESS.withName("remove_from_selection"),
            ADDRESS.withName("get_is_selected"),
            ADDRESS.withName("get_selection_container"),
            ADDRESS.withName("set_value"),
            ADDRESS.withName("get_value"),
            ADDRESS.withName("get_is_read_only"),
            ADDRESS.withName("get_maximum"),
            ADDRESS.withName("get_minimum"),
            ADDRESS.withName("get_large_change"),
            ADDRESS.withName("get_small_change"),
            ADDRESS.withName("set_value_string"),
            ADDRESS.withName("get_value_string"),
            ADDRESS.withName("get_visible_ranges"),
            ADDRESS.withName("range_from_child"),
            ADDRESS.withName("range_from_point"),
            ADDRESS.withName("get_document_range"),
            ADDRESS.withName("get_supported_text_selection"),
            ADDRESS.withName("get_column_count"),
            ADDRESS.withName("get_row_count"),
            ADDRESS.withName("get_item"),
            ADDRESS.withName("get_column"),
            ADDRESS.withName("get_column_span"),
            ADDRESS.withName("get_containing_grid"),
            ADDRESS.withName("get_row"),
            ADDRESS.withName("get_row_span"),
            ADDRESS.withName("get_column_headers"),
            ADDRESS.withName("get_row_headers"),
            ADDRESS.withName("get_row_or_column_major"),
            ADDRESS.withName("get_column_header_items"),
            ADDRESS.withName("get_row_header_items"),
            ADDRESS.withName("toggle"),
            ADDRESS.withName("get_toggle_state"),
            ADDRESS.withName("collapse"),
            ADDRESS.withName("expand"),
            ADDRESS.withName("get_expand_collapse_state"),
            ADDRESS.withName("get_can_move"),
            ADDRESS.withName("get_can_resize"),
            ADDRESS.withName("get_can_rotate"),
            ADDRESS.withName("move"),
            ADDRESS.withName("resize"),
            ADDRESS.withName("rotate"),
            ADDRESS.withName("scroll"),
            ADDRESS.withName("set_scroll_percent"),
            ADDRESS.withName("get_horizontally_scrollable"),
            ADDRESS.withName("get_horizontal_scroll_percent"),
            ADDRESS.withName("get_horizontal_view_size"),
            ADDRESS.withName("get_vertically_scrollable"),
            ADDRESS.withName("get_vertical_scroll_percent"),
            ADDRESS.withName("get_vertical_view_size"),
            ADDRESS.withName("scroll_into_view"),
            ADDRESS.withName("accessible_disposed"));

    /**
     * {@code GwinTextRangeCallbacks} ({@code glass_win_api.h}): the 18 {@code jmethodID}s of
     * {@code WinTextRangeProvider._initIDs} plus {@code range_disposed}, with
     * {@link #GWIN_ACCESSIBLE_CALLBACKS_LAYOUT}'s rules. {@code gwin_sizeof_text_range_callbacks} pins
     * the size; {@code gwin_test_fire_text_range_callback} numbers the slots 0..18.
     */
    static final StructLayout GWIN_TEXT_RANGE_CALLBACKS_LAYOUT = MemoryLayout.structLayout(
            ADDRESS.withName("clone"),
            ADDRESS.withName("compare"),
            ADDRESS.withName("compare_endpoints"),
            ADDRESS.withName("expand_to_enclosing_unit"),
            ADDRESS.withName("find_attribute"),
            ADDRESS.withName("find_text"),
            ADDRESS.withName("get_attribute_value"),
            ADDRESS.withName("get_bounding_rectangles"),
            ADDRESS.withName("get_enclosing_element"),
            ADDRESS.withName("get_text"),
            ADDRESS.withName("move"),
            ADDRESS.withName("move_endpoint_by_unit"),
            ADDRESS.withName("move_endpoint_by_range"),
            ADDRESS.withName("select"),
            ADDRESS.withName("add_to_selection"),
            ADDRESS.withName("remove_from_selection"),
            ADDRESS.withName("scroll_into_view"),
            ADDRESS.withName("get_children"),
            ADDRESS.withName("range_disposed"));

    /** {@code int32_t gwin_sizeof_accessible_callbacks(void)}; {@link #GWIN_ACCESSIBLE_CALLBACKS_LAYOUT} must agree. */
    private static final MethodHandle GWIN_SIZEOF_ACCESSIBLE_CALLBACKS =
            bindGlass("gwin_sizeof_accessible_callbacks", FunctionDescriptor.of(JAVA_INT));

    /** {@code int32_t gwin_sizeof_text_range_callbacks(void)}; {@link #GWIN_TEXT_RANGE_CALLBACKS_LAYOUT} must agree. */
    private static final MethodHandle GWIN_SIZEOF_TEXT_RANGE_CALLBACKS =
            bindGlass("gwin_sizeof_text_range_callbacks", FunctionDescriptor.of(JAVA_INT));

    /**
     * {@code int32_t gwin_sizeof_variant(void)}; {@link WinAccessibilityMarshalling#GWIN_VARIANT_LAYOUT} must agree,
     * field for field.
     */
    private static final MethodHandle GWIN_SIZEOF_VARIANT =
            bindGlass("gwin_sizeof_variant", FunctionDescriptor.of(JAVA_INT));

    /** {@code int32_t gwin_a11y_set_callbacks(const GwinAccessibleCallbacks* cb)}. */
    private static final MethodHandle GWIN_A11Y_SET_CALLBACKS =
            bindGlass("gwin_a11y_set_callbacks", FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** {@code int32_t gwin_a11y_text_range_set_callbacks(const GwinTextRangeCallbacks* cb)}. */
    private static final MethodHandle GWIN_A11Y_TEXT_RANGE_SET_CALLBACKS =
            bindGlass("gwin_a11y_text_range_set_callbacks", FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /**
     * {@code gwin_accessible_t gwin_a11y_create(int64_t accessible_id)}. Not
     * {@link Linker.Option#critical}, as nothing in this section is: the destroy calls re-enter the JVM
     * from a destructor and the raise call blocks in UIAutomationCore.
     */
    private static final MethodHandle GWIN_A11Y_CREATE =
            bindGlass("gwin_a11y_create", FunctionDescriptor.of(ADDRESS, JAVA_LONG));

    /** {@code void gwin_a11y_destroy(gwin_accessible_t acc)}: {@code Release}, not {@code delete}. */
    private static final MethodHandle GWIN_A11Y_DESTROY =
            bindGlass("gwin_a11y_destroy", FunctionDescriptor.ofVoid(ADDRESS));

    /** {@code gwin_text_range_t gwin_a11y_text_range_create(gwin_accessible_t acc, int64_t range_id)}. */
    private static final MethodHandle GWIN_A11Y_TEXT_RANGE_CREATE = bindGlass("gwin_a11y_text_range_create",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG));

    /** {@code void gwin_a11y_text_range_destroy(gwin_text_range_t range)}. */
    private static final MethodHandle GWIN_A11Y_TEXT_RANGE_DESTROY =
            bindGlass("gwin_a11y_text_range_destroy", FunctionDescriptor.ofVoid(ADDRESS));

    /**
     * {@code int64_t gwin_a11y_raise_property_changed(gwin_accessible_t acc, int32_t property_id,
     * const GwinVariant* old_value, const GwinVariant* new_value)}: the {@code HRESULT},
     * sign-extended.
     */
    private static final MethodHandle GWIN_A11Y_RAISE_PROPERTY_CHANGED =
            bindGlass("gwin_a11y_raise_property_changed",
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));

    /*
     * Identity is the int64_t id the peer registered itself under, never WinAccessible.id or
     * WinTextRangeProvider.id - those two are part of the UIA runtime id a client can see. The handles
     * that cross as long are raw C++ object pointers, not ids.
     *
     * Every slot returns int32_t GwinStatus even where its Java target is void, because the JNI turned a
     * pending Throwable into E_FAIL for those too. GWIN_ERR_UPCALL is that E_FAIL, and a stub that
     * reports it writes no out-parameter: the library pre-zeroes them, which is exactly what
     * CallXxxMethod left behind when it returned with an exception pending.
     *
     * Thread: the JavaFX application thread, inside the gwin_run_loop downcall's message pump, except
     * advise_event_added / advise_event_removed and the two disposal slots, which Windows can dial on a
     * COM/RPC thread; the class documentation states what that changed.
     */

    /** {@code int32_t (*)(int64_t id)} - a slot whose Java target takes nothing and returns nothing. */
    static final FunctionDescriptor A11Y_ACT_FD = FunctionDescriptor.of(JAVA_INT, JAVA_LONG);

    static final FunctionDescriptor A11Y_ACT_I_FD = FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT);

    static final FunctionDescriptor A11Y_ACT_II_FD =
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT);

    static final FunctionDescriptor A11Y_ACT_D_FD = FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_DOUBLE);

    static final FunctionDescriptor A11Y_ACT_DD_FD =
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_DOUBLE, JAVA_DOUBLE);

    /** {@code int32_t (*)(int64_t id, int32_t, int64_t)} - the two advise slots' raw {@code SAFEARRAY*}. */
    static final FunctionDescriptor A11Y_ACT_IJ_FD =
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_LONG);

    /** {@code int32_t (*)(int64_t id, int32_t, int64_t other_id, int32_t)} - {@code move_endpoint_by_range}. */
    static final FunctionDescriptor A11Y_ACT_IJI_FD =
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_INT);

    /** {@code int32_t (*)(int64_t id, const uint16_t* text, int32_t len)} - borrowed UTF-16 code units. */
    static final FunctionDescriptor A11Y_ACT_TEXT_FD =
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_INT);

    /** {@code int32_t (*)(int64_t id, T* out)} - one scalar or one {@code GwinVariant} out-parameter. */
    static final FunctionDescriptor A11Y_OUT_FD = FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS);

    /** {@code int32_t (*)(int64_t id, T** out, int32_t* out_count)} - a block and its count. */
    static final FunctionDescriptor A11Y_OUT2_FD =
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, ADDRESS);

    static final FunctionDescriptor A11Y_I_OUT_FD =
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, ADDRESS);

    /** {@code int32_t (*)(int64_t id, int32_t, T** out, int32_t* out_count)} - {@code get_text}. */
    static final FunctionDescriptor A11Y_I_OUT2_FD =
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, ADDRESS, ADDRESS);

    /** {@code int32_t (*)(int64_t id, int64_t, T* out)} - a raw pointer or another provider's id in. */
    static final FunctionDescriptor A11Y_J_OUT_FD =
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, ADDRESS);

    static final FunctionDescriptor A11Y_DD_OUT_FD =
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_DOUBLE, JAVA_DOUBLE, ADDRESS);

    static final FunctionDescriptor A11Y_II_OUT_FD =
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT, ADDRESS);

    /** {@code int32_t (*)(int64_t id, int32_t, int32_t, int32_t, T* out)} - {@code move_endpoint_by_unit}. */
    static final FunctionDescriptor A11Y_III_OUT_FD =
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS);

    /** {@code int32_t (*)(int64_t id, int32_t, int64_t other_id, int32_t, T* out)} - {@code compare_endpoints}. */
    static final FunctionDescriptor A11Y_IJI_OUT_FD =
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_INT, ADDRESS);

    /**
     * {@code int32_t (*)(int64_t id, int32_t, const GwinVariant*, int32_t, int64_t* out)} -
     * {@code find_attribute}, whose {@code GwinVariant*} the library always passes as {@code NULL}.
     */
    static final FunctionDescriptor A11Y_IVB_OUT_FD =
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS);

    /** {@code int32_t (*)(int64_t id, const uint16_t* text, int32_t len, int32_t, int32_t, int64_t* out)}. */
    static final FunctionDescriptor A11Y_FIND_TEXT_FD =
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS);

    /**
     * {@code void (*)(int64_t id)} - the two disposal slots, which stand where {@code DeleteGlobalRef}
     * stood and are the only place a registry entry may be dropped. Void, and they must not throw.
     */
    static final FunctionDescriptor A11Y_DISPOSED_FD = FunctionDescriptor.ofVoid(JAVA_LONG);

    /** The slot order of {@link #GWIN_ACCESSIBLE_CALLBACKS_LAYOUT}. */
    static final List<String> ACCESSIBLE_SLOT_NAMES = List.of("get_pattern_provider",
            "get_host_raw_element_provider", "get_property_value", "get_bounding_rectangle", "get_fragment_root",
            "get_embedded_fragment_roots", "get_runtime_id", "navigate", "set_focus", "element_provider_from_point",
            "get_focus", "advise_event_added", "advise_event_removed", "invoke", "get_selection",
            "get_can_select_multiple", "get_is_selection_required", "select", "add_to_selection",
            "remove_from_selection", "get_is_selected", "get_selection_container", "set_value", "get_value",
            "get_is_read_only", "get_maximum", "get_minimum", "get_large_change", "get_small_change",
            "set_value_string", "get_value_string", "get_visible_ranges", "range_from_child", "range_from_point",
            "get_document_range", "get_supported_text_selection", "get_column_count", "get_row_count", "get_item",
            "get_column", "get_column_span", "get_containing_grid", "get_row", "get_row_span", "get_column_headers",
            "get_row_headers", "get_row_or_column_major", "get_column_header_items", "get_row_header_items",
            "toggle", "get_toggle_state", "collapse", "expand", "get_expand_collapse_state", "get_can_move",
            "get_can_resize", "get_can_rotate", "move", "resize", "rotate", "scroll", "set_scroll_percent",
            "get_horizontally_scrollable", "get_horizontal_scroll_percent", "get_horizontal_view_size",
            "get_vertically_scrollable", "get_vertical_scroll_percent", "get_vertical_view_size",
            "scroll_into_view", "accessible_disposed");

    /** The target methods of {@link WinAccessibleCallbacks}, parallel to {@link #ACCESSIBLE_SLOT_NAMES}. */
    private static final List<String> ACCESSIBLE_SLOT_TARGETS = List.of("onGetPatternProvider",
            "onGetHostRawElementProvider", "onGetPropertyValue", "onGetBoundingRectangle", "onGetFragmentRoot",
            "onGetEmbeddedFragmentRoots", "onGetRuntimeId", "onNavigate", "onSetFocus",
            "onElementProviderFromPoint", "onGetFocus", "onAdviseEventAdded", "onAdviseEventRemoved", "onInvoke",
            "onGetSelection", "onGetCanSelectMultiple", "onGetIsSelectionRequired", "onSelect", "onAddToSelection",
            "onRemoveFromSelection", "onGetIsSelected", "onGetSelectionContainer", "onSetValue", "onGetValue",
            "onGetIsReadOnly", "onGetMaximum", "onGetMinimum", "onGetLargeChange", "onGetSmallChange",
            "onSetValueString", "onGetValueString", "onGetVisibleRanges", "onRangeFromChild", "onRangeFromPoint",
            "onGetDocumentRange", "onGetSupportedTextSelection", "onGetColumnCount", "onGetRowCount", "onGetItem",
            "onGetColumn", "onGetColumnSpan", "onGetContainingGrid", "onGetRow", "onGetRowSpan",
            "onGetColumnHeaders", "onGetRowHeaders", "onGetRowOrColumnMajor", "onGetColumnHeaderItems",
            "onGetRowHeaderItems", "onToggle", "onGetToggleState", "onCollapse", "onExpand",
            "onGetExpandCollapseState", "onGetCanMove", "onGetCanResize", "onGetCanRotate", "onMove", "onResize",
            "onRotate", "onScroll", "onSetScrollPercent", "onGetHorizontallyScrollable",
            "onGetHorizontalScrollPercent", "onGetHorizontalViewSize", "onGetVerticallyScrollable",
            "onGetVerticalScrollPercent", "onGetVerticalViewSize", "onScrollIntoView", "onAccessibleDisposed");

    /**
     * The descriptors, parallel to {@link #ACCESSIBLE_SLOT_NAMES}. Package-private because
     * {@code WinGlassNativeShim} builds its recording table from these very constants, so that what a
     * fire hook proves is the production descriptor and not a copy of it.
     */
    static final List<FunctionDescriptor> ACCESSIBLE_SLOT_DESCRIPTORS = List.of(A11Y_I_OUT_FD, A11Y_OUT_FD,
            A11Y_I_OUT_FD, A11Y_OUT2_FD, A11Y_OUT_FD, A11Y_OUT2_FD, A11Y_OUT2_FD, A11Y_I_OUT_FD, A11Y_ACT_FD,
            A11Y_DD_OUT_FD, A11Y_OUT_FD, A11Y_ACT_IJ_FD, A11Y_ACT_IJ_FD, A11Y_ACT_FD, A11Y_OUT2_FD, A11Y_OUT_FD,
            A11Y_OUT_FD, A11Y_ACT_FD, A11Y_ACT_FD, A11Y_ACT_FD, A11Y_OUT_FD, A11Y_OUT_FD, A11Y_ACT_D_FD,
            A11Y_OUT_FD, A11Y_OUT_FD, A11Y_OUT_FD, A11Y_OUT_FD, A11Y_OUT_FD, A11Y_OUT_FD, A11Y_ACT_TEXT_FD,
            A11Y_OUT2_FD, A11Y_OUT2_FD, A11Y_J_OUT_FD, A11Y_DD_OUT_FD, A11Y_OUT_FD, A11Y_OUT_FD, A11Y_OUT_FD,
            A11Y_OUT_FD, A11Y_II_OUT_FD, A11Y_OUT_FD, A11Y_OUT_FD, A11Y_OUT_FD, A11Y_OUT_FD, A11Y_OUT_FD,
            A11Y_OUT2_FD, A11Y_OUT2_FD, A11Y_OUT_FD, A11Y_OUT2_FD, A11Y_OUT2_FD, A11Y_ACT_FD, A11Y_OUT_FD,
            A11Y_ACT_FD, A11Y_ACT_FD, A11Y_OUT_FD, A11Y_OUT_FD, A11Y_OUT_FD, A11Y_OUT_FD, A11Y_ACT_DD_FD,
            A11Y_ACT_DD_FD, A11Y_ACT_D_FD, A11Y_ACT_II_FD, A11Y_ACT_DD_FD, A11Y_OUT_FD, A11Y_OUT_FD, A11Y_OUT_FD,
            A11Y_OUT_FD, A11Y_OUT_FD, A11Y_OUT_FD, A11Y_ACT_FD, A11Y_DISPOSED_FD);

    /** The slot order of {@link #GWIN_TEXT_RANGE_CALLBACKS_LAYOUT}. */
    static final List<String> TEXT_RANGE_SLOT_NAMES = List.of("clone", "compare", "compare_endpoints",
            "expand_to_enclosing_unit", "find_attribute", "find_text", "get_attribute_value",
            "get_bounding_rectangles", "get_enclosing_element", "get_text", "move", "move_endpoint_by_unit",
            "move_endpoint_by_range", "select", "add_to_selection", "remove_from_selection", "scroll_into_view",
            "get_children", "range_disposed");

    /** The target methods of {@link WinTextRangeCallbacks}, parallel to {@link #TEXT_RANGE_SLOT_NAMES}. */
    private static final List<String> TEXT_RANGE_SLOT_TARGETS = List.of("onRangeClone", "onRangeCompare",
            "onRangeCompareEndpoints", "onRangeExpandToEnclosingUnit", "onRangeFindAttribute", "onRangeFindText",
            "onRangeGetAttributeValue", "onRangeGetBoundingRectangles", "onRangeGetEnclosingElement",
            "onRangeGetText", "onRangeMove", "onRangeMoveEndpointByUnit", "onRangeMoveEndpointByRange",
            "onRangeSelect", "onRangeAddToSelection", "onRangeRemoveFromSelection", "onRangeScrollIntoView",
            "onRangeGetChildren", "onRangeDisposed");

    /** Parallel to {@link #TEXT_RANGE_SLOT_NAMES}; package-private for {@link #ACCESSIBLE_SLOT_DESCRIPTORS}' reason. */
    static final List<FunctionDescriptor> TEXT_RANGE_SLOT_DESCRIPTORS = List.of(A11Y_OUT_FD, A11Y_J_OUT_FD,
            A11Y_IJI_OUT_FD, A11Y_ACT_I_FD, A11Y_IVB_OUT_FD, A11Y_FIND_TEXT_FD, A11Y_I_OUT_FD, A11Y_OUT2_FD,
            A11Y_OUT_FD, A11Y_I_OUT2_FD, A11Y_II_OUT_FD, A11Y_III_OUT_FD, A11Y_ACT_IJI_FD, A11Y_ACT_FD, A11Y_ACT_FD,
            A11Y_ACT_FD, A11Y_ACT_I_FD, A11Y_OUT2_FD, A11Y_DISPOSED_FD);

    private WinAccessibilityCalls() {
    }

    private static boolean accessibilityCallbacksInstalled;

    /**
     * The 70 and the 19 stubs, created once by {@link #installAccessibilityCallbacks} in
     * {@link Arena#global()} and kept so that {@link #reinstallAccessibilityCallbacks} can write the same
     * addresses again. Never replaced: a stub the library has been given must outlive the process,
     * because there is no point at which {@code glass.dll} can promise that no further UI Automation
     * call will arrive, and one can arrive on a thread the JVM has never seen.
     */
    private static MemorySegment[] accessibleCallbackStubs;
    private static MemorySegment[] textRangeCallbackStubs;

    static int sizeOfAccessibleCallbacks() {
        try {
            return (int) GWIN_SIZEOF_ACCESSIBLE_CALLBACKS.invokeExact();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    static int sizeOfTextRangeCallbacks() {
        try {
            return (int) GWIN_SIZEOF_TEXT_RANGE_CALLBACKS.invokeExact();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    static int sizeOfVariant() {
        try {
            return (int) GWIN_SIZEOF_VARIANT.invokeExact();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * <b>Called from the static initializer of {@code WinAccessible} and of
     * {@code WinTextRangeProvider}</b> - where {@code _initIDs} stood, and the only two places a provider
     * can come into existence, so both tables are installed before the first {@code gwin_a11y_create}
     * whichever class initializes first. Both go in together because the library chooses its arm per
     * table: the install is the flip that starts passing ids, and the two arms must never be mixed.
     * Idempotent and synchronized, because these two initializers can run on different threads.
     * <p>
     * The tables are copied by the library, so the confined arena that holds them is closed here.
     */
    static synchronized void installAccessibilityCallbacks() {
        if (accessibilityCallbacksInstalled) {
            return;
        }
        accessibleCallbackStubs = stubsFor(WinAccessibleCallbacks.class, ACCESSIBLE_SLOT_TARGETS,
                ACCESSIBLE_SLOT_DESCRIPTORS);
        textRangeCallbackStubs = stubsFor(WinTextRangeCallbacks.class, TEXT_RANGE_SLOT_TARGETS,
                TEXT_RANGE_SLOT_DESCRIPTORS);
        writeAccessibilityTables();
        accessibilityCallbacksInstalled = true;
    }

    static synchronized boolean accessibilityCallbacksInstalled() {
        return accessibilityCallbacksInstalled;
    }

    /**
     * Writes the production tables again, with the stubs {@link #installAccessibilityCallbacks} created.
     * The one caller is {@code WinGlassNativeShim}, after it has installed a recording table to drive the
     * two fire hooks through the facade's own descriptors: both installers copy by value and the last
     * call wins, so this is the restore. Installs first if nothing has been installed yet; never creates
     * a second set of stubs.
     */
    static synchronized void reinstallAccessibilityCallbacks() {
        if (!accessibilityCallbacksInstalled) {
            installAccessibilityCallbacks();
            return;
        }
        writeAccessibilityTables();
    }

    /** The 89 installed stubs, accessible slots then text-range slots, for the test that reads them back. */
    static synchronized List<MemorySegment> installedAccessibilityCallbackStubs() {
        List<MemorySegment> stubs = new ArrayList<>(ACCESSIBLE_SLOT_NAMES.size() + TEXT_RANGE_SLOT_NAMES.size());
        if (accessibilityCallbacksInstalled) {
            Collections.addAll(stubs, accessibleCallbackStubs);
            Collections.addAll(stubs, textRangeCallbackStubs);
        }
        return Collections.unmodifiableList(stubs);
    }

    private static void writeAccessibilityTables() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment accessibleTable = arena.allocate(GWIN_ACCESSIBLE_CALLBACKS_LAYOUT);
            fillCallbackTable(accessibleTable, GWIN_ACCESSIBLE_CALLBACKS_LAYOUT, ACCESSIBLE_SLOT_NAMES,
                    accessibleCallbackStubs);
            requireOk((int) GWIN_A11Y_SET_CALLBACKS.invokeExact(accessibleTable), "gwin_a11y_set_callbacks");
            MemorySegment textRangeTable = arena.allocate(GWIN_TEXT_RANGE_CALLBACKS_LAYOUT);
            fillCallbackTable(textRangeTable, GWIN_TEXT_RANGE_CALLBACKS_LAYOUT, TEXT_RANGE_SLOT_NAMES,
                    textRangeCallbackStubs);
            requireOk((int) GWIN_A11Y_TEXT_RANGE_SET_CALLBACKS.invokeExact(textRangeTable),
                    "gwin_a11y_text_range_set_callbacks");
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /* -- the downcalls ------------------------------------------------------------------------------ */

    /**
     * {@code gwin_a11y_create(accessible_id)}: {@code new GlassAccessible}, the body of
     * {@code Java_com_sun_glass_ui_win_WinAccessible__1createGlassAccessible} without its
     * {@code jobject}. No OS call and no thread marshal, so it works without a toolkit. 0 only when the
     * allocation failed, which {@code WinAccessible} turns into
     * {@code RuntimeException("could not create platform accessible")}.
     *
     * @return the {@code GlassAccessible*}, which is also its {@code IRawElementProviderSimple*}
     */
    static long createAccessible(long accessibleId) {
        try {
            return ((MemorySegment) GWIN_A11Y_CREATE.invokeExact(accessibleId)).address();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code gwin_a11y_destroy(acc)}: {@code GlassAccessible::Release}, which drops the Java peer's
     * reference and deletes only when the last one goes. {@code acc} must not be 0 - the JNI entry point
     * dereferenced it unconditionally and {@code WinAccessible} guards with {@code if (peer != 0L)};
     * that split stays as it is.
     */
    static void destroyAccessible(long accessible) {
        try {
            GWIN_A11Y_DESTROY.invokeExact(MemorySegment.ofAddress(accessible));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code gwin_a11y_text_range_create(acc, range_id)}: {@code new GlassTextRangeProvider} owned by
     * {@code acc}, which it AddRefs, so a live range pins its accessible. 0 when {@code accessible} is 0
     * or the allocation failed - and {@code WinTextRangeProvider} does not check, as it never did.
     *
     * @return the {@code GlassTextRangeProvider*}, which is also its {@code ITextRangeProvider*}
     */
    static long createTextRange(long accessible, long rangeId) {
        try {
            MemorySegment range = (MemorySegment) GWIN_A11Y_TEXT_RANGE_CREATE.invokeExact(
                    MemorySegment.ofAddress(accessible), rangeId);
            return range.address();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code gwin_a11y_text_range_destroy(range)}: {@code GlassTextRangeProvider::Release}, with
     * {@link #destroyAccessible}'s rules. A {@code range} of 0 - a range that failed to be created,
     * which {@code WinTextRangeProvider.dispose()} passes on unchecked - returns without doing anything;
     * the JNI entry point this replaces dereferenced it.
     */
    static void destroyTextRange(long range) {
        try {
            GWIN_A11Y_TEXT_RANGE_DESTROY.invokeExact(MemorySegment.ofAddress(range));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code gwin_a11y_raise_property_changed(acc, property_id, old_value, new_value)}: the one UI
     * Automation entry point of this section that keeps a C body, because building the two
     * {@code VARIANT}s means {@code oleaut32!SysAllocStringLen}, {@code SafeArrayCreateVector},
     * {@code SafeArrayPutElement} and an {@code IUnknown::AddRef} through a vtable slot - the
     * {@code copyVariant} marshalling the inbound direction needs in C anyway.
     * <p>
     * Both structs and both of their blocks live in a confined arena for the length of the call, which
     * is all the library asks: it reads them and frees nothing. The first variant that cannot be built
     * short-circuits with its own {@code HRESULT} and no OS call is made, which is what the JNI
     * returned.
     *
     * @return the {@code HRESULT}, sign-extended, as the JNI's {@code jlong} was
     */
    static long raiseAutomationPropertyChangedEvent(long provider, int propertyId, WinVariant oldValue,
                                                    WinVariant newValue) {
        try (Arena arena = Arena.ofConfined()) {
            BlockAllocator allocator = byteSize -> arena.allocate(byteSize, JAVA_DOUBLE.byteAlignment());
            // A null WinVariant crosses as a NULL pointer, not as a VT_EMPTY struct: copyVariant(NULL)
            // answered E_FAIL and made no OS call, and a VT_EMPTY struct is a value the OS accepts.
            MemorySegment previous = variantArgument(arena, oldValue, allocator);
            MemorySegment current = variantArgument(arena, newValue, allocator);
            return (long) GWIN_A11Y_RAISE_PROPERTY_CHANGED.invokeExact(MemorySegment.ofAddress(provider),
                    propertyId, previous, current);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code UIAutomationCore!UiaRaiseAutomationEvent(pProvider, id)} - bound straight from the OS,
     * because the JNI body was one {@code reinterpret_cast}, one {@code static_cast} and that call.
     * {@code pProvider} is a {@code GlassAccessible*}, whose first base <em>is</em> the
     * {@code IRawElementProviderSimple*} the function wants; that identity is the library's, recorded in
     * {@code glass_win_api.h}, and this crossing relies on it exactly as the JNI did.
     *
     * @return the {@code HRESULT}, sign-extended, as the JNI's {@code jlong} was
     */
    static long raiseAutomationEvent(long provider, int eventId) {
        try {
            return (int) UiaCore.RAISE_AUTOMATION_EVENT.invokeExact(MemorySegment.ofAddress(provider), eventId);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code UIAutomationCore!UiaClientsAreListening()} - the whole of the former JNI body. */
    static boolean uiaClientsAreListening() {
        try {
            return ((int) UiaCore.CLIENTS_ARE_LISTENING.invokeExact()) != 0;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code UIAutomationCore.dll} and the two functions of it this package needs, bound on first use
     * rather than in this class's initializer.
     * <p>
     * The laziness is behaviour parity, not taste: {@code glass.dll} delay-loads
     * {@code UIAutomationCore} ({@code win.cmake}, {@code /DELAYLOAD:Uiautomationcore.dll}), so a
     * process that never reaches accessibility never loads it, and a machine on which it could not be
     * resolved lost accessibility only. Binding it in {@link WinGlassNative}'s own initializer would
     * turn that into a failed initializer for this whole class - every Glass call would then die with
     * {@code NoClassDefFoundError} for a library it does not use. A holder class keeps both the load
     * point and the blast radius where the JNI had them, exactly as {@code Winmm} does for the timer.
     * <p>
     * The cost is that {@link WinGlassNative#boundSymbols()} grows when accessibility is first used;
     * the tests that assert that list force this holder first.
     */
    static final class UiaCore {

        static final String NAME = "UIAutomationCore";

        static final SymbolLookup LOOKUP = systemLibrary(NAME + ".dll");

        /** {@code HRESULT UiaRaiseAutomationEvent(IRawElementProviderSimple* pProvider, EVENTID id)}. */
        static final MethodHandle RAISE_AUTOMATION_EVENT = bindUiaCore("UiaRaiseAutomationEvent",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));

        /** {@code BOOL UiaClientsAreListening(void)}. */
        static final MethodHandle CLIENTS_ARE_LISTENING = bindUiaCore("UiaClientsAreListening",
                FunctionDescriptor.of(JAVA_INT));

        private UiaCore() {
        }

        private static MethodHandle bindUiaCore(String name, FunctionDescriptor descriptor) {
            return bind(LOOKUP, NAME, name, descriptor);
        }
    }
}
