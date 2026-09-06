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
import com.sun.media.jfxmedia.events.PlayerStateEvent;
import com.sun.media.jfxmedia.events.PlayerStateListener;
import com.sun.media.jfxmedia.locator.ConnectionHolder;
import com.sun.media.jfxmedia.locator.Locator;
import com.sun.media.jfxmedia.logging.Logger;
import com.sun.media.jfxmedia.track.AudioTrack;
import com.sun.media.jfxmediaimpl.JfxMediaNative;
import com.sun.media.jfxmediaimpl.NativeMedia;
import com.sun.media.jfxmediaimpl.NativeMediaPlayer;
import com.sun.media.jfxmediaimpl.platform.gstreamer.GSTPlatform;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntUnaryOperator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Binding tests for {@link JfxMediaNative}, the {@code jfxm_*} facade that replaced the module's JNI
 * (see {@code modules/javafx.media/FFM-ABI-CONTRACT.md}). All but one of them are hardware free: no
 * audio device is opened, and the pipeline {@link #mediaOverATinyWavFileDisposesWithoutLeaks} builds is
 * only created and linked, never started. The exception is
 * {@link #playerOverATinyWavFileDisposesWithoutLeaks}: {@code jfxm_player_init} takes the pipeline to
 * {@code PAUSED}, which does open the platform's audio sink, so that one test - and only that one - is
 * skipped where no audio output exists - and even then it asserts the no-device path before it skips.
 * The library itself is not optional: this module builds it, so {@link MediaNatives#require} fails the
 * class when a reachable {@code jfxmedia} cannot be used and skips only when this build produced none at
 * all.
 * <p>
 * Nothing here plays anything. {@link MediaPlaybackTest} is the class that does, and it is the only place
 * the {@code audio_track} and {@code audio_spectrum} slots and the Playing and Finished halves of the
 * {@code state} slot are exercised at all; what this class contributes to those is
 * {@link #callbackTableSlotOffsetsMatchTheCompiledStructs}, which pins every slot's offset against the C
 * compiler but, as its own javadoc says, cannot reach the slot names.
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
    private static final int BOUND_SYMBOL_COUNT = 58;

    private static final int ERROR_NONE = MediaError.ERROR_NONE.code();
    private static final int ERROR_MEDIA_NULL = MediaError.ERROR_MEDIA_NULL.code();

    /**
     * {@code HLSConnectionHolder.HLS_PROP_GET_DURATION}, the property id {@code java_source_query} emits
     * while it is serving a duration query. Copied rather than referenced: the holder is package private.
     */
    private static final int HLS_PROP_GET_DURATION = 1;

    /**
     * How long a player gets to preroll before {@link #playerOverATinyWavFileDisposesWithoutLeaks} gives
     * up on it. Measured on an idle machine the Ready arrives within a fraction of a millisecond of the
     * player being handed over; this is a bound, not an expectation, and generous enough that a loaded
     * CI machine cannot reach it while a pipeline that never prerolls still fails rather than hangs.
     */
    private static final long PREROLL_TIMEOUT_SECONDS = 10L;

    /** How long a disposed player's event queue thread gets to end before it is called a leak. */
    private static final long TEARDOWN_TIMEOUT_MILLIS = 15_000L;

    @BeforeAll
    static void loadLibrary() {
        MediaNatives.require();
    }

    @Test
    @Order(1)
    void logSinkDeliversNativeMessages() {
        List<String> messages = new CopyOnWriteArrayList<>();
        JfxMediaNative.setLogObserver((level, message) -> messages.add(level + ": " + message));
        try {
            assertTrue(JfxMediaNative.logInit(), "jfxm_log_init: the native log sink could not be installed");
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
        assertEquals(4, JfxMediaNative.JFXM_ABI_VERSION);
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
     * The same guard for the two callback tables, and the one check {@code sizeof} cannot stand in for.
     * Every slot of {@code JfxmPlayerCallbacks} and {@code JfxmStreamCallbacks} is a function pointer, so
     * every permutation of either struct has the same {@code sizeof} and
     * {@link #structLayoutsMatchTheCompiledStructs} goes on passing while the Java layout fills slot
     * <i>n</i> with what C compiled at slot <i>m</i> - a new frame delivered to the warning handler, a
     * {@code seek} answered by {@code copy_block}, and nothing failing anywhere.
     * <p>
     * What makes this more than arithmetic is where each side of the comparison comes from.
     * {@code jfxm_offsetof_*_callbacks} switches on the field index, resolves it to a <em>named</em>
     * struct member and returns the C compiler's {@code offsetof} of that member, so its answer follows
     * the struct's declaration order. The Java side follows the slot list the layout was built from. Move
     * a member within the C struct without renumbering the index enum beside it - the one edit that
     * silently redirects every upcall - and the two stop agreeing here, index by index, with the slot
     * named in the failure. Writing {@code i * 8} as the expectation instead would assert the Java layout
     * against itself and see none of it.
     * <p>
     * What this cannot reach, and the reason it is a guard rather than a proof: the slot <em>names</em>
     * never cross the boundary. C offers an index, not a name, so reordering
     * {@code JfxMediaNative.PLAYER_SLOTS} on the Java side moves both sides of every comparison together
     * and stays green. Closing that would need C to export the names. Until it does, the Java list order
     * is what has to be read against {@code jfxmedia_api.h} by eye when a slot is added.
     */
    @Test
    void callbackTableSlotOffsetsMatchTheCompiledStructs() {
        assertSlotOffsets("JfxmPlayerCallbacks", JfxMediaNative.PLAYER_CALLBACKS_FIELDS,
                JfxMediaNative.PLAYER_CALLBACKS, JfxMediaNative::offsetofPlayerCallbacks);
        assertSlotOffsets("JfxmStreamCallbacks", JfxMediaNative.STREAM_CALLBACKS_FIELDS,
                JfxMediaNative.STREAM_CALLBACKS, JfxMediaNative::offsetofStreamCallbacks);
    }

    private static void assertSlotOffsets(String struct, List<String> slots, StructLayout layout,
                                          IntUnaryOperator offsetof) {
        assertEquals(layout.memberLayouts().size(), slots.size(), struct + ": slot count");
        for (int i = 0; i < slots.size(); i++) {
            String slot = slots.get(i);
            long layoutOffset = layout.byteOffset(PathElement.groupElement(slot));
            assertEquals(offsetof.applyAsInt(i), layoutOffset,
                    "offsetof(" + struct + ", " + slot + ") vs the Java layout");
        }
        assertEquals(-1, offsetof.applyAsInt(slots.size()), struct + ": index past the last slot");
        assertEquals(-1, offsetof.applyAsInt(-1), struct + ": negative index");
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

    /** The {@code eventPlayer*} constants in {@code CPipeline::PlayerState} order. */
    private static int[] eventPlayerConstants() throws ReflectiveOperationException {
        String[] names = { "eventPlayerUnknown", "eventPlayerReady", "eventPlayerPlaying",
            "eventPlayerPaused", "eventPlayerStopped", "eventPlayerStalled", "eventPlayerFinished",
            "eventPlayerError" };
        return constantsOf(NativeMediaPlayer.class, names);
    }

    /**
     * The same guard for {@code AudioTrack}'s channel mask bits, which {@code FfiPlayerEventDispatcher}
     * carries its own copy of and ORs together for every audio track it reports. A renumbering on either
     * side would mislabel every track's channels with nothing failing, so C is compared against the
     * constants themselves.
     */
    @Test
    void audioTrackChannelMappingMatchesTheTrackConstants() throws ReflectiveOperationException {
        int[] expected = constantsOf(AudioTrack.class, new String[] { "UNKNOWN", "FRONT_LEFT",
            "FRONT_RIGHT", "FRONT_CENTER", "REAR_LEFT", "REAR_RIGHT", "REAR_CENTER" });
        for (int channel = 0; channel < expected.length; channel++) {
            assertEquals(expected[channel], JfxMediaNative.audioTrackChannel(channel),
                    "channel " + channel);
        }
        assertEquals(-1, JfxMediaNative.audioTrackChannel(expected.length));
        assertEquals(-1, JfxMediaNative.audioTrackChannel(-1));
    }

    /**
     * And for {@code Logger}'s levels, which {@code jni/Logger.h} carries its own copy of and stamps on
     * every message the native log sink delivers. A renumbering on either side would shift every native
     * log level - messages would still arrive, at the wrong severity - so C is compared against the
     * constants themselves.
     */
    @Test
    void logLevelMappingMatchesTheLoggerConstants() throws ReflectiveOperationException {
        int[] expected = constantsOf(Logger.class,
                new String[] { "DEBUG", "INFO", "WARNING", "ERROR", "OFF" });
        for (int level = 0; level < expected.length; level++) {
            assertEquals(expected[level], JfxMediaNative.logLevel(level), "level " + level);
        }
        assertEquals(-1, JfxMediaNative.logLevel(expected.length));
        assertEquals(-1, JfxMediaNative.logLevel(-1));
    }

    /**
     * Reads {@code int} constants reflectively rather than naming them in code: they are compile-time
     * constants, so {@code javac} would fold this class's copy of them in and a later change to the
     * declaring class would not reach the comparison until something happened to recompile the tests.
     */
    private static int[] constantsOf(Class<?> owner, String[] names) throws ReflectiveOperationException {
        int[] values = new int[names.length];
        for (int i = 0; i < names.length; i++) {
            values[i] = owner.getField(names[i]).getInt(null);
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
     * The one path through {@code jfxm_spectrum_set_bands} that no player can reach: a NULL spectrum,
     * which takes the pair nowhere and hands it straight back. C still owes the release action exactly
     * one call - contract section 11 and {@code jfxmedia_api.h} both promise it "including when spectrum
     * is NULL" - because the facade registers the handover before it makes the call, and only that
     * release retires the entry. Never running it strands the entry for the life of the JVM; running it
     * twice would fire the upcall on a holder C has already destroyed.
     * <p>
     * With no spectrum there is nothing else that could be holding a reference, so the release happens
     * inside the call and the counter can be read immediately after it.
     */
    @Test
    void aNullSpectrumStillHandsTheBandsBackExactlyOnce() {
        int baseline = JfxMediaNative.registrySize();
        AtomicInteger released = new AtomicInteger();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment magnitudes = arena.allocate(JAVA_FLOAT, 8);
            MemorySegment phases = arena.allocate(JAVA_FLOAT, 8);

            JfxMediaNative.spectrumSetBands(0L, 8, magnitudes, phases, released::incrementAndGet);
            assertEquals(1, released.get(), "a NULL spectrum released the pair a number of times that is not once");
            assertEquals(baseline, JfxMediaNative.registrySize(), "the band handover was left in the registry");

            // A null action asks for no handover at all: nothing is registered, C is handed no way to
            // give the pair back, and the call still has to be harmless.
            JfxMediaNative.spectrumSetBands(0L, 8, magnitudes, phases, null);
            assertEquals(baseline, JfxMediaNative.registrySize(), "a null release action registered a handover");
        }
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
        int level = AudioOutput.loggerLevel();
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

    /**
     * The fixture every test over the tiny WAV file rests on is silence, and nothing else checks that it
     * is. Its data chunk is filled with 0x80, the midpoint of unsigned 8 bit PCM; the obvious spelling -
     * the zero fill this fixture used to leave behind - is full scale negative DC instead, and nothing
     * else here would tell the two apart. {@link #copyBlockReportsHowManyBytesItCopied} compares what
     * came back out of {@code copy_block} against {@code TinyWav.bytes()}, which holds the fixture up
     * against itself and so passes on any fill at all.
     * <p>
     * The header fields the fill depends on are pinned with it. 0x80 is the silent level of 8 bit
     * samples only, so the bits per sample field is checked; the samples have to begin where the header
     * ends and run to the end of the file, so the data chunk's tag and its length are checked at the
     * offsets the format fixes them at. The length check would not notice {@code TinyWav.HEADER_SIZE}
     * move, being the very expression {@code TinyWav.bytes()} wrote that field from, and neither would
     * the fill loop, which starts there; so two more assertions cover that. The constant is required to
     * still be the canonical 44, and the RIFF chunk size - the one header field {@code TinyWav.bytes()}
     * spells out the format's way instead of deriving from {@code HEADER_SIZE} - is required to agree
     * with the length of the array it actually produced.
     * <p>
     * {@code TinyWav.SIZE} is not pinned here, because everything that could pin it tracks it instead:
     * the size check, both chunk lengths and the fill loop's bound. A changed size shows up only as a
     * changed duration in {@link #playerOverATinyWavFileDisposesWithoutLeaks}, the one test in this
     * class that a machine with no audio output skips.
     * <p>
     * Nothing here touches the native layer. It sits in this class because the fixture does, which means
     * it is skipped along with everything else on a build that produced no natives, even though it needs
     * none.
     */
    @Test
    void theTinyWavFixtureIsSilentAndNotZeroFilled() {
        byte[] wav = TinyWav.bytes();
        assertEquals(TinyWav.SIZE, wav.length, "the fixture's size");
        assertEquals(44, TinyWav.HEADER_SIZE,
                "the canonical RIFF/WAVE header size, which the data chunk length and the fill derive from");

        // The header fields at the offsets the canonical RIFF/WAVE layout fixes them at.
        ByteBuffer header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(TinyWav.SIZE - 8, header.getInt(4),
                "the RIFF chunk size, the one header field that does not derive from HEADER_SIZE");
        assertEquals(8, header.getShort(34), "bits per sample");
        assertEquals("data", new String(wav, 36, 4, StandardCharsets.US_ASCII), "the data chunk tag");
        assertEquals(TinyWav.SIZE - TinyWav.HEADER_SIZE, header.getInt(40), "the data chunk length");

        for (int i = TinyWav.HEADER_SIZE; i < TinyWav.SIZE; i++) {
            assertEquals((byte) 0x80, wav[i], "sample byte " + i + " is not the unsigned 8 bit silent level");
        }
    }

    /**
     * ABI revision 3 gave {@code copy_block} an {@code int32_t} return, because the {@code void} slot it
     * replaced could not tell a short copy from a good one and C pushed the window on to the demuxer as
     * media data either way (contract section 9). Three answers matter: the normal path returns
     * {@code size} and the window holds the staged bytes; a window wider than anything the holder has
     * staged comes back short, zero filled past the shortfall and logged as an ERROR; and a target the
     * registry no longer has returns 0, which is also what a {@code copy-block} emission with no
     * connected handler yields - correctly so, since that case used to leave the window wholly
     * uninitialised.
     */
    @Test
    void copyBlockReportsHowManyBytesItCopied(@TempDir Path dir) throws Exception {
        ConnectionHolder holder = connectionHolder(dir);
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        PrintStream err = System.err;
        int level = AudioOutput.loggerLevel();
        try (Arena arena = Arena.ofConfined()) {
            JfxMediaNative.CallbackTable callbacks = JfxMediaNative.installStreamCallbacks(arena, holder);
            try {
                assertEquals(TinyWav.SIZE, JfxMediaNative.invokeSlot(callbacks, "read_next_block"));

                MemorySegment window = arena.allocate(TinyWav.SIZE);
                assertEquals(TinyWav.SIZE,
                        JfxMediaNative.invokeSlot(callbacks, "copy_block", window, TinyWav.SIZE),
                        "the whole window was staged, so the whole window was copied");
                assertArrayEquals(TinyWav.bytes(), window.toArray(JAVA_BYTE), "the staged bytes");

                int staged = holder.getBuffer().capacity();
                int tail = 16;
                MemorySegment wide = arena.allocate(staged + (long) tail);
                wide.fill((byte) 0x5a);
                System.setErr(new PrintStream(log, true, StandardCharsets.UTF_8));
                Logger.setLevel(Logger.ERROR);
                assertEquals(staged,
                        JfxMediaNative.invokeSlot(callbacks, "copy_block", wide, staged + tail),
                        "a window wider than the staged buffer comes back short");
                for (int i = 0; i < tail; i++) {
                    assertEquals((byte) 0, wide.get(JAVA_BYTE, staged + i), "byte " + i + " past the shortfall");
                }

                callbacks.unregister();
                assertEquals(0, JfxMediaNative.invokeSlot(callbacks, "copy_block", window, TinyWav.SIZE),
                        "a late copy_block found a target");
            } finally {
                Logger.setLevel(level);
                System.setErr(err);
                callbacks.unregister();
            }
        } finally {
            holder.closeConnection();
        }

        String logged = log.toString(StandardCharsets.UTF_8);
        assertTrue(logged.contains("copy_block"), logged);
        assertTrue(logged.contains("zero filled"), logged);
    }

    /**
     * The other answer a read can give, and the only one this class did not pin. {@code ConnectionHolder}
     * specifies -1 for a read that reached the end of the stream (and, for the positioned read, a
     * position at or past the end of the file), and the two upcall targets hand that straight back:
     * {@code onReadNextBlock} and {@code onReadBlock} return -2 only for a target the registry no longer
     * has and for a target that threw.
     * <p>
     * C reads the difference. {@code javasource} takes -1 to {@code GST_EVENT_EOS} on its push path in
     * the default mode and to a segment request in HLS mode, and to {@code GST_FLOW_EOS} on its pull
     * path, while -2 becomes {@code GST_FLOW_FLUSHING} on both - its {@code EOS_CODE} and
     * {@code OTHER_ERROR_CODE}. {@code AVFMediaPlayer}'s resource-loader delegate ends its fill loop on
     * either, then finishes the request on -1 and fails it with {@code finishLoadingWithError:} on
     * anything else negative - its {@code READ_EOS_CODE}. Every other read assertion in this class pins
     * -2 or a byte count, so a holder or a target that stopped propagating -1 would turn an ordinary end
     * of file into a failed load, and nothing here would say so.
     */
    @Test
    void aReadAtTheEndOfTheStreamReportsEosAndNotAFailure(@TempDir Path dir) throws Exception {
        ConnectionHolder holder = connectionHolder(dir);
        try (Arena arena = Arena.ofConfined()) {
            JfxMediaNative.CallbackTable callbacks = JfxMediaNative.installStreamCallbacks(arena, holder);
            try {
                assertEquals(TinyWav.SIZE, JfxMediaNative.invokeSlot(callbacks, "read_next_block"),
                        "the first sequential read stages the whole file");
                assertEquals(-1, JfxMediaNative.invokeSlot(callbacks, "read_next_block"),
                        "a sequential read with the file already consumed is EOS, not a failed read");
                assertEquals(-1, JfxMediaNative.invokeSlot(callbacks, "read_block", (long) TinyWav.SIZE, 16),
                        "a positioned read starting at the end of the file is EOS, not a failed read");
            } finally {
                callbacks.unregister();
            }
        } finally {
            holder.closeConnection();
        }
    }

    /**
     * The scratch cell's real invariant, pinned where neither the compiler nor the ABI can check it.
     * <p>
     * {@code JfxMediaNative} gives each thread one out-parameter cell and hands it to all eight
     * out-parameter wrappers, and upcalls genuinely do run nested inside those wrappers on the caller's
     * thread: {@code jfxm_player_get_duration} asks the pipeline through
     * {@code gst_element_query_duration}, which is served synchronously on the calling thread, and on an
     * HLS source that query reaches {@code java_source_query}, which emits {@code HLS_PROP_GET_DURATION}
     * and arrives back in the facade as the {@code property} upcall. What keeps the outer call's out
     * value intact is therefore write ordering, not the absence of the nesting: the natives write
     * {@code *out} last, and an upcall target may reach an out-parameter wrapper only on a thread that
     * never issues one itself. That second rule is narrower than a blanket ban on upcall targets
     * reaching wrappers, because one target does reach one: {@code onNewFrame} reaches
     * {@code frameGetInfo} through {@code NativeVideoBuffer}, and it is safe only because
     * {@code new_frame} is delivered on the appsink streaming thread or the AVF display-link thread,
     * neither of which ever runs a wrapper.
     * <p>
     * This test pins the second of those for the stream callbacks - the targets that do arrive on a
     * thread issuing out-parameter downcalls. It seeds the cell the way a native that had already
     * written {@code *out} would leave it - the pessimistic ordering, so that any claim of the cell
     * shows - runs every stream slot through its real function pointer, which is the same upcall stub
     * and the same thread a nested query uses, and requires the cell back byte for byte with its out
     * value unchanged. It fails the day one of those targets reaches an out-parameter wrapper, which on
     * this thread is exactly the edit the {@code SCRATCH} javadoc forbids. It says nothing about
     * {@code onNewFrame}, which the rule permits and no assertion here covers. Nothing here needs a
     * player, a pipeline or an audio device.
     */
    @Test
    @Order(12)
    void anUpcallNestedInAnOutParameterDowncallLeavesTheScratchCellAlone(@TempDir Path dir) throws Exception {
        ConnectionHolder holder = connectionHolder(dir);
        try (Arena arena = Arena.ofConfined()) {
            JfxMediaNative.CallbackTable callbacks = JfxMediaNative.installStreamCallbacks(arena, holder);
            try {
                MemorySegment cell = JfxMediaNative.scratchCell();
                assertSame(cell, JfxMediaNative.scratchCell(),
                        "one cell per thread: a nested wrapper would write through the outer call's out pointer");
                assertEquals(JfxMediaNative.FRAME_INFO.byteSize(), cell.byteSize(), "the cell is sized for the widest");

                byte[] outParameter = new byte[(int) cell.byteSize()];
                for (int i = 0; i < outParameter.length; i++) {
                    outParameter[i] = (byte) (0xA5 ^ i);
                }
                MemorySegment.copy(outParameter, 0, cell, JAVA_BYTE, 0L, outParameter.length);
                double outValue = cell.get(JAVA_DOUBLE, 0L);

                // Every slot C can call while an out-parameter downcall of ours is on the stack, called
                // the way C calls it. close_connection goes last because it ends the connection.
                assertEquals(0, JfxMediaNative.invokeSlot(callbacks, "need_buffer"));
                assertEquals(1, JfxMediaNative.invokeSlot(callbacks, "is_seekable"));
                assertEquals(1, JfxMediaNative.invokeSlot(callbacks, "is_random_access"));
                assertEquals(0, JfxMediaNative.invokeSlot(callbacks, "property",
                        HLS_PROP_GET_DURATION, 0), "the duration property, the upcall of the nested path");
                assertEquals(0L, JfxMediaNative.invokeSlot(callbacks, "seek", 0L));
                assertEquals(TinyWav.SIZE, JfxMediaNative.invokeSlot(callbacks, "read_next_block"));
                MemorySegment window = arena.allocate(TinyWav.SIZE);
                assertEquals(TinyWav.SIZE, JfxMediaNative.invokeSlot(callbacks, "copy_block", window, TinyWav.SIZE));
                assertEquals(TinyWav.SIZE, JfxMediaNative.invokeSlot(callbacks, "read_block", 0L, TinyWav.SIZE));
                assertNull(JfxMediaNative.invokeSlot(callbacks, "close_connection"), "a void slot");

                assertSame(cell, JfxMediaNative.scratchCell(), "the cell is per thread and never replaced");
                assertArrayEquals(outParameter, cell.toArray(JAVA_BYTE),
                        "an upcall target claimed the scratch cell of the downcall it was nested in");
                assertEquals(outValue, cell.get(JAVA_DOUBLE, 0L), "the outer call's out value was corrupted");
            } finally {
                callbacks.unregister();
            }
        } finally {
            holder.closeConnection();
        }
    }

    /** A real, open {@code FileConnectionHolder} over the tiny WAV file; needs nothing native. */
    private static ConnectionHolder connectionHolder(Path dir) throws IOException, URISyntaxException {
        Locator locator = new Locator(TinyWav.writeTo(dir.resolve("silence.wav")).toUri());
        return locator.createConnectionHolder();
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
    void mediaOverATinyWavFileDisposesWithoutLeaks() throws IOException, URISyntaxException {
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
     * That null is the one this test tolerates, and only with the evidence for it: {@link AudioOutput}
     * captures the media log around the call, skips when the log shows the platform could not open its
     * audio output, fails when it does not, and puts the whole captured log and the name of everything
     * the skip leaves uncovered into either message, so that neither outcome is a mystery. What still
     * covers this path when it is skipped is {@link #mediaOverATinyWavFileDisposesWithoutLeaks}, which
     * exercises the same registry, arena and stream-callback lifecycle without a sink, plus both Linux
     * CI jobs, where a sink does open (the workflow installs a null ALSA device for exactly that reason).
     * <p>
     * <b>What a dispose owes, and how much of it is measurable.</b> Three things are asserted after
     * {@code dispose()}, and one is deliberately not. The registry has to be back where it started, which
     * is what proves the arenas and their ids were released in the right order. The player's
     * {@code EventQueueThread} has to have ended, which is the one thread a player owns that a JVM can
     * see and count; a dispose that stopped reaching {@code terminateLoop} would leave one per player
     * here and per {@code AudioClip.play()} in {@code MediaPlaybackTest}. And reaching either assertion
     * is itself the proof that {@code dispose()} returned rather than blocking in a native join. What is
     * <em>not</em> asserted is native memory: the leaks in this stack are tens of bytes against
     * megabytes of pipeline allocation, and no measurement Java can make separates them.
     * {@link MediaThreads} sets out that boundary in full.
     * <p>
     * Nothing the pipeline knows about the stream is knowable before it has prerolled.
     * {@code CGstAudioPlaybackPipeline::Init} ends by asking for {@code GST_STATE_PAUSED} and does not
     * wait for it, and until {@code wavparse}'s loop task has pulled the 44-byte header through
     * {@code javasource} the duration query fails, {@code playerGetDuration} throws and
     * {@link NativeMediaPlayer#getDuration()} swallows that into {@code Infinity}. So the duration is
     * asked for only after the Ready that {@code CGstAudioPlaybackPipeline::BusCallback} publishes once
     * the pipeline has reached {@code PAUSED} with no state change pending - which is also the only
     * coverage the {@code player_state} upcall slot of {@code JfxmPlayerCallbacks} has. Any future
     * assertion on a {@code jfxm_player_get_*} value belongs after that wait too:
     * {@code jfxm_player_get_presentation_time} is the same shape, its {@code GetStreamTime} answering
     * {@code ERROR_GSTREAMER_PIPELINE_QUERY_POSITION} for as long as the pipeline is not up yet.
     * <p>
     * Nothing is played here. {@link MediaPlaybackTest} is where a file is decoded and run to its end,
     * and where the {@code audio_track}, {@code audio_spectrum} and Playing and Finished halves of the
     * {@code state} slot are covered; this test stops at {@code PAUSED} because everything below is about
     * what a player that never played still has to give back.
     */
    @Test
    @Order(10)
    void playerOverATinyWavFileDisposesWithoutLeaks() throws IOException, InterruptedException, URISyntaxException {
        Path file = tinyWavFile();
        try {
            Locator locator = tinyWavLocator(file);
            int baseline = JfxMediaNative.registrySize();
            int baselineEventQueues = MediaThreads.count(MediaThreads.EVENT_QUEUE);

            AudioOutput.Attempt attempt = AudioOutput.createPlayer(locator);
            if (attempt.player() == null) {
                assertEquals(baseline, JfxMediaNative.registrySize(),
                        "a player whose pipeline never started left its callback registry entry behind");
                MediaThreads.awaitCountAtMost(MediaThreads.EVENT_QUEUE, baselineEventQueues,
                        TEARDOWN_TIMEOUT_MILLIS,
                        "a player whose pipeline never started left its event queue thread live");
                AudioOutput.reportNoPlayer(attempt.log());      // never returns: aborts or fails
            }
            MediaPlayer player = attempt.player();
            try {
                assertTrue(JfxMediaNative.registrySize() > baseline,
                        "the connection holder and the player must be registered while the player lives");
                assertEquals(baselineEventQueues + 1, MediaThreads.count(MediaThreads.EVENT_QUEUE),
                        "a live player owns exactly one event queue thread");

                // The pipeline is still prerolling; see the javadoc. Registering after the fact is safe:
                // NativeMediaPlayer caches state events until a first listener arrives and replays them
                // to it, so the Ready cannot be missed however early or late this runs.
                ReadyLatch ready = new ReadyLatch();
                player.addMediaPlayerListener(ready);
                assertTrue(ready.await(PREROLL_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                        "the pipeline never prerolled to PAUSED");
                player.removeMediaPlayerListener(ready);

                // Downcalls through the pipeline the player just built.
                assertEquals(0.0025, player.getDuration(), 1.0e-6, "20 bytes of 8 bit mono at 8 kHz");
                assertEquals(PlayerStateEvent.PlayerState.READY, player.getState(),
                        "a prerolled player that was never played is READY and nothing else");

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

                // A rejected band count leaves the spectrum exactly as it was. The pair installed above
                // still belongs to C, which goes on writing through it, so reporting an empty spectrum
                // after the throw would describe memory that is still being filled. NativeAudioSpectrum
                // deviates from the JNI implementation here for that reason, and this is the guard.
                assertThrows(IllegalArgumentException.class, () -> spectrum.setBandCount(1));
                assertEquals(64, spectrum.getBandCount(), "a rejected band count retired the live pair");
                assertEquals(64, spectrum.getMagnitudes(null).length, "a rejected band count emptied the magnitudes");
                assertEquals(64, spectrum.getPhases(null).length, "a rejected band count emptied the phases");

                // Installing a pair registers a band handover that only C can retire, by running the
                // release upcall once it has dropped its last reference to the superseded pair. On the
                // GStreamer backend that happens inside jfxm_spectrum_set_bands itself, so however many
                // times the count changes there is exactly one live handover per spectrum. A registry
                // that grew would mean a superseded pair was never handed back - the leak; one that
                // shrank would mean a pair was handed back while C could still write through it.
                int afterFirst = JfxMediaNative.registrySize();
                spectrum.setBandCount(32);
                spectrum.setBandCount(64);
                spectrum.setBandCount(128);
                assertEquals(afterFirst, JfxMediaNative.registrySize(),
                        "the superseded band handover was not released exactly once per set_bands");
            } finally {
                player.dispose();
            }
            assertEquals(baseline, JfxMediaNative.registrySize(), "dispose left a registry entry behind");
            MediaThreads.awaitCountAtMost(MediaThreads.EVENT_QUEUE, baselineEventQueues,
                    TEARDOWN_TIMEOUT_MILLIS, "a disposed player kept its event queue thread");
        } finally {
            deleteWhenPossible(file);
        }
    }

    /**
     * The other half of what a dispose owes a media that never played, and the half the registry cannot
     * see: the connection itself. {@code close_connection} reaches the holder from
     * {@code CGstPipelineFactory::SourceCloseConnection}, i.e. from the pipeline's
     * {@code READY -> NULL} transition, so a pipeline that never left {@code GST_STATE_NULL} - the media
     * of {@link #mediaOverATinyWavFileDisposesWithoutLeaks}, and any media a player never came up for -
     * never fires it. Unregistering the stream callbacks drops the id that upcall would have arrived
     * through, which is what keeps the registry balanced either way; the {@code FileChannel} or
     * {@code URLConnection} behind it stays open for the life of the JVM unless {@code dispose()} closes
     * the holder itself.
     */
    @Test
    @Order(11)
    void disposingAMediaClosesTheConnectionTheStreamCallbacksNeverClosed()
            throws IOException, URISyntaxException {
        Path file = tinyWavFile();
        try {
            RecordingLocator locator = recordingTinyWavLocator(file);

            Media created = GSTPlatform.getPlatformInstance().createMedia(locator);
            assertNotNull(created, "GSTPlatform.createMedia returned null");
            NativeMedia media = assertInstanceOf(NativeMedia.class, created);
            assertEquals(1, locator.holders.size(), "the media takes exactly one connection holder");
            ConnectionHolder holder = locator.holders.get(0);
            assertTrue(holder.readNextBlock() > 0, "the holder has to be open while the media lives");

            media.dispose();

            assertThrows(ClosedChannelException.class, holder::readNextBlock,
                    "dispose left the connection holder open");
        } finally {
            deleteWhenPossible(file);
        }
    }

    /**
     * The tiny WAV file, in the system temp directory rather than in a {@code @TempDir}: a pipeline that
     * never left {@code GST_STATE_NULL} - the media of the hardware-free test, and a player whose
     * {@code jfxm_player_init} failed - never makes the {@code READY -> NULL} transition on which
     * {@code javasource} emits {@code close-connection}, so nothing but {@code GSTMedia.dispose()} ever
     * closes its connection holder and a test that aborts before it reaches one leaves the file open.
     * Windows will not delete an open file, and a {@code @TempDir} that cannot be cleaned up fails the
     * test it belongs to, which would turn "no audio device" back into a failure.
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

    /**
     * {@code file} as an initialised {@link Locator}; the platform has to recognise it as WAV. Nothing
     * here may be skipped: {@code audio/x-wav} is in both of {@code GSTPlatform}'s content type lists
     * unconditionally, and {@code NativeMediaManager.canPlayContentType} answers from those lists and
     * not from the native layer, so there is no machine on which this legitimately fails.
     */
    private static Locator tinyWavLocator(Path file) throws IOException, URISyntaxException {
        Locator locator = new Locator(file.toUri());
        locator.init();
        assertEquals("audio/x-wav", locator.getContentType());
        return locator;
    }

    /** The same, as a {@link RecordingLocator}. */
    private static RecordingLocator recordingTinyWavLocator(Path file)
            throws IOException, URISyntaxException {
        RecordingLocator locator = new RecordingLocator(file.toUri());
        locator.init();
        assertEquals("audio/x-wav", locator.getContentType());
        return locator;
    }

    /**
     * A {@link Locator} that keeps every connection holder it hands out. The media never exposes the
     * holder it created, so this is how a test asks the holder itself whether it was closed.
     */
    private static final class RecordingLocator extends Locator {
        private final List<ConnectionHolder> holders = new ArrayList<>();

        RecordingLocator(URI uri) throws URISyntaxException {
            super(uri);
        }

        @Override
        public ConnectionHolder createConnectionHolder() throws IOException {
            ConnectionHolder holder = super.createConnectionHolder();
            holders.add(holder);
            return holder;
        }
    }

    /**
     * A {@link PlayerStateListener} that opens its latch on the Ready the player publishes once its
     * pipeline has prerolled, and ignores the other six notifications - a player that is never played
     * sends none of them. This is the only thing that arrives through the {@code player_state} upcall in
     * this class, so the latch opening is also that slot's coverage.
     */
    private static final class ReadyLatch implements PlayerStateListener {
        private final CountDownLatch ready = new CountDownLatch(1);

        boolean await(long timeout, TimeUnit unit) throws InterruptedException {
            return ready.await(timeout, unit);
        }

        @Override
        public void onReady(PlayerStateEvent evt) {
            ready.countDown();
        }

        @Override
        public void onPlaying(PlayerStateEvent evt) {
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
        }

        @Override
        public void onHalt(PlayerStateEvent evt) {
        }
    }
}
