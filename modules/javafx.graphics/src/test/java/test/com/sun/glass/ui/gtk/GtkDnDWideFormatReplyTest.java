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
import java.nio.ByteOrder;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static test.com.sun.glass.ui.gtk.GtkDnDReentrantDropTest.EXCHANGE_PROPERTY;
import static test.com.sun.glass.ui.gtk.GtkDnDReentrantDropTest.MARKER_MILLIS;
import static test.com.sun.glass.ui.gtk.GtkDnDReentrantDropTest.await;
import static test.com.sun.glass.ui.gtk.GtkDnDReentrantDropTest.destroy;
import static test.com.sun.glass.ui.gtk.GtkDnDReentrantDropTest.exchangeDirectory;
import static test.com.sun.glass.ui.gtk.GtkDnDReentrantDropTest.signal;
import static test.com.sun.glass.ui.gtk.GtkDnDReentrantDropTest.value;
import static test.com.sun.glass.ui.gtk.GtkDnDReentrantDropTest.window;
import static test.com.sun.glass.ui.gtk.GtkDnDRefusedConversionTest.DROP_MILLIS;
import static test.com.sun.glass.ui.gtk.GtkDnDTargetTest.RIGHT_X;
import static test.com.sun.glass.ui.gtk.GtkDnDTargetTest.TOP;

/**
 * A drop-target read of a 32-bit-format reply answers the reply's bytes. {@code gdk_selection_property_get}, whose
 * answer {@code wait_for_selection_data_hook} stores, already counts bytes:
 * {@code _gdk_x11_display_get_selection_property} counts a 32-bit item as the C {@code long} Xlib hands it on as
 * ({@code XGetWindowProperty}), 8 bytes on LP64. Commit {@code 033187ad90}'s {@code dnd_target_get_raw} multiplied
 * that count by {@code format / 8} again and so read a 32-bit reply at four times its size, past the end of its
 * block.
 * <p>
 * A Glass source sends its data with format 8, but GTK answers a conversion larger than
 * {@code GTK_SELECTION_MAX_SIZE} (at most 262,144 bytes on X11) with an INCR header instead
 * ({@code _gtk_selection_request}): type {@code INCR}, format 32, one item, the data's size. The source of one drag
 * offers {@link #SMALL_MIME}, a few bytes, and {@link #LARGE_MIME}, {@link #LARGE_SIZE} bytes. At the drop the target
 * reads the small one, a format-8 reply whose read the multiplication never changed, then the large one, which
 * answers the header: {@link #LARGE_SIZE} as a native-order {@code long}. Commit {@code 033187ad90} answered 32 bytes
 * there, the header, the block's terminating NUL and 23 bytes beyond the block. Glass reads no INCR transfer, and GTK
 * sends the data only as the requestor deletes the property, so the large read is the drop's last conversion.
 * <p>
 * Source and target run in two child JVMs, with the drag source ({@link GtkDnDReentrantDropTest#dragSource}), the
 * marker handshake and the window placement of {@link GtkDnDReentrantDropTest}. The target releases the button once
 * it has answered the {@code dragOver} of its own pointer move, so the drop is an ordinary one. The class runs with
 * and without a window manager.
 */
@EnabledOnOs(OS.LINUX)
@Timeout(300)
public class GtkDnDWideFormatReplyTest {

    /** The mime the source offers a few bytes as, which GTK sends as a format-8 reply. */
    static final String SMALL_MIME = "application/x-jfx-dnd-small";

    /** The mime the source offers {@link #LARGE_SIZE} bytes as, which GTK answers with an INCR header. */
    static final String LARGE_MIME = "application/x-jfx-dnd-large";

    /** More than the 262,144 bytes {@code GTK_SELECTION_MAX_SIZE} allows at most in one property. */
    static final int LARGE_SIZE = 300_000;

    /** The bytes the source offers as {@link #SMALL_MIME}. */
    private static final byte[] SMALL_BYTES = {11, 22, 33};

    /** The mimes of the drag as the target sees them, sorted: Glass offers a custom mime as the target of its name. */
    static final String MIMES = LARGE_MIME + "|" + SMALL_MIME;

    /** How many of a buffer's bytes {@link #describe} writes; it always writes their count. */
    static final int SHOWN_BYTES = 64;

    /** What a read that answers no data records. */
    static final String NO_DATA = "null";

    /** The small read's answer as {@link #describe} writes it: the source's bytes. */
    static final String SMALL_READ = "ByteBuffer 3 [11, 22, 33]";

    /**
     * The large read's answer as {@link #describe} writes it: the INCR header's one item, {@link #LARGE_SIZE}, as the
     * 8 bytes of a native-order {@code long} - {@code [-32, -109, 4, 0, 0, 0, 0, 0]} on little-endian x86_64 and
     * aarch64.
     */
    static final String INCR_HEADER_READ =
            describe(ByteBuffer.allocate(Long.BYTES).order(ByteOrder.nativeOrder()).putLong(0, LARGE_SIZE));

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
        Path exchange = exchangeDirectory("dnd-wide-format-reply-exchange");
        List<String> options = List.of("-DUSE_ROBOT=true", "-D" + EXCHANGE_PROPERTY + "=" + exchange);
        GtkGlassChildJvm.Pending sourceChild = GtkGlassChildJvm.start(GtkDnDWideFormatReplyTest.class,
                "sourceScenario", options);
        GtkGlassChildJvm.Pending targetChild = null;
        try {
            targetChild = GtkGlassChildJvm.start(GtkDnDWideFormatReplyTest.class, "targetScenario", options);
            target = GtkGlassChildJvm.await(targetChild, GtkGlassChildJvm.CHILD_SECONDS);
            source = GtkGlassChildJvm.await(sourceChild, GtkGlassChildJvm.CHILD_SECONDS);
        } finally {
            destroy(sourceChild);
            destroy(targetChild);
        }
    }

    /** The drag was dropped on the target and ended with the action the target answered. */
    @Test
    public void theDragWasDropped() {
        assertEquals("true", value(target, "drag1.dropped"), "drag1.dropped");
        assertEquals("COPY", value(source, "drag1.performed"), "drag1.performed");
    }

    /** The target sees both mimes the source offers, so both reads below converted a target the source has. */
    @Test
    public void theTargetSeesBothMimes() {
        assertEquals(MIMES, value(target, "drag1.drop.mimes"), "drag1.drop.mimes");
    }

    /** A read of a format-8 reply answers the source's bytes. */
    @Test
    public void aFormat8ReplyAnswersItsBytes() {
        assertEquals(SMALL_READ, value(target, "drag1.drop.small"), "drag1.drop.small");
    }

    /** A read of a format-32 reply, the INCR header, answers its 8 bytes and no more. */
    @Test
    public void aFormat32ReplyAnswersItsBytes() {
        assertEquals(INCR_HEADER_READ, value(target, "drag1.drop.large"), "drag1.drop.large");
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
     * The drop target: answers {@code COPY} everywhere and, at the drop, records the mimes and reads
     * {@link #SMALL_MIME}, then {@link #LARGE_MIME}.
     */
    static final class WideFormatTargetHooks extends GtkEventTrace.ViewHooks {

        /** What the FX thread records; the scenario copies it into its map. */
        final Map<String, String> records = new ConcurrentHashMap<>();
        volatile boolean entered;
        volatile boolean armed;
        volatile boolean overAnswered;
        volatile boolean dropped;

        @Override
        int dragAction(String phase, int recommended) {
            if ("enter".equals(phase)) {
                // runs once this upcall has returned and process_dnd_target_drag_motion has answered the enter
                Platform.runLater(() -> entered = true);
            } else if ("over".equals(phase) && armed) {
                armed = false;
                Platform.runLater(() -> overAnswered = true);
            }
            return Clipboard.ACTION_COPY;
        }

        @Override
        void dragDrop(ClipboardAssistance assistance) {
            records.put("drag1.drop.mimes", outcome(() -> {
                String[] mimes = GtkGlassShim.dndMimesFromSystem().clone();
                Arrays.sort(mimes);
                return String.join("|", mimes);
            }));
            records.put("drag1.drop.small", outcome(() -> describe(GtkGlassShim.dndPopFromSystem(SMALL_MIME))));
            records.put("drag1.drop.large", outcome(() -> describe(GtkGlassShim.dndPopFromSystem(LARGE_MIME))));
            records.put("drag1.dropped", "true");
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

    /**
     * A read's value: {@code null}, or its class's simple name and its content - a buffer's remaining byte count and
     * at most {@link #SHOWN_BYTES} of those bytes.
     */
    private static String describe(Object value) {
        return switch (value) {
            case null -> NO_DATA;
            case ByteBuffer buffer -> {
                byte[] bytes = new byte[buffer.remaining()];
                buffer.duplicate().get(bytes);
                String shown = Arrays.toString(Arrays.copyOf(bytes, Math.min(bytes.length, SHOWN_BYTES)));
                yield "ByteBuffer " + bytes.length + " " + shown + (bytes.length > SHOWN_BYTES ? " ..." : "");
            }
            default -> value.getClass().getSimpleName() + " " + value;
        };
    }

    /** What the source of the drag offers. */
    private static Map<String, Object> content(int drag) {
        byte[] large = new byte[LARGE_SIZE];
        for (int i = 0; i < large.length; i++) {
            large[i] = (byte) (i % 251);
        }
        return Map.of(SMALL_MIME, ByteBuffer.wrap(SMALL_BYTES.clone()), LARGE_MIME, ByteBuffer.wrap(large));
    }

    /** Runs in {@link GtkGlassChild}: the drag source of the one drag. */
    static void sourceScenario(Map<String, String> out) throws Exception {
        GtkDnDReentrantDropTest.dragSource(out, 1, GtkDnDWideFormatReplyTest::content);
    }

    /**
     * Runs in {@link GtkGlassChild}: the drop target, window w2. It waits until it has answered the enter, moves the
     * pointer over itself, waits until it has answered that {@code dragOver}, releases the button and waits for the
     * reads of the drop. When a step does not come it tells the source to stop and throws, and {@link GtkGlassChild}
     * writes what was recorded so far.
     */
    static void targetScenario(Map<String, String> out) throws Exception {
        Path exchange = Path.of(System.getProperty(EXCHANGE_PROPERTY));
        WideFormatTargetHooks target = new WideFormatTargetHooks();
        String stop = null;
        try {
            // one window after the other, as in GtkDnDReentrantDropTest
            await(exchange, "source.ready");
            GtkEventTrace t = new GtkEventTrace();
            GlassRobot robot = t.robot();
            signal(exchange, "target.ready");
            stop = "drag1.target.stop";
            GtkTraceWindow window = window(t, "w2", "v2", RIGHT_X, target);
            signal(exchange, "drag1.target.armed");
            if (!GtkGlassChild.waitFor(MARKER_MILLIS, () -> target.entered)) {
                throw new IllegalStateException("drag1.enter: the drag did not enter the target within 60 s");
            }
            target.armed = true;
            GtkGlassChild.onFx(() -> {
                robot.mouseMove(RIGHT_X + 110, TOP + 110);
                return null;
            });
            if (!GtkGlassChild.waitFor(MARKER_MILLIS, () -> target.overAnswered)) {
                throw new IllegalStateException("drag1.over: the target answered no dragOver within 60 s");
            }
            GtkGlassChild.onFx(() -> {
                robot.mouseRelease(MouseButton.PRIMARY);
                return null;
            });
            if (!GtkGlassChild.waitFor(DROP_MILLIS, () -> target.dropped)) {
                throw new IllegalStateException("drag1.drop: the reads of the drop did not return within 20 s");
            }
            await(exchange, "drag1.source.done");
            t.settle();
            signal(exchange, "drag1.target.done");
            stop = null;
            t.act(window::close);
        } finally {
            out.putAll(target.records);
            if (stop != null) {
                signal(exchange, stop);
            }
        }
    }
}
