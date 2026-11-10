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

package test.com.sun.glass.ui.gtk;

import com.sun.glass.ui.Clipboard;
import com.sun.glass.ui.ClipboardAssistance;
import com.sun.glass.ui.GlassRobot;
import com.sun.glass.ui.gtk.GtkGlassShim;
import com.sun.glass.ui.gtk.GtkTraceWindow;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import javafx.application.Platform;
import javafx.scene.input.MouseButton;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static test.com.sun.glass.ui.gtk.GtkDnDReentrantDropTest.EXCHANGE_PROPERTY;
import static test.com.sun.glass.ui.gtk.GtkDnDReentrantDropTest.MARKER_MILLIS;
import static test.com.sun.glass.ui.gtk.GtkDnDReentrantDropTest.READ_MILLIS;
import static test.com.sun.glass.ui.gtk.GtkDnDReentrantDropTest.await;
import static test.com.sun.glass.ui.gtk.GtkDnDReentrantDropTest.destroy;
import static test.com.sun.glass.ui.gtk.GtkDnDReentrantDropTest.exchangeDirectory;
import static test.com.sun.glass.ui.gtk.GtkDnDReentrantDropTest.signal;
import static test.com.sun.glass.ui.gtk.GtkDnDReentrantDropTest.value;
import static test.com.sun.glass.ui.gtk.GtkDnDReentrantDropTest.window;
import static test.com.sun.glass.ui.gtk.GtkDnDTargetTest.CUSTOM_MIME;
import static test.com.sun.glass.ui.gtk.GtkDnDTargetTest.RIGHT_X;
import static test.com.sun.glass.ui.gtk.GtkDnDTargetTest.TOP;

/**
 * A conversion that the drag source refuses answers no data at the drop target, also on a window an earlier
 * conversion was answered on, and ends no wait but its own. {@code dnd_target_receive_data} asks the source to
 * convert into the requestor window's {@code GDK_SELECTION} property ({@code gdk_selection_convert}), and its event
 * hook, {@code wait_for_selection_data_hook}, reads an answer from there with {@code gdk_selection_property_get},
 * which leaves the property in place. A source refuses a target it does not offer with a {@code SelectionNotify}
 * whose property is {@code None}, which GDK hands on with the target as its property
 * ({@code gdk_x11_display_translate_event}). Commit {@code 033187ad90} tested the property for 0, which GDK never
 * delivers, so it read the property after a refusal as after an answer, and answered the data an earlier conversion
 * had left there; and it took any {@code SELECTION_NOTIFY} on the requestor window, so the answer to one conversion
 * also ended the wait of another one pending on that window.
 * <p>
 * One target window takes three drags:
 * <ul>
 * <li>the first source offers {@code text/plain}, and a read at the drop answers its text, which the conversion left
 * in the window's {@code GDK_SELECTION} property;</li>
 * <li>the second source offers {@link GtkDnDTargetTest#CUSTOM_MIME} alone. At the drop a {@code text/plain} read -
 * the {@code UTF8_STRING}, {@code text/plain} and {@code STRING} conversions of {@code dnd_target_get_string}, each
 * refused - answers {@code null}, not the first drag's text; a read of that mime then answers the source's bytes, so
 * a refusal does not stop a later conversion on the same window from answering; a second {@code text/plain} read
 * answers {@code null} again, not those bytes;</li>
 * <li>the third source offers that mime alone, with other bytes. The target releases the button inside its
 * {@code dragOver} and reads {@code text/plain} there, so the drop is dispatched inside that read's wait, as in
 * {@link GtkDnDReentrantDropTest}, and reads the mime: a wait nested in the other. The target's queue holds the
 * {@code XdndDrop}, then the refusal of {@code UTF8_STRING}, then the answer to the nested conversion. The refusal
 * ends the outer wait only, the nested read answers the third source's bytes, and the outer read answers
 * {@code null}. Matched on the window alone, as at commit {@code 033187ad90}, the refusal ends the nested wait as
 * well, before its answer has come. The outer read is a refused one on purpose: an answer is read from the property
 * when the hook sees it, and dispatched inside the nested wait it would race the source's answer to the nested
 * conversion, which rewrites that property.</li>
 * </ul>
 * The tests also pin what makes the others meaningful: every drop reached the same native window, the first drag's
 * read answered its text, the second and third drags offer no text type, and only the third drop ran inside a read.
 * <p>
 * Source and target run in two child JVMs and take turns through marker files, with the drag source
 * ({@link GtkDnDReentrantDropTest#dragSource}), the marker handshake and the window placement of
 * {@link GtkDnDReentrantDropTest}. For the first two drags the target releases the button once it has answered the
 * {@code dragOver} of its own pointer move, so those drops are ordinary ones. The class runs with and without a
 * window manager.
 */
@EnabledOnOs(OS.LINUX)
@Timeout(300)
public class GtkDnDRefusedConversionTest {

    static final int DRAGS = 3;

    /** The drag whose drop runs inside a read of its {@code dragOver}. */
    static final int NESTED_DRAG = 3;

    /** The text the first drag's source offers. */
    static final String TEXT = "first drag";

    /** How long the target waits for the reads of a drop, which answer well within a second. */
    static final long DROP_MILLIS = 20_000;

    /** What the target reads at the drop of each drag, in this order. */
    static final Map<Integer, List<String>> READS = Map.of(
            1, List.of("text/plain"),
            2, List.of("text/plain", CUSTOM_MIME, "text/plain"),
            3, List.of(CUSTOM_MIME));

    /**
     * The mimes of the first drag as the target sees them, sorted: Glass offers {@code text/plain} as
     * {@code UTF8_STRING}, {@code text/plain} and {@code STRING} ({@code add_gtk_target_for_mime}), and the target
     * lists a text target as {@code text/plain} and by its name.
     */
    static final String TEXT_MIMES = "STRING|UTF8_STRING|text/plain";

    /** The first drag's text as {@link #describe} writes it. */
    static final String TEXT_READ = "String " + TEXT;

    /** The second drag's bytes as {@link #describe} writes them. */
    static final String SECOND_READ = "ByteBuffer [1, 2, 3]";

    /** The third drag's bytes as {@link #describe} writes them. */
    static final String THIRD_READ = "ByteBuffer [4, 5, 6]";

    /** What a read that answers no data records. */
    static final String NO_DATA = "null";

    private static GtkGlassChildJvm.Run source;
    private static GtkGlassChildJvm.Run target;

    /**
     * Runs the two children at the same time, awaited without {@link GtkGlassChildJvm#runTogether} for the reason
     * {@link GtkDnDReentrantDropTest#runScenario} gives: each test pins what the children recorded, and
     * {@link #bothChildrenRanToTheEnd} their exits.
     */
    @BeforeAll
    @Timeout(2 * GtkGlassChildJvm.BEFORE_ALL_SECONDS)
    static void runScenario() {
        GtkGlassChildJvm.requireRobot();
        Path exchange = exchangeDirectory("dnd-refused-conversion-exchange");
        List<String> options = List.of("-DUSE_ROBOT=true", "-D" + EXCHANGE_PROPERTY + "=" + exchange);
        GtkGlassChildJvm.Pending sourceChild = GtkGlassChildJvm.start(GtkDnDRefusedConversionTest.class,
                "sourceScenario", options);
        GtkGlassChildJvm.Pending targetChild = null;
        try {
            targetChild = GtkGlassChildJvm.start(GtkDnDRefusedConversionTest.class, "targetScenario", options);
            target = GtkGlassChildJvm.await(targetChild, GtkGlassChildJvm.CHILD_SECONDS);
            source = GtkGlassChildJvm.await(sourceChild, GtkGlassChildJvm.CHILD_SECONDS);
        } finally {
            destroy(sourceChild);
            destroy(targetChild);
        }
    }

    /** What the target read for {@code mime}, the {@code read}-th read at the drop of drag {@code drag}. */
    private static String read(int drag, int read, String mime) {
        return value(target, "drag" + drag + ".drop." + read + "." + mime);
    }

    /**
     * Every drag was dropped on the target's one window, whose native window stayed the same, and ended with the
     * action the target answered.
     */
    @Test
    public void everyDragWasDroppedOnTheSameWindow() {
        String window = value(target, "drag1.drop.window");
        assertNotEquals("0", window, "drag1.drop.window");
        for (int drag = 1; drag <= DRAGS; drag++) {
            assertEquals(window, value(target, "drag" + drag + ".drop.window"), "drag " + drag);
            assertEquals("true", value(target, "drag" + drag + ".dropped"), "drag " + drag);
            assertEquals("COPY", value(source, "drag" + drag + ".performed"), "drag " + drag);
        }
    }

    /** The third drop ran inside the read of its {@code dragOver}, the first two inside no read. */
    @Test
    public void onlyTheThirdDropRanInsideARead() {
        for (int drag = 1; drag <= DRAGS; drag++) {
            assertEquals(Boolean.toString(drag == NESTED_DRAG), value(target, "drag" + drag + ".drop.insideOverRead"),
                    "drag " + drag);
        }
    }

    /** The first drag offers text, and its read answers it: the window's {@code GDK_SELECTION} now holds that text. */
    @Test
    public void theFirstDragsReadAnswersItsText() {
        assertEquals(TEXT_MIMES, value(target, "drag1.drop.mimes"), "drag1.drop.mimes");
        assertEquals(TEXT_READ, read(1, 1, "text/plain"));
    }

    /** The second and third drags offer the custom mime alone, so their sources refuse every text conversion. */
    @Test
    public void theSecondAndThirdDragsOfferNoText() {
        assertEquals(CUSTOM_MIME, value(target, "drag2.drop.mimes"), "drag2.drop.mimes");
        assertEquals(CUSTOM_MIME, value(target, "drag3.drop.mimes"), "drag3.drop.mimes");
    }

    /** A {@code text/plain} read that the source refuses answers no data, not the text of the first drag. */
    @Test
    public void aRefusedConversionAnswersNoData() {
        assertEquals(NO_DATA, read(2, 1, "text/plain"));
    }

    /** After that refusal, a conversion the source answers on the same window answers the source's bytes. */
    @Test
    public void anAnsweredConversionAfterARefusalAnswersTheSourcesData() {
        assertEquals(SECOND_READ, read(2, 2, CUSTOM_MIME));
    }

    /** A refused {@code text/plain} read after that answer answers no data, not the bytes it left behind. */
    @Test
    public void aRefusedConversionAfterAnAnsweredOneAnswersNoData() {
        assertEquals(NO_DATA, read(2, 3, "text/plain"));
    }

    /**
     * The read nested in the wait of the {@code dragOver}'s read answers its own conversion's bytes: the refusal of
     * the outer conversion, dispatched inside the nested wait, does not end it.
     */
    @Test
    public void aReadNestedInAnotherReadsWaitAnswersItsOwnConversion() {
        assertEquals(THIRD_READ, read(3, 1, CUSTOM_MIME));
    }

    /** The {@code dragOver}'s read, refused and answered inside the nested wait, answers no data. */
    @Test
    public void theRefusedReadADropInterruptedAnswersNoData() {
        assertEquals(NO_DATA, value(target, "drag3.over.text/plain"), "drag3.over.text/plain");
    }

    /** Both children ran their scenario to the end and left no fatal error log. */
    @Test
    public void bothChildrenRanToTheEnd() {
        for (GtkGlassChildJvm.Run run : List.of(source, target)) {
            assertEquals(0, run.exitCode(), run.describe());
            assertTrue(run.errorFiles().isEmpty(), run.describe());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The scenario (two child JVMs)
    // ---------------------------------------------------------------------------------------------

    /**
     * The drop target: answers {@code COPY} everywhere and reads {@link #READS} at each drop, recording the mimes,
     * the native window, whether the drop ran inside a read and each read. In the armed {@code dragOver} of
     * {@link #NESTED_DRAG} it releases the button through the robot and reads {@code text/plain}; in the others it
     * reads nothing before the drop.
     */
    static final class RefusedConversionTargetHooks extends GtkEventTrace.ViewHooks {

        /** What the FX thread records; the scenario copies it into its map. */
        final Map<String, String> records = new ConcurrentHashMap<>();
        volatile GlassRobot robot;
        volatile GtkTraceWindow window;
        volatile int drag;
        volatile boolean entered;
        volatile boolean armed;
        volatile boolean overAnswered;
        volatile boolean overReadReturned;
        volatile boolean dropped;
        private boolean inOverRead;

        @Override
        int dragAction(String phase, int recommended) {
            if ("enter".equals(phase)) {
                // runs once this upcall has returned and process_dnd_target_drag_motion has answered the enter
                Platform.runLater(() -> entered = true);
            } else if ("over".equals(phase) && armed) {
                armed = false;
                if (drag == NESTED_DRAG) {
                    releaseAndRead();
                } else {
                    Platform.runLater(() -> overAnswered = true);
                }
            }
            return Clipboard.ACTION_COPY;
        }

        /**
         * Releases the button before the read sends its {@code XConvertSelection}, so that the drop reaches the
         * target's queue before the {@code SELECTION_NOTIFY} (see {@link GtkDnDReentrantDropTest}, steps 2 and 3).
         */
        private void releaseAndRead() {
            robot.mouseRelease(MouseButton.PRIMARY);
            inOverRead = true;
            String read;
            try {
                read = outcome(() -> describe(GtkGlassShim.dndPopFromSystem("text/plain")));
            } finally {
                inOverRead = false;
            }
            records.put("drag" + drag + ".over.text/plain", read);
            overReadReturned = true;
        }

        @Override
        void dragDrop(ClipboardAssistance assistance) {
            String prefix = "drag" + drag + ".";
            records.put(prefix + "drop.insideOverRead", Boolean.toString(inOverRead));
            records.put(prefix + "drop.window", Long.toString(window.getNativeWindow()));
            records.put(prefix + "drop.mimes", outcome(() -> {
                String[] mimes = GtkGlassShim.dndMimesFromSystem().clone();
                Arrays.sort(mimes);
                return String.join("|", mimes);
            }));
            List<String> reads = READS.get(drag);
            for (int read = 1; read <= reads.size(); read++) {
                String mime = reads.get(read - 1);
                records.put(prefix + "drop." + read + "." + mime,
                        outcome(() -> describe(GtkGlassShim.dndPopFromSystem(mime))));
            }
            records.put(prefix + "dropped", "true");
            dropped = true;
        }
    }

    /** What {@code call} answered, or the class and message of what it threw. */
    private static String outcome(Callable<String> call) {
        try {
            return call.call();
        } catch (Exception e) {
            return e.getClass().getName() + ": " + e.getMessage();
        }
    }

    /** A read's value: {@code null}, or its class's simple name and its content - a buffer's remaining bytes. */
    private static String describe(Object value) {
        return switch (value) {
            case null -> NO_DATA;
            case ByteBuffer buffer -> {
                byte[] bytes = new byte[buffer.remaining()];
                buffer.duplicate().get(bytes);
                yield "ByteBuffer " + Arrays.toString(bytes);
            }
            default -> value.getClass().getSimpleName() + " " + value;
        };
    }

    /** What the source of drag {@code drag} offers. */
    private static Map<String, Object> content(int drag) {
        return switch (drag) {
            case 1 -> Map.of("text/plain", TEXT);
            case 2 -> Map.of(CUSTOM_MIME, ByteBuffer.wrap(new byte[] {1, 2, 3}));
            case 3 -> Map.of(CUSTOM_MIME, ByteBuffer.wrap(new byte[] {4, 5, 6}));
            default -> throw new IllegalArgumentException("drag " + drag);
        };
    }

    /** Runs in {@link GtkGlassChild}: the drag source of the three drags. */
    static void sourceScenario(Map<String, String> out) throws Exception {
        GtkDnDReentrantDropTest.dragSource(out, DRAGS, GtkDnDRefusedConversionTest::content);
    }

    /**
     * Runs in {@link GtkGlassChild}: the drop target, one window w2 for every drag. For each drag it waits until it
     * has answered the enter and moves the pointer over itself; then, for {@link #NESTED_DRAG}, it waits for the
     * read of that {@code dragOver}, and for the others until it has answered that {@code dragOver}, when it releases
     * the button; then it waits for the reads of the drop. When a step does not come it tells the source to stop and
     * throws, and {@link GtkGlassChild} writes what was recorded so far.
     */
    static void targetScenario(Map<String, String> out) throws Exception {
        Path exchange = Path.of(System.getProperty(EXCHANGE_PROPERTY));
        RefusedConversionTargetHooks target = new RefusedConversionTargetHooks();
        String stop = null;
        try {
            // one window after the other, as in GtkDnDReentrantDropTest
            await(exchange, "source.ready");
            GtkEventTrace t = new GtkEventTrace();
            target.robot = t.robot();
            signal(exchange, "target.ready");
            stop = "drag1.target.stop";
            GtkTraceWindow window = window(t, "w2", "v2", RIGHT_X, target);
            target.window = window;
            for (int drag = 1; drag <= DRAGS; drag++) {
                String prefix = "drag" + drag + ".";
                stop = prefix + "target.stop";
                target.drag = drag;
                target.entered = false;
                target.overAnswered = false;
                target.overReadReturned = false;
                target.dropped = false;
                signal(exchange, prefix + "target.armed");
                if (!GtkGlassChild.waitFor(MARKER_MILLIS, () -> target.entered)) {
                    throw new IllegalStateException(prefix + "enter: the drag did not enter the target within 60 s");
                }
                target.armed = true;
                GtkGlassChild.onFx(() -> {
                    target.robot.mouseMove(RIGHT_X + 110, TOP + 110);
                    return null;
                });
                if (drag == NESTED_DRAG) {
                    if (!GtkGlassChild.waitFor(READ_MILLIS, () -> target.overReadReturned)) {
                        throw new IllegalStateException(prefix + "over: popFromSystem did not return within 20 s");
                    }
                } else {
                    if (!GtkGlassChild.waitFor(MARKER_MILLIS, () -> target.overAnswered)) {
                        throw new IllegalStateException(prefix + "over: the target answered no dragOver within 60 s");
                    }
                    GtkGlassChild.onFx(() -> {
                        target.robot.mouseRelease(MouseButton.PRIMARY);
                        return null;
                    });
                }
                if (!GtkGlassChild.waitFor(DROP_MILLIS, () -> target.dropped)) {
                    throw new IllegalStateException(prefix + "drop: the reads of the drop did not return within 20 s");
                }
                await(exchange, prefix + "source.done");
                t.settle();
                signal(exchange, prefix + "target.done");
                stop = null;
            }
            t.act(window::close);
        } finally {
            out.putAll(target.records);
            if (stop != null) {
                signal(exchange, stop);
            }
        }
    }
}
