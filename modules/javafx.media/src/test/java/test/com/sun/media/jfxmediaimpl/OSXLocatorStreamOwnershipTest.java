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
import com.sun.media.jfxmedia.locator.ConnectionHolder;
import com.sun.media.jfxmedia.locator.Locator;
import com.sun.media.jfxmedia.logging.Logger;
import com.sun.media.jfxmediaimpl.JfxMediaNative;
import com.sun.media.jfxmediaimpl.NativeMedia;
import com.sun.media.jfxmediaimpl.platform.Platform;
import com.sun.media.jfxmediaimpl.platform.osx.OSXPlatform;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The teardown contract of the AVFoundation {@code jar:}/{@code jrt:} path: who owns the
 * {@code CLocatorStream} and the {@code CFfiStreamCallbacks} adapter behind it, and what has to have
 * happened to them by the time {@code jfxm_media_dispose} returns.
 * <p>
 * {@code -[AVFMediaPlayer initWithURL:eventHandler:locatorStream:]} takes ownership of that pair from
 * the moment it is entered and frees it through {@code AVFDestroyLocatorStream} - in {@code -dispose},
 * and on the two paths that return nil. Before that change {@code -dispose} called
 * {@code CloseConnection()} and dropped the pointer, so every successful {@code jar:}/{@code jrt:} AVF
 * player leaked both native allocations. Nothing under {@code mediaCreate} / {@code playerInit} /
 * {@code mediaDispose} had any test coverage at all, which is what these two tests close.
 *
 * <h2>What this cannot see, stated up front</h2>
 * The leak itself is a native {@code new} with no matching {@code delete}: nothing Java-visible changes
 * when it happens, so no assertion here can observe it, and none pretends to. Neither can a native
 * <em>double</em> free be observed as anything but a crash. What is observable, and what these tests
 * pin, are the ownership rule's other consequences:
 * <ul>
 * <li>{@code AVFDestroyLocatorStream(ls, YES)} ran and reached the adapter - the {@code close_connection}
 *     upcall arrives and closes the connection.</li>
 * <li>{@code streamArena.close()} did not throw, which is where a use-after-free of the upcall stubs or
 *     an arena closed twice would surface.</li>
 * <li>Disposing again is a no-op: no second {@code jfxm_media_dispose} (which would be the double free
 *     the new ownership rule exists to avoid), no second {@code Arena.close()} and no second close of
 *     the connection - the class of bug that was separately fixed in {@code HLSConnectionHolder}.</li>
 * <li>The process survives all of it. A double {@code AVFDestroyLocatorStream} is a double free, and
 *     the JVM aborting is the only way Java would ever hear about it, so a test that runs to its last
 *     assertion is a real, if indirect, guard on that.</li>
 * </ul>
 *
 * <h2>Why "exactly one closeConnection()" is not what is asserted</h2>
 * On the successful path {@link ConnectionHolder#closeConnection()} is deliberately called
 * <em>twice</em>: once from the native {@code close_connection} upcall during
 * {@code jfxm_media_dispose}, and once from {@code OSXMedia.dispose()}, which closes the holder on
 * every path because a player that never came up has no {@code AVFMediaPlayer} to fire the upcall.
 * Asserting one call there would be asserting something false.
 * <p>
 * Counting them is not possible either. It would need a spying {@link ConnectionHolder}, and
 * {@code ConnectionHolder} has package-private abstract methods, so it cannot be subclassed from a
 * test package; javafx.media has no shim source tree to put one in. Counting closes of the holder's
 * <em>channel</em> instead does not work while the player is alive: {@code URIConnectionHolder.seek()}
 * itself closes and reopens the channel on every data request, and AVFoundation's resource loader
 * issues those from its own queue, so any channel a test installs can be evicted at a moment the test
 * does not control.
 * <p>
 * So the first test does the one thing that is race free and answers the question exactly:
 * <strong>it disarms the Java fallback</strong>. With {@code OSXMedia}'s {@code streamConnection}
 * field set to null, {@code dispose()}'s {@code closeQuietly} has nothing to close, and a connection
 * that is nevertheless closed when {@code dispose()} returns can only have been closed from C. The two
 * closers are then not merely counted apart, they are separated: exactly one of them is left able to
 * run. No interleaving can blur it, because every read of {@code locatorStream} and the whole of
 * {@code -[AVFMediaPlayer dispose]} are held inside the same {@code @synchronized(self)} monitor, so a
 * loading request either completes entirely before the teardown or sees a NULL stream afterwards.
 * <p>
 * A recording channel is used in the second test, where it is safe: by then the AVF player has been
 * disposed and released, so nothing but the second dispose can touch the holder at all.
 *
 * <h2>Hardware</h2>
 * None is needed. {@code jfxm_media_create} on the AVF backend only records the location and the
 * stream table, and {@code jfxm_avf_player_init} builds an {@code AVPlayer} and an
 * {@code AVFAudioProcessor} - C++ objects, no audio unit, no display link, no decoder. The media is a
 * jar entry holding a few bytes that are not media at all, so nothing is ever decoded and no codec,
 * device or network is involved. The jar entry does have to <em>exist</em>: {@code Locator}'s
 * connection holder for a {@code jar:} URL opens the stream in its constructor, so an entry that is
 * not there fails before {@code mediaCreate} is reached and would test none of this.
 */
@EnabledOnOs(OS.MAC)
public class OSXLocatorStreamOwnershipTest {

    /**
     * The entry the test jar carries. Its extension is what {@code MediaUtils.filenameToContentType}
     * answers from, and {@code video/mp4} is in {@code OSXPlatform}'s content types.
     */
    private static final String ENTRY_NAME = "media/not-really-media.mp4";

    /** {@code MediaUtils.CONTENT_TYPE_MP4}. */
    private static final String ENTRY_CONTENT_TYPE = "video/mp4";

    /**
     * The entry's contents: enough bytes for the resource loader to have something to hand over, and
     * not media, so that AVFoundation fails the item rather than opening anything.
     */
    private static final byte[] ENTRY_BYTES =
            "This is not an MPEG-4 file.".getBytes(StandardCharsets.US_ASCII);

    /**
     * {@code JfxMediaNative}'s {@code close_connection} upcall target. A close arriving through this
     * frame came from C, i.e. from {@code AVFDestroyLocatorStream}; a close that does not have it on
     * the stack came from {@code NativeMedia.closeQuietly}, the Java fallback.
     */
    private static final String CLOSE_CONNECTION_TARGET = "onCloseConnection";

    @BeforeAll
    static void loadLibrary() {
        MediaNatives.require();
    }

    /**
     * The whole successful lifecycle - {@code mediaCreate} with a stream table, {@code playerInit},
     * {@code mediaDispose} - with the Java fallback disarmed, so that the connection can only be closed
     * by {@code AVFDestroyLocatorStream(ls, YES)}. The stream arena has to close cleanly and the
     * registry has to come back to where it started.
     */
    @Test
    void disposingAJarBackedAvfPlayerClosesItsConnectionFromNativeCodeAndClosesItsStreamArena()
            throws IOException, URISyntaxException, ReflectiveOperationException {
        Path jar = jarWithOneEntry();
        try {
            // The platform is asked for first so that an OSXPlatform which did not load is reported
            // here and not as the MediaException that Locator.init() then raises: video/mp4 is a type
            // only OSXPlatform claims on macOS, so NativeMediaManager stops recognising it as soon as
            // PlatformManager has dropped that platform.
            Platform platform = avfPlatform();
            RecordingLocator locator = jarLocator(jar);
            int registryBaseline = JfxMediaNative.registrySize();

            MediaPlayer player = createPlayer(platform, locator);
            NativeMedia media = assertInstanceOf(NativeMedia.class, player.getMedia(),
                    "OSXPlatform built a player over something that is not a NativeMedia");

            assertEquals(1, locator.holders.size(), "the media takes exactly one connection holder");
            ConnectionHolder holder = locator.holders.get(0);

            // The preconditions of the ownership contract: this is the jar: path, so a stream table was
            // installed, and the inner AVFMediaPlayer - the object that owns the pair - was built. A
            // non-zero equalizer can only come from -[AVFMediaPlayer audioEqualizer], because
            // -[OSXMediaPlayer audioEqualizer] forwards to an inner player that would answer 0 if the
            // AVF initializer had returned nil. Without that object the teardown under test never runs.
            assertNotNull(field(media, "streamArena"), "the jar: path installs a stream callback arena");
            assertNotNull(field(media, "streamCallbacks"), "the jar: path installs a stream table");
            assertSame(holder, field(media, "streamConnection"), "the media holds its connection");
            long mediaRef = (long) field(media, "refNativeMedia");
            assertNotEquals(0L, mediaRef, "jfxm_media_create returned no handle");
            assertNotEquals(0L, JfxMediaNative.playerGetAudioEqualizer(mediaRef),
                    "jfxm_avf_player_init reported success without building an AVFMediaPlayer, so"
                    + " nothing ever took ownership of the CLocatorStream and the teardown this test is"
                    + " about did not run. Re-run with -Djfxmedia.loglevel=debug for what AVFoundation"
                    + " said.");

            // Disarm the Java half of the close. OSXMedia.dispose() closes streamConnection on every
            // path, which is right - a player that never came up has no AVFMediaPlayer to fire
            // close_connection - but it also means a dropped native close would be invisible. With the
            // field cleared, closeQuietly(null) does nothing and the only code left that can close this
            // holder is CFfiStreamCallbacks::CloseConnection, reached from AVFDestroyLocatorStream.
            // Nothing else in dispose() reads the field.
            setField(media, "streamConnection", null);

            // No crash is not something Java can assert directly. It is asserted by there being
            // anything after this line at all: a double free of the adapter/locator pair aborts the
            // JVM, which surefire reports as a crashed fork and a hs_err_pid log, not as a failure.
            assertDoesNotThrow(player::dispose, "disposing the player threw");

            // The holder can only be in this state because C closed it. A resource-loading request can
            // neither have raced this nor left the holder closed by accident: the loader's whole body
            // and the whole of -[AVFMediaPlayer dispose] are held inside @synchronized(self), and the
            // seek that URIConnectionHolder performs per data request closes and *reopens* the channel.
            assertThrows(ClosedChannelException.class, holder::readNextBlock,
                    "the connection is still open, so nothing closed it: -[AVFMediaPlayer dispose] did"
                    + " not reach AVFDestroyLocatorStream(ls, YES), or reached it with the connection"
                    + " flag clear. The Java fallback that would otherwise have hidden this was"
                    + " deliberately disarmed above.");

            // releaseCallbacks() nulls streamArena only after Arena.close() has returned, and
            // finishDispose() swallows what it throws, so a non-null arena here is that throw.
            assertNull(field(media, "streamArena"),
                    "streamArena.close() threw - a native thread had not unwound from an upcall stub, or"
                    + " the arena was closed twice - and NativeMedia.finishDispose() logged and"
                    + " swallowed it. Run with -Djfxmedia.loglevel=error to see the message.");
            assertNull(field(media, "streamCallbacks"), "the stream table was not released");
            assertEquals(0L, (long) field(media, "refNativeMedia"), "the media handle was not cleared");
            assertEquals(registryBaseline, JfxMediaNative.registrySize(),
                    "the player and stream registry entries were not both released");
        } finally {
            deleteWhenPossible(jar);
        }
    }

    /**
     * The other half of "freed exactly once": a second dispose must find nothing left to do. A second
     * {@code jfxm_media_dispose} would free the {@code AVFMediaPlayer} and its locator stream again, a
     * second {@code Arena.close()} throws {@link IllegalStateException}, and a second
     * {@code close_connection} would reach the recording channel installed here.
     * <p>
     * The second dispose is driven on the media rather than on the player, because
     * {@code NativeMediaPlayer.dispose()} drops its media reference on the first call and so would not
     * reach any of this a second time - which would make the test pass without exercising anything.
     * Nothing here is disarmed: the Java fallback is part of what must not run twice.
     */
    @Test
    void disposingAJarBackedAvfMediaASecondTimeChangesNothing()
            throws IOException, URISyntaxException, ReflectiveOperationException {
        Path jar = jarWithOneEntry();
        try {
            Platform platform = avfPlatform();
            RecordingLocator locator = jarLocator(jar);

            MediaPlayer player = createPlayer(platform, locator);
            NativeMedia media = assertInstanceOf(NativeMedia.class, player.getMedia(),
                    "OSXPlatform built a player over something that is not a NativeMedia");
            assertEquals(1, locator.holders.size(), "the media takes exactly one connection holder");
            ConnectionHolder holder = locator.holders.get(0);

            player.dispose();
            assertNull(field(media, "streamArena"), "the first dispose did not close the stream arena");
            int registryAfterFirstDispose = JfxMediaNative.registrySize();

            // Safe here, and only here: the AVF player has been disposed and released, so no resource
            // loading request can reach this holder any more and nothing but the second dispose can
            // close the channel installed now. Its delegate is null - the first dispose left the
            // holder's channel field null - so the recorder stands in for nothing.
            RecordingChannel channel = interceptChannel(holder);

            assertDoesNotThrow(media::dispose, "disposing the media a second time threw");

            assertEquals(0, channel.closeCount(),
                    () -> "the connection was closed again after it had already been closed:\n"
                            + channel.report());
            assertEquals(registryAfterFirstDispose, JfxMediaNative.registrySize(),
                    "the second dispose changed the registry");
        } finally {
            deleteWhenPossible(jar);
        }
    }

    /**
     * The AVFoundation platform, which is not optional here: this build compiles {@code jfxmedia_avf}
     * from source, so a macOS run in which it is missing or will not initialise is a broken build and
     * fails, exactly as {@link MediaNatives} decides for {@code jfxmedia} itself.
     */
    private static Platform avfPlatform() {
        Platform platform = OSXPlatform.getPlatformInstance();
        assertNotNull(platform, "OSXPlatform has no instance, which means NativeLibLoader could not"
                + " load jfxmedia_avf. This build produces it - see"
                + " modules/javafx.media/native/CMakeLists.txt - so a macOS run without it is a broken"
                + " build, not a machine fact.");
        assertTrue(platform.loadPlatform(), "OSXPlatform.loadPlatform() failed: jfxm_osx_platform_init"
                + " could not find a usable AVFMediaPlayer class in jfxmedia_avf.");
        return platform;
    }

    /**
     * Creates the player with the media log turned up and captured, so that a null player - which
     * {@code OSXPlatform.createMediaPlayer} returns after logging and swallowing the
     * {@code MediaException} - is reported with the reason rather than as a bare null.
     * <p>
     * Both streams are captured because {@link Logger} splits them: ERROR and WARNING go to
     * {@code System.err}, INFO and DEBUG to {@code System.out}. Nothing is waited for; every failure on
     * this path is raised synchronously by the {@code OSXMediaPlayer} constructor.
     */
    private static MediaPlayer createPlayer(Platform platform, Locator locator) {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream out = System.out;
        PrintStream err = System.err;
        int level = loggerLevel();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        Logger.setLevel(Logger.DEBUG);
        JfxMediaNative.logSetLevel(Logger.DEBUG);
        MediaPlayer player;
        try {
            player = platform.createMediaPlayer(locator);
        } finally {
            JfxMediaNative.logSetLevel(level);
            Logger.setLevel(level);
            System.setErr(err);
            System.setOut(out);
        }
        if (player == null) {
            fail("OSXPlatform.createMediaPlayer returned null for " + locator.getStringLocation()
                    + "; no audio device, display or codec is needed to build this player, so this is a"
                    + " failure of jfxm_media_create or jfxm_avf_player_init. The captured media log"
                    + " was:\n" + captured.toString(StandardCharsets.UTF_8));
        }
        return player;
    }

    /** {@link Logger}'s current level; it has no getter, so it is found by asking what it will log. */
    private static int loggerLevel() {
        for (int level : new int[] { Logger.DEBUG, Logger.INFO, Logger.WARNING, Logger.ERROR }) {
            if (Logger.canLog(level)) {
                return level;
            }
        }
        return Logger.OFF;
    }

    /**
     * A jar holding one entry that is not media, in the system temp directory rather than in a
     * {@code @TempDir}: {@code JarURLConnection} caches the {@code JarFile} it opens, so the file stays
     * open for the life of the JVM and a {@code @TempDir} that cannot be cleaned up would fail the test
     * it belongs to.
     */
    private static Path jarWithOneEntry() throws IOException {
        Path file = Files.createTempFile("jfx-media-avf-locator", ".jar");
        file.toFile().deleteOnExit();
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(file))) {
            jar.putNextEntry(new JarEntry(ENTRY_NAME));
            jar.write(ENTRY_BYTES);
            jar.closeEntry();
        }
        return file;
    }

    /** Deletes the file when the platform allows it; {@code deleteOnExit} is the fallback. */
    private static void deleteWhenPossible(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // The cached JarFile still has it open; it goes at exit.
        }
    }

    /**
     * The jar entry as an initialised {@link Locator}. Nothing here may be skipped: {@code video/mp4}
     * is in {@code OSXPlatform}'s content type list unconditionally, and {@code Locator.init()} only
     * has to open and close a stream over a file this method just wrote.
     */
    private static RecordingLocator jarLocator(Path jar) throws IOException, URISyntaxException {
        RecordingLocator locator =
                new RecordingLocator(new URI("jar:" + jar.toUri() + "!/" + ENTRY_NAME));
        locator.init();
        assertEquals(ENTRY_CONTENT_TYPE, locator.getContentType());
        assertEquals("jar", locator.getURI().getScheme(),
                "only jar: and jrt: locations get a stream callback table");
        return locator;
    }

    /**
     * Replaces the holder's channel with one that records its closes. The field is package private and
     * {@code src/test/addExports} opens the package, which is how {@code HLSConnectionHolderTest} also
     * reaches this holder's insides.
     */
    private static RecordingChannel interceptChannel(ConnectionHolder holder)
            throws ReflectiveOperationException {
        Field field = ConnectionHolder.class.getDeclaredField("channel");
        field.setAccessible(true);
        RecordingChannel channel = new RecordingChannel((ReadableByteChannel) field.get(holder));
        field.set(holder, channel);
        return channel;
    }

    /** Reads a declared field of {@code target}'s own class; the platform media types are all final. */
    private static Object field(Object target, String name) throws ReflectiveOperationException {
        return declaredField(target, name).get(target);
    }

    /** Writes one, which is how the Java half of a two-sided teardown is taken out of the picture. */
    private static void setField(Object target, String name, Object value)
            throws ReflectiveOperationException {
        declaredField(target, name).set(target, value);
    }

    private static Field declaredField(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    /**
     * Whether the calling thread got here through {@code JfxMediaNative}'s {@code close_connection}
     * upcall target, i.e. from C. The frame is a plain static method, so the walker sees it; nothing
     * else on this stack can be in {@code JfxMediaNative}.
     */
    private static boolean calledFromTheCloseConnectionUpcall() {
        return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                .walk(frames -> frames.anyMatch(frame -> frame.getDeclaringClass() == JfxMediaNative.class
                        && frame.getMethodName().equals(CLOSE_CONNECTION_TARGET)));
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
     * The channel a {@link ConnectionHolder} closes, wrapped so that each close and where it came from
     * is visible. Only installed on a holder no live player can reach; see the class comment on why a
     * channel installed under a live AVF player would not survive the resource loader's seeks.
     */
    private static final class RecordingChannel implements ReadableByteChannel {

        /** One entry per close, {@code true} when it arrived through the native upcall. */
        private final List<Boolean> closes = new CopyOnWriteArrayList<>();
        private final List<String> stacks = new CopyOnWriteArrayList<>();

        /** The channel this one stands in for, or {@code null} when the holder was already closed. */
        private final ReadableByteChannel delegate;

        RecordingChannel(ReadableByteChannel delegate) {
            this.delegate = delegate;
        }

        @Override
        public int read(ByteBuffer destination) throws IOException {
            return delegate == null ? -1 : delegate.read(destination);
        }

        @Override
        public boolean isOpen() {
            return delegate == null ? closes.isEmpty() : delegate.isOpen();
        }

        @Override
        public void close() throws IOException {
            closes.add(calledFromTheCloseConnectionUpcall());
            stacks.add(stackTrace());
            if (delegate != null) {
                delegate.close();
            }
        }

        int closeCount() {
            return closes.size();
        }

        /** Every close this channel saw, with the stack it came from; only used in failure messages. */
        String report() {
            StringBuilder text = new StringBuilder(1024);
            for (int i = 0; i < closes.size(); i++) {
                text.append("close ").append(i + 1)
                        .append(closes.get(i) ? " (native upcall)" : " (Java)")
                        .append('\n').append(stacks.get(i));
            }
            return text.length() == 0 ? "no close was recorded" : text.toString();
        }

        private static String stackTrace() {
            StringBuilder text = new StringBuilder(512);
            for (StackTraceElement element : new Throwable().getStackTrace()) {
                text.append("    at ").append(element).append('\n');
            }
            return text.toString();
        }
    }
}
