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

import com.sun.media.jfxmedia.MediaError;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static test.com.sun.media.jfxmediaimpl.MediaTestHooks.ADAPTERS_CONSTRUCTED;
import static test.com.sun.media.jfxmediaimpl.MediaTestHooks.ADAPTERS_DESTROYED;
import static test.com.sun.media.jfxmediaimpl.MediaTestHooks.BIN_FINALIZED;
import static test.com.sun.media.jfxmediaimpl.MediaTestHooks.BIN_FINALIZED_FLOATING;
import static test.com.sun.media.jfxmediaimpl.MediaTestHooks.BIN_WATCHED;
import static test.com.sun.media.jfxmediaimpl.MediaTestHooks.FAULTS_FIRED;
import static test.com.sun.media.jfxmediaimpl.MediaTestHooks.SOURCE_FINALIZED;
import static test.com.sun.media.jfxmediaimpl.MediaTestHooks.SOURCE_FINALIZED_FLOATING;
import static test.com.sun.media.jfxmediaimpl.MediaTestHooks.SOURCE_WATCHED;
import static test.com.sun.media.jfxmediaimpl.MediaTestHooks.counter;

/**
 * Each failure return of {@code CGstPipelineFactory::CreateSourceElement} releases what it made. The three returns
 * after the {@code javasource} element exists - {@code gst_bin_new} failed, the buffer element could not be
 * created, the source would not link to it - cannot be provoked from outside, so the hooks build of
 * {@code jfxmedia} replaces the call each one stands for with a failure ({@code jfxm_test_arm_source_fault}), and
 * puts a weak reference on the {@code javasource} and the bin that counts their finalization. That is all these
 * tests see: the progress buffer is not watched, and an element released once too often is still finalized once,
 * so it shows only as a crash of the child, if at all.
 * <p>
 * What each return leaves behind is still floating, so a release has to sink it before it unrefs it: a plain unref
 * would finalize it all the same, and only a debug GLib would complain. The weak reference's notify reads the
 * floating flag, which is what shows the sink happened on a GLib that does not complain. Finalizing the
 * {@code javasource} frees its stream-callbacks adapter through the {@code close-connection} closure, and the
 * adapter counters show that happens exactly once.
 * <p>
 * The fourth test is the control: the same media with no fault armed is created, the same elements are watched,
 * and its dispose finalizes them the same way. A valid input does not fail, and since every test asserts the exact
 * error, a failure return that reported another one would not pass.
 * <p>
 * Each test runs {@link SourceElementReleaseProbe} in a child JVM of its own, against the hooks build.
 */
public class SourceElementReleaseTest {

    /** How long a child that creates one media gets; it normally takes about a second. */
    private static final long CHILD_TIMEOUT_SECONDS = 60L;

    @Test
    void aFailedBinCreationReleasesTheSourceElement(@TempDir Path dir) {
        ChildJvm.Run run = createMedia(dir, MediaTestHooks.FAULT_BIN_NEW);

        assertCreationFailed(run, MediaError.ERROR_GSTREAMER_BIN_CREATE);
        assertReleasedOnce(run, "the javasource element", SOURCE_WATCHED, SOURCE_FINALIZED,
                SOURCE_FINALIZED_FLOATING);
        assertEquals(0, counter(run, BIN_WATCHED), () -> "the fault replaces gst_bin_new, so no bin was made:"
                + run.describe());
        assertEquals(0, counter(run, BIN_FINALIZED), () -> "a bin was finalized:" + run.describe());
        assertAdaptersFreedOnce(run);
        assertRegistryBalanced(run);
    }

    @Test
    void aMissingBufferElementReleasesTheSourceElementAndTheEmptyBin(@TempDir Path dir) {
        ChildJvm.Run run = createMedia(dir, MediaTestHooks.FAULT_BUFFER_ELEMENT);

        assertCreationFailed(run, MediaError.ERROR_GSTREAMER_ELEMENT_CREATE);
        assertReleasedOnce(run, "the javasource element", SOURCE_WATCHED, SOURCE_FINALIZED,
                SOURCE_FINALIZED_FLOATING);
        assertReleasedOnce(run, "the empty bin", BIN_WATCHED, BIN_FINALIZED, BIN_FINALIZED_FLOATING);
        assertAdaptersFreedOnce(run);
        assertRegistryBalanced(run);
    }

    @Test
    void aFailedLinkReleasesTheSourceElementAndTheBin(@TempDir Path dir) {
        ChildJvm.Run run = createMedia(dir, MediaTestHooks.FAULT_LINK);

        assertCreationFailed(run, MediaError.ERROR_GSTREAMER_ELEMENT_LINK);
        assertReleasedOnce(run, "the javasource element", SOURCE_WATCHED, SOURCE_FINALIZED,
                SOURCE_FINALIZED_FLOATING);
        assertReleasedOnce(run, "the bin", BIN_WATCHED, BIN_FINALIZED, BIN_FINALIZED_FLOATING);
        assertAdaptersFreedOnce(run);
        assertRegistryBalanced(run);
    }

    @Test
    void withNoFaultTheSameMediaIsCreatedAndItsDisposeReleasesTheElements(@TempDir Path dir) {
        ChildJvm.Run run = createMedia(dir, MediaTestHooks.FAULT_NONE);

        assertEquals("succeeded", run.value("create"), () -> "the media with no fault armed was not created:"
                + run.describe());
        assertEquals(0, counter(run, FAULTS_FIRED), () -> "a fault fired with none armed:" + run.describe());
        assertReleasedOnce(run, "the javasource element", SOURCE_WATCHED, SOURCE_FINALIZED,
                SOURCE_FINALIZED_FLOATING);
        assertReleasedOnce(run, "the bin", BIN_WATCHED, BIN_FINALIZED, BIN_FINALIZED_FLOATING);
        assertAdaptersFreedOnce(run);
        assertRegistryBalanced(run);
    }

    private static ChildJvm.Run createMedia(Path dir, int fault) {
        Path hooks = MediaTestHooks.requireHooksBuild();
        ChildJvm.Run run = ChildJvm.run(SourceElementReleaseProbe.class, MediaTestHooks.hooksLibraryPath(hooks),
                dir, "fault-" + fault, CHILD_TIMEOUT_SECONDS, List.of(Integer.toString(fault)));
        assertTrue(run.clean(), () -> "the child that creates the media failed:" + run.describe());
        System.out.println("fault " + fault + ": " + run.value("library.loaded") + ", creation " + run.value("create")
                + " " + run.value("error") + ", counters " + counters(run) + ", child " + run.millis() + " ms");
        MediaTestHooks.assertLoaded(run, hooks, true);
        assertEquals("audio/x-wav", run.value("content.type"), () -> "the served file was not recognised:"
                + run.describe());
        assertEquals("ERROR_NONE", run.value("platform.init"), () -> "GStreamer did not initialise:"
                + run.describe());
        assertEquals("true", run.value("done"), () -> "the child did not run to its end:" + run.describe());
        return run;
    }

    private static void assertCreationFailed(ChildJvm.Run run, MediaError expected) {
        assertEquals(1, counter(run, FAULTS_FIRED), () -> "the armed fault did not fire, so the source element was"
                + " not built on the need_buffer branch it guards:" + run.describe());
        assertEquals("failed", run.value("create"), () -> "the creation succeeded through an injected failure:"
                + run.describe());
        assertEquals(expected.name(), run.value("error"), () -> "the creation failed with the wrong error:"
                + run.describe());
    }

    /**
     * The element was made, finalized exactly once, and was no longer floating when it was: whatever released it
     * sank the reference it dropped.
     */
    private static void assertReleasedOnce(ChildJvm.Run run, String what, int watched, int finalized,
                                           int floating) {
        assertEquals(1, counter(run, watched), () -> what + " was not watched once:" + run.describe());
        assertEquals(1, counter(run, finalized), () -> what + " was finalized " + counter(run, finalized)
                + " times, not once: the path that abandoned it did not release it:" + run.describe());
        assertEquals(0, counter(run, floating), () -> what + " was finalized while still floating: it was"
                + " unreffed without being sunk first:" + run.describe());
    }

    private static void assertAdaptersFreedOnce(ChildJvm.Run run) {
        int constructed = counter(run, ADAPTERS_CONSTRUCTED);
        int destroyed = counter(run, ADAPTERS_DESTROYED);
        assertTrue(constructed >= 1, () -> "no stream-callbacks adapter was made:" + run.describe());
        assertEquals(constructed, destroyed, () -> constructed + " stream-callbacks adapters were made and "
                + destroyed + " destroyed:" + run.describe());
    }

    private static void assertRegistryBalanced(ChildJvm.Run run) {
        assertEquals(run.value("registry.baseline"), run.value("registry.after"), () -> "the creation left a"
                + " callback registry entry behind:" + run.describe());
    }

    /** Counters 0 to 8 as reported, in id order. */
    private static List<String> counters(ChildJvm.Run run) {
        return IntStream.rangeClosed(SOURCE_WATCHED, FAULTS_FIRED).mapToObj(id -> run.value("counter." + id))
                .toList();
    }
}
