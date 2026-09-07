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

import com.sun.media.jfxmedia.MediaException;
import com.sun.media.jfxmedia.MediaManager;
import com.sun.media.jfxmedia.locator.Locator;
import com.sun.media.jfxmediaimpl.NativeMediaManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * The body of {@link NativeMediaManagerDegradationTest}, run in a JVM of its own because the media
 * facade's state - the library, the singleton and the platform list - is per class loader and cannot be
 * unloaded once it is good. That JVM is started with a {@code java.library.path} on which {@code jfxmedia}
 * is missing or unusable; every line this class prints is asserted on by the test.
 * <p>
 * Everything here is deliberately the plain public entry path an application takes, so that what the test
 * proves is what an application sees: media that is not there has to degrade to a {@link MediaException},
 * not take the {@code NativeMediaManager} singleton down with it.
 */
public final class MediaWithoutNativesProbe {

    private MediaWithoutNativesProbe() {
    }

    /**
     * Walks the media entry points that touch the native layer and prints the outcome of each.
     *
     * @param args ignored
     * @throws Exception if a step fails in a way that is not a {@link MediaException}, which fails the test
     */
    public static void main(String[] args) throws Exception {
        // Before the fix this line alone was fatal: the UnsatisfiedLinkError raised by loadLibraries()
        // escaped the constructor and the singleton's class initializer with it.
        System.out.println("MANAGER=" + (NativeMediaManager.getDefaultInstance() != null));

        // And every later call reported the wreckage as NoClassDefFoundError.
        System.out.println("MANAGER_AGAIN=" + (NativeMediaManager.getDefaultInstance() != null));

        System.out.println("CONTENT_TYPES=" + Arrays.toString(MediaManager.getSupportedContentTypes()));
        System.out.println("PROTOCOLS=" + MediaManager.canPlayProtocol("file"));

        Path file = Files.createTempFile("silence", ".wav");
        file.toFile().deleteOnExit();
        TinyWav.writeTo(file);
        Locator locator = new Locator(file.toUri());
        locator.init();
        System.out.println("LOCATOR=" + locator.getContentType());

        // The JNI behaviour this has to reproduce: the platform cannot initialise, so GSTPlatform posts
        // ERROR_MANAGER_ENGINEINIT_FAIL, which with no error listener registered is a MediaException.
        try {
            MediaManager.getPlayer(locator);
            System.out.println("PLAYER=created");
        } catch (MediaException e) {
            System.out.println("PLAYER_MEDIA_EXCEPTION=" + e.getMessage());
        }

        System.out.println("DONE");
    }
}
