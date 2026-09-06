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

import com.sun.media.jfxmedia.Media;
import com.sun.media.jfxmedia.MediaPlayer;
import com.sun.media.jfxmedia.effects.AudioSpectrum;
import com.sun.media.jfxmedia.events.AudioSpectrumEvent;
import com.sun.media.jfxmedia.events.AudioSpectrumListener;
import com.sun.media.jfxmedia.events.MediaErrorListener;
import com.sun.media.jfxmedia.events.PlayerStateEvent;
import com.sun.media.jfxmedia.events.PlayerStateEvent.PlayerState;
import com.sun.media.jfxmedia.events.PlayerStateListener;
import com.sun.media.jfxmedia.locator.Locator;
import com.sun.media.jfxmedia.track.AudioTrack;
import com.sun.media.jfxmedia.track.Track;
import com.sun.media.jfxmediaimpl.JfxMediaNative;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.scene.media.AudioClip;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The tests that play media. Everything else in this module proves that the Java side of the
 * {@code jfxm_*} ABI agrees with itself and with {@code jfxmedia_api.h}: symbols resolve, layouts match
 * {@code sizeof} and {@code offsetof}, descriptors match their upcall targets, arenas and the registry
 * balance. None of that can prove that the library decodes a sample, or that a callback arrives at the
 * Java method the layout says it does. These two tests are where a real GStreamer pipeline runs a real
 * file to its end, and they are the only ones in the fork that can fail because a callback landed on the
 * wrong function pointer.
 * <p>
 * That failure mode is why the assertions are on <em>content</em> rather than on arrival.
 * {@code JfxmPlayerCallbacks} is thirteen function pointers, several of which take an integer and return
 * an integer, so a table whose slots were permuted still calls something with something. What it cannot
 * do is deliver a one channel 8 kHz PCM track through {@code audio_track}, then a Ready and a Playing and
 * a Finished through {@code state}, and spectrum magnitudes above the silence floor through
 * {@code audio_spectrum}, from a file that says exactly those things and nothing else. The slot offsets
 * are separately pinned against the C compiler by
 * {@code JfxMediaNativeTest.callbackTableSlotOffsetsMatchTheCompiledStructs}; this is the other half of
 * that guard, and the half that also covers the slot <em>names</em>, which never cross the ABI boundary
 * and which that test says in as many words it cannot reach.
 * <p>
 * <b>Where these run, and what a skip costs.</b> They need an audio output device, for the reason
 * {@link AudioOutput} sets out in full: the sink is opened during {@code jfxm_player_init} and there is
 * no null sink to fall back to, so with no device there is no player and nothing after
 * {@code jfxm_media_create} happens at all. Both Linux CI jobs install a null ALSA device before the
 * build and run everything here; a developer machine with a sound card runs it; a runner with neither
 * skips with a message naming every slot the skip leaves uncovered. The no-device path is not simply
 * skipped, though: {@link #requirePlayer} asserts what has to be true of a player that could not be built
 * - a null return rather than a throw, a log that explains it in the sink's own words rather than in any
 * that would name a broken build, and a registry left exactly as it was found - before it reports the
 * skip. Both tests therefore assert something on a machine with no audio device as well.
 * <p>
 * Nothing here polls a state or sleeps its way to an assertion. {@code CGstAudioPlaybackPipeline::Init}
 * asks for {@code PAUSED} and does not wait for it, and no {@code jfxm_player_get_*} value is knowable
 * until the pipeline has prerolled, so every assertion is behind the Ready that
 * {@code CGstAudioPlaybackPipeline::BusCallback} publishes when it has. Every wait is a latch with an
 * explicit bound that fails carrying what did arrive.
 */
public class MediaPlaybackTest {

    /**
     * How long a player gets to preroll. Measured on an idle machine the Ready arrives within a fraction
     * of a millisecond of the player being handed over; this is a bound, not an expectation, and generous
     * enough that a loaded CI machine cannot reach it while a pipeline that never prerolls still fails
     * rather than hangs.
     */
    private static final long PREROLL_TIMEOUT_SECONDS = 10L;

    /**
     * How long the one second file gets to reach end of stream once it is playing. Wall clock playback
     * time is not one second everywhere - a null ALSA device consumes buffers as fast as they arrive
     * rather than at the rate they describe - so this bounds a pipeline that stalled, not one that is
     * slow.
     */
    private static final long PLAYBACK_TIMEOUT_SECONDS = 30L;

    /** How long an asynchronous teardown gets to give its threads back before it is called a leak. */
    private static final long TEARDOWN_TIMEOUT_MILLIS = 15_000L;

    /** How long the {@code audio_track} upcall gets to reach {@code NativeMedia.addTrack} after Ready. */
    private static final long TRACK_TIMEOUT_MILLIS = 5_000L;

    /** {@code NativeAudioSpectrum.DEFAULT_THRESHOLD}, copied because that class is package private. */
    private static final float SILENCE_FLOOR_DB = -60.0f;

    /** {@code NativeAudioSpectrum.DEFAULT_INTERVAL}, copied for the same reason. */
    private static final double SPECTRUM_INTERVAL_SECONDS = 0.1;

    /**
     * How many {@code AudioClip.play()} cycles {@link #everyAudioClipPlaybackGivesBackTheThreadsItTook}
     * runs. Twenty is well clear of the noise in a thread count, and they are played one at a time, so it
     * never approaches the sixteen concurrent players {@code NativeMediaAudioClipPlayer} allows.
     */
    private static final int AUDIO_CLIP_PLAYBACKS = 20;

    /**
     * The number of extra live JVM threads a run of {@link #AUDIO_CLIP_PLAYBACKS} playbacks may leave
     * behind. A playback that leaked its player leaves at least two - the event queue thread and the
     * media pulse timer - so half a thread per playback is a bound that no leak of the kind
     * {@link MediaThreads} describes can pass and that ordinary JVM housekeeping cannot reach.
     */
    private static final int THREAD_SLACK = AUDIO_CLIP_PLAYBACKS / 2;

    @BeforeAll
    static void loadLibrary() {
        MediaNatives.require();
    }

    /**
     * The test the fork did not have: a file is decoded and played to its end, and every fact the player
     * reports about it is checked against the file that was generated.
     * <p>
     * What each assertion is for, because none of them is here to describe GStreamer:
     * <ul>
     * <li><b>Ready arrives</b> - the {@code state} slot of {@code JfxmPlayerCallbacks} reached
     *     {@code NativeMediaPlayer.sendPlayerStateEvent}, and the pipeline prerolled.</li>
     * <li><b>The duration is the file's duration</b> - {@code jfxm_player_get_duration} against
     *     {@link SineWav}: 16000 bytes of 16 bit mono at 8 kHz is 1.000 s, to a tolerance that a sample
     *     rate or a block align read out of the wrong field could not survive.</li>
     * <li><b>One PCM audio track, one channel, 8 kHz</b> - the {@code audio_track} slot, checked by its
     *     payload and not by its arrival. This is the assertion a permuted callback table cannot
     *     pass.</li>
     * <li><b>Playing, then Finished</b> - the rest of the {@code state} slot, and the proof that the
     *     pipeline ran the file out rather than stopping somewhere inside it.</li>
     * <li><b>Spectrum magnitudes above the floor</b> - the {@code audio_spectrum} slot, and the only
     *     assertion here that decoded <em>samples</em> rather than merely buffers went through: the
     *     {@code spectrum} element sits ahead of the sink, silence reads as {@link #SILENCE_FLOOR_DB} in
     *     every band, and {@code SineWav}'s tone reads some 40 dB above it.</li>
     * <li><b>No error and no halt</b> - the {@code media_error} and {@code halt} slots, which have to
     *     stay silent. A pipeline that fails part way through can still reach Finished; this is what
     *     stops that from passing.</li>
     * <li><b>The registry and the event queue thread come back</b> - and reaching those two assertions
     *     at all is the assertion that {@code dispose()} completed, since a dispose that did not would
     *     fail on the method timeout rather than wedge the build.</li>
     * </ul>
     */
    @Test
    void aGeneratedToneIsDecodedAndPlayedToTheEnd() throws IOException, URISyntaxException {
        Path file = toneFile(SineWav.ONE_SECOND_FRAMES);
        try {
            Locator locator = toneLocator(file);
            int baselineRegistry = JfxMediaNative.registrySize();
            int baselineEventQueues = MediaThreads.count(MediaThreads.EVENT_QUEUE);

            MediaPlayer player = requirePlayer(locator, baselineRegistry);
            // Both listener lists hold weak references, so these two locals are what keeps the recorders
            // alive for the length of the playback.
            StateRecorder states = new StateRecorder();
            SpectrumRecorder spectrum = new SpectrumRecorder();
            try {
                player.addMediaPlayerListener(states);
                player.addMediaErrorListener(states);
                states.awaitReady(PREROLL_TIMEOUT_SECONDS);

                assertEquals(SineWav.seconds(SineWav.ONE_SECOND_FRAMES), player.getDuration(), 1.0e-3,
                        "16000 bytes of 16 bit mono at 8 kHz");

                Media media = player.getMedia();
                assertNotNull(media, "the player has to hand back the media it was built over");
                List<Track> tracks = awaitTracks(media);
                assertEquals(1, tracks.size(), () -> "one audio track and nothing else: " + tracks);
                AudioTrack track = assertInstanceOf(AudioTrack.class, tracks.get(0),
                        "the audio_track upcall did not produce an AudioTrack");
                assertEquals(Track.Encoding.PCM, track.getEncodingType(), "encoding");
                assertEquals(SineWav.CHANNELS, track.getNumChannels(), "channels");
                assertEquals((float) SineWav.SAMPLE_RATE, track.getEncodedSampleRate(), 0.0f,
                        "encoded sample rate");
                // The channel mask is derived from the channel count and travels as a separate argument
                // of the same upcall, so it pins a second field of the slot's payload: one channel is
                // FRONT_CENTER and nothing else, and the bit's value is what
                // JfxMediaNativeTest.audioTrackChannelMappingMatchesTheTrackConstants holds C to.
                assertEquals(AudioTrack.FRONT_CENTER, track.getChannelMask(), "channel mask");

                AudioSpectrum bands = player.getAudioSpectrum();
                assertNotNull(bands);
                bands.setEnabled(true);
                player.addAudioSpectrumListener(spectrum);

                player.play();
                states.awaitPlaying(PREROLL_TIMEOUT_SECONDS);
                states.awaitFinished(PLAYBACK_TIMEOUT_SECONDS);

                assertEquals(PlayerState.FINISHED, player.getState(),
                        "end of stream did not leave the player in FINISHED");
                states.assertPublishedInOrder(PlayerState.READY, PlayerState.PLAYING,
                        PlayerState.FINISHED);
                assertEquals(List.of(), states.failures(), "the player reported an error or a halt");

                assertTrue(spectrum.events() > 0, "the audio_spectrum upcall never arrived; the spectrum "
                        + "element is ahead of the sink, so a playback that decoded anything at all "
                        + "produces one of these every " + SPECTRUM_INTERVAL_SECONDS + " s");
                assertTrue(spectrum.loudestBand() > SILENCE_FLOOR_DB,
                        "every band stayed at the " + SILENCE_FLOOR_DB + " dB silence floor across "
                        + spectrum.events() + " spectrum events, so no sample of SineWav's "
                        + SineWav.TONE_HZ + " Hz tone reached the spectrum element; the loudest band seen"
                        + " was " + spectrum.loudestBand() + " dB");
            } finally {
                player.removeAudioSpectrumListener(spectrum);
                player.removeMediaPlayerListener(states);
                player.dispose();
            }

            awaitRegistry(baselineRegistry, "dispose left a registry entry behind");
            MediaThreads.awaitCountAtMost(MediaThreads.EVENT_QUEUE, baselineEventQueues,
                    TEARDOWN_TIMEOUT_MILLIS, "a disposed player kept its event queue thread");
        } finally {
            deleteWhenPossible(file);
        }
    }

    /**
     * The leak test the review asked for, and an honest account of what it can weigh.
     * <p>
     * {@code AudioClip.play()} is the most repeated operation in the audio API and the one the native
     * layer scales with: {@code NativeMediaAudioClipPlayer.play} builds a whole {@code MediaPlayer} per
     * playback, so every call creates a pipeline, a {@code directsoundsink} or {@code alsasink} element,
     * an event queue thread and a media pulse timer, and has to give all of them back when the clip
     * finishes. Twenty playbacks that leaked their player would leave forty JVM threads and twenty
     * registry entries behind. That is what this asserts, and until it existed nothing in the fork could
     * see a per-playback leak at all.
     * <p>
     * What it deliberately does not assert is native memory. The {@code GSTDirectSoundNotify} object that
     * {@code InitNotificator} leaks on purpose ({@code gstdirectsoundnotify.cpp}) is about eighty bytes
     * plus an {@code SRWLOCK}: twenty playbacks leak under two kilobytes, against the megabytes a single
     * GStreamer pipeline allocates and frees around them. No measurement available to Java has the
     * resolution to separate the two, and a test that claimed otherwise would be asserting on allocator
     * noise. {@link MediaThreads} records that boundary in full, including why the notification thread
     * itself is invisible to a JVM thread count. What the twenty cycles do cover of that path is
     * liveness: each one runs an {@code InitNotificator}/{@code ReleaseNotificator} pair, including the
     * untimed join in the second, and every wait here is bounded, so a join that stopped returning fails
     * this test with a thread population rather than hanging the build.
     * <p>
     * The baseline is taken after one warm-up playback rather than before it. The first
     * {@code AudioClip.play()} of a JVM starts the clip scheduler thread, which is a singleton and never
     * ends, and pulls in whatever the platform loads on its way to a first pipeline; those belong in the
     * baseline, not in the leak.
     */
    @Test
    void everyAudioClipPlaybackGivesBackTheThreadsItTook() throws IOException, URISyntaxException {
        Path file = toneFile(SineWav.CLIP_FRAMES);
        try {
            // The same gate the playback test uses, and the same assertions on the no-device path: an
            // AudioClip cannot report why it played nothing, because NativeMediaAudioClipPlayer.play
            // hands its MediaException to the clip scheduler thread, which has no caller to raise it to.
            int baselineRegistry = JfxMediaNative.registrySize();
            int baselineEventQueues = MediaThreads.count(MediaThreads.EVENT_QUEUE);
            requirePlayer(toneLocator(file), baselineRegistry).dispose();
            awaitRegistry(baselineRegistry, "the audio-output probe left a registry entry behind");

            AudioClip clip = new AudioClip(file.toUri().toString());
            // Nothing here reads the spectrum, so the clips are played silent: twenty audible beeps in a
            // build is a nuisance, and no assertion below is downstream of the volume element.
            clip.setVolume(0.0);

            // An AudioClip playback is torn down by the clip scheduler thread after isPlaying() has gone
            // false, so every count below has to be given time to settle rather than read on the instant.
            playToCompletion(clip, 1);
            MediaThreads.awaitCountAtMost(MediaThreads.EVENT_QUEUE, baselineEventQueues,
                    TEARDOWN_TIMEOUT_MILLIS,
                    "the warm-up AudioClip playback never gave its event queue thread back");
            awaitRegistry(baselineRegistry,
                    "the warm-up AudioClip playback left a callback registry entry behind");
            int baselineThreads = MediaThreads.names().size();

            playToCompletion(clip, AUDIO_CLIP_PLAYBACKS);

            MediaThreads.awaitCountAtMost(MediaThreads.EVENT_QUEUE, baselineEventQueues,
                    TEARDOWN_TIMEOUT_MILLIS,
                    AUDIO_CLIP_PLAYBACKS + " AudioClip playbacks left player event queue threads live, so"
                    + " a player was built per playback and never disposed");
            MediaThreads.awaitTotalAtMost(baselineThreads + THREAD_SLACK, TEARDOWN_TIMEOUT_MILLIS,
                    AUDIO_CLIP_PLAYBACKS + " AudioClip playbacks grew the JVM's thread population from "
                    + baselineThreads);
            awaitRegistry(baselineRegistry, AUDIO_CLIP_PLAYBACKS
                    + " AudioClip playbacks left callback registry entries behind");
        } finally {
            deleteWhenPossible(file);
        }
    }

    /**
     * The player, or a skip that says what the skip costs. Never returns null.
     * <p>
     * The whole no-audio-device path is asserted here rather than assumed away:
     * {@code GSTPlatform.createMediaPlayer} has to return null rather than throw, and the registry has to
     * be exactly where the caller found it - a player whose {@code jfxm_player_init} failed had already
     * installed thirteen upcall stubs and a registry id, and {@code GSTMediaPlayer}'s catch block is the
     * only thing that gives them back. {@link AudioOutput#reportNoPlayer} then decides, from the captured
     * log, whether that null is this machine or this build.
     */
    private static MediaPlayer requirePlayer(Locator locator, int baselineRegistry) {
        AudioOutput.Attempt attempt = AudioOutput.createPlayer(locator);
        if (attempt.player() == null) {
            assertEquals(baselineRegistry, JfxMediaNative.registrySize(),
                    "a player whose pipeline never started left its callback registry entry behind");
            AudioOutput.reportNoPlayer(attempt.log());      // never returns: aborts or fails
        }
        return attempt.player();
    }

    /**
     * Waits for the callback registry to come back to {@code expected}, and asserts it there.
     * <p>
     * A wait rather than a reading, for the same reason {@link MediaThreads#awaitCountAtMost} is one: a
     * player's registry ids are dropped by the {@code dispose()} that ends it, and an
     * {@code AudioClip} dispose runs on the clip scheduler thread some time after the clip has reported
     * itself finished. Reading the size on the instant makes both the assertion and the <em>baseline</em>
     * of whatever test runs next a race - which is exactly how this test first passed while leaving three
     * entries outstanding for the next one to trip over.
     */
    private static void awaitRegistry(int expected, String because) {
        long deadline = System.currentTimeMillis() + TEARDOWN_TIMEOUT_MILLIS;
        int size = JfxMediaNative.registrySize();
        while (size != expected && System.currentTimeMillis() < deadline) {
            sleep();
            size = JfxMediaNative.registrySize();
        }
        assertEquals(expected, size, because);
    }

    /**
     * Plays {@code clip} {@code times} over, one at a time, failing rather than hanging when a playback
     * does not finish. Sequential because what is being counted is complete
     * create-preroll-play-finish-dispose rounds, not concurrency.
     */
    private static void playToCompletion(AudioClip clip, int times) {
        for (int i = 0; i < times; i++) {
            clip.play();
            long deadline = System.currentTimeMillis() + TEARDOWN_TIMEOUT_MILLIS;
            while (clip.isPlaying() && System.currentTimeMillis() < deadline) {
                sleep();
            }
            if (clip.isPlaying()) {
                fail("AudioClip playback " + (i + 1) + " of " + times + " did not finish within "
                        + TEARDOWN_TIMEOUT_MILLIS + " ms. A playback that never reports finished has "
                        + "either never started - the clip scheduler thread ends on the first throw out "
                        + "of NativeMediaAudioClipPlayer.play, and every later play queues behind it "
                        + "forever - or never torn down. " + MediaThreads.names().size()
                        + " JVM threads were live.");
            }
        }
    }

    /**
     * The tracks the {@code audio_track} upcall added to {@code media}, waiting for the first of them.
     * {@code Media.getTracks} answers null until one arrives, and although the pipeline reports its
     * tracks while it prerolls - so they are normally already there when Ready is - the two are published
     * along different paths and nothing orders them.
     */
    private static List<Track> awaitTracks(Media media) {
        long deadline = System.currentTimeMillis() + TRACK_TIMEOUT_MILLIS;
        List<Track> tracks = media.getTracks();
        while (tracks == null && System.currentTimeMillis() < deadline) {
            sleep();
            tracks = media.getTracks();
        }
        assertNotNull(tracks, "no track arrived within " + TRACK_TIMEOUT_MILLIS + " ms of the player "
                + "becoming ready, so the audio_track slot of JfxmPlayerCallbacks never reached "
                + "NativeMedia.addTrack");
        return tracks;
    }

    /**
     * The tone file, in the system temp directory rather than in a {@code @TempDir} for the reason
     * {@code JfxMediaNativeTest.tinyWavFile} gives: a pipeline that never left {@code GST_STATE_NULL}
     * never closes its connection holder, Windows will not delete an open file, and a {@code @TempDir}
     * that cannot be cleaned up fails the test it belongs to - which would turn "no audio device" back
     * into a failure.
     */
    private static Path toneFile(int frames) throws IOException {
        Path file = SineWav.writeTo(Files.createTempFile("jfx-media-tone", ".wav"), frames);
        file.toFile().deleteOnExit();
        assertEquals(SineWav.size(frames), Files.size(file));
        return file;
    }

    /** Deletes the file when the platform allows it; {@code deleteOnExit} is the fallback. */
    private static void deleteWhenPossible(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // A connection holder the pipeline never closed still has it open; it goes at exit.
        }
    }

    /** {@code file} as an initialised {@link Locator}; the platform has to recognise it as WAV. */
    private static Locator toneLocator(Path file) throws IOException, URISyntaxException {
        Locator locator = new Locator(file.toUri());
        locator.init();
        assertEquals("audio/x-wav", locator.getContentType());
        return locator;
    }

    private static void sleep() {
        try {
            Thread.sleep(25L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for the pipeline", e);
        }
    }

    /**
     * Every state the player published, in order, and the errors and halts it must not publish. One latch
     * per state this class waits for; the recorded list is what a failure carries, so that "never reached
     * Playing" also says what it did reach instead.
     */
    private static final class StateRecorder implements PlayerStateListener, MediaErrorListener {

        private final List<PlayerState> states = new CopyOnWriteArrayList<>();
        private final List<String> failures = new CopyOnWriteArrayList<>();
        private final CountDownLatch ready = new CountDownLatch(1);
        private final CountDownLatch playing = new CountDownLatch(1);
        private final CountDownLatch finished = new CountDownLatch(1);

        void awaitReady(long seconds) {
            await(ready, seconds, PlayerState.READY);
        }

        void awaitPlaying(long seconds) {
            await(playing, seconds, PlayerState.PLAYING);
        }

        void awaitFinished(long seconds) {
            await(finished, seconds, PlayerState.FINISHED);
        }

        /**
         * Requires the given states to have been published, in this relative order. Deliberately not an
         * equality check against the whole list: a state that is legitimately platform dependent - a
         * Stalled on a loaded machine - would then be a failure, and the thing worth pinning is the
         * order, since a permuted {@code state} slot is what would scramble it.
         */
        void assertPublishedInOrder(PlayerState... expected) {
            List<PlayerState> published = List.copyOf(states);
            int previous = -1;
            for (PlayerState state : expected) {
                int at = published.indexOf(state);
                assertTrue(at > previous, () -> "the player published " + published + ", which does not "
                        + "hold " + List.of(expected) + " in that order");
                previous = at;
            }
        }

        List<String> failures() {
            return List.copyOf(failures);
        }

        private void await(CountDownLatch latch, long seconds, PlayerState state) {
            boolean opened;
            try {
                opened = latch.await(seconds, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for " + state, e);
            }
            if (!opened) {
                fail("the player never reached " + state + " within " + seconds + " s. The states it did"
                        + " publish were " + states + " and the failures it reported were " + failures);
            }
        }

        @Override
        public void onReady(PlayerStateEvent evt) {
            record(evt);
            ready.countDown();
        }

        @Override
        public void onPlaying(PlayerStateEvent evt) {
            record(evt);
            playing.countDown();
        }

        @Override
        public void onFinish(PlayerStateEvent evt) {
            record(evt);
            finished.countDown();
        }

        @Override
        public void onPause(PlayerStateEvent evt) {
            record(evt);
        }

        @Override
        public void onStop(PlayerStateEvent evt) {
            record(evt);
        }

        @Override
        public void onStall(PlayerStateEvent evt) {
            record(evt);
        }

        @Override
        public void onHalt(PlayerStateEvent evt) {
            record(evt);
            failures.add("halt: " + evt.getMessage());
        }

        @Override
        public void onError(Object source, int errorCode, String message) {
            failures.add("error " + errorCode + ": " + message);
        }

        private void record(PlayerStateEvent evt) {
            states.add(evt.getState());
        }
    }

    /**
     * What the {@code audio_spectrum} upcall delivered: how many events, and the loudest band any of them
     * held. The magnitudes are read inside the callback on purpose - the band arrays are written in place
     * by C and describe the window the event announces, so a reading taken afterwards describes some
     * other window, or, after a dispose, none.
     */
    private static final class SpectrumRecorder implements AudioSpectrumListener {

        private final AtomicInteger events = new AtomicInteger();
        private volatile float loudest = Float.NEGATIVE_INFINITY;

        int events() {
            return events.get();
        }

        float loudestBand() {
            return loudest;
        }

        @Override
        public void onAudioSpectrumEvent(AudioSpectrumEvent evt) {
            events.incrementAndGet();
            float peak = loudest;
            for (float magnitude : evt.getSource().getMagnitudes(null)) {
                peak = Math.max(peak, magnitude);
            }
            loudest = peak;
        }
    }
}
