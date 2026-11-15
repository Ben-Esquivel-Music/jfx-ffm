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
import com.sun.media.jfxmedia.effects.AudioSpectrum;
import com.sun.media.jfxmedia.events.AudioSpectrumEvent;
import com.sun.media.jfxmedia.events.AudioSpectrumListener;
import com.sun.media.jfxmedia.events.MediaErrorListener;
import com.sun.media.jfxmedia.events.PlayerStateEvent;
import com.sun.media.jfxmedia.events.PlayerStateListener;
import com.sun.media.jfxmedia.locator.Locator;
import com.sun.media.jfxmedia.logging.Logger;
import com.sun.media.jfxmediaimpl.JfxMediaNative;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * The child JVM of {@link SpectrumBandChangeTest}: plays a tone with the spectrum on and changes the band count
 * while it plays, in rounds, and reports whether each round ended with a delivery of full band arrays.
 * <p>
 * A round alternates between {@link #LOWER_COUNTS} and {@link #HIGHER_COUNTS} for a given time: it pauses for
 * {@value #PAUSE_MILLIS} ms, lowers the count, raises it again at once, and so on, and ends on a raise. It then
 * waits for a delivery whose arrays have that last count and whose strongest band is the one
 * {@link SineWav}'s tone falls in for it ({@link #toneBand}). A spectrum message is computed for the count the
 * element had when it ran, and a band of one count covers another frequency range than the same band of another,
 * so a delivery whose arrays were filled from a message for another count, or only partly, would put the peak
 * elsewhere, or nowhere: that is what makes the peak the proof of a full write. A round is bounded by time rather
 * than by a number of changes because a change has to take the element's lock, and how often it gets it depends on
 * the audio output: about every {@value #PAUSE_MILLIS} ms where the output is paced, and only between two buffers
 * where it is not, as on a null ALSA device, where the element is posting nearly all the time.
 * <p>
 * The stale case - a message handled after the count it was computed for was replaced - is made, not waited for.
 * Where the audio output is paced the bus thread handles spectrum messages faster than the element computes them,
 * so a change almost never finds one queued. {@link BusStall} changes that. The element computes and posts the
 * messages of a whole buffer under the lock a change has to take, so a change made then waits; when a lowering has
 * waited for a while, the stall stops the world with full collections until the lowering has landed. The bus
 * thread stops at its next spectrum upcall, while the element, which is native, goes on computing and posting, now
 * for the lowered count, and the raise that follows at once lands while those messages are still queued: the bus
 * thread then finds them behind a count larger than their own. That direction is the point. A message shorter than
 * the arrays is the one the handler used to read past the end of; one longer than them it only read in part. The
 * pause before each lowering is what makes the change that waits a lowering, and measured on Windows with the
 * roles the other way round, every stale message was the longer kind.
 * <p>
 * Against the hooks build the probe also reads the spectrum counters of {@code jfxm_test_counter}, and keeps adding
 * rounds after the planned ones until the bus handler has dropped the given number of messages as stale, or the
 * given time has passed. The counters are reset while the spectrum is still off, so no message can be counted in
 * part.
 * <p>
 * Arguments: {@code shipped} or {@code hooks}; the file a no-player log goes to; the spectrum interval in seconds;
 * the number of planned rounds; how long a round changes the count for, in milliseconds; the number of stale drops
 * to reach; how long to keep adding rounds for, in milliseconds.
 */
public final class SpectrumBandChangeProbe {

    /** Every second change goes to one of these. Each is chosen so that the tone lies close to one band's centre. */
    private static final int[] LOWER_COUNTS = { 37, 46, 64 };

    /** ... and every other change to one of these, which raises the count. */
    private static final int[] HIGHER_COUNTS = { 100, 128, 137 };

    /**
     * Five minutes of tone, 4.8 MB. Where the audio output is paced that is far more than any schedule here plays;
     * where it is not, as on a null ALSA device, the pipeline runs through it some twenty times faster than real
     * time, and this still outlasts the planned rounds several times over.
     */
    private static final int TONE_FRAMES = 300 * SineWav.SAMPLE_RATE;

    /** How long the player gets to preroll, and then to start playing. */
    private static final long STATE_TIMEOUT_MILLIS = 10_000L;

    /** How long a round waits for its delivery of full band arrays once its last change is made. */
    private static final long DELIVERY_TIMEOUT_MILLIS = 5_000L;

    /** The pause before each lowering. */
    private static final long PAUSE_MILLIS = 2L;

    /**
     * How long a lowering has to have been waiting before {@link BusStall} takes it for one held up by the element.
     * One that is not takes some tens of microseconds.
     */
    private static final long HELD_UP_NANOS = 500_000L;

    /** How often {@link BusStall} looks whether it has been armed. */
    private static final long STALL_POLL_NANOS = 1_000_000L;

    /**
     * The longest one stall goes on for. The lowering it waits for lands natively within the few tens of milliseconds
     * the element keeps it waiting, but only reports that from Java, which back-to-back collections leave little room
     * for, so the stall ends at this bound rather than run on into the rest of the round.
     */
    private static final long MAX_STALL_MILLIS = 100L;

    /** The magnitude from which a band is one no spectrum message carries; see {@link Deliveries}. */
    private static final float NO_MESSAGE_CARRIES_DB = 0.0f;

    private SpectrumBandChangeProbe() {
    }

    /**
     * @param args see the class description
     * @throws Exception if anything fails that is not reported, which ends the child with its stack trace
     */
    public static void main(String[] args) throws Exception {
        boolean hooksBuild = "hooks".equals(args[0]);
        Path noPlayerLog = Path.of(args[1]);
        double interval = Double.parseDouble(args[2]);
        int plannedRounds = Integer.parseInt(args[3]);
        long roundMillis = Long.parseLong(args[4]);
        int staleTarget = Integer.parseInt(args[5]);
        long staleTimeLimitMillis = Long.parseLong(args[6]);

        JfxmediaLibraryProbe.reportLibrary();
        JfxmTestHooksNative hooks = hooksBuild ? JfxmTestHooksNative.bind() : null;

        Path file = SineWav.writeTo(Files.createTempFile("jfx-media-spectrum", ".wav"), TONE_FRAMES);
        file.toFile().deleteOnExit();
        Locator locator = new Locator(file.toUri());
        locator.init();
        int baselineRegistry = JfxMediaNative.registrySize();
        AudioOutput.Attempt attempt = AudioOutput.createPlayer(locator);
        if (attempt.player() == null) {
            Files.writeString(noPlayerLog, attempt.log(), StandardCharsets.UTF_8);
            ChildJvm.report("player", "none");
            reportRegistry(baselineRegistry);
            ChildJvm.report("done", true);
            System.exit(0);
        }
        // The log of the creation goes into the output too: a critical raised while the pipeline was built is in it.
        System.out.println(attempt.log());
        ChildJvm.report("player", "created");
        // AudioOutput turned the native log off again; a GLib critical reaches the output only as a native ERROR.
        Logger.setLevel(Logger.WARNING);
        JfxMediaNative.logSetLevel(Logger.WARNING);

        MediaPlayer player = attempt.player();
        States states = new States();
        Deliveries deliveries = new Deliveries();
        BusStall stall = new BusStall();
        Thread staller = Thread.ofPlatform().daemon().name("bus-stall").start(stall);
        try {
            player.addMediaPlayerListener(states);
            player.addMediaErrorListener(states);
            states.await(states.ready, "Ready");
            if (hooks != null) {
                hooks.reset();
            }

            AudioSpectrum spectrum = player.getAudioSpectrum();
            spectrum.setInterval(interval);
            player.addAudioSpectrumListener(deliveries);
            spectrum.setEnabled(true);
            player.play();
            states.await(states.playing, "Playing");

            long start = System.nanoTime();
            int round = 0;
            boolean delivered = true;
            while (delivered && !states.finished()) {
                delivered = runRound(spectrum, deliveries, stall, round, roundMillis, hooks);
                round++;
                if (round >= plannedRounds && (hooks == null
                        || hooks.counter(MediaTestHooks.SPECTRUM_STALE_DROPPED) >= staleTarget
                        || elapsedMillis(start) >= staleTimeLimitMillis)) {
                    break;
                }
            }
            spectrum.setEnabled(false);
            stall.stop();
            staller.join();
            ChildJvm.report("rounds", round);
            ChildJvm.report("schedule.millis", elapsedMillis(start));
            ChildJvm.report("bus.stalls", stall.stalls());
            ChildJvm.report("deliveries", deliveries.total());
            ChildJvm.report("deliveries.impossible", deliveries.impossible());
            ChildJvm.report("deliveries.impossible.first", deliveries.firstImpossible());
            ChildJvm.report("player.finished", states.finished());
            ChildJvm.report("player.failures", states.failures);
            if (hooks != null) {
                // Events first: a message's bands are counted as written before its event is counted as sent, so
                // reading the written count second can only find it larger.
                reportCounter(hooks, MediaTestHooks.SPECTRUM_EVENTS_SENT);
                reportCounter(hooks, MediaTestHooks.SPECTRUM_BANDS_WRITTEN);
                reportCounter(hooks, MediaTestHooks.SPECTRUM_STALE_DROPPED);
            }
        } finally {
            stall.stop();
            player.removeAudioSpectrumListener(deliveries);
            player.removeMediaPlayerListener(states);
            player.dispose();
        }
        reportRegistry(baselineRegistry);
        ChildJvm.report("done", true);
        System.exit(0);
    }

    /**
     * One round: changes for at least {@code roundMillis}, a lowering and a raise at a time, then the wait for a full
     * delivery of the last count.
     *
     * @return whether that delivery arrived
     */
    private static boolean runRound(AudioSpectrum spectrum, Deliveries deliveries, BusStall stall, int round,
                                    long roundMillis, JfxmTestHooksNative hooks) throws InterruptedException {
        long roundStart = System.nanoTime();
        int changes = 0;
        int count;
        stall.arm();
        do {
            int pair = changes / 2;
            Thread.sleep(PAUSE_MILLIS);
            stall.lowering();
            spectrum.setBandCount(LOWER_COUNTS[(round + pair) % LOWER_COUNTS.length]);
            stall.lowered();
            count = HIGHER_COUNTS[(round + pair) % HIGHER_COUNTS.length];
            deliveries.expect(count, toneBand(count));
            spectrum.setBandCount(count);
            changes += 2;
        } while (elapsedMillis(roundStart) < roundMillis);
        long start = System.nanoTime();
        boolean delivered = deliveries.await(DELIVERY_TIMEOUT_MILLIS);
        ChildJvm.report("round." + round, changes + " changes, the last a raise to " + count + " bands, tone in band "
                + toneBand(count) + ": " + (delivered ? "delivered" : "no such delivery") + " after "
                + elapsedMillis(start) + " ms; last delivery " + deliveries.last()
                + (hooks != null ? "; stale so far " + hooks.counter(MediaTestHooks.SPECTRUM_STALE_DROPPED) : ""));
        ChildJvm.report("round." + round + ".changes", changes);
        ChildJvm.report("round." + round + ".delivered", delivered);
        return delivered;
    }

    /**
     * The band {@link SineWav#TONE_HZ} falls in for {@code bands} bands: the {@code spectrum} element runs an FFT of
     * {@code 2 * bands - 2} points, whose bin {@code i} is at {@code i * rate / points} Hz.
     */
    static int toneBand(int bands) {
        return (int) Math.round(SineWav.TONE_HZ * (2.0 * bands - 2) / SineWav.SAMPLE_RATE);
    }

    private static void reportCounter(JfxmTestHooksNative hooks, int id) {
        ChildJvm.report("counter." + id, hooks.counter(id));
    }

    private static void reportRegistry(int baseline) {
        ChildJvm.report("registry.baseline", baseline);
        ChildJvm.report("registry.after", JfxMediaNative.registrySize());
    }

    private static long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    /**
     * The spectrum deliveries: how many, what the last one held, whether one matched what a round waits for, and how
     * many held a band no message carries. The arrays are read inside the callback, because C writes them in place
     * and a later read describes a later message.
     * <p>
     * A band no message carries is a magnitude of {@value #NO_MESSAGE_CARRIES_DB} dB or more. The element reports
     * magnitudes relative to full scale, and even a full-scale sine stays some 11 dB below that once the window has
     * taken its share; {@link SineWav}'s tone, at a tenth of full scale, peaks around -30 dB, and the seed Java fills
     * new arrays with is -60. What does write 0 is a read past the end of a message's list: the lookup gives no
     * value and {@code g_value_get_float} answers 0 for it - silently where GLib is the bundled lite build, whose
     * {@code g_return_val_if_fail} returns without logging, as on Windows.
     */
    private static final class Deliveries implements AudioSpectrumListener {

        private int expectedCount;
        private int expectedBand;
        private boolean matched;
        private long total;
        private int lastCount = -1;
        private int lastPeak = -1;
        private long impossible;
        private String firstImpossible = "none";

        synchronized void expect(int count, int band) {
            expectedCount = count;
            expectedBand = band;
            matched = false;
        }

        synchronized boolean await(long millis) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
            while (!matched) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    return false;
                }
                TimeUnit.NANOSECONDS.timedWait(this, left);
            }
            return true;
        }

        synchronized long total() {
            return total;
        }

        synchronized String last() {
            return lastCount + " bands, strongest " + lastPeak;
        }

        /** How many deliveries held a band no message carries. */
        synchronized long impossible() {
            return impossible;
        }

        /** The first such band, as {@code "<band> of <count> bands: <magnitude> dB"}, or {@code "none"}. */
        synchronized String firstImpossible() {
            return firstImpossible;
        }

        @Override
        public void onAudioSpectrumEvent(AudioSpectrumEvent evt) {
            float[] magnitudes = evt.getSource().getMagnitudes(null);
            int peak = strongest(magnitudes);
            synchronized (this) {
                total++;
                lastCount = magnitudes.length;
                lastPeak = peak;
                if (magnitudes.length > 0 && magnitudes[peak] >= NO_MESSAGE_CARRIES_DB) {
                    if (impossible++ == 0) {
                        firstImpossible = peak + " of " + magnitudes.length + " bands: " + magnitudes[peak] + " dB";
                    }
                }
                if (magnitudes.length == expectedCount && peak == expectedBand) {
                    matched = true;
                    notifyAll();
                }
            }
        }

        /** The index of the largest magnitude; the first of equal ones, so arrays at the floor answer 0. */
        private static int strongest(float[] magnitudes) {
            int peak = 0;
            for (int i = 1; i < magnitudes.length; i++) {
                if (magnitudes[i] > magnitudes[peak]) {
                    peak = i;
                }
            }
            return peak;
        }
    }

    /**
     * Stops the world, once per round, when a lowering has been waiting for {@link #HELD_UP_NANOS}, and keeps it
     * stopped until that lowering has landed, for {@link #MAX_STALL_MILLIS} at most: see the class description. Full
     * collections, one after another, are what stop it, because every thread that enters Java waits for one to end -
     * the bus thread at its spectrum upcall - and no native thread does. One collection is not enough: the element
     * can keep a change waiting for a few tens of milliseconds, longer than a collection, and the bus thread handles
     * messages faster than the element computes them, so a backlog made early is gone before the change lands. Once
     * per round because once is enough, and because where the audio output is not paced, as on a null ALSA device,
     * the element posts all the time and every change waits.
     */
    private static final class BusStall implements Runnable {

        private volatile long loweringStarted;
        private volatile boolean armed;
        private volatile boolean running = true;
        private volatile int stalls;

        /** Lets the next lowering that is held up stall the bus thread. */
        void arm() {
            armed = true;
        }

        void lowering() {
            loweringStarted = System.nanoTime();
        }

        void lowered() {
            loweringStarted = 0L;
        }

        void stop() {
            running = false;
        }

        int stalls() {
            return stalls;
        }

        /**
         * Spins while armed, so that the first collection starts soon after the lowering is seen waiting; a timed
         * park, a millisecond or more on Windows, would start it late.
         */
        @Override
        public void run() {
            while (running) {
                if (!armed) {
                    LockSupport.parkNanos(STALL_POLL_NANOS);
                    continue;
                }
                long started = loweringStarted;
                if (started != 0L && System.nanoTime() - started >= HELD_UP_NANOS) {
                    armed = false;
                    long stallEnd = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(MAX_STALL_MILLIS);
                    while (loweringStarted == started && System.nanoTime() < stallEnd) {
                        System.gc();
                    }
                    stalls++;
                } else {
                    Thread.onSpinWait();
                }
            }
        }
    }

    /** The states the player reaches, and the errors and halts it reports. */
    private static final class States implements PlayerStateListener, MediaErrorListener {

        private final CountDownLatch ready = new CountDownLatch(1);
        private final CountDownLatch playing = new CountDownLatch(1);
        private final CountDownLatch finished = new CountDownLatch(1);
        private final List<String> failures = new CopyOnWriteArrayList<>();

        void await(CountDownLatch latch, String state) throws InterruptedException {
            if (!latch.await(STATE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("the player did not reach " + state + " within "
                        + STATE_TIMEOUT_MILLIS + " ms; failures: " + failures);
            }
        }

        boolean finished() {
            return finished.getCount() == 0;
        }

        @Override
        public void onReady(PlayerStateEvent evt) {
            ready.countDown();
        }

        @Override
        public void onPlaying(PlayerStateEvent evt) {
            playing.countDown();
        }

        @Override
        public void onPause(PlayerStateEvent evt) {
        }

        @Override
        public void onStop(PlayerStateEvent evt) {
        }

        @Override
        public void onStall(PlayerStateEvent evt) {
        }

        @Override
        public void onFinish(PlayerStateEvent evt) {
            finished.countDown();
        }

        @Override
        public void onHalt(PlayerStateEvent evt) {
            failures.add("halt: " + evt.getMessage());
        }

        @Override
        public void onError(Object source, int errorCode, String message) {
            failures.add("error " + errorCode + ": " + message);
        }
    }
}
