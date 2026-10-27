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
import com.sun.glass.ui.Window;
import com.sun.glass.ui.gtk.GtkGlassShim;
import com.sun.glass.ui.gtk.GtkTraceWindow;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import javafx.application.Platform;
import javafx.scene.input.MouseButton;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static test.com.sun.glass.ui.gtk.GtkDnDTargetTest.CUSTOM_MIME;
import static test.com.sun.glass.ui.gtk.GtkDnDTargetTest.HEIGHT;
import static test.com.sun.glass.ui.gtk.GtkDnDTargetTest.LEFT_X;
import static test.com.sun.glass.ui.gtk.GtkDnDTargetTest.MESSAGE;
import static test.com.sun.glass.ui.gtk.GtkDnDTargetTest.RIGHT_X;
import static test.com.sun.glass.ui.gtk.GtkDnDTargetTest.TOP;
import static test.com.sun.glass.ui.gtk.GtkDnDTargetTest.WIDTH;

/**
 * A drop that GDK dispatches inside the wait of a drop-target read, {@code popFromSystem} called from the target's
 * {@code dragOver}: {@code dnd_target_receive_data} runs the main loop until the {@code SELECTION_NOTIFY} of its
 * conversion arrives, and {@code process_dnd_target_drop_start}, dispatched there, ends the drag and clears
 * {@code enter_ctx.ctx}, which commit {@code 033187ad90} left set. Two drags, a read in the {@code dragOver} of
 * each, whose drop reads nothing:
 * <ul>
 * <li>the first source offers text: the read answers it, because {@code wait_for_selection_data_hook} matches the
 * {@code SELECTION_NOTIFY} against the requestor window that {@code dnd_target_receive_data} passed to
 * {@code gdk_selection_convert}. Recomputed from {@code enter_ctx.ctx} at every event, as at commit
 * {@code 033187ad90}, that window is {@code NULL} once the drop has cleared the context, and the wait never
 * ends;</li>
 * <li>the second source offers no text, so {@code dnd_target_get_string}, its {@code UTF8_STRING} conversion
 * refused, goes on to {@code TEXT_PLAIN} and {@code STRING} after the drop: {@code dnd_target_receive_data} answers
 * {@code FALSE} for each, as there is no drag left to convert from, and the read answers {@code null}. Without that
 * check {@code gdk_drag_get_selection(NULL)} answers {@code GDK_NONE}, {@code gdk_selection_convert} sends no
 * request, and the wait for a {@code SELECTION_NOTIFY} never ends.</li>
 * </ul>
 * Source and target run in two child JVMs. In one process GDK hands an XDND message to its own window straight to
 * its event queue ({@code xdnd_send_xevent}), so the target's {@code XdndFinished} reaches the source before the
 * source has read the target's {@code SelectionRequest}; GTK has released the selection by then
 * ({@code gtk_drag_source_release_selections}) and leaves the request unanswered ({@code _gtk_selection_request}),
 * whichever C runs. Between two processes the X server delivers each client's requests in the order that client sent
 * them, and that fixes the order of every step:
 * <ol>
 * <li>the target moves the pointer over itself only after it has answered the enter ({@code gdk_drag_status}), so
 * the source reads that {@code XdndStatus} before the motion and sends the {@code XdndPosition} of the
 * {@code dragOver} - a motion that arrives while the source still waits for a status is not sent at all;</li>
 * <li>in that {@code dragOver} the target releases the button through the robot ({@code XTestFakeButtonEvent} and
 * {@code XSync}) before {@code popFromSystem} sends {@code XConvertSelection}, so the source's queue holds the
 * {@code ButtonRelease} before the {@code SelectionRequest};</li>
 * <li>the source handles the release first - GDK's managed drag emits {@code drop-performed}, and GTK sends
 * {@code XdndDrop} - and then answers the {@code SelectionRequest}, so the target's queue holds the
 * {@code XdndDrop} before the {@code SELECTION_NOTIFY};</li>
 * <li>the target sends its {@code XdndFinished} after its {@code XConvertSelection}, so the source still owns the
 * selection when it answers.</li>
 * </ol>
 * The children wait for each other's marker files, never for a time; {@code drop.insideOverPop} proves that each
 * drop did run inside the read, so a GTK that ordered them otherwise fails these tests instead of passing them
 * vacuously.
 * <p>
 * Each drag has a target window of its own. {@code wait_for_selection_data_hook} reads a refused conversion from
 * the requestor's {@code GDK_SELECTION} property as it reads an answered one - GDK translates a
 * {@code SELECTION_NOTIFY} without a property into one that names the target ({@code gdk_x11_display_translate_event}),
 * so the hook's check for property 0 does not see the refusal - and neither that read
 * ({@code gdk_selection_property_get}) nor the hook deletes the property. On the window the first drag's text was
 * read on, the second drag's refused {@code UTF8_STRING} would answer that text again; on a window no conversion has
 * been answered on, it answers nothing.
 * <p>
 * The windows are moved to their places once shown and the robot aims at points their content covers there, so the
 * class runs with and without a window manager.
 */
@EnabledOnOs(OS.LINUX)
@Timeout(300)
public class GtkDnDReentrantDropTest {

    /** The system property naming the directory the two children exchange their marker files in. */
    static final String EXCHANGE_PROPERTY = "gtk.dnd.reentrant.exchange";

    /** The text the first drag's source offers. */
    static final String TEXT = "reentrant";

    static final int DRAGS = 2;

    /** How long the target waits for the read of its {@code dragOver}, which answers well within a second. */
    static final long READ_MILLIS = 20_000;

    /** How long a child waits for the other child's next marker, or for its own drag to reach a step. */
    static final long MARKER_MILLIS = 60_000;

    /**
     * How far right of and below its frame's position a window's content may start: the frame's insets under a
     * window manager, 0 without one. The robot's points lie 100 to 130 pixels right of and 100 to 110 below that
     * position, inside the {@code WIDTH} x {@code HEIGHT} content wherever within this slack it starts.
     */
    static final int PLACEMENT_SLACK = 50;

    /** How many rounds of {@link GtkEventTrace#settle} a window gets to reach its place once moved there. */
    static final int PLACEMENT_ROUNDS = 40;

    private static GtkGlassChildJvm.Run source;
    private static GtkGlassChildJvm.Run target;

    /**
     * Runs the two children at the same time. They are awaited without {@link GtkGlassChildJvm#runTogether}, which
     * fails the whole class when a child exits with an error: each test pins what the children recorded for its own
     * drag, and {@link #bothChildrenRanToTheEnd} their exits.
     */
    @BeforeAll
    @Timeout(2 * GtkGlassChildJvm.BEFORE_ALL_SECONDS)
    static void runScenario() {
        GtkGlassChildJvm.requireRobot();
        Path exchange = exchangeDirectory();
        List<String> options = List.of("-DUSE_ROBOT=true", "-D" + EXCHANGE_PROPERTY + "=" + exchange);
        GtkGlassChildJvm.Pending sourceChild = GtkGlassChildJvm.start(GtkDnDReentrantDropTest.class,
                "sourceScenario", options);
        GtkGlassChildJvm.Pending targetChild = null;
        try {
            targetChild = GtkGlassChildJvm.start(GtkDnDReentrantDropTest.class, "targetScenario", options);
            target = GtkGlassChildJvm.await(targetChild, GtkGlassChildJvm.CHILD_SECONDS);
            source = GtkGlassChildJvm.await(sourceChild, GtkGlassChildJvm.CHILD_SECONDS);
        } finally {
            destroy(sourceChild);
            destroy(targetChild);
        }
    }

    /** Kills {@code child} if it still runs, which it does only when the wait for the other child failed. */
    private static void destroy(GtkGlassChildJvm.Pending child) {
        if (child != null && child.process().isAlive()) {
            child.process().destroyForcibly();
        }
    }

    private static Path exchangeDirectory() {
        try {
            Path exchange = Path.of("target", "gtk-glass-child", "dnd-reentrant-exchange").toAbsolutePath();
            if (Files.isDirectory(exchange)) {
                try (Stream<Path> files = Files.list(exchange)) {
                    for (Path file : files.toList()) {
                        Files.delete(file);
                    }
                }
            }
            Files.createDirectories(exchange);
            return exchange;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String value(GtkGlassChildJvm.Run run, String key) {
        String value = run.values().get(key);
        if (value == null) {
            throw new AssertionError("the child recorded no " + key + ": " + run.describe());
        }
        return value;
    }

    /** Every drop ran inside the read of its target's {@code dragOver}: the tests below are not vacuous. */
    @Test
    public void everyDropRanInsideTheReadOfItsDragOver() {
        for (int drag = 1; drag <= DRAGS; drag++) {
            assertEquals("true", value(target, "drag" + drag + ".drop.insideOverPop"), "drag " + drag);
        }
    }

    /** The read whose wait the drop ended answers the text the source offered. */
    @Test
    public void aReadWhoseWaitADropEndsAnswersTheSourcesText() {
        assertEquals("true", value(target, "drag1.over.returned"), "drag1.over.returned");
        assertEquals(TEXT, value(target, "drag1.over.pop"), "drag1.over.pop");
    }

    /** A conversion that the same read starts after that drop answers no value. */
    @Test
    public void aConversionAfterADropInsideTheReadAnswersNoValue() {
        assertEquals("true", value(target, "drag2.over.returned"), "drag2.over.returned");
        assertEquals("null", value(target, "drag2.over.pop"), "drag2.over.pop");
    }

    /**
     * The drop inside the read ended the drag: once the read has returned the target natives throw the
     * {@code IllegalStateException} of {@code check_state_in_drag}, and the source's {@code dragEnd} reports the
     * action the target accepted.
     */
    @Test
    public void theDropInsideTheReadEndedTheDrag() {
        for (int drag = 1; drag <= DRAGS; drag++) {
            assertEquals(IllegalStateException.class.getName() + ": " + MESSAGE,
                    value(target, "drag" + drag + ".over.afterPop.mimes"), "drag " + drag);
            assertEquals("COPY", value(source, "drag" + drag + ".performed"), "drag " + drag);
        }
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
     * The drop target: answers {@code COPY} everywhere; its enter reads nothing, its armed {@code dragOver} releases
     * the button and reads {@code text/plain}, and its drop records only whether it ran inside that read. A read at
     * the drop would start a wait of its own, and the outer read's {@code SELECTION_NOTIFY}, queued first, would end
     * both waits before the drop had cleared the context: the case under test would not arise.
     */
    static final class ReentrantTargetHooks extends GtkEventTrace.ViewHooks {

        /** What the FX thread records; the scenario copies it into its map. */
        final Map<String, String> records = new ConcurrentHashMap<>();
        volatile GlassRobot robot;
        volatile String prefix;
        volatile boolean entered;
        volatile boolean armed;
        volatile boolean readReturned;
        private boolean inRead;

        @Override
        int dragAction(String phase, int recommended) {
            if ("enter".equals(phase)) {
                // runs once this upcall has returned and process_dnd_target_drag_motion has answered the enter
                Platform.runLater(() -> entered = true);
            } else if ("over".equals(phase) && armed) {
                armed = false;
                releaseAndRead();
            }
            return Clipboard.ACTION_COPY;
        }

        private void releaseAndRead() {
            records.put(prefix + "over.started", "true");
            robot.mouseRelease(MouseButton.PRIMARY);
            inRead = true;
            String read;
            try {
                read = outcome(() -> String.valueOf(GtkGlassShim.dndPopFromSystem("text/plain")));
            } finally {
                inRead = false;
            }
            records.put(prefix + "over.pop", read);
            records.put(prefix + "over.afterPop.mimes",
                    outcome(() -> "returned " + String.join("|", GtkGlassShim.dndMimesFromSystem())));
            readReturned = true;
        }

        @Override
        void dragDrop(ClipboardAssistance target) {
            records.put(prefix + "drop.insideOverPop", Boolean.toString(inRead));
            records.put(prefix + "dropped", "true");
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

    /** What the source of drag {@code drag} offers. */
    private static Map<String, Object> content(int drag) {
        return switch (drag) {
            case 1 -> Map.of("text/plain", TEXT);
            case 2 -> Map.of(CUSTOM_MIME, ByteBuffer.wrap(new byte[] {1, 2, 3}));
            default -> throw new IllegalArgumentException("drag " + drag);
        };
    }

    /**
     * Runs in {@link GtkGlassChild}: the drag source, w1. For each drag it presses, starts the drag and moves into
     * the target; the target does the rest. Stops early when the target says it failed.
     */
    static void sourceScenario(Map<String, String> out) throws Exception {
        Path exchange = Path.of(System.getProperty(EXCHANGE_PROPERTY));
        GtkEventTrace t = new GtkEventTrace();
        GtkDnDTargetTest.SourceHooks source = new GtkDnDTargetTest.SourceHooks();
        GtkTraceWindow w1 = window(t, "w1", "v1", LEFT_X, source);
        try {
            signal(exchange, "source.ready");
            await(exchange, "target.ready");
            for (int drag = 1; drag <= DRAGS; drag++) {
                String prefix = "drag" + drag + ".";
                source.content = content(drag);
                source.flushReturned = false;
                await(exchange, prefix + "target.armed");
                t.mouseMove(LEFT_X + 100, TOP + 100);
                t.mousePress(MouseButton.PRIMARY);
                t.mouseMove(LEFT_X + 110, TOP + 105);
                t.mouseMove(LEFT_X + 130, TOP + 110);
                t.mouseMove(RIGHT_X + 100, TOP + 100);
                if (!GtkGlassChild.waitFor(MARKER_MILLIS, () -> source.flushReturned)) {
                    throw new IllegalStateException(prefix + "flush: the source's flush did not return within 60 s");
                }
                t.settle();
                out.put(prefix + "performed", String.valueOf(source.performed));
                signal(exchange, prefix + "source.done");
                if (awaitEither(exchange, prefix + "target.done", prefix + "target.stop").endsWith(".stop")) {
                    break;
                }
            }
        } finally {
            t.act(w1::close);
        }
    }

    /**
     * Runs in {@link GtkGlassChild}: the drop target, a window of its own for each drag (w2, then w3; see the class
     * comment). For each drag it waits until it has answered the enter, moves the pointer over itself, and waits for
     * the read of that {@code dragOver}. When the read does not return it tells the source to stop and throws, and
     * {@link GtkGlassChild} writes what was recorded so far, with the FX thread still in the read.
     */
    static void targetScenario(Map<String, String> out) throws Exception {
        Path exchange = Path.of(System.getProperty(EXCHANGE_PROPERTY));
        ReentrantTargetHooks target = new ReentrantTargetHooks();
        String stop = null;
        try {
            // one window after the other, as in GtkDnDTraceTest: a window shown while the other process's is being
            // created is restacked
            await(exchange, "source.ready");
            GtkEventTrace t = new GtkEventTrace();
            target.robot = t.robot();
            signal(exchange, "target.ready");
            for (int drag = 1; drag <= DRAGS; drag++) {
                String prefix = "drag" + drag + ".";
                stop = prefix + "target.stop";
                GtkTraceWindow window = window(t, "w" + (drag + 1), "v" + (drag + 1), RIGHT_X, target);
                target.prefix = prefix;
                target.entered = false;
                target.readReturned = false;
                signal(exchange, prefix + "target.armed");
                if (!GtkGlassChild.waitFor(MARKER_MILLIS, () -> target.entered)) {
                    throw new IllegalStateException(prefix + "enter: the drag did not enter the target within 60 s");
                }
                target.armed = true;
                GtkGlassChild.onFx(() -> {
                    target.robot.mouseMove(RIGHT_X + 110, TOP + 110);
                    return null;
                });
                boolean returned = GtkGlassChild.waitFor(READ_MILLIS, () -> target.readReturned);
                target.records.put(prefix + "over.returned", Boolean.toString(returned));
                if (!returned) {
                    throw new IllegalStateException(prefix + "over: popFromSystem did not return within 20 s");
                }
                await(exchange, prefix + "source.done");
                t.settle();
                t.act(window::close);
                signal(exchange, prefix + "target.done");
                stop = null;
            }
        } finally {
            out.putAll(target.records);
            if (stop != null) {
                signal(exchange, stop);
            }
        }
    }

    /**
     * Creates and shows a window whose frame is at {@code x}, {@link GtkDnDTargetTest#TOP}, and waits until its
     * content is where the robot's points expect it. A window manager places a window it maps where it likes -
     * openbox at the top left of the screen - so the window is moved there again once shown, as
     * {@link GtkEventTraceTest} does; the content's origin, as the X server has it, must then lie at most
     * {@link #PLACEMENT_SLACK} pixels right of and below that point (the frame's insets). Throws, naming the origin,
     * when it does not get there.
     */
    private static GtkTraceWindow window(GtkEventTrace t, String name, String viewName, int x,
                                         GtkEventTrace.ViewHooks hooks) throws Exception {
        GtkTraceWindow window = GtkGlassChild.onFx(() -> t.window(name, null, Window.TITLED | Window.CLOSABLE,
                viewName, hooks));
        t.act(() -> window.setBounds(x, TOP, true, true, -1, -1, WIDTH, HEIGHT, 0, 0));
        t.act(() -> window.setVisible(true));
        t.act(() -> window.setPosition(x, TOP));
        int[] origin = origin(window);
        for (int round = 0; round < PLACEMENT_ROUNDS && !placed(origin, x); round++) {
            t.settle();
            origin = origin(window);
        }
        if (!placed(origin, x)) {
            throw new IllegalStateException(name + ": the content stays at " + origin[0] + "," + origin[1]
                    + ", not within " + PLACEMENT_SLACK + " px right of and below " + x + "," + TOP);
        }
        return window;
    }

    /** Where {@code window}'s content starts on the screen, as the X server has it. */
    private static int[] origin(GtkTraceWindow window) throws Exception {
        return GtkGlassChild.onFx(() -> GtkGlassShim.rootPosition(window.getNativeWindow()));
    }

    private static boolean placed(int[] origin, int x) {
        return origin[0] >= x && origin[0] <= x + PLACEMENT_SLACK
                && origin[1] >= TOP && origin[1] <= TOP + PLACEMENT_SLACK;
    }

    /** Creates {@code marker} in the exchange directory, for the other child. */
    private static void signal(Path exchange, String marker) throws IOException {
        Files.writeString(exchange.resolve(marker), "1");
    }

    /** Waits up to {@link #MARKER_MILLIS} for the other child to create {@code marker}. */
    private static void await(Path exchange, String marker) throws Exception {
        if (!GtkGlassChild.waitFor(MARKER_MILLIS, () -> Files.isRegularFile(exchange.resolve(marker)))) {
            throw new IllegalStateException("the other child JVM did not create " + marker + " within 60 s");
        }
    }

    /** Waits up to {@link #MARKER_MILLIS} for the other child to create either marker; answers the one it made. */
    private static String awaitEither(Path exchange, String first, String second) throws Exception {
        if (!GtkGlassChild.waitFor(MARKER_MILLIS, () -> Files.isRegularFile(exchange.resolve(first))
                || Files.isRegularFile(exchange.resolve(second)))) {
            throw new IllegalStateException("the other child JVM created neither " + first + " nor " + second
                    + " within 60 s");
        }
        return Files.isRegularFile(exchange.resolve(first)) ? first : second;
    }
}
