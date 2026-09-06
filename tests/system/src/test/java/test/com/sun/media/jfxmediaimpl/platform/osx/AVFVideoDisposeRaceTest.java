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

import com.sun.javafx.PlatformUtil;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import test.util.Util;

import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The regression test for the use after free in the AVFoundation player's {@code CVDisplayLink}
 * callback: disposing a video player while frames are in flight must neither crash the process nor hang
 * it.
 *
 * <h2>What was wrong, and what the fix risks</h2>
 * {@code displayLinkCallback} in {@code AVFMediaPlayer.mm} used to be handed an unretained {@code self}
 * and to dereference it - and, through {@code -sendPixelBuffer:}, the {@code eventHandler} that
 * {@code -[OSXMediaPlayer dispose]} deletes - on a CoreVideo thread that {@code -dispose} did not wait
 * for. Any dispose of a playing video with a frame in flight was therefore a SIGSEGV waiting for the
 * right interleaving, on the most ordinary operation a media application performs. The fix gives every
 * player a token in a process-global registry, hands the token rather than a pointer to
 * {@code CVDisplayLinkSetOutputCallback}, and makes {@code -dispose} retire the token and wait on
 * {@code gDisplayLinkIdle} until every callback holding a claim has left. That removes the crash and
 * introduces the only other way this can go wrong: the wait not ending.
 * <p>
 * Until this test the fork had no media playback test in {@code tests/system} at all - the only media it
 * constructed anywhere was a bare {@code new MediaView()} in
 * {@code test.robot.javafx.scene.NodeInitializationStressTest} - so neither the original crash nor a
 * regression in the join would have been caught by anything.
 *
 * <h2>Why the work happens in another JVM</h2>
 * Both failure modes are invisible to an assertion. A use after free kills the process, and a deadlock
 * in a native condition wait cannot be unblocked by the interrupt that a JUnit {@code @Timeout}
 * delivers - the same reason {@code test.shutdowntest.ShutdownHookTest} gives every socket wait an
 * explicit {@code setSoTimeout} rather than relying on its class timeout. So the loop runs in
 * {@link AVFVideoDisposeRaceApp}, launched exactly as {@code ShutdownHookTest} launches
 * {@code ShutdownHookApp}: through {@link Util#createApplicationLaunchCommand}, which gives the worker
 * the module path, {@code --add-modules}, {@code --enable-native-access} and {@code java.library.path}
 * that {@code tests/system/pom.xml} writes into {@code target/st.run.args}, and with the exit status
 * carrying the verdict.
 * <p>
 * That buys the two things this test needs and cannot get in process:
 * <ul>
 * <li>A crash is an exit status. {@code reuseForks=false} already attributes a dead fork to a test
 *     class, but a worker turns it into a specific, readable failure instead of a surefire report about
 *     a VM that did not say goodbye.</li>
 * <li>A hang is bounded twice. The worker joins its own {@code dispose()} thread with a bound and can
 *     report {@link Constants#EXIT_DISPOSE_HUNG} with the stack of the thread that is stuck; if the
 *     worker itself wedges somewhere it cannot see, {@link #WORKER_TIMEOUT_MILLIS} and
 *     {@code destroyForcibly} end it here.</li>
 * </ul>
 * The argfiles that {@code tests/system} passes to its own fork are not in {@code st.run.args}, so the
 * media exports the worker needs travel as {@link #JVM_ARGS} on its command line. Nothing in this class
 * touches {@code javafx.media} itself, which is deliberate: surefire runs discovery for this module
 * inside the Maven JVM, where none of those exports exist.
 *
 * <h2>Where it runs</h2>
 * macOS only - {@code AVFMediaPlayer} is the AVFoundation backend and does not exist elsewhere - and
 * only under {@code -DFULL_TEST=true}, which is what enables this whole module. It plays a five second
 * generated movie thirty times over and takes about half a minute.
 * <p>
 * What a pass means is worth stating plainly. A race test cannot prove the absence of a race: it
 * disposes thirty times at thirty different points with frames demonstrably in flight, and reports that
 * none of them crashed or wedged. What it can do without qualification is fail - loudly, with a signal
 * number or a stuck stack - the moment the retire-and-wait in {@code -dispose} stops holding, which is
 * more than the fork had before it.
 */
@Timeout(value = 210_000, unit = TimeUnit.MILLISECONDS)
public class AVFVideoDisposeRaceTest {

    private static final String CLASS_NAME = AVFVideoDisposeRaceTest.class.getName();
    private static final String PACKAGE_NAME = CLASS_NAME.substring(0, CLASS_NAME.lastIndexOf("."));
    private static final String WORKER = PACKAGE_NAME + ".AVFVideoDisposeRaceApp";

    /**
     * How long the worker gets. Thirty cycles of a create, a preroll, a fifth of a second of playback
     * and a dispose take some twenty five seconds on an idle machine; this is six times that, and it
     * sits comfortably below the class {@code @Timeout}, which is the backstop for the interruptible
     * wait below rather than for the worker.
     */
    private static final long WORKER_TIMEOUT_MILLIS = 150_000L;

    /**
     * The exports the worker needs. {@code st.run.args} carries the module path and
     * {@code --enable-native-access} but not the {@code addExports} argfiles, and
     * {@link Util#createApplicationLaunchCommand} adds only a minimum set of its own, so the packages
     * {@link AVFVideoDisposeRaceApp} reaches into have to be named here. They are not needed to
     * <em>compile</em> it: the test sources of this module are compiled against javafx.media on the
     * classpath, where nothing is encapsulated.
     */
    private static final String[] JVM_ARGS = {
        "--add-exports=javafx.media/com.sun.media.jfxmedia=ALL-UNNAMED",
        "--add-exports=javafx.media/com.sun.media.jfxmedia.control=ALL-UNNAMED",
        "--add-exports=javafx.media/com.sun.media.jfxmedia.events=ALL-UNNAMED",
        "--add-exports=javafx.media/com.sun.media.jfxmedia.locator=ALL-UNNAMED",
        "--add-exports=javafx.media/com.sun.media.jfxmedia.track=ALL-UNNAMED",
        "--add-exports=javafx.media/com.sun.media.jfxmediaimpl=ALL-UNNAMED"
    };

    @Test
    public void testDisposingAPlayingVideoWithFramesInFlight() throws Exception {
        assumeTrue(PlatformUtil.isMac(), "AVFMediaPlayer and its CVDisplayLink are macOS only");

        final ArrayList<String> cmd = Util.createApplicationLaunchCommand(WORKER, null, JVM_ARGS);
        final ProcessBuilder builder = new ProcessBuilder(cmd);
        builder.redirectError(ProcessBuilder.Redirect.INHERIT);
        builder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        Process process = builder.start();
        try {
            if (!process.waitFor(WORKER_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                fail(WORKER + " did not exit within " + WORKER_TIMEOUT_MILLIS + " ms. It bounds its own"
                        + " dispose and every wait it makes, so a worker that is still running here is"
                        + " stuck somewhere it cannot see - the likeliest place being a native call that"
                        + " no interrupt can leave. Its output is above.");
            }
            check(process.exitValue());
        } finally {
            // A no-op once waitFor has seen the worker exit; on every other path forcibly terminate it
            // so it cannot outlive this test.
            process.destroyForcibly();
        }
    }

    /** Turns the worker's exit status into a verdict. Its own diagnostics are already on the console. */
    private static void check(int exitValue) {
        if (exitValue >= Constants.SIGNAL_EXIT_BASE) {
            fail(WORKER + " was killed by signal " + (exitValue - Constants.SIGNAL_EXIT_BASE)
                    + ". Disposing a playing AVFoundation video player crashed the JVM, which is the use"
                    + " after free the display link token registry in AVFMediaPlayer.mm exists to"
                    + " prevent: the CVDisplayLink callback reached a player, or an eventHandler, that"
                    + " -dispose had already torn down. Look for hs_err_pid*.log in tests/system.");
        }
        switch (exitValue) {
            case Constants.EXIT_OK:
                return;
            case 0:
                fail(WORKER + " exited 0. Every path out of it halts with an explicit status, so this is"
                        + " something else ending the process.");
                break;
            case 1:
                fail(WORKER + ": unable to launch java application");
                break;
            case Constants.EXIT_NO_PLATFORM:
                fail(WORKER + ": the AVFoundation platform is not available. This build compiles"
                        + " jfxmedia_avf from source, so on macOS that is a broken build rather than a"
                        + " fact about the machine.");
                break;
            case Constants.EXIT_CONTENT_TYPE:
                fail(WORKER + ": the generated movie did not resolve to video/mp4, so it would never have"
                        + " reached OSXPlatform.");
                break;
            case Constants.EXIT_NO_PLAYER:
                fail(WORKER + ": no media player could be created for the generated movie.");
                break;
            case Constants.EXIT_NO_READY:
                fail(WORKER + ": the player never prerolled to READY.");
                break;
            case Constants.EXIT_NO_VIDEO_TRACK:
                fail(WORKER + ": the player reported no video track, so no CVDisplayLink was ever built"
                        + " and there was nothing for a dispose to race.");
                break;
            case Constants.EXIT_NO_PLAYING:
                fail(WORKER + ": the player never reached PLAYING.");
                break;
            case Constants.EXIT_NO_FRAMES:
                fail(WORKER + ": no video frame arrived during playback, so AVFoundation did not decode"
                        + " the movie the test generates and nothing about the display link lifetime was"
                        + " exercised. This is a failure and not a skip on purpose: the test needs a"
                        + " video source this macOS can play, and hiding that would leave the fix"
                        + " uncovered.");
                break;
            case Constants.EXIT_NOT_FLOWING:
                fail(WORKER + ": frames had stopped arriving before dispose was called, so the cycle"
                        + " disposed an idle player and covered nothing.");
                break;
            case Constants.EXIT_DISPOSE_HUNG:
                fail(WORKER + ": dispose() never returned. The display link join in -[AVFMediaPlayer"
                        + " dispose] deadlocked - it waits on gDisplayLinkIdle for every outstanding"
                        + " claim on the player's registry entry, and one of them was never released."
                        + " The stack of the stuck thread is above.");
                break;
            case Constants.EXIT_MEDIA_ERROR:
                fail(WORKER + ": the player reported an error or a halt during playback.");
                break;
            case Constants.EXIT_REGISTRY:
                fail(WORKER + ": a disposed player left callback registry entries behind.");
                break;
            case Constants.EXIT_UNEXPECTED:
                fail(WORKER + ": unexpected exception; the stack trace is above.");
                break;
            default:
                fail(WORKER + ": unexpected exit status " + exitValue);
                break;
        }
    }
}
