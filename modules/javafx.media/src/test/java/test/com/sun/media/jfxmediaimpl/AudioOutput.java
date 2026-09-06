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

import com.sun.media.jfxmedia.MediaPlayer;
import com.sun.media.jfxmedia.locator.Locator;
import com.sun.media.jfxmedia.logging.Logger;
import com.sun.media.jfxmediaimpl.JfxMediaNative;
import com.sun.media.jfxmediaimpl.platform.gstreamer.GSTPlatform;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.abort;

/**
 * The one environmental decision the media tests make - <em>does this machine have an audio output?</em> -
 * and everything needed to make it with evidence rather than by guess. Its counterpart {@link MediaNatives}
 * makes the build decision and deliberately says nothing about hardware; this class is where the hardware
 * question lives, and it is the only place either question is answered.
 * <p>
 * The question cannot be avoided and it cannot be answered before the fact. {@code jfxm_player_init} takes
 * the pipeline to {@code PAUSED}, and {@code CGstPipelineFactory::CreateAudioSinkElement} hardcodes
 * {@code directsoundsink}, {@code osxaudiosink} or {@code alsasink} with no null sink to fall back to, so
 * on a machine with no audio output the sink's device open fails during {@code NULL -> READY}, the state
 * change fails, {@code GSTMediaPlayer}'s constructor throws {@code MediaException} and
 * {@code GSTPlatform.createMediaPlayer} swallows it, logs it at DEBUG and returns null. That is
 * pre-existing upstream behaviour and no test here is the place to change it.
 * <p>
 * <b>What that costs, precisely.</b> Nothing downstream of the sink's open happens: no preroll, so no
 * Ready, no {@code audio_track}, no {@code duration_update}, no {@code audio_spectrum}, no end of stream,
 * and no {@code AudioClip} playback either - {@code NativeMediaAudioClipPlayer.play} builds a player per
 * {@code AudioClip.play()} through the same path. So a machine with no audio output can cover the whole of
 * {@code jfxm_media_create} and none of {@code jfxm_player_*}. The {@code player_state},
 * {@code audio_track}, {@code duration_update} and {@code audio_spectrum} slots of
 * {@code JfxmPlayerCallbacks} have no other coverage anywhere in this module; a skip here means the fork
 * has not proven that any of the four lands on the target the Java layout says it does.
 * <p>
 * <b>Where it is not skipped.</b> Both Linux CI jobs of {@code .github/workflows/submit.yml} write a null
 * ALSA device into {@code ~/.asoundrc} before the build for exactly this reason, so {@code alsasink} opens
 * and every one of those tests runs there. A developer machine with a sound card runs them too. What is
 * left is a CI runner with no device and no null device configured - Windows and macOS today.
 * <p>
 * A null player is therefore tolerated, but only against the log that explains it: the media log is
 * captured around the call, and {@link #reportNoPlayer} fails on anything that names a broken build,
 * skips on the wording of a sink that could not open its device, and fails on anything else. All three
 * carry the whole captured log, because a skip that cannot be explained is as bad as a failure that
 * cannot be.
 */
final class AudioOutput {

    /**
     * How long a failed player creation gets to explain itself. The GStreamer error that explains it is
     * posted on the pipeline's bus and logged from the bus thread, so it can arrive just after
     * {@code createMediaPlayer} has returned null.
     */
    private static final long LOG_GRACE_MILLIS = 5_000L;

    /**
     * What a captured media log looks like when the platform could not open its audio output. GStreamer
     * reports that as an ERROR message carrying the sink's own text, followed by a DEBUG message naming
     * the element that failed, so both the wording and the element name are worth looking for: the
     * wording differs per platform (ALSA says "Could not open audio device for playback",
     * {@code directsoundsink} reports whatever {@code DirectSoundCreate} returned) while the element
     * name is always in the debug line.
     */
    private static final List<String> NO_AUDIO_OUTPUT_MARKERS =
            List.of("audiosink", "alsasink", "directsoundsink", "could not open audio device");

    /**
     * What a captured log says when the reason for a null player is the build and not the machine.
     * Any of these forces a failure, and is checked before {@link #NO_AUDIO_OUTPUT_MARKERS}, because
     * every one of them can also put a sink element's name in the log and so satisfy that list on its
     * own: the first five are the facade failing to bind at all, and the rest are pipeline construction
     * failures - an element factory {@code gstreamer-lite} or {@code fxplugins} never registered, a bin
     * that would not take an element, a link that would not hold. No absent sound card can cause any of
     * them; a {@code fxplugins} that did not build causes the sixth and seventh, which is how this list
     * was arrived at.
     */
    private static final List<String> BROKEN_BUILD_MARKERS = List.of(
            "unsatisfiedlinkerror",
            "missing native symbol",
            "was not granted native access",
            "noclassdeffounderror",
            "exceptionininitializererror",
            "no such element factory",
            "error_gstreamer_element_create",
            "error_gstreamer_audio_sink_create",
            "error_gstreamer_video_sink_create",
            "error_gstreamer_bin_add_element",
            "error_gstreamer_element_link",
            "error_manager_engineinit_fail");

    private AudioOutput() {
    }

    /** What {@link #createPlayer} saw: the player, {@code null} when none was built, and the log. */
    record Attempt(MediaPlayer player, String log) {
    }

    /**
     * Creates a player with the media log turned up to DEBUG and captured, so that a null player can be
     * told apart from a broken one.
     * <p>
     * Both streams are captured because {@link Logger} splits them: ERROR and WARNING go to
     * {@code System.err} and INFO and DEBUG to {@code System.out}, and the explanation for a failed
     * player arrives one part on each - the sink's own error message on {@code System.err}, and on
     * {@code System.out} the GStreamer debug line naming the element that failed together with
     * {@code GSTPlatform}'s note that it caught the exception.
     * <p>
     * The native level is set through the facade rather than through {@code Logger.setLevel}, which only
     * reaches C once {@code Logger.initNative()} has run: these tests drive the facade directly and
     * never build a {@code NativeMediaManager}. Both levels and both streams are restored afterwards.
     *
     * @param locator the media to build a player over
     * @return the player and the log; {@link Attempt#player()} is null when none was built
     */
    static Attempt createPlayer(Locator locator) {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream out = System.out;
        PrintStream err = System.err;
        int level = loggerLevel();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        Logger.setLevel(Logger.DEBUG);
        JfxMediaNative.logSetLevel(Logger.DEBUG);
        try {
            MediaPlayer player = GSTPlatform.getPlatformInstance().createMediaPlayer(locator);
            if (player == null) {
                awaitNativeError(captured);
            }
            return new Attempt(player, captured.toString(StandardCharsets.UTF_8));
        } finally {
            JfxMediaNative.logSetLevel(Logger.OFF);
            Logger.setLevel(level);
            System.setErr(err);
            System.setOut(out);
        }
    }

    /**
     * Never returns: fails when the captured log names a broken build, aborts when it shows the platform
     * could not open its audio output, and fails otherwise. The broken-build test comes first, so that
     * no build failure can leave here as a skip.
     *
     * @param log the log {@link #createPlayer} captured
     */
    static void reportNoPlayer(String log) {
        String detail = "GSTPlatform.createMediaPlayer returned null; the captured media log was:\n" + log;
        String broken = brokenBuildMarker(log);
        if (broken != null) {
            fail("the media natives are broken, not this machine: the log names \"" + broken + "\", "
                    + "which no missing audio device can cause. " + detail);
        }
        if (reportsNoAudioOutput(log)) {
            abort("this machine has no audio output, so GStreamer could not build a playback pipeline. "
                    + "NOT COVERED BY THIS RUN: every jfxm_player_* entry point, and with it the "
                    + "player_state, audio_track, duration_update and audio_spectrum slots of "
                    + "JfxmPlayerCallbacks, which nothing else in this module reaches - no preroll, no "
                    + "Ready, no decoded sample, no end of stream, no AudioClip cycle. Still covered "
                    + "here: jfxm_media_create and all nine JfxmStreamCallbacks slots, by "
                    + "JfxMediaNativeTest.mediaOverATinyWavFileDisposesWithoutLeaks and its neighbours. "
                    + "Covered elsewhere: both Linux CI jobs, which install a null ALSA device before "
                    + "the build (.github/workflows/submit.yml) and so run everything skipped here. "
                    + detail);
        }
        fail(detail);
    }

    /**
     * Whether the captured log says the platform could not open its audio output, which is the one
     * reason for a null player that is not a regression. Both halves have to be there: the swallowed
     * exception, so that this cannot match a log left over from something else, and one of the
     * {@link #NO_AUDIO_OUTPUT_MARKERS}, so that a null player for any other reason - a failing
     * {@code jfxm_media_create} above all, which reports a {@code MediaError} and no sink - is not
     * quietly skipped.
     *
     * @param log the log {@link #createPlayer} captured
     * @return whether the log blames the machine's audio output
     */
    static boolean reportsNoAudioOutput(String log) {
        String lower = log.toLowerCase(Locale.ROOT);
        if (!lower.contains("caught exception while creating media player")) {
            return false;
        }
        return NO_AUDIO_OUTPUT_MARKERS.stream().anyMatch(lower::contains);
    }

    /** The level {@link Logger} is currently at, which it does not otherwise report. */
    static int loggerLevel() {
        for (int level : new int[] { Logger.DEBUG, Logger.INFO, Logger.WARNING, Logger.ERROR }) {
            if (Logger.canLog(level)) {
                return level;
            }
        }
        return Logger.OFF;
    }

    /**
     * Waits, for at most {@link #LOG_GRACE_MILLIS}, for the bus thread to log the error that explains a
     * null player. Returning early when it never comes is fine: the caller reports the log it has, and
     * an unexplained null player is a failure. It returns early for a broken build too, so that one
     * fails at once rather than after the whole grace period.
     */
    private static void awaitNativeError(ByteArrayOutputStream captured) {
        long deadline = System.currentTimeMillis() + LOG_GRACE_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            String log = captured.toString(StandardCharsets.UTF_8);
            if (reportsNoAudioOutput(log) || brokenBuildMarker(log) != null) {
                return;
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * The first {@link #BROKEN_BUILD_MARKERS} entry the log carries, or {@code null} when it carries
     * none.
     */
    private static String brokenBuildMarker(String log) {
        String lower = log.toLowerCase(Locale.ROOT);
        return BROKEN_BUILD_MARKERS.stream().filter(lower::contains).findFirst().orElse(null);
    }
}
