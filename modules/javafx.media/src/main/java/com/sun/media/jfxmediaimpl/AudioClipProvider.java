/*
 * Copyright (c) 2010, 2026, Oracle and/or its affiliates. All rights reserved.
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

package com.sun.media.jfxmediaimpl;

import java.net.URI;
import com.sun.media.jfxmedia.AudioClip;
import java.net.URISyntaxException;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Singleton class that manages AudioClip implementations.
 */
public class AudioClipProvider {
    private static AudioClipProvider primaDonna;

    public static synchronized AudioClipProvider getProvider() {
        if (null == primaDonna) {
            primaDonna = new AudioClipProvider();
        }
        return primaDonna;
    }

    private AudioClipProvider() {
        // NativeAudioClip was implemented by the iOS platform only, which this fork does not build;
        // every desktop platform already fell back on NativeMediaAudioClip.
    }

    public AudioClip load(URI source) throws URISyntaxException, FileNotFoundException, IOException {
        return NativeMediaAudioClip.load(source);
    }

    public AudioClip create(byte[] data, int dataOffset, int sampleCount, int sampleFormat, int channels, int sampleRate)
            throws IllegalArgumentException
    {
        return NativeMediaAudioClip.create(data, dataOffset, sampleCount, sampleFormat, channels, sampleRate);
    }

    public void stopAllClips() {
        NativeMediaAudioClip.stopAllClips();
    }
}
