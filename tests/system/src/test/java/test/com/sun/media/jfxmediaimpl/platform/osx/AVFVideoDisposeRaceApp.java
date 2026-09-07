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

package test.com.sun.media.jfxmediaimpl.platform.osx;

import com.sun.media.jfxmedia.Media;
import com.sun.media.jfxmedia.MediaManager;
import com.sun.media.jfxmedia.MediaPlayer;
import com.sun.media.jfxmedia.control.VideoDataBuffer;
import com.sun.media.jfxmedia.control.VideoRenderControl;
import com.sun.media.jfxmedia.events.MediaErrorListener;
import com.sun.media.jfxmedia.events.NewFrameEvent;
import com.sun.media.jfxmedia.events.PlayerStateEvent;
import com.sun.media.jfxmedia.events.PlayerStateEvent.PlayerState;
import com.sun.media.jfxmedia.events.PlayerStateListener;
import com.sun.media.jfxmedia.events.VideoRendererListener;
import com.sun.media.jfxmedia.locator.Locator;
import com.sun.media.jfxmedia.track.Track;
import com.sun.media.jfxmedia.track.VideoTrack;
import com.sun.media.jfxmediaimpl.JfxMediaNative;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * The worker launched by {@link AVFVideoDisposeRaceTest}: create, play and dispose an AVFoundation
 * video player over and over, with video frames genuinely in flight at each dispose.
 *
 * <h2>What it is looking for</h2>
 * {@code -[AVFMediaPlayer dispose]} tears down an object whose {@code CVDisplayLink} callback runs on a
 * thread of CoreVideo's, at the refresh rate of the display, for as long as the player is playing
 * video. Before the display link token registry that {@code AVFMediaPlayer.mm} now carries, that
 * callback dereferenced an unretained {@code self} and reached an {@code eventHandler} that
 * {@code -[OSXMediaPlayer dispose]} deletes, so an ordinary dispose of an ordinary video with one frame
 * in flight was a use after free. The fix registers a token per player and makes {@code -dispose} retire
 * it and wait for every outstanding callback before it goes any further, which trades a crash for the
 * possibility of a deadlock in the wait.
 * <p>
 * Neither failure is an assertion. The first kills the process, the second stops it. That is the whole
 * shape of this worker:
 * <ul>
 * <li>It is a separate JVM, so a SIGSEGV is an exit status the parent reads rather than a dead test
 *     run. {@code tests/system} forks per test class already, so this buys attribution to a single
 *     method and, much more importantly, it buys the parent a {@code waitFor} it can time out.</li>
 * <li>{@code dispose()} runs on its own daemon thread and is joined with a bound. A join that
 *     deadlocked in {@code pthread_cond_wait} cannot be unblocked by an interrupt, so no
 *     {@code @Timeout} in the parent JVM could ever have cut it short; the only thing that can report
 *     it is a thread that is not the one that is stuck.</li>
 * <li>Every exit goes through {@link Runtime#halt}, not {@code System.exit}. A worker whose whole
 *     subject is a native deadlock cannot afford an orderly shutdown that waits for anything.</li>
 * </ul>
 *
 * <h2>How dispose is made to land mid playback</h2>
 * A loop that disposes an idle player tests nothing, so each cycle waits for evidence that frames are
 * moving and then disposes at a deliberately varied moment:
 * <ol>
 * <li>Wait for READY, then for PLAYING. Both are latches on the player's own state events.</li>
 * <li><em>Arm</em> the frame gate after PLAYING has been published, so the frames it counts are ones the
 *     display link delivered during playback. This matters: {@code NativeMediaPlayer} caches and
 *     publishes the frame that arrives when preroll completes, so a gate on "the first frame" could be
 *     satisfied before playback started at all.</li>
 * <li>Wait for {@link #MIN_FRAMES_BEFORE_DISPOSE} to {@code MIN_FRAMES_BEFORE_DISPOSE +
 *     FRAME_GATE_SPREAD - 1} of those frames, the count stepping with the iteration, so dispose lands at
 *     a different position in the stream each time.</li>
 * <li>Park for a sub-frame interval that walks across one 60 Hz refresh period as the iterations go by
 *     ({@link #JITTER_STEP_NANOS} against {@link #VSYNC_NANOS}), which moves the dispose around inside
 *     the gap between two display link callbacks. The coupling is loose and is not claimed to be more
 *     than that: a frame reaches this JVM along the player's event queue thread, so the offset from the
 *     callback that produced it is not something a test can pin. What it does buy is thirty cycles that
 *     dispose at thirty different offsets rather than thirty copies of one, and that is what a window
 *     as narrow as the few microseconds {@code -sendPixelBuffer:} takes needs to be stepped on.</li>
 * <li>Check, immediately before disposing, that a frame arrived within {@link #MAX_FRAME_GAP_MILLIS}
 *     and that end of media has not been reached. A cycle that disposed a stalled or finished player
 *     covered nothing, and says so with {@link Constants#EXIT_NOT_FLOWING} rather than passing.</li>
 * </ol>
 *
 * <h2>The preroll trap</h2>
 * No {@code jfxm_player_get_*} value is knowable before the pipeline has prerolled, and this worker
 * reads several of them. Every one of those reads is below the READY latch and none of them is below a
 * sleep: the duration and the track list are only asked for in {@link #describeAsset}, which is called
 * after {@code awaitReady} has returned true. The track list is then polled rather than read once,
 * because {@code Media.getTracks()} answers null until the {@code video_track} upcall has arrived and
 * nothing orders that against READY.
 */
public final class AVFVideoDisposeRaceApp {

    /** {@code MediaUtils.CONTENT_TYPE_MP4}, which is what the generated movie has to resolve to. */
    private static final String CONTENT_TYPE_MP4 = "video/mp4";

    /**
     * How many create/play/dispose cycles to run. The window is a few microseconds wide per refresh, so
     * one cycle proves nothing; thirty of them, each disposing at a different frame and a different
     * phase of the refresh, is what makes a hit likely while keeping the whole worker inside half a
     * minute on an idle machine.
     */
    private static final int ITERATIONS = 30;

    /**
     * The smallest number of frames that must arrive after PLAYING before a cycle may dispose. Three
     * rather than one, because the first frame a listener sees can be the one cached at preroll.
     */
    private static final int MIN_FRAMES_BEFORE_DISPOSE = 3;

    /** How far the frame gate walks. Cycle {@code i} waits for {@code MIN + i % SPREAD} frames. */
    private static final int FRAME_GATE_SPREAD = 6;

    /** One refresh period at 60 Hz, the interval the dispose jitter is spread across. */
    private static final long VSYNC_NANOS = 16_666_667L;

    /**
     * How far the jitter advances per iteration. Coprime with {@link #VSYNC_NANOS} so that thirty
     * iterations land on thirty different phases, and deliberately not a random number: a race test
     * that cannot be re-run identically cannot be bisected.
     */
    private static final long JITTER_STEP_NANOS = 1_367_000L;

    /** How long the player gets to preroll and publish READY. A bound, not an expectation. */
    private static final long READY_TIMEOUT_MILLIS = 15_000L;

    /** How long {@code play()} gets to publish PLAYING. */
    private static final long PLAYING_TIMEOUT_MILLIS = 15_000L;

    /** How long the armed frame gate gets to fill. At 30 fps it needs about a fifth of a second. */
    private static final long FRAME_TIMEOUT_MILLIS = 15_000L;

    /**
     * How long {@code dispose()} gets to return. The display link join it now performs waits for
     * callbacks that are already running, each of which is a copy of one frame, so this is four orders
     * of magnitude more than it can legitimately need and it exists solely to name a deadlock.
     */
    private static final long DISPOSE_TIMEOUT_MILLIS = 20_000L;

    /** How long the callback registry gets to come back to its baseline after a dispose. */
    private static final long TEARDOWN_TIMEOUT_MILLIS = 15_000L;

    /** How long the {@code video_track} upcall gets to reach {@code NativeMedia.addTrack} after READY. */
    private static final long TRACK_TIMEOUT_MILLIS = 5_000L;

    /**
     * The longest gap between the last frame and the dispose that still counts as "frames were
     * flowing". A 30 fps movie delivers one every 33 ms, so half a second is loose enough that only a
     * genuinely stalled pipeline fails it.
     */
    private static final long MAX_FRAME_GAP_MILLIS = 500L;

    private AVFVideoDisposeRaceApp() {
    }

    public static void main(String[] args) {
        int status;
        try {
            status = run();
        } catch (Throwable t) {
            t.printStackTrace(System.err);
            status = Constants.EXIT_UNEXPECTED;
        }
        System.out.flush();
        System.err.flush();
        // halt, not exit: a native join that did not return owns a thread and a process global mutex,
        // and there is nothing an orderly shutdown could do about either except wait for them.
        Runtime.getRuntime().halt(status);
    }

    private static int run() throws Exception {
        Path movie = TinyMotionJpegMovie.write(Files.createTempFile("jfx-media-avf-dispose-race", ".mp4"));
        movie.toFile().deleteOnExit();
        System.out.println("generated " + Files.size(movie) + " bytes of Photo-JPEG ("
                + TinyMotionJpegMovie.FRAME_COUNT + " frames, " + TinyMotionJpegMovie.seconds() + " s) at "
                + movie);
        try {
            if (!MediaManager.canPlayContentType(CONTENT_TYPE_MP4)) {
                System.err.println("nothing here claims " + CONTENT_TYPE_MP4 + ". On macOS that type is"
                        + " OSXPlatform's alone - GSTPlatform lists only aiff and wav there - so this is"
                        + " jfxmedia_avf failing to load. This build compiles it from source, see"
                        + " modules/javafx.media/native/CMakeLists.txt, so that is a broken build and not"
                        + " a fact about this machine.");
                return Constants.EXIT_NO_PLATFORM;
            }

            for (int iteration = 0; iteration < ITERATIONS; iteration++) {
                int status = cycle(iteration, movie);
                if (status != Constants.EXIT_OK) {
                    return status;
                }
            }
            System.out.println(ITERATIONS + " create/play/dispose cycles completed with frames in flight");
            return Constants.EXIT_OK;
        } finally {
            deleteWhenPossible(movie);
        }
    }

    /**
     * One create/play/dispose cycle. Returns {@link Constants#EXIT_OK} or the code the caller should
     * halt with; nothing here disposes a player on a failing path, because the process is about to be
     * halted and a dispose of a player that is already misbehaving could hang and hide the real code.
     */
    private static int cycle(int iteration, Path movie) throws Exception {
        Locator locator = new Locator(movie.toUri());
        locator.init();
        if (!CONTENT_TYPE_MP4.equals(locator.getContentType())) {
            System.err.println("the locator resolved " + movie.getFileName() + " to "
                    + locator.getContentType() + " rather than " + CONTENT_TYPE_MP4
                    + ", so it would not reach OSXPlatform at all");
            return Constants.EXIT_CONTENT_TYPE;
        }

        int registryBaseline = JfxMediaNative.registrySize();

        MediaPlayer player;
        try {
            player = MediaManager.getPlayer(locator);
        } catch (RuntimeException e) {
            e.printStackTrace(System.err);
            return Constants.EXIT_NO_PLAYER;
        }
        if (player == null) {
            System.err.println("no player for " + locator.getStringLocation() + " on cycle " + iteration
                    + ". Re-run with -Djfxmedia.loglevel=debug for what AVFoundation said.");
            return Constants.EXIT_NO_PLAYER;
        }

        // Both listener lists hold weak references, so these two locals are what keeps the recorders
        // alive for the length of the cycle. They are read again after the dispose, so nothing can
        // collect them early either.
        StateRecorder states = new StateRecorder();
        FrameRecorder frames = new FrameRecorder();
        player.addMediaPlayerListener(states);
        player.addMediaErrorListener(states);

        VideoRenderControl video = player.getVideoRenderControl();
        if (video == null) {
            System.err.println("the player has no video render control, so no frame can be counted");
            return Constants.EXIT_NO_VIDEO_TRACK;
        }
        video.addVideoRendererListener(frames);

        if (!states.awaitReady(READY_TIMEOUT_MILLIS)) {
            System.err.println("cycle " + iteration + " never reached READY within " + READY_TIMEOUT_MILLIS
                    + " ms. States published: " + states.states() + "; failures: " + states.failures());
            return Constants.EXIT_NO_READY;
        }

        // Below the READY latch, and only below it, is where anything may be asked of the player: no
        // jfxm_player_get_* value is knowable before the item has prerolled.
        if (iteration == 0) {
            int described = describeAsset(player);
            if (described != Constants.EXIT_OK) {
                return described;
            }
        }

        player.play();
        if (!states.awaitPlaying(PLAYING_TIMEOUT_MILLIS)) {
            System.err.println("cycle " + iteration + " never reached PLAYING within "
                    + PLAYING_TIMEOUT_MILLIS + " ms. States published: " + states.states());
            return Constants.EXIT_NO_PLAYING;
        }

        int gate = MIN_FRAMES_BEFORE_DISPOSE + iteration % FRAME_GATE_SPREAD;
        frames.arm(gate);
        if (!frames.await(FRAME_TIMEOUT_MILLIS)) {
            System.err.println("cycle " + iteration + " saw " + frames.count() + " video frames in "
                    + FRAME_TIMEOUT_MILLIS + " ms of playing and needed " + gate + " after PLAYING."
                    + " The player is running, so the movie this test generates was not decoded:"
                    + " AVFoundation accepted the asset and produced no pixel buffer for it. Either the"
                    + " Photo-JPEG movie TinyMotionJpegMovie writes is malformed, or this macOS no longer"
                    + " decodes it, and in both cases the fix is to give the test a video source it can"
                    + " play. Nothing about the display link lifetime is covered until it can.");
            return Constants.EXIT_NO_FRAMES;
        }

        long jitterNanos = (iteration * JITTER_STEP_NANOS) % VSYNC_NANOS;
        LockSupport.parkNanos(jitterNanos);

        long gapMillis = frames.millisSinceLastFrame();
        if (gapMillis > MAX_FRAME_GAP_MILLIS) {
            System.err.println("cycle " + iteration + " had no frame for " + gapMillis + " ms when it came"
                    + " to dispose, so nothing was in flight and this cycle covered nothing");
            return Constants.EXIT_NOT_FLOWING;
        }
        if (states.finished()) {
            System.err.println("cycle " + iteration + " reached end of media before it could dispose."
                    + " The generated movie is " + TinyMotionJpegMovie.seconds() + " s long and this"
                    + " should be impossible; a finished player has already stopped its display link.");
            return Constants.EXIT_NOT_FLOWING;
        }

        long framesAtDispose = frames.count();
        double positionAtDispose = frames.lastTimestamp();

        Disposer disposer = new Disposer(player, "avf-dispose-" + iteration);
        disposer.start();
        if (!disposer.awaitCompletion(DISPOSE_TIMEOUT_MILLIS)) {
            System.err.println("cycle " + iteration + ": dispose() did not return within "
                    + DISPOSE_TIMEOUT_MILLIS + " ms. This is the deadlock the display link join risks -"
                    + " -dispose retires the token and waits on gDisplayLinkIdle for every outstanding"
                    + " claim, and a claim that is never released never lets it go. The stack of the"
                    + " thread that is stuck:" + System.lineSeparator() + disposer.stackTrace());
            return Constants.EXIT_DISPOSE_HUNG;
        }
        if (disposer.failure() != null) {
            System.err.println("cycle " + iteration + ": dispose() threw");
            disposer.failure().printStackTrace(System.err);
            return Constants.EXIT_UNEXPECTED;
        }

        System.out.println("cycle " + iteration + ": disposed after " + framesAtDispose + " frames at "
                + positionAtDispose + " s, gate " + gate + ", jitter " + jitterNanos + " ns, last frame "
                + gapMillis + " ms before dispose");

        if (!states.failures().isEmpty()) {
            System.err.println("cycle " + iteration + " reported " + states.failures());
            return Constants.EXIT_MEDIA_ERROR;
        }
        if (!awaitRegistry(registryBaseline)) {
            System.err.println("cycle " + iteration + " left the callback registry at "
                    + JfxMediaNative.registrySize() + " rather than " + registryBaseline
                    + ", so a disposed player kept its upcall entries");
            return Constants.EXIT_REGISTRY;
        }
        return Constants.EXIT_OK;
    }

    /**
     * Everything worth knowing about the generated asset, read once, and all of it behind READY. A
     * missing video track is fatal rather than cosmetic: {@code -createVideoOutput} is only reached when
     * the AVF player saw a track with visual media, and without it there is no display link and this
     * whole worker would run a loop that covers nothing.
     */
    private static int describeAsset(MediaPlayer player) throws InterruptedException {
        double duration = player.getDuration();
        Media media = player.getMedia();
        List<Track> tracks = media == null ? null : awaitTracks(media);
        System.out.println("asset: duration " + duration + " s, header says "
                + TinyMotionJpegMovie.seconds() + " s, tracks " + tracks);

        boolean hasVideo = false;
        if (tracks != null) {
            for (Track track : tracks) {
                hasVideo |= track instanceof VideoTrack;
            }
        }
        if (!hasVideo) {
            System.err.println("the player reported no video track for a movie that is nothing but a"
                    + " video track, so AVFoundation did not parse it as one. Without a video track"
                    + " -createVideoOutput is never called, no CVDisplayLink is built, and there is no"
                    + " callback for a dispose to race.");
            return Constants.EXIT_NO_VIDEO_TRACK;
        }
        return Constants.EXIT_OK;
    }

    /**
     * The tracks the {@code video_track} upcall added, waiting for the first of them.
     * {@code Media.getTracks()} answers null until one arrives, and although the player reports its
     * tracks while it prerolls, the two are published along different paths and nothing orders them.
     */
    private static List<Track> awaitTracks(Media media) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TRACK_TIMEOUT_MILLIS;
        List<Track> tracks = media.getTracks();
        while (tracks == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(25L);
            tracks = media.getTracks();
        }
        return tracks;
    }

    /**
     * Waits for the callback registry to come back to {@code expected}. A wait rather than a reading:
     * the registry ids of a player are dropped by the dispose that ends it, and reading the size on the
     * instant makes both this check and the baseline of the next cycle a race.
     */
    private static boolean awaitRegistry(int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TEARDOWN_TIMEOUT_MILLIS;
        while (JfxMediaNative.registrySize() != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(25L);
        }
        return JfxMediaNative.registrySize() == expected;
    }

    /** Deletes the movie when the platform allows it; {@code deleteOnExit} is the fallback. */
    private static void deleteWhenPossible(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // Something still has it open; it goes at exit.
        }
    }

    /**
     * {@code dispose()} on a thread that is not this one, so that a dispose which never returns can be
     * reported by a thread that still can. The thread is a daemon because it may still be stuck when
     * the worker halts, and it is never interrupted: an interrupt cannot unblock a native condition
     * wait, and pretending otherwise would turn a deadlock into a silent pass.
     */
    private static final class Disposer {

        private final Thread thread;
        private volatile Throwable failure;

        Disposer(MediaPlayer player, String name) {
            thread = new Thread(() -> {
                try {
                    player.dispose();
                } catch (Throwable t) {
                    failure = t;
                }
            }, name);
            thread.setDaemon(true);
        }

        void start() {
            thread.start();
        }

        boolean awaitCompletion(long millis) throws InterruptedException {
            thread.join(millis);
            return !thread.isAlive();
        }

        Throwable failure() {
            return failure;
        }

        String stackTrace() {
            StringBuilder text = new StringBuilder(512);
            for (StackTraceElement element : thread.getStackTrace()) {
                text.append("    at ").append(element).append(System.lineSeparator());
            }
            return text.toString();
        }
    }

    /**
     * Every state the player published, and the errors and halts it must not publish. One latch per
     * state that is waited for; the recorded list is what a failure carries, so that "never reached
     * PLAYING" also says what it reached instead.
     */
    private static final class StateRecorder implements PlayerStateListener, MediaErrorListener {

        private final List<PlayerState> states = new CopyOnWriteArrayList<>();
        private final List<String> failures = new CopyOnWriteArrayList<>();
        private final CountDownLatch ready = new CountDownLatch(1);
        private final CountDownLatch playing = new CountDownLatch(1);
        private volatile boolean finished;

        boolean awaitReady(long millis) throws InterruptedException {
            return ready.await(millis, TimeUnit.MILLISECONDS);
        }

        boolean awaitPlaying(long millis) throws InterruptedException {
            return playing.await(millis, TimeUnit.MILLISECONDS);
        }

        /** Whether the player has stopped delivering frames of its own accord. */
        boolean finished() {
            return finished;
        }

        List<PlayerState> states() {
            return List.copyOf(states);
        }

        List<String> failures() {
            return List.copyOf(failures);
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
            finished = true;
        }

        @Override
        public void onPause(PlayerStateEvent evt) {
            record(evt);
        }

        @Override
        public void onStop(PlayerStateEvent evt) {
            record(evt);
            finished = true;
        }

        @Override
        public void onStall(PlayerStateEvent evt) {
            record(evt);
        }

        @Override
        public void onHalt(PlayerStateEvent evt) {
            record(evt);
            finished = true;
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
     * What the display link delivered: how many frames, when the last one arrived and where in the
     * movie it was. The timestamp is read inside the callback because that is the only place the frame
     * is held; {@code NativeMediaPlayer.HandleRendererEvents} releases it as soon as the listeners
     * return.
     * <p>
     * {@link #releaseVideoFrames} is empty and has to be: this listener holds no frame, and a dispose is
     * exactly when it is called.
     */
    private static final class FrameRecorder implements VideoRendererListener {

        private final AtomicLong frames = new AtomicLong();
        private volatile long lastFrameNanos = System.nanoTime();
        private volatile double lastTimestamp = -1.0;

        /** Opened by the {@code n}th frame after {@link #arm} was called. */
        private volatile CountDownLatch gate = new CountDownLatch(0);

        /**
         * Starts counting {@code count} fresh frames. Called after PLAYING has been published, so that
         * the frame cached at preroll - which every listener is also told about - cannot satisfy it.
         */
        void arm(int count) {
            gate = new CountDownLatch(count);
        }

        boolean await(long millis) throws InterruptedException {
            return gate.await(millis, TimeUnit.MILLISECONDS);
        }

        long count() {
            return frames.get();
        }

        long millisSinceLastFrame() {
            return (System.nanoTime() - lastFrameNanos) / 1_000_000L;
        }

        double lastTimestamp() {
            return lastTimestamp;
        }

        @Override
        public void videoFrameUpdated(NewFrameEvent event) {
            frames.incrementAndGet();
            lastFrameNanos = System.nanoTime();
            VideoDataBuffer frame = event.getFrameData();
            if (frame != null) {
                lastTimestamp = frame.getTimestamp();
            }
            gate.countDown();
        }

        @Override
        public void releaseVideoFrames() {
        }
    }
}
