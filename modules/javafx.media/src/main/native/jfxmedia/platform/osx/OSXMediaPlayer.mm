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

#import "OSXMediaPlayer.h"
#import "OSXPlayerProtocol.h"
#import <CoreAudio/CoreAudio.h>
#import <jni/Logger.h>
#import <Locator/LocatorStream.h>
#import <jfxmedia_errors.h>
#import <jfxmedia_api.h>
#import <ffi/jfxmedia_avf.h>
#import <ffi/JfxmMediaHandle.h>
#import <ffi/FfiPlayerEventDispatcher.h>
#import <ffi/FfiStreamCallbacks.h>

#import <objc/runtime.h>

static Class gMediaPlayerClass = nil;

@implementation OSXMediaPlayer

+ (BOOL) initPlayerPlatform
{
    BOOL enableAVF = YES;

    // Check environment to see if platforms are enabled
    char *value = getenv("JFXMEDIA_AVF");
    if (value ? strncasecmp(value, "yes", 3) != 0 : NO) {
        enableAVF = NO;
    }

    // Determine if we can use OSX native player libs, without linking directly
    Class klass;

    if (enableAVF) {
        klass = objc_getClass("AVFMediaPlayer");
        if (klass) {
            if ([klass conformsToProtocol:@protocol(OSXPlayerProtocol)]) {
                if ([klass respondsToSelector:@selector(playerAvailable)] ? [klass playerAvailable] : YES) {
                    gMediaPlayerClass = klass;
                    return YES;
                }
            }
        }
    }

    return NO;
}

- (id) init
{
    if ((self = [super init]) != nil) {
    }
    return self;
}

- (id) initWithURL:(NSURL *)source eventHandler:(CPlayerEventDispatcher*)hdlr locatorStream:(CLocatorStream*)ls
{
    // jfxm_avf_player_init: the dispatcher carries the callback table.
    if (!gMediaPlayerClass) {
        // No player class available, abort
        [self release];
        return nil;
    }

    if ((self = [super init]) != nil) {
        movieURL = [source retain];
        eventHandler = hdlr;

        // create the player object
        player = [[gMediaPlayerClass alloc] initWithURL:movieURL eventHandler:eventHandler locatorStream:ls];
    }
    return self;
}

- (void) dealloc
{
    [self dispose]; // just in case
    [movieURL release];
    [super dealloc];
}

- (void) dispose
{
    @synchronized(self) {
        [player dispose];
        [player release];
        player = nil;

        if (eventHandler) {
            delete eventHandler;
        }
        eventHandler = NULL;
    }
}

- (id<OSXPlayerProtocol>) player
{
    return [[player retain] autorelease];
}

- (CAudioEqualizer*) audioEqualizer
{
    return player.audioEqualizer;
}

- (CAudioSpectrum*) audioSpectrum
{
    return player.audioSpectrum;
}

- (int64_t) audioSyncDelay
{
    return player.audioSyncDelay;
}

- (void) setAudioSyncDelay:(int64_t)audioSyncDelay
{
    player.audioSyncDelay = audioSyncDelay;
}

- (double) duration
{
    return player.duration;
}

- (float) rate
{
    return player.rate;
}

- (void) setRate:(float)rate
{
    player.rate = rate;
}

- (double) currentTime
{
    return player.currentTime;
}

- (void) setCurrentTime:(double)currentTime
{
    player.currentTime = currentTime;
}

- (BOOL) mute
{
    return player.mute;
}

- (void) setMute:(BOOL)mute
{
    player.mute = mute;
}

- (float) volume
{
    return player.volume;
}

- (void) setVolume:(float)volume
{
    player.volume = volume;
}

- (float) balance
{
    return player.balance;
}

- (void) setBalance:(float)balance
{
    player.balance = balance;
}

- (void) play
{
    [player play];
}

- (void) pause
{
    [player pause];
}

- (void) finish
{
    [player finish];
}

- (void) stop
{
    [player stop];
}

@end

#pragma mark -
#pragma mark FFM entry points (ffi/jfxmedia_avf.h)

// The jfxm_avf_* functions are the AVFoundation half of jfxmedia_api.h. They are reached only
// through ffi/jfxmedia_api.cpp for handles created with JFXM_BACKEND_AVF. Relative to the former
// Java_..._OSXMediaPlayer_osx* exports: the handle's retained OSXMediaPlayer replaces the
// JObjectPeers lookup, ERROR_MEDIA_NULL / ERROR_PIPELINE_NULL replace the silent defaults for a
// missing player (Java keeps returning those defaults), and out-params are written only on
// ERROR_NONE.

static int32_t AvfResolvePlayer(JfxmMedia *media, OSXMediaPlayer **ppPlayer)
{
    *ppPlayer = nil;
    if (media == NULL) {
        return ERROR_MEDIA_NULL;
    }
    *ppPlayer = (OSXMediaPlayer *)media->osx_player;
    return (*ppPlayer != nil) ? ERROR_NONE : ERROR_PIPELINE_NULL;
}

/*
 * What Java_..._OSXMediaPlayer_osxCreatePlayer did, without the JNIEnv. Its six ThrowJavaException
 * sites map to: sourceURIString / callbacks / content type allocation failures ->
 * ERROR_MEMORY_ALLOCATION, an unparsable URI -> ERROR_FACTORY_INVALID_URI, a nil OSXMediaPlayer ->
 * ERROR_MEDIA_CREATION.
 */
int32_t jfxm_avf_player_init(JfxmMedia *media, const JfxmPlayerCallbacks *cb, void *user)
{
    if (media == NULL) {
        return ERROR_MEDIA_NULL;
    }
    if (media->osx_player != NULL) {
        return ERROR_MEDIA_CREATION; // already initialised
    }

    CLocatorStream *locatorStream = NULL;
    CFfiStreamCallbacks *callbacks = NULL;

    // create the event dispatcher first, as osxCreatePlayer did
    CFfiPlayerEventDispatcher *eventHandler = new (nothrow) CFfiPlayerEventDispatcher(cb, user);
    if (eventHandler == NULL) {
        return ERROR_MEMORY_ALLOCATION;
    }

    NSAutoreleasePool *pool = [[NSAutoreleasePool alloc] init];
    NSString *sourceURIString = [NSString stringWithUTF8String:media->location.c_str()];
    if (!sourceURIString) {
        LOGGER_ERRORMSG("OSXMediaPlayer: Unable to create sourceURIString\n");
        delete eventHandler;
        [pool drain];
        return ERROR_MEMORY_ALLOCATION;
    }

    NSURL *mediaURL = [[NSURL alloc] initWithString:sourceURIString];
    if (!mediaURL) {
        LOGGER_WARNMSG("OSXMediaPlayer: Unable to create mediaURL\n");
        delete eventHandler;
        [pool drain];
        return ERROR_FACTORY_INVALID_URI;
    }

    // Check if we need to use Locator to read data. For FILE/HTTP/HTTPS
    // AVFoundation will read data directly. For JAR/JRT we will use Locator to
    // read data; Java passed a stream table exactly for those schemes.
    NSString *scheme = [mediaURL scheme];
    if ([scheme caseInsensitiveCompare:@"jar"] == NSOrderedSame ||
        [scheme caseInsensitiveCompare:@"jrt"] == NSOrderedSame) {
        if (media->has_stream) {
            callbacks = new (nothrow) CFfiStreamCallbacks(&media->stream, media->stream_user);
        }
        if (callbacks == NULL) {
            [mediaURL release];
            delete eventHandler;
            LOGGER_WARNMSG("OSXMediaPlayer: Unable to create CFfiStreamCallbacks\n");
            [pool drain];
            return ERROR_MEMORY_ALLOCATION;
        }

        locatorStream = new (nothrow) CLocatorStream(callbacks, media->content_type.c_str(),
                                                     media->location.c_str(), media->size_hint);
    }

    OSXMediaPlayer *player = [[OSXMediaPlayer alloc] initWithURL:mediaURL
                                                    eventHandler:eventHandler
                                                   locatorStream:locatorStream];
    if (!player) {
        LOGGER_WARNMSG("OSXMediaPlayer: Unable to create player\n");
        [mediaURL release];
        delete eventHandler;
        delete locatorStream;
        delete callbacks;
        [pool drain];
        return ERROR_MEDIA_CREATION;
    }

    [mediaURL release]; // the player retained its own reference
    media->osx_player = player; // +1 from alloc; released by jfxm_avf_player_dispose
    [pool drain];
    return ERROR_NONE;
}

void jfxm_avf_player_dispose(JfxmMedia *media)
{
    if (media == NULL || media->osx_player == NULL) {
        return;
    }
    NSAutoreleasePool *pool = [[NSAutoreleasePool alloc] init];
    OSXMediaPlayer *player = (OSXMediaPlayer *)media->osx_player;
    media->osx_player = NULL;
    [player dispose];
    // osxDispose let the peer list drop the last retain; here the handle held it.
    [player release];
    [pool drain];
}

void *jfxm_avf_player_get_audio_equalizer(JfxmMedia *media)
{
    NSAutoreleasePool *pool = [[NSAutoreleasePool alloc] init];
    OSXMediaPlayer *player = nil;
    (void)AvfResolvePlayer(media, &player);
    CAudioEqualizer *eq = NULL;
    if (player) {
        eq = player.audioEqualizer;
    }
    [pool drain];
    return eq;
}

void *jfxm_avf_player_get_audio_spectrum(JfxmMedia *media)
{
    NSAutoreleasePool *pool = [[NSAutoreleasePool alloc] init];
    OSXMediaPlayer *player = nil;
    (void)AvfResolvePlayer(media, &player);
    CAudioSpectrum *spectrum = NULL;
    if (player) {
        spectrum = player.audioSpectrum;
    }
    [pool drain];
    return spectrum;
}

int32_t jfxm_avf_player_get_audio_sync_delay(JfxmMedia *media, int64_t *out_millis)
{
    NSAutoreleasePool *pool = [[NSAutoreleasePool alloc] init];
    OSXMediaPlayer *player = nil;
    int32_t result = AvfResolvePlayer(media, &player);
    if (player && out_millis != NULL) {
        *out_millis = (int64_t)player.audioSyncDelay;
    }
    [pool drain];
    return result;
}

int32_t jfxm_avf_player_set_audio_sync_delay(JfxmMedia *media, int64_t millis)
{
    NSAutoreleasePool *pool = [[NSAutoreleasePool alloc] init];
    OSXMediaPlayer *player = nil;
    int32_t result = AvfResolvePlayer(media, &player);
    if (player) {
        player.audioSyncDelay = millis; // stored by AVFAudioProcessor, never applied (as before)
    }
    [pool drain];
    return result;
}

int32_t jfxm_avf_player_play(JfxmMedia *media)
{
    NSAutoreleasePool *pool = [[NSAutoreleasePool alloc] init];
    OSXMediaPlayer *player = nil;
    int32_t result = AvfResolvePlayer(media, &player);
    if (player) {
        [player play];
    }
    [pool drain];
    return result;
}

int32_t jfxm_avf_player_pause(JfxmMedia *media)
{
    NSAutoreleasePool *pool = [[NSAutoreleasePool alloc] init];
    OSXMediaPlayer *player = nil;
    int32_t result = AvfResolvePlayer(media, &player);
    if (player) {
        [player pause];
    }
    [pool drain];
    return result;
}

int32_t jfxm_avf_player_stop(JfxmMedia *media)
{
    NSAutoreleasePool *pool = [[NSAutoreleasePool alloc] init];
    OSXMediaPlayer *player = nil;
    int32_t result = AvfResolvePlayer(media, &player);
    if (player) {
        [player stop];
    }
    [pool drain];
    return result;
}

int32_t jfxm_avf_player_finish(JfxmMedia *media)
{
    NSAutoreleasePool *pool = [[NSAutoreleasePool alloc] init];
    OSXMediaPlayer *player = nil;
    int32_t result = AvfResolvePlayer(media, &player);
    if (player) {
        [player finish];
    }
    [pool drain];
    return result;
}

int32_t jfxm_avf_player_get_rate(JfxmMedia *media, float *out_rate)
{
    NSAutoreleasePool *pool = [[NSAutoreleasePool alloc] init];
    OSXMediaPlayer *player = nil;
    int32_t result = AvfResolvePlayer(media, &player);
    if (player && out_rate != NULL) {
        *out_rate = player.rate;
    }
    [pool drain];
    return result;
}

int32_t jfxm_avf_player_set_rate(JfxmMedia *media, float rate)
{
    NSAutoreleasePool *pool = [[NSAutoreleasePool alloc] init];
    OSXMediaPlayer *player = nil;
    int32_t result = AvfResolvePlayer(media, &player);
    if (player) {
        player.rate = rate;
    }
    [pool drain];
    return result;
}

int32_t jfxm_avf_player_get_presentation_time(JfxmMedia *media, double *out_seconds)
{
    NSAutoreleasePool *pool = [[NSAutoreleasePool alloc] init];
    OSXMediaPlayer *player = nil;
    int32_t result = AvfResolvePlayer(media, &player);
    if (player && out_seconds != NULL) {
        *out_seconds = player.currentTime;
    }
    [pool drain];
    return result;
}

int32_t jfxm_avf_player_get_mute(JfxmMedia *media, int32_t *out_mute)
{
    NSAutoreleasePool *pool = [[NSAutoreleasePool alloc] init];
    OSXMediaPlayer *player = nil;
    int32_t result = AvfResolvePlayer(media, &player);
    if (player && out_mute != NULL) {
        *out_mute = (player.mute != NO) ? 1 : 0;
    }
    [pool drain];
    return result;
}

int32_t jfxm_avf_player_set_mute(JfxmMedia *media, int32_t mute)
{
    NSAutoreleasePool *pool = [[NSAutoreleasePool alloc] init];
    OSXMediaPlayer *player = nil;
    int32_t result = AvfResolvePlayer(media, &player);
    if (player) {
        player.mute = (mute != 0);
    }
    [pool drain];
    return result;
}

int32_t jfxm_avf_player_get_volume(JfxmMedia *media, float *out_volume)
{
    NSAutoreleasePool *pool = [[NSAutoreleasePool alloc] init];
    OSXMediaPlayer *player = nil;
    int32_t result = AvfResolvePlayer(media, &player);
    if (player && out_volume != NULL) {
        *out_volume = player.volume;
    }
    [pool drain];
    return result;
}

int32_t jfxm_avf_player_set_volume(JfxmMedia *media, float volume)
{
    NSAutoreleasePool *pool = [[NSAutoreleasePool alloc] init];
    OSXMediaPlayer *player = nil;
    int32_t result = AvfResolvePlayer(media, &player);
    if (player) {
        player.volume = volume;
    }
    [pool drain];
    return result;
}

int32_t jfxm_avf_player_get_balance(JfxmMedia *media, float *out_balance)
{
    NSAutoreleasePool *pool = [[NSAutoreleasePool alloc] init];
    OSXMediaPlayer *player = nil;
    int32_t result = AvfResolvePlayer(media, &player);
    if (player && out_balance != NULL) {
        *out_balance = player.balance;
    }
    [pool drain];
    return result;
}

int32_t jfxm_avf_player_set_balance(JfxmMedia *media, float balance)
{
    NSAutoreleasePool *pool = [[NSAutoreleasePool alloc] init];
    OSXMediaPlayer *player = nil;
    int32_t result = AvfResolvePlayer(media, &player);
    if (player) {
        player.balance = balance;
    }
    [pool drain];
    return result;
}

int32_t jfxm_avf_player_get_duration(JfxmMedia *media, double *out_seconds)
{
    NSAutoreleasePool *pool = [[NSAutoreleasePool alloc] init];
    OSXMediaPlayer *player = nil;
    int32_t result = AvfResolvePlayer(media, &player);
    if (player && out_seconds != NULL) {
        *out_seconds = player.duration; // -1.0 while unknown, mapped to +Infinity in Java
    }
    [pool drain];
    return result;
}

int32_t jfxm_avf_player_seek(JfxmMedia *media, double seconds)
{
    NSAutoreleasePool *pool = [[NSAutoreleasePool alloc] init];
    OSXMediaPlayer *player = nil;
    int32_t result = AvfResolvePlayer(media, &player);
    if (player) {
        player.currentTime = seconds;
    }
    [pool drain];
    return result;
}
