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

package test.com.sun.media.jfxmediaimpl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static test.com.sun.media.jfxmediaimpl.MediaTestHooks.SPECTRUM_BANDS_WRITTEN;
import static test.com.sun.media.jfxmediaimpl.MediaTestHooks.SPECTRUM_EVENTS_SENT;
import static test.com.sun.media.jfxmediaimpl.MediaTestHooks.SPECTRUM_STALE_DROPPED;
import static test.com.sun.media.jfxmediaimpl.MediaTestHooks.counter;

/**
 * Changing the spectrum's band count while audio plays: the bus handler sizes what it reads from the message it
 * received, never from the band count Java last set, and hands Java only band sets it wrote in full.
 * <p>
 * A {@code spectrum} message carries one magnitude and one phase per band of the count the element had when it
 * computed them, and the bus thread handles it some time later. A count raised in between used to have the
 * handler read past the end of the message's lists and deliver bands that were never computed: zeros, since the
 * lookup past the end gives no value and {@code g_value_get_float} answers 0 for it, and a GLib critical for each,
 * where GLib reports one. Now a message whose count is not that of the arrays installed when it is handled is
 * dropped as stale: nothing is written and no event is sent.
 * <p>
 * Both tests play {@link SineWav}'s tone in a child JVM ({@link SpectrumBandChangeProbe}), whose output is where
 * GLib's criticals land, and change the count for {@value #ROUND_MILLIS} ms a round, {@value #PLANNED_ROUNDS}
 * rounds at least, lowering it and raising it again at once, and ending on a raise. Each round has to end with a
 * delivery of full band arrays: the arrays of the new count, whose strongest band is the one the tone falls in for
 * that count. No delivery may hold a band of 0 dB or more, which no message carries and a read past the end of a
 * message's list writes. One test runs against the shipped library and one against the hooks build, which counts
 * in C what Java cannot tell apart: the messages dropped as stale, the events sent, and the band sets written. It
 * requires the stale case to have happened at least {@value #STALE_TARGET} times, so the drop was really exercised,
 * and no more events than band sets written, so no event was sent for a message that was dropped.
 * <p>
 * The critical is only half the evidence because only Linux, which links the system GLib, prints it. The GLib
 * bundled for Windows and macOS is built with {@code GSTREAMER_LITE}, under which {@code g_return_val_if_fail}
 * returns without logging ({@code 3rd_party/glib/glib/gmessages.h}), so there the read past the end is silent and
 * the zero bands are what it leaves.
 * <p>
 * Where the audio output is paced, the stale case is a race the bus thread nearly always wins by itself: measured
 * over 20 runs on Windows, plain paced changes found a stale message in 18 runs and none in 2, almost all of them in
 * the first round, while the bus thread's upcall was still cold. The probe therefore makes it: it holds the bus
 * thread off with full collections while a lowering is kept waiting by the element, so that the messages computed
 * for the lowered count pile up and the raise that follows finds them queued ({@link SpectrumBandChangeProbe} has
 * the mechanism). A round of {@value #ROUND_MILLIS} ms outlasts the quarter of a second between two bursts of
 * messages, so every round meets one; Windows makes some 200 changes in it and Linux, whose null ALSA device lets
 * the element post all the time, about 30.
 * <p>
 * Neither asserts that no delivery finds the arrays unwritten or partly written. Java reads the arrays of the
 * current count while C may be writing the first message into them, or may not have written one yet, and a
 * listener running then sees the seed values or a mix; that residue is inherent to arrays shared without a lock,
 * and upstream has it too.
 * <p>
 * Like {@link MediaPlaybackTest}, these need an audio output, and on a machine without one they are skipped by
 * {@link AudioOutput#reportNoPlayer} after the no-device path has been checked.
 */
public class SpectrumBandChangeTest {

    /** The spectrum interval the probe plays with, in seconds. */
    private static final double INTERVAL_SECONDS = 0.001;

    /** How many rounds of changes the probe plays at least. */
    private static final int PLANNED_ROUNDS = 5;

    /** How long a round changes the band count for. */
    private static final long ROUND_MILLIS = 400L;

    /** How many messages the hooks build has to have dropped as stale. */
    private static final int STALE_TARGET = 2;

    /** How long the hooks probe keeps adding rounds for when it has not reached {@link #STALE_TARGET}. */
    private static final long STALE_TIME_LIMIT_MILLIS = 20_000L;

    /**
     * How long a child gets before it is killed; above the probe's own bounds. Nothing else bounds these tests:
     * the surefire configuration of javafx.media sets neither a JUnit timeout nor a fork timeout.
     */
    private static final long CHILD_TIMEOUT_SECONDS = 100L;

    /** {@link SpectrumBandChangeProbe}'s bus stall needs {@code System.gc()} to be a stop-the-world collection. */
    private static final List<String> STOP_THE_WORLD_GC =
            List.of("-XX:-DisableExplicitGC", "-XX:-ExplicitGCInvokesConcurrent");

    /**
     * What a GLib critical looks like in the child's output. Once the platform is up, jfxmedia's GLib log handler
     * (GstMediaManager::Init) turns every GLib critical and error into an ERROR of the media log, printed as
     * {@code "Error (<time>): <GLib's text>"}; before that, GLib prints it itself, as
     * {@code "<domain>-CRITICAL **: ..."}. The playback here logs no error of its own, so any ERROR line fails.
     */
    private static final List<String> CRITICAL_MARKERS = List.of("Error (", "-CRITICAL **");

    @Test
    void theShippedLibraryDeliversFullBandsWithoutACriticalWhenTheCountChanges(@TempDir Path dir)
            throws IOException {
        MediaNatives.require();
        Path shipped = MediaTestHooks.nativeBinDir();

        ChildJvm.Run run = play(dir, "shipped", shipped.toString(), 0);

        MediaTestHooks.assertLoaded(run, shipped, false);
        requirePlayer(run, dir);
        assertNoCritical(run);
        assertNoBandNoMessageCarries(run);
        assertEveryRoundDelivered(run);
        assertRegistryBalanced(run);
    }

    @Test
    void theHooksBuildDropsStaleMessagesAndSendsNoEventWithoutAWrite(@TempDir Path dir) throws IOException {
        Path hooks = MediaTestHooks.requireHooksBuild();

        ChildJvm.Run run = play(dir, "hooks", MediaTestHooks.hooksLibraryPath(hooks), STALE_TARGET);

        MediaTestHooks.assertLoaded(run, hooks, true);
        requirePlayer(run, dir);
        assertNoCritical(run);
        assertNoBandNoMessageCarries(run);
        int stale = counter(run, SPECTRUM_STALE_DROPPED);
        int events = counter(run, SPECTRUM_EVENTS_SENT);
        int written = counter(run, SPECTRUM_BANDS_WRITTEN);
        assertTrue(stale >= STALE_TARGET, () -> "stale case not reached: the bus handler dropped " + stale
                + " messages as stale within " + STALE_TIME_LIMIT_MILLIS + " ms of changes, fewer than "
                + STALE_TARGET + ", so the drop was not exercised:" + run.describe());
        assertEveryRoundDelivered(run);
        assertTrue(written > 0, () -> "no band set was written:" + run.describe());
        assertTrue(events <= written, () -> events + " spectrum events were sent and only " + written
                + " band sets written, so an event went out for a message that wrote nothing:" + run.describe());
        assertRegistryBalanced(run);
    }

    private static ChildJvm.Run play(Path dir, String mode, String libraryPath, int staleTarget) {
        ChildJvm.Run run = ChildJvm.run(SpectrumBandChangeProbe.class, STOP_THE_WORLD_GC, libraryPath, dir, mode,
                CHILD_TIMEOUT_SECONDS, List.of(mode, noPlayerLog(dir).toString(), Double.toString(INTERVAL_SECONDS),
                        Integer.toString(PLANNED_ROUNDS), Long.toString(ROUND_MILLIS),
                        Integer.toString(staleTarget), Long.toString(STALE_TIME_LIMIT_MILLIS)));
        System.out.println(mode + " spectrum run: " + summary(run));
        assertTrue(run.clean(), () -> "the child that plays the tone failed:" + run.describe());
        assertEquals("true", run.value("done"), () -> "the child did not run to its end:" + run.describe());
        return run;
    }

    /** One line of what the child reported, for the test's own output. */
    private static String summary(ChildJvm.Run run) {
        List<String> changes = IntStream.range(0, PLANNED_ROUNDS)
                .mapToObj(round -> run.value("round." + round + ".changes"))
                .toList();
        return run.value("rounds") + " rounds of " + changes + " changes, " + run.value("bus.stalls")
                + " bus stalls, " + run.value("deliveries") + " deliveries (" + run.value("deliveries.impossible")
                + " with a band no message carries), stale "
                + run.value("counter." + SPECTRUM_STALE_DROPPED) + ", events "
                + run.value("counter." + SPECTRUM_EVENTS_SENT) + ", written "
                + run.value("counter." + SPECTRUM_BANDS_WRITTEN) + ", " + criticals(run).size()
                + " critical lines, schedule " + run.value("schedule.millis") + " ms, child " + run.millis() + " ms";
    }

    /**
     * Returns when the child built a player. When it could not, asserts what {@link MediaPlaybackTest} asserts of
     * that path and then skips or fails as {@link AudioOutput#reportNoPlayer} decides from the creation log.
     */
    private static void requirePlayer(ChildJvm.Run run, Path dir) throws IOException {
        if ("none".equals(run.value("player"))) {
            assertRegistryBalanced(run);
            AudioOutput.reportNoPlayer(Files.readString(noPlayerLog(dir), StandardCharsets.UTF_8));
        }
        assertEquals("created", run.value("player"), () -> "the child reported no player:" + run.describe());
    }

    private static void assertNoCritical(ChildJvm.Run run) {
        List<String> criticals = criticals(run);
        assertTrue(criticals.isEmpty(), () -> criticals.size() + " lines of the child's output hold one of "
                + CRITICAL_MARKERS + ", the first: " + criticals.get(0) + ":" + run.describe());
    }

    private static void assertNoBandNoMessageCarries(ChildJvm.Run run) {
        assertEquals("0", run.value("deliveries.impossible"), () -> run.value("deliveries.impossible")
                + " deliveries held a magnitude of 0 dB or more, which no spectrum message carries and a read past"
                + " the end of a message's list writes; the first was band " + run.value("deliveries.impossible.first")
                + ":" + run.describe());
    }

    private static List<String> criticals(ChildJvm.Run run) {
        return run.output().lines()
                .filter(line -> CRITICAL_MARKERS.stream().anyMatch(line::contains))
                .toList();
    }

    private static void assertEveryRoundDelivered(ChildJvm.Run run) {
        assertEquals("false", run.value("player.finished"), () -> "the tone ran out before the changes did:"
                + run.describe());
        assertEquals("[]", run.value("player.failures"), () -> "the player reported an error or a halt:"
                + run.describe());
        int rounds = Integer.parseInt(run.value("rounds"));
        for (int round = 0; round < rounds; round++) {
            int failed = round;
            assertEquals("true", run.value("round." + round + ".delivered"), () -> "round " + failed + " got no"
                    + " delivery of full band arrays of its last count: " + run.value("round." + failed) + ":"
                    + run.describe());
        }
        assertTrue(rounds >= PLANNED_ROUNDS, () -> rounds + " rounds were played, fewer than the "
                + PLANNED_ROUNDS + " planned:" + run.describe());
    }

    private static void assertRegistryBalanced(ChildJvm.Run run) {
        assertEquals(run.value("registry.baseline"), run.value("registry.after"), () -> "the player left a"
                + " callback registry entry behind:" + run.describe());
    }

    private static Path noPlayerLog(Path dir) {
        return dir.resolve("no-player-log.txt");
    }
}
