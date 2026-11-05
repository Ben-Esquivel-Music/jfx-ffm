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

package test.com.sun.glass.ui.mac;

import com.sun.glass.ui.mac.MacGlassNativeShim;
import com.sun.glass.ui.mac.MacGlassNativeShim.Conversion;
import com.sun.glass.ui.mac.MacGlassNativeShim.FrameworkBindings;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.management.ManagementFactory;
import java.lang.reflect.AccessFlag;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.function.Executable;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Binding tests for {@code com.sun.glass.ui.mac.MacGlassNative}, the FFM facade over the {@code gmac_*} C ABI of
 * {@code libglass} ({@code native-glass/mac/glass_mac_api.h}): the symbols against the header, that the package
 * declares no {@code native} method, the ABI guard and constants, {@code gmac_init}, the detached probe, the
 * shim-only hooks for the pool depth and the two UTF-16 conversions, and how a system-library binding of an aggregate
 * fails, which calls nothing native.
 * <p>
 * The library is loaded in this JVM: it has no {@code JNI_OnLoad}, loading it runs none of its code, and
 * {@code gmac_init} creates the thread-data key. Nothing here creates an {@code NSApplication} or touches a window,
 * and the test proves it: AppKit's {@code NSApp} is nil before the first call into the library and after the last.
 * Gated like the Metal tests ({@link MacGlassNatives}).
 * <p>
 * The bindings of the aggregates, which bind after the facade has initialized and on any thread, run in a child JVM
 * ({@link LateBindingChild}): they add to the symbol lists this JVM's tests compare whole.
 */
@EnabledOnOs(OS.MAC)
public class MacGlassNativeTest {

    /**
     * Every function the facade binds, in header order: its own, then the {@code gmac_window_*}, the
     * {@code gmac_view_*} and the {@code gmac_pasteboard_*} exports, {@code gmac_clipboard_file_reference_path}, the
     * {@code gmac_url_*} and {@code gmac_dialog_*} exports, the {@code gmac_menubar_*} and {@code gmac_menu_*}
     * exports and the {@code gmac_a11y_*} exports, which {@code MacWindowCalls}, {@code MacViewCalls},
     * {@code MacPasteboardCalls}, {@code MacClipboardCalls}, {@code MacDialogCalls}, {@code MacMenuCalls} and
     * {@code MacAccessibilityCalls} bind in their initializers and the shim's {@code loadLibrary} initializes next,
     * in that order.
     */
    static final List<String> BOUND_SYMBOLS = List.of(
            "gmac_abi_version", "gmac_abi_constant", "gmac_init", "gmac_free", "gmac_main_thread_detached",
            "gmac_sizeof_app_callbacks", "gmac_sizeof_screen_info", "gmac_sizeof_color", "gmac_sizeof_pref",
            "gmac_sizeof_timer_callbacks", "gmac_sizeof_window_callbacks", "gmac_sizeof_view_caps",
            "gmac_sizeof_view_callbacks", "gmac_sizeof_gesture_callbacks", "gmac_sizeof_pasteboard_rep",
            "gmac_sizeof_pasteboard_item", "gmac_sizeof_file_filter", "gmac_sizeof_dialog_callbacks",
            "gmac_sizeof_menu_image", "gmac_sizeof_menu_callbacks", "gmac_sizeof_variant",
            "gmac_sizeof_accessible_callbacks", "gmac_offsetof_screen_info",
            "gmac_offsetof_color", "gmac_offsetof_pref", "gmac_offsetof_view_caps", "gmac_offsetof_pasteboard_rep",
            "gmac_offsetof_pasteboard_item", "gmac_offsetof_file_filter", "gmac_offsetof_menu_image",
            "gmac_offsetof_variant", "gmac_app_install_callbacks",
            "gmac_timer_install_callbacks", "gmac_window_install_callbacks", "gmac_view_install_callbacks",
            "gmac_gesture_install_callbacks", "gmac_dialog_install_callbacks", "gmac_menu_install_callbacks",
            "gmac_a11y_install_callbacks", "gmac_app_init", "gmac_app_create_delegate", "gmac_app_run_loop",
            "gmac_app_finish_terminating", "gmac_app_enter_nested_loop", "gmac_app_leave_nested_loop",
            "gmac_app_invoke_later", "gmac_app_invoke_and_wait",
            "gmac_app_is_trigger_reactivation", "gmac_app_hide", "gmac_app_hide_other_applications",
            "gmac_app_unhide_all_applications", "gmac_screen_video_refresh_period", "gmac_screens_query",
            "gmac_app_data_directory", "gmac_app_class_name", "gmac_app_collect_preferences",
            "gmac_key_java_to_mac", "gmac_key_code_for_char", "gmac_current_modifier_flags", "gmac_app_open_uri",
            "gmac_image_create", "gmac_cursor_init", "gmac_cursor_create", "gmac_cursor_set", "gmac_cursor_set_custom",
            "gmac_cursor_set_visible", "gmac_cursor_best_size", "gmac_timer_init", "gmac_timer_start_period",
            "gmac_timer_start_vsync", "gmac_timer_stop", "gmac_timer_pause", "gmac_timer_resume",
            "gmac_timer_paused", "gmac_pool_push", "gmac_pool_pop", "gmac_key_find_mac",
            "gmac_mouse_location_flipped", "gmac_window_create", "gmac_window_close",
            "gmac_window_leave_native_full_screen", "gmac_window_set_view", "gmac_window_set_menubar",
            "gmac_window_minimize", "gmac_window_maximize", "gmac_window_set_bounds", "gmac_window_set_visible",
            "gmac_window_set_resizable", "gmac_window_request_focus",
            "gmac_window_set_focusable", "gmac_window_set_title", "gmac_window_set_level", "gmac_window_set_alpha",
            "gmac_window_set_background", "gmac_window_set_enabled", "gmac_window_set_minimum_size",
            "gmac_window_set_maximum_size", "gmac_window_set_icon", "gmac_window_set_dark_frame",
            "gmac_window_to_front", "gmac_window_to_back", "gmac_window_grab_focus", "gmac_window_ungrab_focus",
            "gmac_window_perform_drag", "gmac_window_perform_title_bar_double_click_action",
            "gmac_window_set_button_style", "gmac_window_set_button_appearance", "gmac_view_create",
            "gmac_view_get_native_frame_buffer", "gmac_view_get_x", "gmac_view_get_y", "gmac_view_close",
            "gmac_view_begin", "gmac_view_end", "gmac_view_schedule_repaint", "gmac_view_enter_fullscreen",
            "gmac_view_exit_fullscreen", "gmac_view_upload_pixels_direct", "gmac_view_upload_pixels_bytes",
            "gmac_view_upload_pixels_ints", "gmac_view_enable_input_method_events",
            "gmac_view_finish_input_method_composition", "gmac_view_multi_click_time_ms",
            "gmac_pasteboard_create_system", "gmac_pasteboard_create_user", "gmac_pasteboard_get_name",
            "gmac_pasteboard_get_utis", "gmac_pasteboard_get_item_as_raw_image", "gmac_pasteboard_get_item_string",
            "gmac_pasteboard_get_item_bytes", "gmac_pasteboard_put_items", "gmac_pasteboard_clear",
            "gmac_pasteboard_get_seed", "gmac_pasteboard_release", "gmac_pasteboard_get_allowed_operation",
            "gmac_clipboard_file_reference_path", "gmac_url_start_accessing_security_scoped_resource",
            "gmac_url_stop_accessing_security_scoped_resource", "gmac_url_get_bookmark",
            "gmac_url_create_from_bookmark", "gmac_url_copy_path", "gmac_dialog_open", "gmac_dialog_save",
            "gmac_dialog_folder", "gmac_menubar_create", "gmac_menubar_insert", "gmac_menubar_remove",
            "gmac_menu_create", "gmac_menu_item_create", "gmac_menu_insert", "gmac_menu_remove",
            "gmac_menu_set_title", "gmac_menu_set_shortcut", "gmac_menu_set_image", "gmac_menu_set_enabled",
            "gmac_menu_set_checked", "gmac_a11y_create_peer", "gmac_a11y_invalidate_parent", "gmac_a11y_peer_id",
            "gmac_a11y_id_to_variant", "gmac_a11y_unignored_children");

    /** The functions the facade binds critical, in header order. */
    static final List<String> CRITICAL_SYMBOLS = List.of(
            "gmac_abi_version", "gmac_abi_constant", "gmac_main_thread_detached", "gmac_sizeof_app_callbacks",
            "gmac_sizeof_screen_info", "gmac_sizeof_color", "gmac_sizeof_pref", "gmac_sizeof_timer_callbacks",
            "gmac_sizeof_window_callbacks", "gmac_sizeof_view_caps", "gmac_sizeof_view_callbacks",
            "gmac_sizeof_gesture_callbacks", "gmac_sizeof_pasteboard_rep", "gmac_sizeof_pasteboard_item",
            "gmac_sizeof_file_filter", "gmac_sizeof_dialog_callbacks", "gmac_sizeof_menu_image",
            "gmac_sizeof_menu_callbacks", "gmac_sizeof_variant", "gmac_sizeof_accessible_callbacks",
            "gmac_offsetof_screen_info", "gmac_offsetof_color", "gmac_offsetof_pref",
            "gmac_offsetof_view_caps", "gmac_offsetof_pasteboard_rep", "gmac_offsetof_pasteboard_item",
            "gmac_offsetof_file_filter", "gmac_offsetof_menu_image", "gmac_offsetof_variant", "gmac_timer_paused");

    /** The shim-only test hooks, in header order. */
    static final List<String> TEST_HOOKS = List.of(
            "gmac_test_pool_depth", "gmac_test_set_main_java_attached", "gmac_test_string_or_empty",
            "gmac_test_string_or_nil", "gmac_test_fire_app_callback", "gmac_test_inspect_and_release_app_delegate",
            "gmac_test_inspect_and_release_image", "gmac_test_fire_timer_callback", "gmac_test_sdk_constant",
            "gmac_test_color_space_srgb_name", "gmac_test_fire_window_callback",
            "gmac_test_nslocale_language_direction", "gmac_test_fire_view_callback",
            "gmac_test_fire_gesture_callback", "gmac_test_fire_key_event", "gmac_test_fire_input_method",
            "gmac_test_default_view_caps", "gmac_test_clipboard_mime_to_uti", "gmac_test_clipboard_uti_to_mime",
            "gmac_test_pasteboard_utis_block", "gmac_test_pasteboard_raw_image",
            "gmac_test_pasteboard_raw_image_bounds", "gmac_test_pasteboard_describe_items",
            "gmac_test_fire_dialog_callback", "gmac_test_dialog_describe_filters", "gmac_test_fire_menu_callback",
            "gmac_test_menu_key_equivalent", "gmac_test_fire_accessible_callback", "gmac_test_a11y_variant_to_object",
            "gmac_test_a11y_utf8_string", "gmac_test_a11y_is_equal_to_string", "gmac_test_a11y_enum_pointer");

    /** {@code enum GmacAbiConstant} in index order: the macro each index answers, and its value. */
    private static final List<AbiConstant> ABI_CONSTANTS = List.of(
            new AbiConstant("GMAC_OK", 0),
            new AbiConstant("GMAC_ERR_NO_DATA", -1),
            new AbiConstant("GMAC_ERR_UNSUPPORTED_OS", -2),
            new AbiConstant("GMAC_ERR_NOT_ATTACHED", -3),
            new AbiConstant("GMAC_ERR_DETACHED_OFF_MAIN", -4),
            new AbiConstant("GMAC_UPCALL_OK", 0),
            new AbiConstant("GMAC_UPCALL_THREW", 1),
            new AbiConstant("GMAC_PREF_COLOR", 0),
            new AbiConstant("GMAC_PREF_COLOR_ARRAY", 1),
            new AbiConstant("GMAC_PREF_STRING", 2),
            new AbiConstant("GMAC_PREF_BOOLEAN", 3),
            new AbiConstant("GMAC_MODIFIER_FLAG_CAPS_LOCK", 65536),
            new AbiConstant("GMAC_PASTEBOARD_REP_BAD", -1),
            new AbiConstant("GMAC_PASTEBOARD_REP_NONE", 0),
            new AbiConstant("GMAC_PASTEBOARD_REP_STRING", 1),
            new AbiConstant("GMAC_PASTEBOARD_REP_BYTES", 2),
            new AbiConstant("GMAC_PASTEBOARD_REP_IMAGE", 3),
            new AbiConstant("GMAC_PASTEBOARD_ITEM_NULL", -1));

    private static final Path HEADER = Path.of("src/main/native-glass/mac/glass_mac_api.h");

    /* The production classes only: the shims are compiled into the test classes. */
    private static final Path PACKAGE_CLASSES = Path.of("target/classes/com/sun/glass/ui/mac");

    /* The return type may not cross a '(' or a '"', so the #define of GMAC_EXPORT itself never matches. */
    private static final Pattern PROTOTYPE = Pattern.compile(
            "GMAC_EXPORT\\s+([\\w\\s*]+?)\\b(gmac_\\w+)\\s*\\(([^)]*)\\)\\s*;");

    private static final Map<String, Map<String, String>> CHILD_RESULTS = new ConcurrentHashMap<>();

    private static final Pattern COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    private static final Pattern INT_DEFINE = Pattern.compile(
            "#define\\s+(\\w+)\\s+\\(?\\s*(-?\\d+)\\s*\\)?\\s*$", Pattern.MULTILINE);

    private static final Pattern ABI_CONSTANT_ENUM = Pattern.compile("enum\\s+GmacAbiConstant\\s*\\{([^}]*)}");

    /** A string with an embedded NUL, two BMP ideographs and a surrogate pair: both conversions keep every unit. */
    private static final String UNITS = "a\u0000b日本😀";

    private static final String ABSENT_FRAMEWORK = "NoSuchFramework";
    private static final String ABSENT_FRAMEWORK_INSTALL_NAME =
            "/System/Library/Frameworks/NoSuchFramework.framework/NoSuchFramework";

    /* Open already, because libglass links it: opening it again runs none of its code. */
    private static final String PRESENT_FRAMEWORK = "CoreGraphics";

    /* Absent from every library, so that a handle that wrongly resolved could never call into one. */
    private static final String ABSENT_FUNCTION = "CGNoSuchFunction";
    private static final String ABSENT_DATA_SYMBOL = "kCGNoSuchConstant";

    private record AbiConstant(String macro, int value) {
    }

    @BeforeAll
    static void loadGlass() {
        MacGlassNatives.require();
        assertTrue(MacGlassNativeShim.nsAppIsNil(), "an NSApplication exists before the first call into libglass");
        MacGlassNativeShim.loadLibrary();
    }

    @AfterAll
    static void noApplicationExistsAfterTheLastCall() {
        assertTrue(MacGlassNativeShim.nsAppIsNil(), "an NSApplication exists after the last call into libglass");
    }

    @Test
    public void theTestListsExactlyTheExportsOfTheHeader() throws IOException {
        List<String> exports = new ArrayList<>(BOUND_SYMBOLS);
        exports.addAll(TEST_HOOKS);
        assertEquals(exports, List.copyOf(headerPrototypes().keySet()));
    }

    @Test
    public void theFacadeBindsEveryExportButTheTestHooksInHeaderOrder() {
        assertEquals(BOUND_SYMBOLS, MacGlassNativeShim.boundSymbols());
        assertEquals(List.of(), MacGlassNativeShim.missingSymbols());
    }

    @Test
    public void loadingAgainNeitherFailsNorBindsAgain() {
        MacGlassNativeShim.loadLibrary();
        MacGlassNativeShim.loadLibrary();
        assertEquals(BOUND_SYMBOLS, MacGlassNativeShim.boundSymbols());
    }

    @Test
    public void theShimBindsExactlyTheTestHooks() {
        assertEquals(TEST_HOOKS, List.copyOf(MacGlassNativeShim.testHookCarrierTypes().keySet()));
    }

    @Test
    public void everyDescriptorHasTheCarriersOfItsPrototype() throws IOException {
        Map<String, MethodType> expected = headerPrototypes();
        Map<String, MethodType> bound = new LinkedHashMap<>(MacGlassNativeShim.boundCarrierTypes());
        bound.putAll(MacGlassNativeShim.testHookCarrierTypes());
        assertEquals(expected, bound);
    }

    @Test
    public void everyExportResolvesInTheLoadedLibrary() throws IOException {
        SymbolLookup lookup = SymbolLookup.loaderLookup();
        for (String name : headerPrototypes().keySet()) {
            assertTrue(lookup.find(name).isPresent(), "libglass does not export " + name);
        }
    }

    /**
     * AC 2: every native of {@code com.sun.glass.ui.mac} is a downcall now, so no class of the package, nested and
     * anonymous ones included, declares a {@code native} method. Reads the compiled classes, so none is loaded.
     */
    @Test
    public void noClassOfThePackageDeclaresANativeMethod() throws IOException {
        assertTrue(Files.isRegularFile(PACKAGE_CLASSES.resolve("MacApplication.class")),
                "no compiled package at " + PACKAGE_CLASSES.toAbsolutePath());
        List<String> natives = new ArrayList<>();
        try (DirectoryStream<Path> classFiles = Files.newDirectoryStream(PACKAGE_CLASSES, "*.class")) {
            for (Path classFile : classFiles) {
                ClassModel model = ClassFile.of().parse(classFile);
                for (MethodModel method : model.methods()) {
                    if (method.flags().has(AccessFlag.NATIVE)) {
                        natives.add(model.thisClass().asInternalName() + "." + method.methodName().stringValue()
                                + method.methodType().stringValue());
                    }
                }
            }
        }
        assertEquals(List.of(), natives);
    }

    @Test
    public void onlyTheConstantAndFlagProbesAreCritical() {
        assertEquals(CRITICAL_SYMBOLS, MacGlassNativeShim.criticalSymbols());
    }

    @Test
    public void theLibraryHasTheAbiVersionTheFacadeWasWrittenFor() throws IOException {
        assertEquals(1, MacGlassNativeShim.expectedAbiVersion());
        assertEquals(MacGlassNativeShim.expectedAbiVersion(), headerDefines().get("GLASS_MAC_ABI_VERSION"));
        assertEquals(MacGlassNativeShim.expectedAbiVersion(), MacGlassNativeShim.abiVersion());
    }

    @Test
    public void theAbiConstantTableIsTheEnumOfTheHeader() throws IOException {
        List<String> entries = new ArrayList<>();
        for (AbiConstant constant : ABI_CONSTANTS) {
            entries.add("GMAC_ABI_" + constant.macro().substring("GMAC_".length()));
        }
        entries.add("GMAC_ABI_CONSTANT_COUNT");
        assertEquals(entries, headerEnumEntries());
    }

    @Test
    public void everyAbiConstantIsTheValueOfItsMacro() throws IOException {
        Map<String, Integer> defines = headerDefines();
        for (int index = 0; index < ABI_CONSTANTS.size(); index++) {
            AbiConstant constant = ABI_CONSTANTS.get(index);
            assertEquals(constant.value(), defines.get(constant.macro()), "the header's " + constant.macro());
            assertEquals(constant.value(), MacGlassNativeShim.abiConstant(index), "gmac_abi_constant(" + index + ")");
        }
    }

    @Test
    public void everyConstantTheJavaSideSpellsOutIsItsAbiConstant() {
        Map<String, Integer> java = MacGlassNativeShim.javaConstants();
        List<String> macros = new ArrayList<>();
        for (int index = 0; index < ABI_CONSTANTS.size(); index++) {
            String macro = ABI_CONSTANTS.get(index).macro();
            macros.add(macro);
            if (java.containsKey(macro)) {
                assertEquals(MacGlassNativeShim.abiConstant(index), java.get(macro), macro);
            }
        }
        assertTrue(macros.containsAll(java.keySet()), "a Java constant without an ABI constant: " + java.keySet());
    }

    @Test
    public void indicesOutsideTheConstantTableAnswerInt32Min() {
        for (int index : new int[] {Integer.MIN_VALUE, -1, ABI_CONSTANTS.size(), Integer.MAX_VALUE}) {
            assertEquals(Integer.MIN_VALUE, MacGlassNativeShim.abiConstant(index), "index " + index);
        }
    }

    @Test
    public void gmacInitAnswersOkAgainAndKeepsTheThreadDataKey() throws Exception {
        int[] depthStatusDepth = onFreshThread(() -> {
            MacGlassNativeShim.stringOrEmpty("pool", 4);
            int before = MacGlassNativeShim.poolDepth();
            int status = MacGlassNativeShim.init();
            return new int[] {before, status, MacGlassNativeShim.poolDepth()};
        });
        // A second pthread_key_create would hand out a new key, under which this thread has no data: -1.
        assertArrayEquals(new int[] {0, 0, 0}, depthStatusDepth);
    }

    @Test
    public void freeIgnoresNull() {
        MacGlassNativeShim.free(MemorySegment.NULL);
    }

    @Test
    public void theDetachedProbeFollowsTheAttachedFlagOffTheMainThread() {
        assertFalse(MacGlassNativeShim.isMainThread(), "the java launcher runs no Java thread on the main thread");
        int previous = MacGlassNativeShim.setMainJavaAttached(0);
        try {
            assertEquals(0, previous, "nothing in this JVM ran the run loop, which attaches the main thread");
            assertTrue(MacGlassNativeShim.mainThreadDetached());
            assertEquals(0, MacGlassNativeShim.setMainJavaAttached(1));
            assertFalse(MacGlassNativeShim.mainThreadDetached());
        } finally {
            MacGlassNativeShim.setMainJavaAttached(previous);
        }
    }

    @Test
    public void aThreadHasNoPoolDepthBeforeItsFirstPoolAndZeroAfterIt() throws Exception {
        int[] depths = onFreshThread(() -> {
            int before = MacGlassNativeShim.poolDepth();
            MacGlassNativeShim.stringOrNil("pool", 4);
            return new int[] {before, MacGlassNativeShim.poolDepth()};
        });
        assertArrayEquals(new int[] {-1, 0}, depths);
    }

    @Test
    public void orEmptyAnswersEmptyForNullAndForEmpty() {
        assertEquals(new Conversion(0, ""), MacGlassNativeShim.stringOrEmpty(null, 8));
        assertEquals(new Conversion(0, ""), MacGlassNativeShim.stringOrEmpty("", 8));
    }

    @Test
    public void orNilAnswersNilForNullOnly() {
        assertEquals(new Conversion(-1, ""), MacGlassNativeShim.stringOrNil(null, 8));
        assertEquals(new Conversion(0, ""), MacGlassNativeShim.stringOrNil("", 8));
    }

    @Test
    public void bothConversionsKeepEveryCodeUnit() {
        Conversion expected = new Conversion(UNITS.length(), UNITS);
        assertEquals(expected, MacGlassNativeShim.stringOrEmpty(UNITS, 16));
        assertEquals(expected, MacGlassNativeShim.stringOrNil(UNITS, 16));
    }

    @Test
    public void aShortBufferGetsAPrefixAndTheWholeLength() {
        assertEquals(new Conversion(4, "ab"), MacGlassNativeShim.stringOrEmpty("abcd", 2));
        assertEquals(new Conversion(4, "ab"), MacGlassNativeShim.stringOrNil("abcd", 2));
    }

    @Test
    public void aggregatesBindingAtOnceLoseNoBinding() throws Exception {
        Map<String, String> child = runChild(LateBindingChild.class);
        assertEquals("", child.get("problems"), "a binding or one of its downcalls in the child");
        List<String> late = new ArrayList<>(LateBindingChild.absentSymbols());
        late.add(LateBindingChild.PRESENT_SYMBOL);
        assertFacadeFirstThenExactly(BOUND_SYMBOLS, late, list(child.get("bound")), "boundSymbols()");
        assertFacadeFirstThenExactly(List.of(), LateBindingChild.absentSymbols(), list(child.get("missing")),
                "missingSymbols()");
        assertFacadeFirstThenExactly(CRITICAL_SYMBOLS, LateBindingChild.absentCriticalSymbols(),
                list(child.get("critical")), "criticalSymbols()");
    }

    @Test
    public void aMissingSymbolOfAnAggregateFailsOnlyItsOwnDowncall() throws Exception {
        Map<String, String> child = runChild(LateBindingChild.class);
        assertEquals("", child.get("problems"), "every missing symbol's downcall throws an error naming it");
        assertEquals("loaded", child.get("facadeAfter"), "ensureLoaded() after the missing symbols");
        assertEquals("1", child.get("abiVersion"), "the facade's own downcall after the missing symbols");
        assertEquals(child.get("hookPoolDepth"), child.get("latePoolDepth"),
                "a symbol the library has, bound after the missing ones");
    }

    @Test
    public void aFrameworkThatDoesNotOpenFailsEachDowncallAndReadWithOneErrorNamingItsInstallName() {
        FrameworkBindings bindings = MacGlassNativeShim.bindFramework(ABSENT_FRAMEWORK, ABSENT_FUNCTION,
                ABSENT_DATA_SYMBOL);
        UnsatisfiedLinkError error = assertEachCallFailsWithOneErrorNaming(ABSENT_FRAMEWORK_INSTALL_NAME,
                () -> callFunction(bindings));
        assertSame(error, assertEachCallFailsWithOneErrorNaming(ABSENT_FRAMEWORK_INSTALL_NAME,
                () -> readDataValue(bindings)), "the read fails with the downcall's error");
    }

    @Test
    public void aFunctionAFrameworkLacksFailsEachOfItsDowncallsWithOneErrorNamingIt() {
        FrameworkBindings bindings = MacGlassNativeShim.bindFramework(PRESENT_FRAMEWORK, ABSENT_FUNCTION,
                ABSENT_DATA_SYMBOL);
        UnsatisfiedLinkError error = assertEachCallFailsWithOneErrorNaming(ABSENT_FUNCTION,
                () -> callFunction(bindings));
        assertTrue(error.getMessage().contains(PRESENT_FRAMEWORK), error.getMessage());
    }

    @Test
    public void aDataSymbolAFrameworkLacksFailsEachOfItsReadsWithOneErrorNamingIt() {
        FrameworkBindings bindings = MacGlassNativeShim.bindFramework(PRESENT_FRAMEWORK, ABSENT_FUNCTION,
                ABSENT_DATA_SYMBOL);
        UnsatisfiedLinkError error = assertEachCallFailsWithOneErrorNaming(ABSENT_DATA_SYMBOL,
                () -> readDataValue(bindings));
        assertTrue(error.getMessage().contains(PRESENT_FRAMEWORK), error.getMessage());
    }

    @Test
    public void systemLibraryBindingsStayOutOfLibglasssSymbolLists() {
        List<String> bound = MacGlassNativeShim.boundSymbols();
        List<String> missing = MacGlassNativeShim.missingSymbols();
        List<String> critical = MacGlassNativeShim.criticalSymbols();
        MacGlassNativeShim.bindFramework(PRESENT_FRAMEWORK, ABSENT_FUNCTION, ABSENT_DATA_SYMBOL);
        MacGlassNativeShim.bindFramework(ABSENT_FRAMEWORK, ABSENT_FUNCTION, ABSENT_DATA_SYMBOL);
        assertEquals(bound, MacGlassNativeShim.boundSymbols());
        assertEquals(missing, MacGlassNativeShim.missingSymbols());
        assertEquals(critical, MacGlassNativeShim.criticalSymbols());
    }

    /**
     * Binds as the aggregates will, after the facade has initialized: {@link #THREADS} threads at once, each binding
     * symbols the library lacks, half of them critical, and calling each downcall; then one symbol the library has.
     * Writes what it saw to the file {@code args[0]}, and nothing else if an {@code NSApplication} exists before its
     * first call into the library.
     */
    public static final class LateBindingChild {

        static final int THREADS = 16;
        static final int SYMBOLS_PER_THREAD = 32;
        static final String PRESENT_SYMBOL = "gmac_test_pool_depth";

        private static final FunctionDescriptor INT_RESULT = FunctionDescriptor.of(JAVA_INT);

        public static void main(String[] args) throws Throwable {
            Path result = Path.of(args[0]);
            if (!MacGlassNativeShim.nsAppIsNil()) {
                Files.writeString(result, "nsAppNilBefore=false\n", StandardCharsets.UTF_8);
                return;
            }
            MacGlassNativeShim.loadLibrary();
            Queue<String> problems = new ConcurrentLinkedQueue<>();
            CountDownLatch start = new CountDownLatch(1);
            List<Thread> threads = new ArrayList<>();
            for (int thread = 0; thread < THREADS; thread++) {
                int index = thread;
                threads.add(Thread.ofPlatform().name("LateBindingChild-" + thread)
                        .start(() -> bindAbsentSymbols(index, start, problems)));
            }
            start.countDown();
            for (Thread thread : threads) {
                thread.join();
            }
            MethodHandle present = MacGlassNativeShim.bind(PRESENT_SYMBOL, INT_RESULT);
            int latePoolDepth = (int) present.invokeExact();
            int hookPoolDepth = MacGlassNativeShim.poolDepth();
            String facadeAfter = facadeState();
            int abiVersion = MacGlassNativeShim.abiVersion();
            boolean nsAppNilAfter = MacGlassNativeShim.nsAppIsNil();
            Files.writeString(result, "problems=" + String.join(" | ", problems).replace('\n', ' ')
                    + "\nbound=" + String.join(",", MacGlassNativeShim.boundSymbols())
                    + "\nmissing=" + String.join(",", MacGlassNativeShim.missingSymbols())
                    + "\ncritical=" + String.join(",", MacGlassNativeShim.criticalSymbols())
                    + "\nfacadeAfter=" + facadeAfter
                    + "\nabiVersion=" + abiVersion
                    + "\nlatePoolDepth=" + latePoolDepth
                    + "\nhookPoolDepth=" + hookPoolDepth
                    + "\nnsAppNilBefore=true"
                    + "\nnsAppNilAfter=" + nsAppNilAfter + "\n", StandardCharsets.UTF_8);
        }

        static List<String> absentSymbols() {
            List<String> names = new ArrayList<>();
            for (int thread = 0; thread < THREADS; thread++) {
                for (int index = 0; index < SYMBOLS_PER_THREAD; index++) {
                    names.add(absentSymbol(thread, index));
                }
            }
            return names;
        }

        static List<String> absentCriticalSymbols() {
            List<String> names = new ArrayList<>();
            for (int thread = 0; thread < THREADS; thread++) {
                for (int index = 0; index < SYMBOLS_PER_THREAD; index++) {
                    if (isCritical(index)) {
                        names.add(absentSymbol(thread, index));
                    }
                }
            }
            return names;
        }

        /** Of a fixed width, so that no name is a prefix of another and an error names exactly one. */
        private static String absentSymbol(int thread, int index) {
            return "gmac_absent_%02d_%02d".formatted(thread, index);
        }

        private static boolean isCritical(int index) {
            return index % 2 == 1;
        }

        private static void bindAbsentSymbols(int thread, CountDownLatch start, Queue<String> problems) {
            try {
                start.await();
                for (int index = 0; index < SYMBOLS_PER_THREAD; index++) {
                    String name = absentSymbol(thread, index);
                    MethodHandle downcall = isCritical(index)
                            ? MacGlassNativeShim.bindCritical(name, INT_RESULT)
                            : MacGlassNativeShim.bind(name, INT_RESULT);
                    checkFailsNaming(name, downcall, problems);
                }
            } catch (Throwable t) {
                problems.add(Thread.currentThread().getName() + " threw " + t);
            }
        }

        private static void checkFailsNaming(String name, MethodHandle downcall, Queue<String> problems) {
            try {
                int answer = (int) downcall.invokeExact();
                problems.add(name + " answered " + answer);
            } catch (UnsatisfiedLinkError e) {
                if (!String.valueOf(e.getMessage()).contains(name)) {
                    problems.add(name + " threw an error naming another symbol: " + e.getMessage());
                }
            } catch (Throwable t) {
                problems.add(name + " threw " + t);
            }
        }

        private static String facadeState() {
            try {
                MacGlassNativeShim.loadLibrary();
                return "loaded";
            } catch (UnsatisfiedLinkError e) {
                return "failed: " + e.getMessage();
            }
        }
    }

    /** {@code actual} is {@code facade} in order, then every entry of {@code late} once, in any order. */
    private static void assertFacadeFirstThenExactly(List<String> facade, List<String> late, List<String> actual,
                                                     String what) {
        assertTrue(actual.size() >= facade.size(), what + " lost the facade's own entries: " + actual);
        assertEquals(facade, actual.subList(0, facade.size()), what + ": the facade's own, in header order");
        List<String> rest = actual.subList(facade.size(), actual.size());
        assertEquals(late.size(), rest.size(), what + ": one entry per late binding");
        assertEquals(new HashSet<>(late), new HashSet<>(rest), what + ": the late bindings");
    }

    private static List<String> list(String joined) {
        return joined == null || joined.isEmpty() ? List.of() : List.of(joined.split(","));
    }

    /** Runs {@code call} twice: each run throws the same {@link UnsatisfiedLinkError}, which names {@code name}. */
    private static UnsatisfiedLinkError assertEachCallFailsWithOneErrorNaming(String name, Executable call) {
        UnsatisfiedLinkError first = assertThrows(UnsatisfiedLinkError.class, call);
        assertTrue(String.valueOf(first.getMessage()).contains(name), first.getMessage());
        assertSame(first, assertThrows(UnsatisfiedLinkError.class, call), "the error of a second call");
        return first;
    }

    private static void callFunction(FrameworkBindings bindings) throws Throwable {
        int answer = (int) bindings.function().invokeExact();
        fail("the downcall answered " + answer);
    }

    private static void readDataValue(FrameworkBindings bindings) throws Throwable {
        MemorySegment value = (MemorySegment) bindings.dataValue().invokeExact();
        fail("the read answered " + value);
    }

    /**
     * Runs {@code main} in a new JVM with this JVM's options, once: the tests share what it wrote. A fatal error log
     * goes under {@code target/}. Fails unless AppKit's {@code NSApp} was nil in the child before its first call into
     * the library and after its last.
     */
    static Map<String, String> runChild(Class<?> main) throws IOException, InterruptedException {
        return runChild(main, main.getSimpleName(), List.of());
    }

    /**
     * {@link #runChild(Class)} with {@code arguments} after the result file, once per {@code name}, which also names
     * the directory under {@code target/} where it runs.
     */
    static Map<String, String> runChild(Class<?> main, String name, List<String> arguments)
            throws IOException, InterruptedException {
        Map<String, String> cached = CHILD_RESULTS.get(name);
        if (cached != null) {
            return cached;
        }
        Path work = childDirectory(name);
        Path errors = work.resolve("hs_err");
        Path output = work.resolve("result.txt");
        Path log = work.resolve("child.log");
        if (Files.exists(errors)) {
            try (Stream<Path> stale = Files.list(errors)) {
                for (Path file : stale.toList()) {
                    Files.delete(file);
                }
            }
        }
        Files.createDirectories(errors);
        Files.deleteIfExists(output);
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        for (String argument : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (!argument.startsWith("-agentlib") && !argument.startsWith("-javaagent")
                    && !argument.startsWith("-XX:ErrorFile")) {
                command.add(argument);
            }
        }
        command.add("-XX:ErrorFile=" + errors.resolve("hs_err_pid%p.log"));
        command.addAll(List.of("-cp", System.getProperty("java.class.path"), main.getName(), output.toString()));
        command.addAll(arguments);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                fail("child JVM " + name + " did not finish; log " + log);
            }
        } finally {
            process.destroyForcibly();
        }
        String[] hsErr;
        try (Stream<Path> files = Files.list(errors)) {
            hsErr = files.map(Path::toString).toArray(String[]::new);
        }
        if (process.exitValue() != 0 || hsErr.length > 0 || !Files.isRegularFile(output)) {
            fail("child JVM " + name + " exited with " + process.exitValue() + ", fatal error logs "
                    + Arrays.toString(hsErr) + "; log:\n" + Files.readString(log, StandardCharsets.UTF_8));
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (String line : Files.readAllLines(output, StandardCharsets.UTF_8)) {
            int equals = line.indexOf('=');
            result.put(line.substring(0, equals), line.substring(equals + 1));
        }
        assertEquals("true", result.get("nsAppNilBefore"), "an NSApplication existed before the child's first call");
        assertEquals("true", result.get("nsAppNilAfter"), "an NSApplication existed after the child's last call");
        CHILD_RESULTS.put(name, result);
        return result;
    }

    /** What the child JVM {@code main} wrote to its stdout and stderr. */
    static Path childLog(Class<?> main) {
        return childDirectory(main.getSimpleName()).resolve("child.log");
    }

    private static Path childDirectory(String name) {
        return Path.of("").toAbsolutePath().resolve("target/mac-glass-native").resolve(name);
    }

    /**
     * Runs {@code task} on a new platform thread: one that has never opened a pool. A virtual thread would read the
     * thread-specific data of whichever carrier it runs on.
     */
    private static int[] onFreshThread(Callable<int[]> task) throws Exception {
        FutureTask<int[]> future = new FutureTask<>(task);
        Thread.ofPlatform().name("MacGlassNativeTest-fresh").start(future);
        return future.get(30, TimeUnit.SECONDS);
    }

    private static String header() throws IOException {
        assertTrue(Files.isRegularFile(HEADER), "run from modules/javafx.graphics: no " + HEADER.toAbsolutePath());
        return COMMENT.matcher(Files.readString(HEADER)).replaceAll(" ");
    }

    /** Every {@code GMAC_EXPORT} prototype of the header, as the Java carrier types it must be bound with. */
    private static Map<String, MethodType> headerPrototypes() throws IOException {
        Map<String, MethodType> prototypes = new LinkedHashMap<>();
        Matcher prototype = PROTOTYPE.matcher(header());
        while (prototype.find()) {
            List<Class<?>> parameters = new ArrayList<>();
            String list = prototype.group(3).trim();
            if (!list.equals("void")) {
                for (String parameter : list.split(",")) {
                    parameters.add(carrier(parameter.trim().replaceFirst("\\w+$", "")));
                }
            }
            prototypes.put(prototype.group(2), MethodType.methodType(carrier(prototype.group(1)), parameters));
        }
        return prototypes;
    }

    /** Every {@code #define} of the header whose value is an integer literal, parenthesized or not. */
    private static Map<String, Integer> headerDefines() throws IOException {
        Map<String, Integer> defines = new LinkedHashMap<>();
        Matcher define = INT_DEFINE.matcher(header());
        while (define.find()) {
            defines.put(define.group(1), Integer.valueOf(define.group(2)));
        }
        return defines;
    }

    private static List<String> headerEnumEntries() throws IOException {
        Matcher body = ABI_CONSTANT_ENUM.matcher(header());
        assertTrue(body.find(), "no enum GmacAbiConstant in " + HEADER);
        List<String> entries = new ArrayList<>();
        for (String entry : body.group(1).split(",")) {
            entries.add(entry.replaceFirst("=.*", "").trim());
        }
        return entries;
    }

    /** Any pointer is an {@code ADDRESS}. */
    private static Class<?> carrier(String cType) {
        String type = cType.replace("const", "").trim();
        if (type.endsWith("*")) {
            return MemorySegment.class;
        }
        return switch (type) {
            case "void" -> void.class;
            case "int32_t" -> int.class;
            case "int64_t", "uint64_t" -> long.class;
            case "uint16_t" -> char.class;
            case "float" -> float.class;
            case "double" -> double.class;
            default -> throw new AssertionError("no carrier for the C type " + cType);
        };
    }
}
