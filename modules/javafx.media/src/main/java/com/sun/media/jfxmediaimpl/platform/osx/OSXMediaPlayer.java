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

package com.sun.media.jfxmediaimpl.platform.osx;

import com.sun.media.jfxmedia.MediaError;
import com.sun.media.jfxmedia.MediaException;
import com.sun.media.jfxmedia.effects.AudioEqualizer;
import com.sun.media.jfxmedia.effects.AudioSpectrum;
import com.sun.media.jfxmedia.locator.Locator;
import com.sun.media.jfxmediaimpl.JfxMediaNative;
import com.sun.media.jfxmediaimpl.NativeMediaPlayer;
import java.lang.foreign.Arena;

/**
 * Mac OS X MediaPlayer implementation.
 */
final class OSXMediaPlayer extends NativeMediaPlayer {
    private final OSXMedia osxMedia;
    private final AudioEqualizer audioEq;
    private final AudioSpectrum audioSpectrum;

    OSXMediaPlayer(OSXMedia sourceMedia) {
        super(sourceMedia);
        init();
        osxMedia = sourceMedia;

        // osxCreatePlayer did both halves; jfxm_media_create takes the location and, for jar:/jrt:,
        // the connection holder, jfxm_player_init the AVPlayer and the callback table.
        int rc = sourceMedia.initNativeMedia();
        if (rc == MediaError.ERROR_NONE.code()) {
            // The 13 upcall stubs and the registry entry must outlive jfxm_media_dispose, which runs
            // in OSXMedia.dispose(), i.e. after playerDispose() (contract sections 4 and 7).
            Arena playerArena = Arena.ofShared();
            JfxMediaNative.CallbackTable callbacks;
            try {
                callbacks = JfxMediaNative.installPlayerCallbacks(playerArena, this);
                // Registering the close action is the last statement of the block, so anything that
                // throws above it leaves the arena with no owner at all: nothing else references it, and
                // a shared arena frees neither its segment nor the stubs it has already allocated until
                // it is closed. installPlayerCallbacks reaches two restricted methods per stub, so this
                // is not theoretical: it throws IllegalCallerException when --enable-native-access does
                // not name javafx.media, and it can run out of memory part-way through the 13
                // trampolines. Closing here cannot hit the case where Arena.close() throws because a
                // native thread has not unwound from a stub: the table is handed to C by
                // jfxm_player_init below, and until that call returns no native code has ever seen any
                // of these stubs.
                sourceMedia.runAfterDispose(() -> {
                    callbacks.unregister();
                    playerArena.close();
                });
            } catch (Throwable t) {
                try {
                    playerArena.close();
                } catch (Throwable closeFailure) {
                    t.addSuppressed(closeFailure);
                }
                // The media is this player's now, and nothing downstream would ever release it:
                // initNativeMedia() above has already created the native media and
                // OSXPlatform.createMediaPlayer only logs the exception and returns null. Same
                // teardown as the rc != 0 path below, and dispose() is idempotent.
                try {
                    dispose();
                } catch (Throwable disposeFailure) {
                    t.addSuppressed(disposeFailure);
                }
                throw t;
            }
            rc = JfxMediaNative.playerInit(sourceMedia.getNativeMediaRef(), callbacks);
        }
        if (rc != MediaError.ERROR_NONE.code()) {
            // Where osxCreatePlayer threw a MediaException. super(sourceMedia) and init() have already
            // registered this player as a marker listener and started its PlayerEventLoop thread, so
            // the whole player has to come down, not just the media: dispose() terminates the loop,
            // runs the (empty) playerDispose() and then OSXMedia.dispose(), which is the same teardown
            // GSTMediaPlayer runs on the same failure. Disposing the media alone left one live event
            // loop thread behind per attempt at a bad URL. Safe from either failure point: the early
            // one never created an arena, a callback table or a native player, and OSXMedia.dispose()
            // is idempotent, so the media is not disposed twice.
            dispose();
            MediaError error = MediaError.getFromCode(rc);
            throw new MediaException("OSXMediaPlayer: unable to create player", null, error);
        }

        long mediaRef = sourceMedia.getNativeMediaRef();
        audioEq = createNativeAudioEqualizer(JfxMediaNative.playerGetAudioEqualizer(mediaRef));
        audioSpectrum = createNativeAudioSpectrum(JfxMediaNative.playerGetAudioSpectrum(mediaRef));
    }

    OSXMediaPlayer(Locator source) {
        this(new OSXMedia(source));
    }

    @Override
    public AudioEqualizer getEqualizer() {
        return audioEq;
    }

    @Override
    public AudioSpectrum getAudioSpectrum() {
        return audioSpectrum;
    }

    /*
     * The forwarders below keep the convention of the ObjC exports they replace: a call that cannot
     * reach the player is silently ignored and the getters answer with the value the ObjC code
     * initialised its result with (0, false, and -1.0 for the duration). Only the constructor throws.
     */

    @Override
    protected long playerGetAudioSyncDelay() throws MediaException {
        long[] audioSyncDelay = new long[1];
        JfxMediaNative.playerGetAudioSyncDelay(osxMedia.getNativeMediaRef(), audioSyncDelay);
        return audioSyncDelay[0];
    }

    @Override
    protected void playerSetAudioSyncDelay(long delay) throws MediaException {
        JfxMediaNative.playerSetAudioSyncDelay(osxMedia.getNativeMediaRef(), delay);
    }

    @Override
    protected void playerPlay() throws MediaException {
        JfxMediaNative.playerPlay(osxMedia.getNativeMediaRef());
    }

    @Override
    protected void playerStop() throws MediaException {
        JfxMediaNative.playerStop(osxMedia.getNativeMediaRef());
    }

    @Override
    protected void playerPause() throws MediaException {
        JfxMediaNative.playerPause(osxMedia.getNativeMediaRef());
    }

    @Override
    protected void playerFinish() throws MediaException {
        JfxMediaNative.playerFinish(osxMedia.getNativeMediaRef());
    }

    @Override
    protected float playerGetRate() throws MediaException {
        float[] rate = new float[1];
        JfxMediaNative.playerGetRate(osxMedia.getNativeMediaRef(), rate);
        return rate[0];
    }

    @Override
    protected void playerSetRate(float rate) throws MediaException {
        JfxMediaNative.playerSetRate(osxMedia.getNativeMediaRef(), rate);
    }

    @Override
    protected double playerGetPresentationTime() throws MediaException {
        double[] presentationTime = new double[1];
        JfxMediaNative.playerGetPresentationTime(osxMedia.getNativeMediaRef(), presentationTime);
        return presentationTime[0];
    }

    @Override
    protected boolean playerGetMute() throws MediaException {
        boolean[] mute = new boolean[1];
        JfxMediaNative.playerGetMute(osxMedia.getNativeMediaRef(), mute);
        return mute[0];
    }

    @Override
    protected void playerSetMute(boolean state) throws MediaException {
        JfxMediaNative.playerSetMute(osxMedia.getNativeMediaRef(), state);
    }

    @Override
    protected float playerGetVolume() throws MediaException {
        float[] volume = new float[1];
        JfxMediaNative.playerGetVolume(osxMedia.getNativeMediaRef(), volume);
        return volume[0];
    }

    @Override
    protected void playerSetVolume(float volume) throws MediaException {
        JfxMediaNative.playerSetVolume(osxMedia.getNativeMediaRef(), volume);
    }

    @Override
    protected float playerGetBalance() throws MediaException {
        float[] balance = new float[1];
        JfxMediaNative.playerGetBalance(osxMedia.getNativeMediaRef(), balance);
        return balance[0];
    }

    @Override
    protected void playerSetBalance(float balance) throws MediaException {
        JfxMediaNative.playerSetBalance(osxMedia.getNativeMediaRef(), balance);
    }

    @Override
    protected double playerGetDuration() throws MediaException {
        double[] duration = { -1.0 };
        JfxMediaNative.playerGetDuration(osxMedia.getNativeMediaRef(), duration);
        if (duration[0] == -1.0) {
            return Double.POSITIVE_INFINITY;
        }

        return duration[0];
    }

    @Override
    protected void playerSeek(double streamTime) throws MediaException {
        JfxMediaNative.playerSeek(osxMedia.getNativeMediaRef(), streamTime);
    }

    @Override
    protected void playerDispose() {
        // osxDispose tore the AVPlayer down here; that is jfxm_media_dispose now, and
        // NativeMediaPlayer.dispose() calls OSXMedia.dispose() right after this method returns.
    }

    @Override
    public void playerInit() throws MediaException {
    }
}
