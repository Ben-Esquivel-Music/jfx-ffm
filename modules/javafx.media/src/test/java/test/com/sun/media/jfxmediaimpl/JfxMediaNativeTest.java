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
import com.sun.media.jfxmedia.MediaError;
import com.sun.media.jfxmedia.MediaPlayer;
import com.sun.media.jfxmedia.effects.AudioEqualizer;
import com.sun.media.jfxmedia.effects.AudioSpectrum;
import com.sun.media.jfxmedia.locator.ConnectionHolder;
import com.sun.media.jfxmedia.locator.Locator;
import com.sun.media.jfxmedia.logging.Logger;
import com.sun.media.jfxmediaimpl.JfxMediaNative;
import com.sun.media.jfxmediaimpl.NativeMedia;
import com.sun.media.jfxmediaimpl.NativeMediaPlayer;
import com.sun.media.jfxmediaimpl.platform.gstreamer.GSTPlatform;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout.PathElement;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.abort;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Binding tests for {@link JfxMediaNative}, the {@code jfxm_*} facade that replaced the module's JNI
 * (see {@code modules/javafx.media/FFM-ABI-CONTRACT.md}). All but one of them are hardware free: no
 * audio device is opened, and the pipeline {@link #mediaOverATinyWavFileDisposesWithoutLeaks} builds is
 * only created and linked, never started. The exception is
 * {@link #playerOverATinyWavFileDisposesWithoutLeaks}: {@code jfxm_player_init} takes the pipeline to
 * {@code PAUSED}, which does open the platform's audio sink, so that one test - and only that one - is
 * skipped where no audio output exists. The class as a whole is skipped when {@code jfxmedia} cannot be
 * loaded, so a build without the media natives stays green.
 * <p>
 * The tests are ordered because two of them depend on being the first caller of
 * {@code jfxm_platform_init} in the JVM: it logs exactly once (the log-sink round trip) and it
 * memoises its result (the idempotence check).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class JfxMediaNativeTest {

    /** {@code sizeof} of the three structs on Windows x64, Linux x64 and macOS (LP64 and LLP64). */
    private static final int SIZEOF_FRAME_INFO = 120;
    private static final int SIZEOF_PLAYER_CALLBACKS = 104;
    private static final int SIZEOF_STREAM_CALLBACKS = 72;

    /** {@code offsetof} of every {@code JfxmFrameInfo} field, in the field-index order of the ABI. */
    private static final int[] FRAME_INFO_OFFSETS = { 0, 8, 12, 16, 20, 24, 28, 32, 36, 40, 56, 88 };

    /** Every function {@code jfxmedia_api.h} exports, all of which the facade binds. */
    private static final int BOUND_SYMBOL_COUNT = 54;

    private static final int ERROR_NONE = MediaError.ERROR_NONE.code();
    private static final int ERROR_MEDIA_NULL = MediaError.ERROR_MEDIA_NULL.code();

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

    @BeforeAll
    static void loadLibrary() {
        try {
            JfxMediaNative.loadLibraries();
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            assumeTrue(false, "jfxmedia is not available: " + e);
        }
    }

    @Test
    @Order(1)
    void logSinkDeliversNativeMessages() {
        List<String> messages = new CopyOnWriteArrayList<>();
        JfxMediaNative.setLogObserver((level, message) -> messages.add(level + ": " + message));
        try {
            assertTrue(JfxMediaNative.logInit(), "jfxm_log_init: logging is compiled into jfxmedia");
            JfxMediaNative.logSetLevel(Logger.DEBUG);

            // The first jfxm_platform_init of the process logs "Initializing GSTPlatform" at DEBUG.
            JfxMediaNative.platformInit();

            assertFalse(messages.isEmpty(), "the native log sink delivered nothing");
            for (String message : messages) {
                assertNotNull(message);
            }
        } finally {
            JfxMediaNative.logSetLevel(Logger.OFF);
            JfxMediaNative.setLogObserver(null);
        }
    }

    /**
     * {@code jfxm_platform_init} is idempotent once it has succeeded: the media manager is a singleton,
     * so a second call hands back the same one and returns {@code ERROR_NONE} again without re-running
     * {@code gst_init_check}. (A failure is not remembered - a later call retries - which is why this
     * asserts on a successful init rather than on the two results simply matching.)
     */
    @Test
    @Order(2)
    void platformInitIsIdempotent() {
        int first = JfxMediaNative.platformInit();
        assertNotNull(MediaError.getFromCode(first), "unknown MediaError code " + first);
        assertEquals(ERROR_NONE, first, "GStreamer failed to initialise");
        assertEquals(first, JfxMediaNative.platformInit(), "a second init changed the result");
    }

    /**
     * Every name has to resolve; nothing here may assert that two names resolve to <em>different</em>
     * addresses. On Windows {@code /OPT:ICF} folds functions with identical bodies, and with
     * {@code __APPLE__} compiled out {@code jfxm_player_get_mute} and {@code jfxm_player_set_mute} are
     * both a handle check followed by {@code return ERROR_NOT_IMPLEMENTED}, so they share one address
     * (contract section 14.1).
     */
    @Test
    void everyBoundSymbolResolves() {
        List<String> bound = JfxMediaNative.boundSymbols();
        assertEquals(BOUND_SYMBOL_COUNT, bound.size(), "bound symbols: " + bound);
        assertEquals(List.of(), JfxMediaNative.missingSymbols(), "jfxmedia does not export these");
        for (String symbol : bound) {
            assertTrue(symbol.startsWith("jfxm_"), symbol);
            assertTrue(JfxMediaNative.resolvesSymbol(symbol), "not exported: " + symbol);
        }
        assertFalse(JfxMediaNative.resolvesSymbol("jfxm_not_a_symbol"));
    }

    @Test
    void abiVersionMatches() {
        assertEquals(1, JfxMediaNative.JFXM_ABI_VERSION);
        assertEquals(JfxMediaNative.JFXM_ABI_VERSION, JfxMediaNative.abiVersion());
    }

    @Test
    void structLayoutsMatchTheCompiledStructs() {
        assertEquals(SIZEOF_FRAME_INFO, JfxMediaNative.sizeofFrameInfo(), "sizeof(JfxmFrameInfo)");
        assertEquals(SIZEOF_PLAYER_CALLBACKS, JfxMediaNative.sizeofPlayerCallbacks(),
                "sizeof(JfxmPlayerCallbacks)");
        assertEquals(SIZEOF_STREAM_CALLBACKS, JfxMediaNative.sizeofStreamCallbacks(),
                "sizeof(JfxmStreamCallbacks)");

        assertEquals(JfxMediaNative.sizeofFrameInfo(), JfxMediaNative.FRAME_INFO.byteSize());
        assertEquals(JfxMediaNative.sizeofPlayerCallbacks(), JfxMediaNative.PLAYER_CALLBACKS.byteSize());
        assertEquals(JfxMediaNative.sizeofStreamCallbacks(), JfxMediaNative.STREAM_CALLBACKS.byteSize());
    }

    @Test
    void frameInfoFieldOffsetsMatchTheCompiledStruct() {
        List<String> fields = JfxMediaNative.FRAME_INFO_FIELDS;
        assertEquals(FRAME_INFO_OFFSETS.length, fields.size());
        for (int i = 0; i < fields.size(); i++) {
            String field = fields.get(i);
            long layoutOffset = JfxMediaNative.FRAME_INFO.byteOffset(PathElement.groupElement(field));
            assertEquals(FRAME_INFO_OFFSETS[i], layoutOffset, "layout offset of " + field);
            assertEquals(FRAME_INFO_OFFSETS[i], JfxMediaNative.offsetofFrameInfo(i),
                    "offsetof(JfxmFrameInfo, " + field + ")");
        }
        assertEquals(-1, JfxMediaNative.offsetofFrameInfo(fields.size()));
        assertEquals(-1, JfxMediaNative.offsetofFrameInfo(-1));
    }

    /**
     * {@code CPipeline::PlayerState} 0..7 maps to the {@code NativeMediaPlayer.eventPlayer*} constants,
     * whose values C now carries its own copy of. The C dispatcher runs this mapping for every state
     * event and {@code NativeMediaPlayer.sendPlayerStateEvent} switches on the result, so if the two
     * sides ever disagree every state is reported as {@code UNKNOWN} and nothing else goes wrong - which
     * is exactly why the guard has to compare C against the constants themselves and not against a copy
     * of what they said when this test was written.
     */
    @Test
    void playerStateMappingMatchesTheEventConstants() throws ReflectiveOperationException {
        int[] expected = eventPlayerConstants();
        for (int state = 0; state < expected.length; state++) {
            assertEquals(expected[state], JfxMediaNative.eventPlayerState(state), "PlayerState " + state);
        }
        assertEquals(-1, JfxMediaNative.eventPlayerState(expected.length));
        assertEquals(-1, JfxMediaNative.eventPlayerState(-1));
    }

    /**
     * The {@code eventPlayer*} constants in {@code CPipeline::PlayerState} order, read reflectively
     * rather than named in code: they are compile-time constants, so {@code javac} would fold this
     * class's copy of them in and a later change to {@code NativeMediaPlayer} would not reach the
     * comparison until something happened to recompile the tests.
     */
    private static int[] eventPlayerConstants() throws ReflectiveOperationException {
        String[] names = { "eventPlayerUnknown", "eventPlayerReady", "eventPlayerPlaying",
            "eventPlayerPaused", "eventPlayerStopped", "eventPlayerStalled", "eventPlayerFinished",
            "eventPlayerError" };
        int[] values = new int[names.length];
        for (int i = 0; i < names.length; i++) {
            values[i] = NativeMediaPlayer.class.getField(names[i]).getInt(null);
        }
        return values;
    }

    @Test
    void nullHandlesReturnTheDocumentedDefaults() {
        assertEquals(ERROR_MEDIA_NULL, JfxMediaNative.playerPlay(0L));
        assertEquals(ERROR_MEDIA_NULL, JfxMediaNative.playerPause(0L));
        assertEquals(ERROR_MEDIA_NULL, JfxMediaNative.playerStop(0L));
        assertEquals(ERROR_MEDIA_NULL, JfxMediaNative.playerFinish(0L));
        assertEquals(ERROR_MEDIA_NULL, JfxMediaNative.playerSeek(0L, 1.0));
        assertEquals(ERROR_MEDIA_NULL, JfxMediaNative.playerGetVolume(0L, new float[1]));
        assertEquals(ERROR_MEDIA_NULL, JfxMediaNative.playerGetDuration(0L, new double[1]));
        assertEquals(ERROR_MEDIA_NULL, JfxMediaNative.playerGetMute(0L, new boolean[1]));
        assertEquals(0L, JfxMediaNative.playerGetAudioEqualizer(0L));
        assertEquals(0L, JfxMediaNative.playerGetAudioSpectrum(0L));

        // The equalizer, spectrum and frame entry points tolerate a null handle, exactly as the JNI
        // code did. jfxm_eq_band_* deliberately does not, so it is not called here.
        assertFalse(JfxMediaNative.eqGetEnabled(0L));
        assertEquals(0, JfxMediaNative.eqGetNumBands(0L));
        assertEquals(0L, JfxMediaNative.eqAddBand(0L, 1000.0, 100.0, 0.0));
        assertFalse(JfxMediaNative.eqRemoveBand(0L, 1000.0));
        assertFalse(JfxMediaNative.spectrumGetEnabled(0L));
        assertEquals(0.0, JfxMediaNative.spectrumGetInterval(0L));
        assertEquals(0, JfxMediaNative.spectrumGetThreshold(0L));

        assertSame(JfxMediaNative.FrameInfo.NONE, JfxMediaNative.frameGetInfo(0L));
        assertEquals(0L, JfxMediaNative.frameConvert(0L, 1));
        JfxMediaNative.frameSetDirty(0L);
        JfxMediaNative.frameDispose(0L);
        JfxMediaNative.mediaDispose(0L);
    }

    /**
     * A plane the frame does not have reads as an empty buffer, never as {@code null} and never as an
     * exception. {@code CVideoFrame::GetDataForPlane} bounds-checked the index against
     * {@code MAX_PLANE_COUNT} rather than against the frame's own plane count and handed everything out
     * of range to {@code NewDirectByteBuffer} as {@code (NULL, 0)}, which is a direct buffer of capacity
     * zero; {@code NativeVideoBuffer.getBufferForPlane} passed that straight to its caller.
     */
    @Test
    void aPlaneTheFrameDoesNotHaveReadsAsAnEmptyBuffer() {
        for (int plane : new int[] { -1, 0, 1, 2, 3, 4, 7 }) {
            ByteBuffer buffer = JfxMediaNative.planeBuffer(JfxMediaNative.FrameInfo.NONE, plane);
            assertNotNull(buffer, "plane " + plane);
            assertTrue(buffer.isDirect(), "plane " + plane);
            assertEquals(0, buffer.capacity(), "plane " + plane);
            assertEquals(ByteOrder.nativeOrder(), buffer.order(), "plane " + plane);
        }
    }

    /**
     * A Throwable that reaches C is undefined behaviour, and every one of the 23 upcall targets is
     * written to make sure none can: it catches, reports the failure the way the JNI code reported a
     * pending exception, and returns. Here the target is a connection holder whose connection is already
     * closed, so the read it does throws, and the slot is called through its function pointer -
     * {@link JfxMediaNative#invokeSlot}, because linking that call is restricted and only the module has
     * native access - exactly as C calls it.
     */
    @Test
    void anUpcallTargetSwallowsWhatItsTargetThrows(@TempDir Path dir) throws Exception {
        ConnectionHolder holder = connectionHolder(dir);
        holder.closeConnection();       // every read from here on throws ClosedChannelException

        ByteArrayOutputStream log = new ByteArrayOutputStream();
        PrintStream err = System.err;
        int level = loggerLevel();
        System.setErr(new PrintStream(log, true, StandardCharsets.UTF_8));
        Logger.setLevel(Logger.ERROR);
        try (Arena arena = Arena.ofConfined()) {
            JfxMediaNative.CallbackTable callbacks = JfxMediaNative.installStreamCallbacks(arena, holder);
            try {
                assertEquals(-2, JfxMediaNative.invokeSlot(callbacks, "read_next_block"),
                        "a stream upcall whose target threw reports -2, as JavaInputStreamCallbacks did");
            } finally {
                callbacks.unregister();
            }
        } finally {
            Logger.setLevel(level);
            System.setErr(err);
        }

        String logged = log.toString(StandardCharsets.UTF_8);
        assertTrue(logged.contains("read_next_block"), logged);
        assertTrue(logged.contains("upcall failed"), logged);
        assertTrue(logged.contains("ClosedChannelException"), logged);
    }

    /**
     * The other half of the same invariant: once the media is disposed nothing may reach its Java target
     * any more. The stubs outlive the dispose by design - they belong to an arena the caller closes only
     * after {@code jfxm_media_dispose} has returned - so what has to stop the late call is the registry
     * id, which the dispose drops. A callback that arrives after it does has to find nothing and report
     * the "no target" value, not reach a holder the pipeline has finished with.
     */
    @Test
    void noUpcallReachesATargetThatHasBeenUnregistered(@TempDir Path dir) throws Exception {
        ConnectionHolder holder = connectionHolder(dir);
        try (Arena arena = Arena.ofConfined()) {
            JfxMediaNative.CallbackTable callbacks = JfxMediaNative.installStreamCallbacks(arena, holder);

            assertEquals(1, JfxMediaNative.invokeSlot(callbacks, "is_seekable"),
                    "a file connection holder is seekable");

            callbacks.unregister();     // what GSTMedia.dispose does before it closes the arena

            assertEquals(0, JfxMediaNative.invokeSlot(callbacks, "is_seekable"),
                    "a late is_seekable found a target");
            assertEquals(-2, JfxMediaNative.invokeSlot(callbacks, "read_next_block"),
                    "a late read_next_block found a target");
            assertNull(JfxMediaNative.invokeSlot(callbacks, "close_connection"), "a void slot");

            assertEquals(TinyWav.SIZE, holder.readNextBlock(),
                    "a late close_connection upcall reached the holder and closed it");
        } finally {
            holder.closeConnection();
        }
    }

    /** A real, open {@code FileConnectionHolder} over the tiny WAV file; needs nothing native. */
    private static ConnectionHolder connectionHolder(Path dir) throws IOException, URISyntaxException {
        Locator locator = new Locator(TinyWav.writeTo(dir.resolve("silence.wav")).toUri());
        return locator.createConnectionHolder();
    }

    /** The level {@link Logger} is currently at, which it does not otherwise report. */
    private static int loggerLevel() {
        for (int level : new int[] { Logger.DEBUG, Logger.INFO, Logger.WARNING, Logger.ERROR }) {
            if (Logger.canLog(level)) {
                return level;
            }
        }
        return Logger.OFF;
    }

    @Test
    void registryRoundTripsAndLeavesNothingBehind() {
        int baseline = JfxMediaNative.registrySize();
        long[] ids = new long[100];
        for (int i = 0; i < ids.length; i++) {
            Object target = new Object();
            ids[i] = JfxMediaNative.register(target);
            assertNotEquals(0L, ids[i], "0 is never a valid registry id");
            assertSame(target, JfxMediaNative.lookup(ids[i]));
        }
        assertEquals(baseline + ids.length, JfxMediaNative.registrySize());

        for (long id : ids) {
            JfxMediaNative.unregister(id);
            assertNull(JfxMediaNative.lookup(id), "a late callback must find nothing");
        }
        assertEquals(baseline, JfxMediaNative.registrySize());
        JfxMediaNative.unregister(ids[0]);
    }

    /**
     * The half of the end-to-end path that genuinely needs no audio hardware, and therefore runs
     * everywhere: a 64 byte WAV file whose data chunk is 2.5 ms of silence goes through
     * {@code Locator} -> {@code GSTMedia} -> {@code jfxm_media_create}, which builds and links the
     * pipeline's elements and drives the {@code JfxmStreamCallbacks} upcalls into the connection
     * holder, and is then disposed. {@code jfxm_media_create} never changes the pipeline's state, so
     * no sink is opened and nothing here can be skipped: this is what keeps the registry, the shared
     * arena and the stream callbacks covered on a machine with no audio output, where
     * {@link #playerOverATinyWavFileDisposesWithoutLeaks} cannot run.
     * <p>
     * The registry has to be back where it started afterwards, which is what proves the callbacks'
     * arena and its registry ids were released in the right order.
     */
    @Test
    @Order(9)
    void mediaOverATinyWavFileDisposesWithoutLeaks() throws IOException {
        Path file = tinyWavFile();
        try {
            Locator locator = tinyWavLocator(file);
            int baseline = JfxMediaNative.registrySize();

            Media created = GSTPlatform.getPlatformInstance().createMedia(locator);
            assertNotNull(created, "GSTPlatform.createMedia returned null");
            NativeMedia media = assertInstanceOf(NativeMedia.class, created);
            try {
                assertSame(locator, media.getLocator());
                assertTrue(JfxMediaNative.registrySize() > baseline,
                        "the connection holder must be registered while the media lives");
            } finally {
                media.dispose();
            }
            assertEquals(baseline, JfxMediaNative.registrySize(), "dispose left a registry entry behind");
        } finally {
            deleteWhenPossible(file);
        }
    }

    /**
     * The rest of that path, the half that does need an audio device: the same media goes on through
     * {@code GSTMediaPlayer} -> {@code jfxm_player_init}, which installs the 13 player stubs and the
     * spectrum band memory and takes the pipeline to {@code PAUSED}, and is finally disposed. Nothing
     * is ever played, but {@code PAUSED} already opens the audio sink, and
     * {@code CGstPipelineFactory::CreateAudioSinkElement} hardcodes {@code directsoundsink},
     * {@code osxaudiosink} or {@code alsasink} with no null sink to fall back to. On a machine with no
     * audio output the state change therefore fails, {@code GSTMediaPlayer}'s constructor throws
     * {@code MediaException}, and {@code GSTPlatform.createMediaPlayer} swallows it, logs it at DEBUG
     * and returns null - pre-existing upstream behaviour that this test is not the place to change.
     * <p>
     * That null is the one this test tolerates, and only with the evidence for it: the media log is
     * captured around the call, a null player is a skip when the log shows the platform could not open
     * its audio output and a failure when it does not, and the whole captured log goes into either
     * message so that neither outcome is a mystery. What still covers this path when it is skipped is
     * {@link #mediaOverATinyWavFileDisposesWithoutLeaks}, which exercises the same registry, arena and
     * stream-callback lifecycle without a sink, plus the Linux and macOS CI jobs, where a sink does open
     * (the Linux workflow installs a null ALSA device for exactly that reason).
     * <p>
     * The registry has to be back where it started afterwards, which is what proves the arenas and the
     * ids were released in the right order.
     */
    @Test
    @Order(10)
    void playerOverATinyWavFileDisposesWithoutLeaks() throws IOException {
        Path file = tinyWavFile();
        try {
            Locator locator = tinyWavLocator(file);
            int baseline = JfxMediaNative.registrySize();

            PlayerAttempt attempt = createPlayer(locator);
            if (attempt.player() == null) {
                reportNoPlayer(attempt.log());      // never returns: aborts or fails
            }
            MediaPlayer player = attempt.player();
            try {
                assertTrue(JfxMediaNative.registrySize() > baseline,
                        "the connection holder and the player must be registered while the player lives");

                // Downcalls through the pipeline the player just built.
                assertEquals(0.0025, player.getDuration(), 1.0e-6, "20 bytes of 8 bit mono at 8 kHz");
                assertNotNull(player.getState());

                AudioEqualizer equalizer = player.getEqualizer();
                assertNotNull(equalizer);
                assertFalse(equalizer.getEnabled(), "the equalizer is off until it is asked for");

                // The band arrays live in an arena of their own and are filled by C; before any audio
                // has been processed they still hold the value NativeAudioSpectrum seeded them with.
                AudioSpectrum spectrum = player.getAudioSpectrum();
                assertNotNull(spectrum);
                assertEquals(128, spectrum.getBandCount());
                float[] magnitudes = spectrum.getMagnitudes(null);
                assertEquals(128, magnitudes.length);
                assertEquals(-60.0f, magnitudes[0]);
                assertEquals(128, spectrum.getPhases(null).length);
                spectrum.setBandCount(64);
                assertEquals(64, spectrum.getBandCount());
                assertEquals(64, spectrum.getMagnitudes(null).length);
            } finally {
                player.dispose();
            }
            assertEquals(baseline, JfxMediaNative.registrySize(), "dispose left a registry entry behind");
        } finally {
            deleteWhenPossible(file);
        }
    }

    /** What {@link #createPlayer} saw: the player, {@code null} when none was built, and the log. */
    private record PlayerAttempt(MediaPlayer player, String log) {
    }

    /**
     * Creates the player with the media log turned up to DEBUG and captured, so that a null player can
     * be told apart from a broken one.
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
     */
    private static PlayerAttempt createPlayer(Locator locator) {
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
            return new PlayerAttempt(player, captured.toString(StandardCharsets.UTF_8));
        } finally {
            JfxMediaNative.logSetLevel(Logger.OFF);
            Logger.setLevel(level);
            System.setErr(err);
            System.setOut(out);
        }
    }

    /**
     * Waits, for at most {@link #LOG_GRACE_MILLIS}, for the bus thread to log the error that explains a
     * null player. Returning early when it never comes is fine: the caller reports the log it has, and
     * an unexplained null player is a failure.
     */
    private static void awaitNativeError(ByteArrayOutputStream captured) {
        long deadline = System.currentTimeMillis() + LOG_GRACE_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (reportsNoAudioOutput(captured.toString(StandardCharsets.UTF_8))) {
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
     * Never returns: aborts the test when the captured log shows the platform could not open its audio
     * output, and fails it otherwise. Both carry the whole log, because a skip that cannot be explained
     * is as bad as a failure that cannot be.
     */
    private static void reportNoPlayer(String log) {
        String detail = "GSTPlatform.createMediaPlayer returned null; the captured media log was:\n" + log;
        if (reportsNoAudioOutput(log)) {
            abort("this machine has no audio output, so GStreamer could not build a playback pipeline; "
                    + "jfxm_media_create is still covered by mediaOverATinyWavFileDisposesWithoutLeaks. "
                    + detail);
        }
        fail(detail);
    }

    /**
     * Whether the captured log says the platform could not open its audio output, which is the one
     * reason for a null player that is not a regression. Both halves have to be there: the swallowed
     * exception, so that this cannot match a log left over from something else, and one of the
     * {@link #NO_AUDIO_OUTPUT_MARKERS}, so that a null player for any other reason - a failing
     * {@code jfxm_media_create} above all, which reports a {@link MediaError} and no sink - is not
     * quietly skipped.
     */
    private static boolean reportsNoAudioOutput(String log) {
        String lower = log.toLowerCase(Locale.ROOT);
        if (!lower.contains("caught exception while creating media player")) {
            return false;
        }
        return NO_AUDIO_OUTPUT_MARKERS.stream().anyMatch(lower::contains);
    }

    /**
     * The tiny WAV file, in the system temp directory rather than in a {@code @TempDir}: a pipeline that
     * never left {@code GST_STATE_NULL} - the media of the hardware-free test, and a player whose
     * {@code jfxm_player_init} failed - never makes the {@code READY -> NULL} transition on which
     * {@code javasource} emits {@code close-connection}, so its connection holder keeps the file open
     * until it is collected. Windows will not delete an open file, and a {@code @TempDir} that cannot be
     * cleaned up fails the test it belongs to, which would turn "no audio device" back into a failure.
     */
    private static Path tinyWavFile() throws IOException {
        Path file = TinyWav.writeTo(Files.createTempFile("jfx-media-silence", ".wav"));
        file.toFile().deleteOnExit();
        assertEquals(TinyWav.SIZE, Files.size(file));
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
    private static Locator tinyWavLocator(Path file) {
        Locator locator;
        try {
            locator = new Locator(file.toUri());
            locator.init();
        } catch (Exception e) {
            return abort("the platform cannot play audio/x-wav here: " + e);
        }
        assertEquals("audio/x-wav", locator.getContentType());
        return locator;
    }
}
