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

package test.com.sun.glass.ui.win;

import com.sun.glass.ui.win.WinGlassNativeShim;
import com.sun.glass.ui.win.WinGlassNativeShim.AnsweredPreferencesCall;
import com.sun.glass.ui.win.WinGlassNativeShim.ComApartment;
import com.sun.glass.ui.win.WinGlassNativeShim.QueryResult;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The preference arms of the Glass toolkit window and the two WinRT sinks, as the C++ of {@code PlatformSupport.cpp}
 * and {@code RoActivationSupport.cpp} at commit {@code 5a709c189d} ({@code GLASS_WIN_ABI_VERSION} 7) dispatches
 * them: which {@code GwinPrefsCallbacks.preferences_changed(user, types)} calls each message produces, with which
 * {@code types} and {@code user}, on which thread, and what the send answers. Every expectation below is what that
 * C produced; nothing here depends on the machine's theme or network.
 * <p>
 * Each scenario runs in a {@link WinGlassChild} JVM with one toolkit. A recording table is installed before the
 * toolkit is created ({@code gwin_prefs_set_callbacks}' rule), with a sentinel {@code user} pointer that nothing
 * dereferences. The messages are sent with {@code SendMessageW} by the toolkit thread to its own window, which calls
 * {@code GlassApplication::WindowProc} directly: no other message - an OS broadcast among them - is handled between
 * noting the number of recorded calls and reading the calls that one send produced. Every message is sent twice,
 * once with the table answering 0 (no change: the arm goes on to {@code DefWindowProc}) and once answering 1 (a
 * change: the arm returns 0). {@code DefWindowProc} answers 0 to each of these messages, so the send answers 0
 * either way; which of the two returned it cannot be observed from here, and is not pinned.
 * <p>
 * Pinned besides: both sinks deliver their own type ({@code PT_UI_SETTINGS} 4 for {@code AutoHideScrollBarsChanged},
 * {@code PT_NETWORK_INFORMATION} 8 for {@code NetworkStatusChanged}) on the toolkit thread, never inside the sink,
 * whether a second thread or the toolkit thread invokes them; the COM apartment of the toolkit thread from
 * {@code gwin_app_create} until {@code gwin_run_loop} has returned ({@code RoInitialize(RO_INIT_SINGLETHREADED)}
 * before the loop's {@code OleInitialize}, both undone by then); the six teardown events in their order; what the
 * two test hooks answer with no delegate or an unknown one; and the two queries' answers to {@code NULL}, without a
 * toolkit and on the toolkit thread. A cleared table and a table whose slot is {@code NULL} receive no call.
 * <p>
 * {@code WinPreferenceActivationFailureTest} pins the same arms in a toolkit whose WinRT activation failed, and
 * {@code WinPreferenceQueryGoldenTest} the queries' values on one machine.
 */
@EnabledOnOs(OS.WINDOWS)
public class WinPreferenceDispatchTraceTest {

    private static final long WAIT_MILLIS = 10_000;

    /** The table's {@code user} pointer: passed back unchanged, never dereferenced. */
    static final long USER = 0x5EED_0000_7A11L;

    /** What the recording table answers in each sweep: no change, then a change. */
    static final List<Integer> ANSWERS = List.of(0, 1);

    static final int WM_SYSCOLORCHANGE = 0x0015;
    static final int WM_SETTINGCHANGE = 0x001A;
    static final int WM_THEMECHANGED = 0x031A;
    static final int WM_DWMCOLORIZATIONCOLORCHANGED = 0x0320;

    /** {@code WM_PREFERENCES_CHANGED} of {@code GlassApplication.h}: what the sinks post, {@code WM_USER + 4}. */
    static final int WM_PREFERENCES_CHANGED = WinGlassNativeShim.WM_USER + 4;

    static final long SPI_GETHIGHCONTRAST = 0x0042;
    static final long SPI_SETHIGHCONTRAST = 0x0043;
    static final long SPI_SETCLIENTAREAANIMATION = 0x1043;

    /** {@code GWIN_ERR_INVALID_ARG} of {@code glass_win_api.h}. */
    static final int INVALID_ARG = -1;

    /** {@code GWIN_PT_UI_SETTINGS} and {@code GWIN_PT_NETWORK_INFORMATION}: the types the two sinks post. */
    static final int UI_SETTINGS = 4;
    static final int NETWORK_INFORMATION = 8;

    /** One message sent to the toolkit window, and the {@code types} of the calls the C made for it, in order. */
    record Case(String label, int message, long wParam, long lParam, String text, List<Integer> types) {

        static Case send(String label, int message, long wParam, long lParam, Integer... types) {
            return new Case(label, message, wParam, lParam, null, List.of(types));
        }

        /** {@code lParam} points at {@code text}, NUL-terminated UTF-16. */
        static Case sendText(String label, int message, long wParam, String text, Integer... types) {
            return new Case(label, message, wParam, 0, text, List.of(types));
        }
    }

    /** Every message, in the order it is sent. */
    static final List<Case> CASES = List.of(
            Case.send("WM_SETTINGCHANGE(SPI_SETHIGHCONTRAST, NULL)",
                    WM_SETTINGCHANGE, SPI_SETHIGHCONTRAST, 0, 6),
            Case.send("WM_SETTINGCHANGE(SPI_SETCLIENTAREAANIMATION, NULL)",
                    WM_SETTINGCHANGE, SPI_SETCLIENTAREAANIMATION, 0, 2),
            Case.sendText("WM_SETTINGCHANGE(0, \"ImmersiveColorSet\")",
                    WM_SETTINGCHANGE, 0, "ImmersiveColorSet", 4),
            Case.sendText("WM_SETTINGCHANGE(SPI_SETHIGHCONTRAST, \"ImmersiveColorSet\"): the wParam wins",
                    WM_SETTINGCHANGE, SPI_SETHIGHCONTRAST, "ImmersiveColorSet", 6),
            Case.sendText("WM_SETTINGCHANGE(SPI_SETCLIENTAREAANIMATION, \"ImmersiveColorSet\"): the wParam wins",
                    WM_SETTINGCHANGE, SPI_SETCLIENTAREAANIMATION, "ImmersiveColorSet", 2),
            Case.sendText("WM_SETTINGCHANGE(SPI_GETHIGHCONTRAST, \"ImmersiveColorSet\"): another wParam",
                    WM_SETTINGCHANGE, SPI_GETHIGHCONTRAST, "ImmersiveColorSet", 4),
            Case.send("WM_SETTINGCHANGE(0x1_0000_0043, NULL): the wParam is read as a UINT",
                    WM_SETTINGCHANGE, 0x1_0000_0000L | SPI_SETHIGHCONTRAST, 0, 6),
            Case.send("WM_SETTINGCHANGE(0, NULL)",
                    WM_SETTINGCHANGE, 0, 0),
            Case.send("WM_SETTINGCHANGE(SPI_GETHIGHCONTRAST, NULL)",
                    WM_SETTINGCHANGE, SPI_GETHIGHCONTRAST, 0),
            Case.sendText("WM_SETTINGCHANGE(0, \"immersivecolorset\"): the comparison is case-sensitive",
                    WM_SETTINGCHANGE, 0, "immersivecolorset"),
            Case.sendText("WM_SETTINGCHANGE(0, \"ImmersiveColorSe\"): a prefix",
                    WM_SETTINGCHANGE, 0, "ImmersiveColorSe"),
            Case.sendText("WM_SETTINGCHANGE(0, \"ImmersiveColorSetX\"): longer",
                    WM_SETTINGCHANGE, 0, "ImmersiveColorSetX"),
            Case.sendText("WM_SETTINGCHANGE(0, \"\")",
                    WM_SETTINGCHANGE, 0, ""),
            Case.sendText("WM_SETTINGCHANGE(0, \"Policy\")",
                    WM_SETTINGCHANGE, 0, "Policy"),
            Case.send("WM_THEMECHANGED(0, 0)",
                    WM_THEMECHANGED, 0, 0, 5),
            Case.send("WM_SYSCOLORCHANGE(0, 0)",
                    WM_SYSCOLORCHANGE, 0, 0, 5),
            Case.send("WM_DWMCOLORIZATIONCOLORCHANGED(0xC40078D7, 1): the arguments are ignored",
                    WM_DWMCOLORIZATIONCOLORCHANGED, 0xC400_78D7L, 1, 5),
            Case.send("WM_PREFERENCES_CHANGED(1, 0)", WM_PREFERENCES_CHANGED, 1, 0, 1),
            Case.send("WM_PREFERENCES_CHANGED(2, 0)", WM_PREFERENCES_CHANGED, 2, 0, 2),
            Case.send("WM_PREFERENCES_CHANGED(4, 0)", WM_PREFERENCES_CHANGED, 4, 0, 4),
            Case.send("WM_PREFERENCES_CHANGED(8, 0)", WM_PREFERENCES_CHANGED, 8, 0, 8),
            Case.send("WM_PREFERENCES_CHANGED(5, 0)", WM_PREFERENCES_CHANGED, 5, 0, 5),
            Case.send("WM_PREFERENCES_CHANGED(12, 0)", WM_PREFERENCES_CHANGED, 12, 0, 12),
            Case.send("WM_PREFERENCES_CHANGED(15, 0)", WM_PREFERENCES_CHANGED, 15, 0, 15),
            Case.send("WM_PREFERENCES_CHANGED(0, 0): the wake, with nothing kept",
                    WM_PREFERENCES_CHANGED, 0, 0),
            Case.send("WM_PREFERENCES_CHANGED(0x10, 0): masked to the wake",
                    WM_PREFERENCES_CHANGED, 0x10, 0),
            Case.send("WM_PREFERENCES_CHANGED(0x1F, 0): masked to 15",
                    WM_PREFERENCES_CHANGED, 0x1F, 0, 15),
            Case.send("WM_PREFERENCES_CHANGED(0xFFFFFFF4, 0): masked to 4",
                    WM_PREFERENCES_CHANGED, 0xFFFF_FFF4L, 0, 4),
            Case.send("WM_PREFERENCES_CHANGED(0x1_0000_0008, 0): masked to 8",
                    WM_PREFERENCES_CHANGED, 0x1_0000_0008L, 0, 8),
            Case.send("WM_PREFERENCES_CHANGED(-1, 0): masked to 15",
                    WM_PREFERENCES_CHANGED, -1L, 0, 15),
            Case.send("WM_PREFERENCES_CHANGED(2, 0x7777): the lParam is ignored",
                    WM_PREFERENCES_CHANGED, 2, 0x7777, 2));

    private static WinGlassChildJvm.Run traced;

    @BeforeAll
    static void requireNatives() {
        WinGlassNatives.require();
    }

    /** The child of {@link #traceDispatch}, launched by the first test that needs it. */
    private static synchronized WinGlassChildJvm.Run traced() {
        if (traced == null) {
            traced = WinGlassChildJvm.run(WinPreferenceDispatchTraceTest.class, "traceDispatch");
            System.out.println("preference dispatch trace: " + traced.values() + " (" + traced.loadedGlass() + ")");
        }
        return traced;
    }

    @Test
    @Timeout(120)
    public void everyPreferenceMessageCallsPreferencesChangedWithItsTypesOnTheToolkitThread() {
        WinGlassChildJvm.Run run = traced();
        for (int answer : ANSWERS) {
            assertTrace(run, "trace.answer" + answer, "the table answering " + answer);
        }
    }

    @Test
    @Timeout(120)
    public void bothSinksDeliverTheirOwnTypeOnTheToolkitThreadAndNeverInsideTheSink() {
        WinGlassChildJvm.Run run = traced();
        assertSinkDelivered(run, "sink.otherThread.autoHide", UI_SETTINGS);
        assertSinkDelivered(run, "sink.otherThread.network", NETWORK_INFORMATION);
        assertSinkDelivered(run, "sink.toolkitThread.autoHide", UI_SETTINGS);
        assertSinkDelivered(run, "sink.toolkitThread.network", NETWORK_INFORMATION);
        assertEquals("0", run.value("sink.toolkitThread.autoHide.inline"),
                "preferences_changed ran inside the auto-hide sink: " + run.describe());
        assertEquals("0", run.value("sink.toolkitThread.network.inline"),
                "preferences_changed ran inside the network sink: " + run.describe());
    }

    /**
     * {@code RoInitialize(RO_INIT_SINGLETHREADED)} in {@code gwin_app_create} makes the toolkit thread the first
     * single-threaded apartment of the process; {@code gwin_run_loop}'s {@code OleInitialize} joins it. The
     * {@code WM_NCDESTROY} of {@code DestroyWindow} destroys the preferences ({@code glass_prefs_destroy}, which was
     * {@code ~PlatformSupport} at {@code 5a709c189d}) and their {@code RoUninitialize}, and the thread stays in the
     * apartment, which {@code OleInitialize} still holds, until the loop returns and its
     * {@code OleUninitialize} ends it. Before creation and after the loop the thread is in no apartment of its own;
     * {@code CoGetApartmentType} then answers {@code CO_E_NOTINITIALIZED}, or the implicit multithreaded apartment
     * when some thread of the process keeps one, which is the process's business, not the toolkit's: after the loop
     * of this child it does.
     */
    @Test
    @Timeout(120)
    public void theToolkitThreadIsASingleThreadedApartmentFromCreationUntilTheLoopHasReturned() {
        WinGlassChildJvm.Run run = traced();
        assertInNoApartmentOfItsOwn(run, "apartment.beforeCreate");
        assertEquals(MAIN_STA, run.value("apartment.afterCreate"),
                "RoInitialize(RO_INIT_SINGLETHREADED) in gwin_app_create: " + run.describe());
        assertEquals(MAIN_STA, run.value("apartment.inLoop"), "after gwin_run_loop's OleInitialize: "
                + run.describe());
        assertEquals(MAIN_STA, run.value("apartment.afterDestroy"),
                "after DestroyWindow, RoUninitialize has run and OleInitialize still holds the apartment: "
                + run.describe());
        assertInNoApartmentOfItsOwn(run, "apartment.afterLoop");
    }

    private static void assertInNoApartmentOfItsOwn(WinGlassChildJvm.Run run, String key) {
        String apartment = run.value(key);
        if (!apartment.startsWith(NOT_INITIALIZED) && !apartment.equals(IMPLICIT_MTA)) {
            fail(key + " is " + apartment + ", not " + NOT_INITIALIZED + " or " + IMPLICIT_MTA
                    + ": the thread is in an apartment of its own\n" + run.describe());
        }
    }

    @Test
    @Timeout(120)
    public void teardownRemovesBothSinksThenReleasesEveryObjectBeforeRoUninitialize() {
        WinGlassChildJvm.Run run = traced();
        assertEquals("true", run.value("loop.returned"), run.describe());
        assertEquals("1,2,3,4,5,6", run.value("events"), "gwin_test_event_log after teardown: " + run.describe());
    }

    @Test
    @Timeout(120)
    public void theTestHooksAnswerInvalidArgumentForAnUnknownDelegateAndOnceTheDelegatesAreDropped() {
        WinGlassChildJvm.Run run = traced();
        String invalid = Integer.toString(INVALID_ARG);
        assertEquals(invalid, run.value("hook.invoke.2"), run.describe());
        assertEquals(invalid, run.value("hook.invoke.-1"), run.describe());
        assertEquals(invalid, run.value("hook.droppedInvoke.autoHide"), run.describe());
        assertEquals(invalid, run.value("hook.droppedInvoke.network"), run.describe());
    }

    @Test
    @Timeout(120)
    public void theQueriesRejectNullZeroFillWithoutAToolkitAndWriteEveryByteWithOne() {
        WinGlassChildJvm.Run run = traced();
        String invalid = Integer.toString(INVALID_ARG);
        for (String when : List.of("beforeCreate", "live", "afterTeardown")) {
            assertEquals(invalid, run.value("query." + when + ".ui.null"), when + ": " + run.describe());
            assertEquals(invalid, run.value("query." + when + ".network.null"), when + ": " + run.describe());
        }
        for (String when : List.of("beforeCreate", "afterTeardown")) {
            assertEquals(zeroFilled(60), run.value("query." + when + ".ui"),
                    "gwin_prefs_query_ui_settings without a toolkit, into 0xA5 bytes: " + run.describe());
            assertEquals(zeroFilled(8), run.value("query." + when + ".network"),
                    "gwin_prefs_query_network without a toolkit, into 0xA5 bytes: " + run.describe());
        }
        assertEquals("status=0,0,0 sameBytes=true", run.value("query.live.ui"),
                "gwin_prefs_query_ui_settings on the toolkit thread, into 0xA5, 0x00 and 0x5A bytes: "
                + run.describe());
        assertEquals("status=0,0,0 sameBytes=true", run.value("query.live.network"),
                "gwin_prefs_query_network on the toolkit thread, into 0xA5, 0x00 and 0x5A bytes: " + run.describe());
    }

    /**
     * With a live toolkit the queries carry what the activated objects answer, not an empty struct. This class already
     * relies on both WinRT registrations succeeding (both delegates are kept and both removals are logged), and the
     * auto-hide registration needs {@code IUISettings5}, which every Windows that has it pairs with
     * {@code IUISettings3} and {@code IUISettings4}: so {@code GwinUiSettings} has {@code available} and all three
     * block flags at 1, wherever this class passes, and its two booleans are 0 or 1 (the C wrote {@code value != 0}).
     * Of {@code GwinNetworkInfo} only its own rules are machine-independent: {@code available} 0 or 1, the cost one of
     * the four {@code GwinNetworkCost} values, and {@code GWIN_NET_COST_UNKNOWN} whenever {@code available} is 0.
     */
    @Test
    @Timeout(120)
    public void aLiveQueryCarriesTheValuesOfTheActivatedObjects() {
        assertLiveQueriesCarryValues(traced(), "query.live");
    }

    @Test
    @Timeout(120)
    public void aClearedTableReceivesNoCall() {
        WinGlassChildJvm.Run run = WinGlassChildJvm.run(WinPreferenceDispatchTraceTest.class, "traceWithAClearedTable");
        assertEquals("0", run.value("table.status"), run.describe());
        assertNoCall(run);
    }

    @Test
    @Timeout(120)
    public void aTableWhoseSlotIsNullReceivesNoCall() {
        WinGlassChildJvm.Run run = WinGlassChildJvm.run(WinPreferenceDispatchTraceTest.class,
                "traceWithANullSlot");
        assertEquals("0", run.value("table.status"), run.describe());
        assertNoCall(run);
    }

    /** {@code CoGetApartmentType} on a thread in no apartment, the process having none: {@code CO_E_NOTINITIALIZED}. */
    static final String NOT_INITIALIZED = "hr=0x800401f0 ";

    /** {@code CoGetApartmentType} on a thread in no apartment while the process has a multithreaded one. */
    static final String IMPLICIT_MTA = "hr=0x0 type=1 qualifier=1";

    /** {@code CoGetApartmentType} on the first single-threaded apartment of the process. */
    static final String MAIN_STA = "hr=0x0 type=3 qualifier=0";

    /*
     * The parent side.
     */

    /** Every message of {@link #CASES} under {@code prefix} produced exactly the calls the C made for it. */
    static void assertTrace(WinGlassChildJvm.Run run, String prefix, String what) {
        assertTrace(run, prefix, what, true);
    }

    /**
     * Every message of {@link #CASES} under {@code prefix} answered 0 and produced the calls the C made for it with a
     * callable slot, or none without one.
     */
    private static void assertTrace(WinGlassChildJvm.Run run, String prefix, String what, boolean callable) {
        List<String> differences = new ArrayList<>();
        for (int i = 0; i < CASES.size(); i++) {
            Case c = CASES.get(i);
            String expected = "result=0 " + (callable ? expectedCalls(c.types()) : "calls=none");
            String actual = run.values().get(key(prefix, i));
            if (!expected.equals(actual)) {
                differences.add(c.label() + ": expected [" + expected + "] but was [" + actual + "]");
            }
        }
        if (!differences.isEmpty()) {
            fail(differences.size() + " of " + CASES.size() + " messages, " + what + ", differ from what the C at"
                    + " 5a709c189d produced:\n" + String.join("\n", differences) + "\n" + run.describe());
        }
    }

    /** What {@link #describe} answers for the C's calls of one message: none, or these types on the toolkit thread. */
    static String expectedCalls(List<Integer> types) {
        return types.isEmpty() ? "calls=none" : "calls=" + join(types) + " thread=toolkit user=sentinel";
    }

    /** The sink recorded under {@code key} answered S_OK, and its type reached the toolkit thread's table. */
    static void assertSinkDelivered(WinGlassChildJvm.Run run, String key, int type) {
        assertEquals("0", run.value(key + ".status"), "the HRESULT of the delegate " + key + " invoked ("
                + WinGlassNativeShim.class.getSimpleName() + ".prefsInvoke; -1 when none is kept): " + run.describe());
        assertEquals("true", run.value(key + ".delivered"), "no preferences_changed(" + type + ") followed "
                + key + "; the calls after it: " + run.value(key + ".calls") + "\n" + run.describe());
        String calls = run.value(key + ".calls");
        if (!calls.contains(" thread=toolkit ") || !calls.endsWith(" user=sentinel")) {
            fail("the calls after " + key + " did not all run on the toolkit thread with the table's user pointer: "
                    + calls + "\n" + run.describe());
        }
    }

    /**
     * The live queries recorded under {@code prefix} carry the values of activated objects, as
     * {@link #aLiveQueryCarriesTheValuesOfTheActivatedObjects} states them; each field that breaks a rule is named.
     */
    static void assertLiveQueriesCarryValues(WinGlassChildJvm.Run run, String prefix) {
        int[] ui = littleEndianInts(run.value(prefix + ".ui.bytes"));
        int[] network = littleEndianInts(run.value(prefix + ".network.bytes"));
        List<String> wrong = new ArrayList<>();
        if (ui.length != 15 || network.length != 2) {
            fail("the live queries recorded " + ui.length * 4 + " and " + network.length * 4 + " bytes, not 60 and 8: "
                    + run.describe());
        }
        requireField(wrong, "GwinUiSettings.available", ui[0], 1, 1);
        requireField(wrong, "GwinUiSettings.colors_valid", ui[1], 1, 1);
        requireField(wrong, "GwinUiSettings.advanced_effects_valid", ui[11], 1, 1);
        requireField(wrong, "GwinUiSettings.advanced_effects_enabled", ui[12], 0, 1);
        requireField(wrong, "GwinUiSettings.auto_hide_valid", ui[13], 1, 1);
        requireField(wrong, "GwinUiSettings.auto_hide_scroll_bars", ui[14], 0, 1);
        requireField(wrong, "GwinNetworkInfo.available", network[0], 0, 1);
        requireField(wrong, "GwinNetworkInfo.cost_type", network[1], 0, 3);
        if (network[0] == 0 && network[1] != 0) {
            wrong.add("GwinNetworkInfo.cost_type is " + network[1] + " with available 0, not GWIN_NET_COST_UNKNOWN");
        }
        if (!wrong.isEmpty()) {
            fail("the live queries under " + prefix + " do not carry the values of activated objects: " + wrong
                    + "\nGwinUiSettings " + run.value(prefix + ".ui.bytes") + "\nGwinNetworkInfo "
                    + run.value(prefix + ".network.bytes") + "\n" + run.describe());
        }
    }

    private static void requireField(List<String> wrong, String field, int value, int min, int max) {
        if (value < min || value > max) {
            wrong.add(field + " is " + value + ", not " + (min == max ? Integer.toString(min) : min + " to " + max));
        }
    }

    /** The 32-bit little-endian words of the bytes {@code hex} spells. */
    static int[] littleEndianInts(String hex) {
        ByteBuffer bytes = ByteBuffer.wrap(HexFormat.of().parseHex(hex)).order(ByteOrder.LITTLE_ENDIAN);
        int[] words = new int[bytes.capacity() / 4];
        for (int i = 0; i < words.length; i++) {
            words[i] = bytes.getInt(4 * i);
        }
        return words;
    }

    private static void assertNoCall(WinGlassChildJvm.Run run) {
        assertTrace(run, "trace.noSlot", "with no callable slot", false);
        assertEquals("0", run.value("calls.total"), "preferences_changed was called in the toolkit's lifetime: "
                + run.describe());
        assertEquals("true", run.value("loop.returned"), run.describe());
    }

    private static String zeroFilled(int size) {
        return "status=0 bytes=" + "00".repeat(size);
    }

    /*
     * The child side.
     */

    /** Runs in {@link WinGlassChild}. */
    static void traceDispatch(Map<String, String> out) {
        recordQueries(out, "beforeCreate");
        WinGlassNativeShim.installAnsweringPreferencesTable(USER);
        WinGlassNativeShim.prefsCapture(true);
        WinGlassNativeShim.observe(true);
        WinSteppedToolkit toolkit = WinSteppedToolkit.start(
                () -> out.put("apartment.beforeCreate", apartment()),
                () -> out.put("apartment.afterCreate", apartment()),
                () -> out.put("apartment.inLoop", apartment()),
                () -> out.put("apartment.afterLoop", apartment()));
        Thread toolkitThread = toolkit.thread();
        int toolkitThreadId = toolkit.nativeThreadId();
        out.put("trace.invoke", Integer.toString(toolkit.invokeAndWait(() -> {
            sweeps(out, toolkit.hwnd(), toolkitThread, toolkitThreadId);
            recordLiveQueries(out);
            out.put("hook.invoke.2", Integer.toString(WinGlassNativeShim.prefsInvoke(2)));
            out.put("hook.invoke.-1", Integer.toString(WinGlassNativeShim.prefsInvoke(-1)));
        })));

        traceSinkOnAnotherThread(out, "sink.otherThread.autoHide", WinGlassNativeShim.DELEGATE_AUTO_HIDE,
                UI_SETTINGS, toolkitThread, toolkitThreadId);
        traceSinkOnAnotherThread(out, "sink.otherThread.network", WinGlassNativeShim.DELEGATE_NETWORK,
                NETWORK_INFORMATION, toolkitThread, toolkitThreadId);
        traceSinkOnTheToolkitThread(out, "sink.toolkitThread.autoHide", WinGlassNativeShim.DELEGATE_AUTO_HIDE,
                UI_SETTINGS, toolkit);
        traceSinkOnTheToolkitThread(out, "sink.toolkitThread.network", WinGlassNativeShim.DELEGATE_NETWORK,
                NETWORK_INFORMATION, toolkit);

        out.put("terminate.post", Integer.toString(toolkit.invokeLater(() -> {
            WinGlassNativeShim.toolkitTerminateLoop();
            out.put("apartment.afterDestroy", apartment());
        })));
        out.put("loop.returned", Boolean.toString(toolkit.awaitLoopReturn(WAIT_MILLIS)));
        out.put("events", WinGlassNativeShim.eventLog().stream().map(String::valueOf)
                .collect(Collectors.joining(",")));
        recordQueries(out, "afterTeardown");
        WinGlassNativeShim.prefsCapture(false);
        out.put("hook.droppedInvoke.autoHide",
                Integer.toString(WinGlassNativeShim.prefsInvoke(WinGlassNativeShim.DELEGATE_AUTO_HIDE)));
        out.put("hook.droppedInvoke.network",
                Integer.toString(WinGlassNativeShim.prefsInvoke(WinGlassNativeShim.DELEGATE_NETWORK)));
        toolkit.releaseAndAwaitEnd(WAIT_MILLIS);
    }

    /** Runs in {@link WinGlassChild}: the recording table, then {@code gwin_prefs_set_callbacks(NULL, NULL)}. */
    static void traceWithAClearedTable(Map<String, String> out) {
        WinGlassNativeShim.installAnsweringPreferencesTable(USER);
        out.put("table.status", Integer.toString(WinGlassNativeShim.clearPreferencesTable()));
        traceWithNoCallableSlot(out);
    }

    /** Runs in {@link WinGlassChild}: the recording table, then a table whose {@code preferences_changed} is NULL. */
    static void traceWithANullSlot(Map<String, String> out) {
        WinGlassNativeShim.installAnsweringPreferencesTable(USER);
        out.put("table.status", Integer.toString(WinGlassNativeShim.installPreferencesTableWithNullSlot(USER)));
        traceWithNoCallableSlot(out);
    }

    private static void traceWithNoCallableSlot(Map<String, String> out) {
        WinNativeToolkit toolkit = WinNativeToolkit.start();
        out.put("trace.invoke", Integer.toString(toolkit.invokeAndWait(() ->
                sweep(out, "trace.noSlot", toolkit.hwnd(), toolkit.thread(), toolkit.nativeThreadId()))));
        out.put("terminate.post", Integer.toString(toolkit.terminate()));
        out.put("loop.returned", Boolean.toString(toolkit.awaitLoopReturn(WAIT_MILLIS)));
        out.put("calls.total", Integer.toString(WinGlassNativeShim.answeredPreferencesCalls().size()));
        toolkit.releaseAndAwaitEnd(WAIT_MILLIS);
    }

    /** {@link #sweep} once per answer of {@link #ANSWERS}, then the answer back at 0. On the toolkit thread. */
    static void sweeps(Map<String, String> out, long hwnd, Thread toolkitThread, int toolkitThreadId) {
        for (int answer : ANSWERS) {
            WinGlassNativeShim.answerPreferences(answer);
            sweep(out, "trace.answer" + answer, hwnd, toolkitThread, toolkitThreadId);
        }
        WinGlassNativeShim.answerPreferences(0);
    }

    /**
     * Sends every message of {@link #CASES} to {@code hwnd} and records, under {@code prefix}, what the send answered
     * and the calls it produced. On the thread that owns {@code hwnd}.
     */
    static void sweep(Map<String, String> out, String prefix, long hwnd, Thread toolkitThread, int toolkitThreadId) {
        for (int i = 0; i < CASES.size(); i++) {
            Case c = CASES.get(i);
            int before = WinGlassNativeShim.answeredPreferencesCalls().size();
            long result = c.text() == null
                    ? WinGlassNativeShim.sendMessage(hwnd, c.message(), c.wParam(), c.lParam())
                    : WinGlassNativeShim.sendMessageWithText(hwnd, c.message(), c.wParam(), c.text().toCharArray());
            out.put(key(prefix, i), "result=" + result + " "
                    + describe(callsSince(before), toolkitThread, toolkitThreadId));
        }
    }

    /**
     * Invokes a kept delegate on a thread of its own and waits until {@code preferences_changed(type)} has been
     * recorded after it: the delegate's status, whether that call came, and every call recorded meanwhile.
     */
    static void traceSinkOnAnotherThread(Map<String, String> out, String key, int which, int type,
                                         Thread toolkitThread, int toolkitThreadId) {
        int before = WinGlassNativeShim.answeredPreferencesCalls().size();
        Thread sink = WinNativeToolkit.startThread("sink", () ->
                out.put(key + ".status", Integer.toString(WinGlassNativeShim.prefsInvoke(which))));
        out.put(key + ".sinkEnded", Boolean.toString(WinNativeToolkit.join(sink, WAIT_MILLIS)));
        recordDelivery(out, key, before, type, toolkitThread, toolkitThreadId);
    }

    /**
     * Invokes a kept delegate on the toolkit thread, inside a runnable of {@code gwin_invoke_and_wait}, and records
     * how many calls were made before it returned there; then waits as {@link #traceSinkOnAnotherThread} does.
     */
    private static void traceSinkOnTheToolkitThread(Map<String, String> out, String key, int which, int type,
                                                    WinSteppedToolkit toolkit) {
        int before = WinGlassNativeShim.answeredPreferencesCalls().size();
        out.put(key + ".invoke", Integer.toString(toolkit.invokeAndWait(() -> {
            out.put(key + ".status", Integer.toString(WinGlassNativeShim.prefsInvoke(which)));
            out.put(key + ".inline", Integer.toString(callsSince(before).size()));
        })));
        recordDelivery(out, key, before, type, toolkit.thread(), toolkit.nativeThreadId());
    }

    private static void recordDelivery(Map<String, String> out, String key, int before, int type,
                                       Thread toolkitThread, int toolkitThreadId) {
        out.put(key + ".delivered", Boolean.toString(WinGlassChild.waitFor(WAIT_MILLIS,
                () -> callsSince(before).stream().anyMatch(c -> c.types() == type))));
        out.put(key + ".calls", describe(callsSince(before), toolkitThread, toolkitThreadId));
    }

    /** Both queries into 0xA5 bytes and into NULL, under {@code query.<when>}. */
    static void recordQueries(Map<String, String> out, String when) {
        out.put("query." + when + ".ui", raw(WinGlassNativeShim.queryUiSettingsRaw((byte) 0xA5)));
        out.put("query." + when + ".network", raw(WinGlassNativeShim.queryNetworkRaw((byte) 0xA5)));
        out.put("query." + when + ".ui.null", Integer.toString(WinGlassNativeShim.queryUiSettingsIntoNull()));
        out.put("query." + when + ".network.null", Integer.toString(WinGlassNativeShim.queryNetworkIntoNull()));
    }

    /**
     * Both queries on the toolkit thread, each into 0xA5, 0x00 and 0x5A bytes: the three statuses and whether the
     * three structs came back equal, which they do only when the export wrote every byte; then into NULL. The bytes
     * themselves depend on the machine and are recorded: only their flag words and ranges are checked
     * ({@link #assertLiveQueriesCarryValues}), never their colours or network cost, and no two reads are compared.
     */
    static void recordLiveQueries(Map<String, String> out) {
        recordLiveQueries(out, "query.live");
    }

    /** {@link #recordLiveQueries(Map)} under {@code prefix}. */
    static void recordLiveQueries(Map<String, String> out, String prefix) {
        List<QueryResult> ui = List.of(WinGlassNativeShim.queryUiSettingsRaw((byte) 0xA5),
                WinGlassNativeShim.queryUiSettingsRaw((byte) 0x00), WinGlassNativeShim.queryUiSettingsRaw((byte) 0x5A));
        List<QueryResult> network = List.of(WinGlassNativeShim.queryNetworkRaw((byte) 0xA5),
                WinGlassNativeShim.queryNetworkRaw((byte) 0x00), WinGlassNativeShim.queryNetworkRaw((byte) 0x5A));
        out.put(prefix + ".ui", liveSummary(ui));
        out.put(prefix + ".network", liveSummary(network));
        out.put(prefix + ".ui.bytes", HexFormat.of().formatHex(ui.get(0).bytes()));
        out.put(prefix + ".network.bytes", HexFormat.of().formatHex(network.get(0).bytes()));
        out.put(prefix + ".ui.null", Integer.toString(WinGlassNativeShim.queryUiSettingsIntoNull()));
        out.put(prefix + ".network.null", Integer.toString(WinGlassNativeShim.queryNetworkIntoNull()));
    }

    private static String liveSummary(List<QueryResult> results) {
        boolean same = results.stream().allMatch(r -> Arrays.equals(r.bytes(), results.get(0).bytes()));
        return "status=" + results.stream().map(r -> Integer.toString(r.status())).collect(Collectors.joining(","))
                + " sameBytes=" + same;
    }

    static String raw(QueryResult result) {
        return "status=" + result.status() + " bytes=" + HexFormat.of().formatHex(result.bytes());
    }

    /** {@code CoGetApartmentType} of the calling thread as {@code hr=0x... type=N qualifier=N}. */
    static String apartment() {
        ComApartment apartment = WinGlassNativeShim.comApartment();
        return "hr=0x" + Integer.toHexString(apartment.hresult()) + " type=" + apartment.type()
                + " qualifier=" + apartment.qualifier();
    }

    static List<AnsweredPreferencesCall> callsSince(int index) {
        List<AnsweredPreferencesCall> calls = WinGlassNativeShim.answeredPreferencesCalls();
        return calls.subList(Math.min(index, calls.size()), calls.size());
    }

    /**
     * {@code calls=none}, or {@code calls=<types> thread=<toolkit, or the names of the threads> user=<sentinel, or
     * the pointers>}.
     */
    static String describe(List<AnsweredPreferencesCall> calls, Thread toolkitThread, int toolkitThreadId) {
        if (calls.isEmpty()) {
            return "calls=none";
        }
        boolean onToolkit = calls.stream()
                .allMatch(c -> c.thread() == toolkitThread && c.nativeThreadId() == toolkitThreadId);
        boolean sentinel = calls.stream().allMatch(c -> c.user() == USER);
        return "calls=" + join(calls.stream().map(AnsweredPreferencesCall::types).toList())
                + " thread=" + (onToolkit ? "toolkit" : calls.stream()
                        .map(c -> c.thread().getName() + "/" + c.nativeThreadId()).collect(Collectors.joining(",")))
                + " user=" + (sentinel ? "sentinel" : calls.stream()
                        .map(c -> "0x" + Long.toHexString(c.user())).collect(Collectors.joining(",")));
    }

    static String key(String prefix, int index) {
        return String.format("%s.%02d", prefix, index);
    }

    private static String join(List<Integer> types) {
        return types.stream().map(String::valueOf).collect(Collectors.joining(","));
    }
}
