/*
 * Copyright (c) 2014, 2026, Oracle and/or its affiliates. All rights reserved.
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

#import "AVFMediaPlayer.h"
#import <objc/runtime.h>
// The display-link registry below: its lock and condition variable, and the allocation of its
// entries. pthread rather than a dispatch or Objective-C lock because the registry needs a
// condition variable for the join in -dispose, and because PTHREAD_MUTEX_INITIALIZER needs no
// dispatch_once to become usable. AVFAudioSpectrumUnit guards its bands with pthread too.
#import <pthread.h>
#import <stdlib.h>
// std::nothrow, for the frame allocation in -sendPixelBuffer:.
#import <new>
#import "CVVideoFrame.h"

#import <jni/Logger.h>
#import <PipelineManagement/NullAudioEqualizer.h>
#import <PipelineManagement/NullAudioSpectrum.h>
// ERROR_LOCATOR_CONNECTION_LOST, the code the resource loader delegate reports to AVFoundation when
// a read fails. The header is generated from MediaError.java into HEADERS_DIR by the headergen tool.
#import <jfxmedia_errors.h>

#import "AVFAudioProcessor.h"

// "borrowed" from green screen player on ADC
// These are used to reduce power consumption when there are no video frames
// to be rendered, which is generally A Good Thing
#define FREEWHEELING_PERIOD_IN_SECONDS 0.5
#define ADVANCE_INTERVAL_IN_SECONDS 0.1

// set to 1 to debug track information
#define DUMP_TRACK_INFO 0

// trick used by Apple in AVGreenScreenPlayer
// This avoids calling [NSString isEqualTo:@"..."]
// The actual value is meaningless, but needs to be unique
static void *AVFMediaPlayerItemStatusContext = &AVFMediaPlayerItemStatusContext;
static void *AVFMediaPlayerItemDurationContext = &AVFMediaPlayerItemDurationContext;
static void *AVFMediaPlayerItemTracksContext = &AVFMediaPlayerItemTracksContext;

// See JDK-8328603. For some streams if we let AVFoundation to decide
// the format and decided format is not supported video will not be outputed
// after we force AVFoundation to supported format (FALLBACK_VO_FORMAT).
// Not sure why it happens, but if we provide supported by JavaFX Media format
// list to AVFoundation will use one of them and no video issue is no longer
// reproducible. We will still have fallback to FALLBACK_VO_FORMAT even if it
// is in the list of prefered formats.
// Uncomment to force list of supported formats by JavaFX.
// Note: This array should match CVVideoFrame::IsFormatSupported().
#define VO_FORMATS @{(id)kCVPixelBufferPixelFormatTypeKey: @[@(kCVPixelFormatType_422YpCbCr8),\
                                                           @(kCVPixelFormatType_420YpCbCr8Planar),\
                                                           @(kCVPixelFormatType_32BGRA)]}
// Uncomment to let AVFoundation decide the format...
//#define VO_FORMATS @{}

#define FORCE_VO_FORMAT 0
#if FORCE_VO_FORMAT
// #define FORCED_VO_FORMAT kCVPixelFormatType_32BGRA
// #define FORCED_VO_FORMAT kCVPixelFormatType_422YpCbCr8
// #define FORCED_VO_FORMAT kCVPixelFormatType_420YpCbCr8Planar
 #define FORCED_VO_FORMAT kCVPixelFormatType_422YpCbCr8_yuvs // Unsupported, use to test fallback
// #define FORCED_VO_FORMAT kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange // Unsupported, use to test fallback
#endif

// Apple really likes to output '2vuy', this should be the least expensive conversion
#define FALLBACK_VO_FORMAT kCVPixelFormatType_422YpCbCr8

#define FOURCC_CHAR(f) ((f) & 0x7f) ? (char)((f) & 0x7f) : '?'

static inline NSString *FourCCToNSString(UInt32 fcc) {
    if (fcc < 0x100) {
        return [NSString stringWithFormat:@"%u", fcc];
    }
    return [NSString stringWithFormat:@"%c%c%c%c",
            FOURCC_CHAR(fcc >> 24),
            FOURCC_CHAR(fcc >> 16),
            FOURCC_CHAR(fcc >> 8),
            FOURCC_CHAR(fcc)];
}

#if DUMP_TRACK_INFO
static void append_log(NSMutableString *s, NSString *fmt, ...) {
    va_list args;
    va_start(args, fmt);
    NSString *appString = [[NSString alloc] initWithFormat:fmt arguments:args];
    [s appendFormat:@"%@\n", appString];
    va_end(args);
}
#define TRACK_LOG(fmt, ...) append_log(trackLog, fmt, ##__VA_ARGS__)
#else
#define TRACK_LOG(...) {}
#endif

// Max number of bytes we will provide per request
#define MAX_READ_SIZE (1024 * 1024)

// CStreamCallbacks::ReadBlock and ::ReadNextBlock return the number of bytes they staged, or -1 at
// end of stream and -2 when the read itself failed (Locator/LocatorStream.h); the javasource
// element spells the same two codes EOS_CODE and OTHER_ERROR_CODE.
#define READ_EOS_CODE (-1)

// AVFoundation accepts a failed load only as an NSError, and the error vocabulary of this stack is
// jfxmedia's own, so the NSErrors created here carry a jfxmedia error code in a jfxmedia domain
// rather than a borrowed Cocoa one.
static NSString * const AVFMediaErrorDomain = @"com.sun.media.jfxmedia";

// Destroys the CLocatorStream and the CStreamCallbacks adapter behind it - the pair that
// -initWithURL:eventHandler:locatorStream: takes ownership of. CLocatorStream has no destructor and
// only stores the adapter pointers (Locator/LocatorStream.cpp), so deleting the locator does not
// delete the adapter; the pair has to come apart the way CGstPipelineFactory::SourceCloseConnection
// takes the GStreamer one apart - close the adapter, then delete it - with the locator deleted
// last. CStreamCallbacks declares a virtual destructor, so deleting through the CStreamCallbacks*
// GetCallbacks() returns runs ~CFfiStreamCallbacks. GetAudioCallbacks() is deliberately not
// touched: SetAudioCallbacks is called only from jfxm_media_create's GStreamer path, so the locator
// jfxm_avf_player_init builds leaves it NULL for the life of the player.
//
// closeConnection is YES only from -dispose. The initializer's failure paths pass NO, matching the
// player-creation failure path of jfxm_avf_player_init and the jfxm_media_create failure path
// before it: there the Java side closes its own connection holder.
static void AVFDestroyLocatorStream(CLocatorStream *ls, BOOL closeConnection) {
    if (ls == NULL) {
        return;
    }

    CStreamCallbacks *callbacks = ls->GetCallbacks();
    if (callbacks != NULL) {
        if (closeConnection) {
            callbacks->CloseConnection();
        }
        delete callbacks;
    }
    delete ls;
}

#pragma mark -
#pragma mark Display-link callback registry

// CVDisplayLinkSetOutputCallback takes one void* of context and hands it back on every vsync, on a
// thread CoreVideo owns and this file never joins. Passing (__bridge void *)self there - which is
// what this file used to do - makes that context an unretained pointer to an object -dispose is in
// the middle of tearing down, and displayLinkCallback dereferences it before -sendPixelBuffer: is
// entered (self.playerOutput) and again after it returns (self.hlsBugResetCount, self.lastHostTime,
// self.player.rate, -hlsBugReset). The isDisposed test inside -sendPixelBuffer: therefore never was
// the fence and could not have been made into one: by the time it is read the pointer may already
// be freed memory, because -[OSXMediaPlayer dispose] releases the player - running -dealloc - as
// soon as [player dispose] returns, then deletes the CPlayerEventDispatcher this callback sends
// through, after which OSXMedia.java closes the shared Arena holding all 13 upcall stubs.
//
// jfxmedia_api.h promises the opposite ("after jfxm_media_dispose returns no callback of any table
// fires again") and the Java side frees the stubs and the registry entry on the strength of it, so
// the promise has to be made true here rather than assumed. This registry is what makes it true:
//
//   * The callback context is a token - a plain integer that is never dereferenced - so a callback
//     that arrives late cannot fault on the context itself. That is what sinks the obvious
//     alternative, a heap-allocated context struct holding a retained self that is freed when the
//     player goes: the struct has to be freed at some point too, and a late callback would fault
//     on that instead.
//   * The token names an entry that holds a strong reference to the player, so a callback that has
//     found its entry cannot have the player deallocated underneath it.
//   * The entry is guarded by this file's own lock, never by the player's monitor, and the callback
//     holds that lock only for the lookup and for the matching release - never across an
//     AVFoundation call, never across an eventHandler send, never across a vsync's worth of work.
//     Worth recording, because it is the one cost this design puts on the render path:
//     gDisplayLinkLock is a process-global, non-priority-inheriting pthread mutex, taken twice per
//     vsync by a thread CoreVideo schedules with a time-constraint (real-time) policy, so a lower
//     priority thread holding it could in principle delay a frame. It is acceptable because every
//     hold on the render path is a short list walk with no allocation, no syscall and no
//     Objective-C message in it, and the list has one node per live player. The one hold that can
//     syscall is the pthread_cond_broadcast in the claim's destructor, and that runs only while a
//     dispose is in progress, never on an ordinary vsync. os_unfair_lock would add priority
//     donation, but it cannot back a condition variable and the join needs one, so it would have to
//     be a second lock over the same state - more to reason about than the contention it removes.
//   * -dispose retires the entry and waits for the in-flight count to reach zero BEFORE it takes
//     @synchronized(self). After that join no callback is running and none can start, so stopping
//     and releasing the display link, deleting eventHandler and unmapping the upcall stubs are all
//     safe - and none of it depends on whether CVDisplayLinkStop drains a callback that is already
//     executing, which Apple documents neither way. That undocumented behaviour was the third
//     rejected alternative: relying on it means the correctness of the whole teardown rests on an
//     implementation detail, and testing it locally would prove nothing about other macOS versions.
//
// The fourth alternative, and the closest one, is worth naming because the first bullet above does
// not actually rule it out: a per-player heap box holding the retain, the in-flight count, the
// retiring flag and the condition variable, handed to CVDisplayLinkSetOutputCallback as itself and
// then *deliberately never freed*. A box that is never freed is never a dangling pointer, so the
// token, this list and the walk all disappear and the callback is one dereference plus the same
// lock pair. It was not chosen for three reasons, none of them about correctness: the leak is
// bounded per player but unbounded over a process lifetime, so an application that creates and
// disposes many MediaPlayers accumulates it; a box that nothing references any more is a real leak
// to leaks(1) and to the sanitizers, i.e. permanent noise in exactly the tools that would be used
// to check this code; and a list that is empty once every player is disposed is a checkable
// invariant, where "everything ever allocated is still allocated on purpose" is not. If the walk
// ever shows up in a profile, the box is the design to move to.
//
// Why the join cannot deadlock, stated as what each thread holds and what it waits for:
//
//   * The display-link thread waits for the registry lock, twice, and holds nothing else while it
//     does. It never asks for @synchronized(self) - no step of the callback takes that monitor -
//     and the Java it upcalls into only offers onto an unbounded queue.
//   * The disposing thread holds the registry lock and waits for the in-flight count; while it
//     waits, pthread_cond_wait has released even that, so it holds nothing at all. Crucially it has
//     NOT yet taken @synchronized(self): a callback can be inside an AVFoundation call that demands
//     bytes from a jar:/jrt: source, and those arrive through
//     -resourceLoader:shouldWaitForLoadingOfRequestedResource:, whose entire body runs inside that
//     monitor on the loader queue. Joining while holding the monitor would deadlock exactly there;
//     joining before taking it cannot, because the monitor is free for the loader queue to take.
//   * The wait therefore terminates whenever an in-flight callback terminates, and every step of
//     one is bounded: AVFoundation calls on the video output, a CVVideoFrame construction, two
//     upcalls that enqueue and return, and two dispatch_asyncs that never wait for their block.
//   * The disposing thread is a Java thread inside a downcall, so the JVM has it in native state
//     and blocking it holds up no safepoint; the display-link thread can still attach, allocate and
//     let GC run while the join waits.
typedef struct AVFDisplayLinkEntry {
    uintptr_t token;
    // The player, holding the +1 taken at registration and given back at retirement. Kept as a
    // void* rather than an object pointer so that this stays a plain C struct: the retain and the
    // release are the two __bridge_retained / __bridge_transfer casts below and nowhere else.
    void *player;
    int inFlight;
    BOOL retiring;
    struct AVFDisplayLinkEntry *next;
} AVFDisplayLinkEntry;

static pthread_mutex_t gDisplayLinkLock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t gDisplayLinkIdle = PTHREAD_COND_INITIALIZER;
static AVFDisplayLinkEntry *gDisplayLinkEntries = NULL;
// Token 0 means "no entry", so it is never handed out: a player whose registration failed, and a
// display link created by -createVideoOutput after -dispose has already retired the token, both end
// up looking up something that cannot be found, which is the outcome they want.
static uintptr_t gDisplayLinkNextToken = 1;

// Callers hold gDisplayLinkLock. Players are counted in ones, not thousands, so the list is walked
// rather than hashed; the walk is the only per-vsync cost this registry adds beyond the lock.
static AVFDisplayLinkEntry *AVFFindDisplayLinkEntry(uintptr_t token) {
    if (token == 0) {
        return NULL;
    }
    for (AVFDisplayLinkEntry *entry = gDisplayLinkEntries; entry != NULL; entry = entry->next) {
        if (entry->token == token) {
            return entry;
        }
    }
    return NULL;
}

// Returns the token to hand to CVDisplayLinkSetOutputCallback, or 0 if the entry could not be
// allocated - in which case every callback returns immediately and no video is delivered, which is
// the same outcome as never creating the display link at all and is the only safe answer available:
// an unregistered player has nothing to fence its callbacks with.
static uintptr_t AVFRegisterDisplayLinkTarget(AVFMediaPlayer *player) {
    AVFDisplayLinkEntry *entry = (AVFDisplayLinkEntry *)calloc(1, sizeof(AVFDisplayLinkEntry));
    if (entry == NULL) {
        return 0;
    }
    entry->player = (__bridge_retained void *)player;
    pthread_mutex_lock(&gDisplayLinkLock);
    entry->token = gDisplayLinkNextToken++;
    entry->next = gDisplayLinkEntries;
    gDisplayLinkEntries = entry;
    uintptr_t token = entry->token;
    pthread_mutex_unlock(&gDisplayLinkLock);
    return token;
}

// The join. Marks the entry so that no further callback can claim it, waits for the callbacks
// already inside it to leave, unlinks it and gives back the reference it held. After this returns,
// no displayLinkCallback for this token is running and none can start again - tokens are never
// reused, so a display link still wired to this one can only ever miss.
static void AVFRetireDisplayLinkTarget(uintptr_t token) {
    if (token == 0) {
        return;
    }

    void *retiredPlayer = NULL;
    AVFDisplayLinkEntry *entry = NULL;
    BOOL unlinked = NO;

    pthread_mutex_lock(&gDisplayLinkLock);
    entry = AVFFindDisplayLinkEntry(token);
    if (entry != NULL) {
        entry->retiring = YES;
        while (entry->inFlight > 0) {
            // entry stays valid across the wait: only the thread that set retiring unlinks or frees
            // it, and only after this loop. Two threads retiring the same token cannot happen -
            // the caller takes the token out of the ivar with an atomic exchange, so exactly one
            // caller ever sees a non-zero token for a given entry.
            pthread_cond_wait(&gDisplayLinkIdle, &gDisplayLinkLock);
        }
        AVFDisplayLinkEntry **link = &gDisplayLinkEntries;
        while (*link != NULL && *link != entry) {
            link = &(*link)->next;
        }
        // Everything below the unlink is conditional on the unlink having happened, and that is
        // not belt and braces. AVFFindDisplayLinkEntry only ever returns a node it reached by
        // walking this same list, so falling off the end here is impossible - but "impossible" and
        // "checked" are different things, and freeing a node that is still linked would leave
        // gDisplayLinkEntries pointing into freed memory, which the very next claim would walk on
        // the real-time display-link thread. So a miss leaks the node and its retain deliberately
        // and says so, rather than converting a broken invariant into a use-after-free. A leaked
        // node is inert: retiring is already set, so no future claim can take it.
        if (*link == entry) {
            *link = entry->next;
            unlinked = YES;
            retiredPlayer = entry->player;
            entry->player = NULL;
        }
    }
    pthread_mutex_unlock(&gDisplayLinkLock);

    if (unlinked) {
        free(entry);
    } else if (entry != NULL) {
        LOGGER_ERRORMSG("AVFMediaPlayer: display link registry entry was not on the list, "
                        "leaking it rather than freeing a reachable node\n");
    }

    if (retiredPlayer != NULL) {
        // Balances the retain taken at registration, deliberately outside the lock: dropping the
        // last reference to a player runs -dealloc, which calls -dispose, which comes back here -
        // and gDisplayLinkLock is not recursive. It cannot be the last reference in practice (the
        // only caller is -dispose, and -[OSXMediaPlayer dispose] holds one across it), and this is
        // also why the strong reference held by the entry is not a leak-making cycle: [player
        // dispose] always precedes [player release] on both of OSXMediaPlayer's teardown paths, so
        // the entry is always gone before the player's last reference is dropped. Doing the release
        // out here costs nothing and removes the question.
        AVFMediaPlayer *retired = (__bridge_transfer AVFMediaPlayer *)retiredPlayer;
        (void)retired;
    }
}

namespace {

// One display-link callback's claim on a registry entry: the constructor takes it if the entry
// exists and is not retiring, the destructor gives it back. A destructor rather than a call at the
// end of displayLinkCallback, because that function returns early on three paths, and because an
// exception could still unwind through it - CVVideoFrame's constructor throws only const char *,
// which -sendPixelBuffer: catches, and its allocation is nothrow, but the Foundation and
// eventHandler calls on the way could raise an NSException. What the destructor buys is that the
// registry stays consistent on every one of those exits; a claim leaked on any of them would hang
// the next -dispose for good. It does not make an unwind into CoreVideo's C frame survivable -
// nothing here could - which is why the one such unwind this code could itself produce was removed
// at the allocation instead.
class AVFDisplayLinkCall {
public:
    explicit AVFDisplayLinkCall(uintptr_t token) : m_entry(NULL), m_player(NULL) {
        if (token == 0) {
            return;
        }
        pthread_mutex_lock(&gDisplayLinkLock);
        AVFDisplayLinkEntry *entry = AVFFindDisplayLinkEntry(token);
        if (entry != NULL && !entry->retiring) {
            entry->inFlight++;
            m_entry = entry;
            m_player = entry->player;
        }
        pthread_mutex_unlock(&gDisplayLinkLock);
    }

    ~AVFDisplayLinkCall() {
        if (m_entry == NULL) {
            return;
        }
        pthread_mutex_lock(&gDisplayLinkLock);
        // The entry cannot have been unlinked or freed while this claim was outstanding:
        // AVFRetireDisplayLinkTarget waits for inFlight to reach zero before it touches either.
        m_entry->inFlight--;
        // Signalling only on the retiring path keeps the ordinary vsync free of it; nobody waits on
        // this condition except a retirement in progress.
        if (m_entry->retiring && m_entry->inFlight == 0) {
            pthread_cond_broadcast(&gDisplayLinkIdle);
        }
        pthread_mutex_unlock(&gDisplayLinkLock);
    }

    // The player this token names, or NULL when the entry is gone or retiring. Returned as a void*
    // so that the __bridge cast happens at the call site, where its result goes straight into a
    // __strong local and is a plain retain: an object pointer returned across a C++ function
    // boundary goes through ARC's autorelease-return convention instead, and the display-link
    // thread has no autorelease pool of its own. The entry keeps the player alive for as long as
    // this claim is outstanding, so the cast at the call site always has something live to retain.
    void *player() const {
        return m_player;
    }

private:
    AVFDisplayLinkCall(const AVFDisplayLinkCall &);
    AVFDisplayLinkCall &operator=(const AVFDisplayLinkCall &);

    AVFDisplayLinkEntry *m_entry;
    void *m_player;
};

}

#pragma mark -

@implementation AVFMediaPlayer

static void SpectrumCallbackProc(void *context, double duration, double timestamp);

static CVReturn displayLinkCallback(CVDisplayLinkRef displayLink,
                                    const CVTimeStamp *inNow,
                                    const CVTimeStamp *inOutputTime,
                                    CVOptionFlags flagsIn,
                                    CVOptionFlags *flagsOut,
                                    void *displayLinkContext);

+ (BOOL) playerAvailable {
    // Check if AVPlayerItemVideoOutput exists, if not we're running on 10.7 or
    // earlier which is no longer supported
    Class klass = objc_getClass("AVPlayerItemVideoOutput");
    return (klass != nil);
}

// Takes ownership of ls and of the CStreamCallbacks adapter behind it: from the moment this
// initializer is entered that pair is freed here - by -dispose on the ordinary path, or by the two
// paths below that return nil. jfxm_avf_player_init's own cleanup covers only the case where this
// object was never created, and the two can never overlap: -[OSXMediaPlayer
// initWithURL:eventHandler:locatorStream:] returns nil only from checks that run before it
// allocates an AVFMediaPlayer. The pair is therefore freed exactly once - given that the third
// exit from this method never happens. That exit is [super init] returning nil, which skips the
// whole body: nothing here would free the pair and jfxm_avf_player_init's !player branch would not
// either, because -[OSXMediaPlayer initWithURL:...] would have returned non-nil. It is unreachable
// rather than handled: [super init] here is -[NSObject init], which never returns nil.
- (id) initWithURL:(NSURL *)source eventHandler:(CPlayerEventDispatcher*)hdlr locatorStream:(CLocatorStream*)ls {
    if ((self = [super init]) != nil) {
        previousWidth = -1;
        previousHeight = -1;
        previousPlayerState = kPlayerState_UNKNOWN;

        eventHandler = hdlr;

        self.movieURL = source;
        _buggyHLSSupport = NO;
        _hlsBugResetCount = 0;

        // Create our own work queue
        playerQueue = dispatch_queue_create(NULL, NULL);

        // Create the player
        _player = [AVPlayer playerWithURL:source];
        if (!_player) {
            AVFDestroyLocatorStream(ls, NO);
            return nil;
        }

        // Setup AVAssetResourceLoaderDelegate if locatorStream provided and use
        // it to load data.
        if (ls != NULL) {
            AVAsset *avAsset = _player.currentItem.asset;
            if ([avAsset isKindOfClass:AVURLAsset.class]) {
                AVURLAsset *avUrlAsset = (AVURLAsset *)avAsset;

                playerLoaderQueue = dispatch_queue_create(NULL, NULL);

                AVAssetResourceLoader *resourceLoader = avUrlAsset.resourceLoader;
                [resourceLoader setDelegate:self queue:playerLoaderQueue];
            } else {
                AVFDestroyLocatorStream(ls, NO);
                return nil;
            }

            // Published after the delegate was installed, where master had it. A loading request
            // arriving in the window between the two reads NULL and returns NO, failing that
            // resource load. The window is pre-existing and left alone: closing it changes
            // behaviour on a path this change is not about, and whether AVFoundation retries such a
            // request is unverified here. It does not weaken the teardown in -dispose, which is
            // what the monitor is relied on for: the window can only make a read see NULL, never a
            // pointer that is being freed.
            self->locatorStream = ls;
        }

        _player.volume = 1.0f;
        _player.muted = NO;

        // Set the player item end action to NONE since we'll handle it internally
        _player.actionAtItemEnd = AVPlayerActionAtItemEndNone;

        // The context the display link will be given, taken here rather than in -createVideoOutput
        // because that method can run on the KVO thread the moment the observers below are
        // installed, and a display link wired with a token this initializer had not assigned yet
        // would deliver no frames for the life of the player. Both of this initializer's
        // nil-returning paths are above this line, so an entry created here is always matched by
        // the retirement in -dispose - which is what keeps the entry's strong reference from
        // stranding the player.
        uintptr_t token = AVFRegisterDisplayLinkTarget(self);
        _displayLinkToken = token;
        if (token == 0) {
            LOGGER_ERRORMSG("AVFMediaPlayer: unable to register the display link callback target, "
                            "video will not be rendered\n");
        }

        /*
         * AVPlayerItem notifications we could listen for:
         * 10.7 AVPlayerItemTimeJumpedNotification -> the item's current time has changed discontinuously
         * 10.7 AVPlayerItemDidPlayToEndTimeNotification -> item has played to its end time
         * 10.7 AVPlayerItemFailedToPlayToEndTimeNotification (userInfo = NSError) -> item has failed to play to its end time
         * 10.9 AVPlayerItemPlaybackStalledNotification -> media did not arrive in time to continue playback
         */
        playerObservers = [[NSMutableArray alloc] init];
        id<NSObject> observer;
        __weak AVFMediaPlayer *blockSelf = self; // retain cycle avoidance
        NSNotificationCenter *center = [NSNotificationCenter defaultCenter];
        observer = [center addObserverForName:AVPlayerItemDidPlayToEndTimeNotification
                                       object:_player.currentItem
                                        queue:[NSOperationQueue mainQueue]
                                   usingBlock:^(NSNotification *note) {
                                       // promote FINISHED state...
                                       [blockSelf setPlayerState:kPlayerState_FINISHED];
                                   }];
        if (observer) {
            [playerObservers addObject:observer];
        }

        keyPathsObserved = [[NSMutableArray alloc] init];
        [self observeKeyPath:@"self.player.currentItem.status"
                 withContext:AVFMediaPlayerItemStatusContext];

        [self observeKeyPath:@"self.player.currentItem.duration"
                 withContext:AVFMediaPlayerItemDurationContext];

        [self observeKeyPath:@"self.player.currentItem.tracks"
                 withContext:AVFMediaPlayerItemTracksContext];


        [self setPlayerState:kPlayerState_UNKNOWN];

        // filled out later
        _videoFormat = nil;
        _lastHostTime = 0LL;

        // Don't create video output until we know we have video
        _playerOutput = nil;
        _displayLink = NULL;

        _audioProcessor = [[AVFAudioProcessor alloc] init];
        if (_audioProcessor.audioSpectrum != nullptr) {
            _audioProcessor.audioSpectrum->SetSpectrumCallbackProc(SpectrumCallbackProc, (__bridge void*)self);
        }

        isDisposed = NO;
    }
    return self;
}

- (void) dealloc {
    [self dispose];

    self.movieURL = nil;
    self.player = nil;
    self.playerOutput = nil;
}

- (CAudioSpectrum*) audioSpectrum {
    AVFAudioSpectrumUnitPtr asPtr = _audioProcessor.audioSpectrum;
    return static_cast<CAudioSpectrum*>(&(*asPtr));
}

- (CAudioEqualizer*) audioEqualizer {
    AVFAudioEqualizerPtr eqPtr = _audioProcessor.audioEqualizer;
    return static_cast<CAudioEqualizer*>(&(*eqPtr));
}

- (void) observeKeyPath:(NSString*)keyPath withContext:(void*)context {
    [self addObserver:self forKeyPath:keyPath options:NSKeyValueObservingOptionNew context:context];
    [keyPathsObserved addObject:keyPath];
}

// If we get an unsupported pixel format in the video output, call this to
// force it to output our fallback format
- (void) setFallbackVideoFormat {
    // schedule this to be done when we're not buried inside the AVPlayer callback
    __weak AVFMediaPlayer *blockSelf = self; // retain cycle avoidance
    dispatch_async(dispatch_get_main_queue(), ^{
        LOGGER_DEBUGMSG(([[NSString stringWithFormat:@"Falling back on video format: %@", FourCCToNSString(FALLBACK_VO_FORMAT)] UTF8String]));
        AVPlayerItemVideoOutput *newOutput =
        [[AVPlayerItemVideoOutput alloc] initWithPixelBufferAttributes:
         @{(id)kCVPixelBufferPixelFormatTypeKey: @(FALLBACK_VO_FORMAT)}];

        if (newOutput) {
            newOutput.suppressesPlayerRendering = YES;

            CVDisplayLinkStop(_displayLink);
            [_player.currentItem removeOutput:_playerOutput];
            [_playerOutput setDelegate:nil queue:nil];

            self.playerOutput = newOutput;
            [_playerOutput setDelegate:blockSelf queue:playerQueue];
            [_playerOutput requestNotificationOfMediaDataChangeWithAdvanceInterval:ADVANCE_INTERVAL_IN_SECONDS];
            [_player.currentItem addOutput:_playerOutput];
        }
    });
}

- (void) createVideoOutput {
    @synchronized(self) {
        // -dispose has already removed the output and stopped the display link, so building new
        // ones here would resurrect both on a player nobody will ever tear down again.
        //
        // This is the one place left where the monitor is held across AVFoundation calls -
        // addOutput:, the display link - and that predates this fork rather than being introduced
        // by the dispose fence. It survives the rule in -observeValueForKeyPath: because none of
        // these reads an AVAssetTrack property, which is the call class with a documented
        // synchronous load fallback; whatever loading addOutput: goes on to cause happens on
        // AVFoundation's own threads and is not waited for here. Narrowing it anyway would be a
        // reasonable follow-up, and it is the second place to look if a jar:/jrt: source hangs.
        if (isDisposed) {
            return;
        }

        // Skip if already created
        if (!_playerOutput) {
#if FORCE_VO_FORMAT
            LOGGER_DEBUGMSG(([[NSString stringWithFormat:@"Forcing VO format: %@", FourCCToNSString(FORCED_VO_FORMAT)] UTF8String]));
#endif
            // Create the player video output
            // kCVPixelFormatType_32ARGB comes out inverted, so don't use it
            // '2vuy' -> kCVPixelFormatType_422YpCbCr8 -> YCbCr_422 (uses less CPU too)
            // kCVPixelFormatType_420YpCbCr8Planar
            _playerOutput = [[AVPlayerItemVideoOutput alloc] initWithPixelBufferAttributes:
#if FORCE_VO_FORMAT
                             @{(id)kCVPixelBufferPixelFormatTypeKey: @(FORCED_VO_FORMAT)}];
#else
                             VO_FORMATS];
#endif
            if (!_playerOutput) {
                return;
            }
            _playerOutput.suppressesPlayerRendering = YES;

            // Set up the display link (do we need this??)
            // The context is the registry token, not (__bridge void *)self - see the display-link
            // registry at the top of this file, which is also the answer to the note that used to
            // stand here about needing a context struct that retains us.
            //
            // The token is read from the ivar on every call rather than cached, so that the one
            // interleaving that can still build a display link after teardown ends harmlessly:
            // -dispose retires the token before it blocks on the monitor this method is holding, so
            // this reads either the retired token or the zero -dispose left behind, and a callback
            // finds no entry either way. The link itself is then stopped and released by -dispose
            // as soon as it gets the monitor.
            uintptr_t token = _displayLinkToken;
            CVDisplayLinkCreateWithActiveCGDisplays(&_displayLink);
            CVDisplayLinkSetOutputCallback(_displayLink, displayLinkCallback, (void *)token);
            // Pause display link to conserve power
            CVDisplayLinkStop(_displayLink);

            // Set up playerOutput delegate
            [_playerOutput setDelegate:self queue:playerQueue];
            [_playerOutput requestNotificationOfMediaDataChangeWithAdvanceInterval:ADVANCE_INTERVAL_IN_SECONDS];

            [_player.currentItem addOutput:_playerOutput];
        }
    }
}

- (void) setPlayerState:(int)newState {
    // Every state event in this player funnels through here, from four different threads: the Java
    // caller thread (play/pause/stop/finish), the KVO thread (READY, HALTED), the main queue (the
    // end-of-media notification block), and the disposing thread. eventHandler is deleted by
    // -[OSXMediaPlayer dispose] as soon as [player dispose] returns, and Java unmaps the upcall
    // stubs behind it when it closes its shared arena, so the send has to be inside the monitor
    // -dispose holds and behind an isDisposed test. The main-queue path is the one that had no
    // other fence at all: -removeObserver: does not cancel a block already enqueued on the main
    // queue, and blockSelf is only __weak, so it can still be non-nil while an autorelease pool
    // holds the player.
    //
    // -dispose calls this itself, from inside the monitor and before it sets isDisposed, so the
    // HALTED event it sends still goes out exactly as before - @synchronized is recursive per
    // thread. The monitor is held only across the send, which enqueues and returns; the one cost is
    // that a state change can now wait on a resource-loader read that holds the monitor, which is a
    // local jar:/jrt: read and is already what -dispose waits on.
    @synchronized(self) {
        if (isDisposed) {
            return;
        }
        if (newState != previousPlayerState) {
            // For now just send up to client
            eventHandler->SendPlayerStateEvent(newState, 0.0);
            previousPlayerState = newState;
        }
    }
}

- (void) hlsBugReset {
    // schedule this to be done when we're not buried inside the AVPlayer callback
    dispatch_async(dispatch_get_main_queue(), ^{
        LOGGER_DEBUGMSG(([[NSString stringWithFormat:@"hlsBugReset()"] UTF8String]));

        if (_playerOutput) {
            _playerOutput.suppressesPlayerRendering = YES;

            CVDisplayLinkStop(_displayLink);
            [_player.currentItem removeOutput:_playerOutput];

            [_playerOutput requestNotificationOfMediaDataChangeWithAdvanceInterval:ADVANCE_INTERVAL_IN_SECONDS];
            [_player.currentItem addOutput:_playerOutput];

            self.hlsBugResetCount = 0;
        }
    });
}

- (void) logNSError:(NSString*)tag error:(NSError*)error
{
    if (error != nil) {
        LOGGER_DEBUGMSG(([
            [NSString stringWithFormat:@"[%@] error code: %d",
            tag, (int)error.code] UTF8String]));
        LOGGER_DEBUGMSG(([
            [NSString stringWithFormat:@"[%@] error description: %@",
            tag, error.localizedDescription] UTF8String]));
    } else {
        LOGGER_DEBUGMSG(([
             [NSString stringWithFormat:@"Error nil for [%@]",
             tag] UTF8String]));
    }
}

- (void) observeValueForKeyPath:(NSString *)keyPath
                       ofObject:(id)object
                         change:(NSDictionary *)change
                        context:(void *)context {
    // This runs on an AVFoundation KVO thread and every branch of it ends in an eventHandler send.
    // -[OSXMediaPlayer dispose] deletes that dispatcher as soon as [player dispose] returns, and
    // OSXMediaPlayer.java then closes the Arena.ofShared() that owns all 13 upcall stubs, so a send
    // that arrives afterwards calls through a freed vtable into an unmapped trampoline - a SIGSEGV
    // with no Java frame. jfxmedia_api.h promises the opposite ("after jfxm_media_dispose returns no
    // callback of any table fires again"), and -removeObserver:forKeyPath: in -dispose does not
    // drain a notification already being delivered on another thread, so the promise needs the
    // monitor to be true. Disposing a player that is still loading is the ordinary "user cancelled"
    // case, and status, duration and tracks all fire repeatedly while it loads.
    //
    // The rule for where that monitor may be taken, which is what the shape below is about:
    //
    //     hold it across the eventHandler sends, and NEVER across an AVFoundation call.
    //
    // -resourceLoader:shouldWaitForLoadingOfRequestedResource: has its entire body inside this same
    // monitor, on the serial playerLoaderQueue. So any AVFoundation call made while holding the
    // monitor that can end up demanding bytes - and for a jar:/jrt: source the bytes come from that
    // delegate - waits on a queue that is waiting on the monitor this thread holds. That is a hard
    // deadlock with no timeout, and it takes MediaPlayer.dispose() down with it, since that blocks
    // on the monitor too. AVAssetTrack property reads are exactly such a call: they have a
    // documented synchronous fallback when the value is not already loaded. Hence the AVFoundation
    // work below - the change dictionary, _player.error, the duration, the whole of
    // -extractTrackInfo - stays outside, and only the sends go inside.
    //
    // The isDisposed test here is advisory: it saves doing the work at all for a player that is
    // already gone. isDisposed is _Atomic, so reading it unlocked is well defined rather than a data
    // race, but it is not the fence. The binding test is the one inside the monitor at each send -
    // in -setPlayerState:, in the duration branch below, and in -extractTrackInfo.
    //
    // Deadlock, for the narrow windows that remain: -dispose holds the monitor from its first line
    // to its last, so a send here can wait for it. Nothing -dispose waits for is this thread.
    // -removeObserver: does not join an in-flight delivery (if it did, there would be no race to
    // fence). SetBands(0, NULL) does wait on the real-time audio thread, because
    // AVFAudioSpectrumUnit::UpdateBands holds the band lock across its callback - which is why
    // -sendSpectrumEventDuration:timestamp: must never take this monitor and has been left alone.
    // A send under the monitor is bounded: the Java target enqueues and returns. And a notification
    // delivered synchronously on the disposing thread from inside -dispose re-enters the monitor
    // instead of blocking - @synchronized is recursive per thread - and finds isDisposed still NO,
    // because -dispose sets it last, so that path behaves exactly as it always did.
    if (isDisposed) {
        return;
    }

    if (context == AVFMediaPlayerItemStatusContext) {
        // According to docs change[NSKeyValueChangeNewKey] can be NSNull when player.currentItem is nil
        if (![change[NSKeyValueChangeNewKey] isKindOfClass:[NSNull class]]) {
            AVPlayerStatus status = (AVPlayerStatus)[[change objectForKey:NSKeyValueChangeNewKey] longValue];
            if (status == AVPlayerStatusReadyToPlay) {
                if (!_movieReady) {
                    LOGGER_DEBUGMSG(([[NSString stringWithFormat:@"Setting player to READY state"] UTF8String]));
                    // Only send this once, though we'll receive notification a few times
                    [self setPlayerState:kPlayerState_READY];
                    _movieReady = true;
                }
            } else if (status == AVPlayerStatusFailed) {
                LOGGER_DEBUGMSG(([[NSString stringWithFormat:@"Setting player to HALTED state"] UTF8String]));
                if (_player != nil) {
                    [self logNSError:@"AVPlayer" error:_player.error];
                    if (_player.currentItem != nil) {
                         [self logNSError:@"AVPlayerItem" error:_player.currentItem.error];
                    }
                }
                [self setPlayerState:kPlayerState_HALTED];
            }
        }
    } else if (context == AVFMediaPlayerItemDurationContext) {
        // send update duration event
        double duration = CMTimeGetSeconds(_player.currentItem.duration);
        @synchronized(self) {
            if (!isDisposed) {
                eventHandler->SendDurationUpdateEvent(duration);
            }
        }
    } else if (context == AVFMediaPlayerItemTracksContext) {
        [self extractTrackInfo];
    } else {
        [super observeValueForKeyPath:keyPath ofObject:object change:change context:context];
    }
}

- (double) currentTime
{
    return CMTimeGetSeconds([self.player currentTime]);
}

- (void) setCurrentTime:(double)time
{
    [self.player seekToTime:CMTimeMakeWithSeconds(time, 1)];
    if (previousPlayerState == kPlayerState_FINISHED) {
        [self play];
    }
}

- (BOOL) mute {
    return self.player.muted;
}

- (void) setMute:(BOOL)state {
    self.player.muted = state;
}

- (int64_t) audioSyncDelay {
    return _audioProcessor.audioDelay;
}

- (void) setAudioSyncDelay:(int64_t)audioSyncDelay {
    _audioProcessor.audioDelay = audioSyncDelay;
}

- (float) balance {
    return _audioProcessor.balance;
}

- (void) setBalance:(float)balance {
    _audioProcessor.balance = balance;
}

- (float) volume {
    return _audioProcessor.volume;
}

- (void) setVolume:(float)volume {
    _audioProcessor.volume = volume;
}

- (float) rate {
    return self.player.rate;
}

- (void) setRate:(float)rate {
    self.player.rate = rate;
}

- (double) duration {
    if (self.player.currentItem.status == AVPlayerItemStatusReadyToPlay) {
        CMTime dur = self.player.currentItem.duration;
        if (!CMTIME_IS_INDEFINITE(dur)) {
            return CMTimeGetSeconds(self.player.currentItem.duration);
        }
    }
    return -1.0;
}

- (void) play {
    [self.player play];
    [self setPlayerState:kPlayerState_PLAYING];
}

- (void) pause {
    [self.player pause];
    [self setPlayerState:kPlayerState_PAUSED];
}

- (void) stop {
    [self.player pause];
    [self.player seekToTime:kCMTimeZero];
    [self setPlayerState:kPlayerState_STOPPED];
}

- (void) finish {
    [self.player pause];
    [self setPlayerState:kPlayerState_FINISHED];
}

- (void) dispose {
    // The display-link join, and it has to be here: before the monitor, before the player is
    // stopped, before anything is released. The full argument is above the registry at the top of
    // this file; the two halves that decide this placement are that a callback in flight can be
    // waiting on @synchronized(self) by way of the resource loader, so joining while holding that
    // monitor would deadlock, and that once this returns the callback thread is gone for good, so
    // everything below - CVDisplayLinkStop, CVDisplayLinkRelease, the eventHandler that
    // -[OSXMediaPlayer dispose] deletes next, and the upcall arena Java closes after that - has
    // nothing left to race with.
    //
    // Taking the token out of the ivar makes this idempotent and single-shot: the -dispose that
    // -dealloc calls, and any second -dispose, read zero and retire nothing. An exchange rather
    // than a load followed by a store, so that "exactly one caller ever sees a non-zero token" is a
    // property of this line alone. -[OSXMediaPlayer dispose] does hold its own monitor across
    // [player dispose] today, which would serialize two disposers anyway - but two retirers of the
    // same token would both wait and then both unlink and free it, so the invariant that
    // AVFRetireDisplayLinkTarget relies on is worth owning here instead of borrowing from another
    // file that could change. The exchange is also the concurrent partner of the plain atomic load
    // in -createVideoOutput, which is why the ivar is _Atomic.
    uintptr_t retiringToken = __c11_atomic_exchange(&_displayLinkToken, (uintptr_t)0, __ATOMIC_SEQ_CST);
    AVFRetireDisplayLinkTarget(retiringToken);

    @synchronized(self) {
        if (!isDisposed) {
            if (_player != nil) {
                // stop the player
                _player.rate = 0.0;
                [_player cancelPendingPrerolls];
            }

            AVFAudioSpectrumUnitPtr asPtr = _audioProcessor.audioSpectrum;
            if (asPtr != nullptr) {
                // Prevent future spectrum callbacks
                asPtr->SetEnabled(FALSE);
                asPtr->SetSpectrumCallbackProc(NULL, NULL);
                asPtr->SetBands(0, NULL);
            }

            if (_playerOutput != nil) {
                [_player.currentItem removeOutput:_playerOutput];
                [_playerOutput setDelegate:nil queue:nil];
            }

            [self setPlayerState:kPlayerState_HALTED];

            NSNotificationCenter *center = [NSNotificationCenter defaultCenter];
            for (id<NSObject> observer in playerObservers) {
                [center removeObserver:observer];
            }

            for (NSString *keyPath in keyPathsObserved) {
                [self removeObserver:self forKeyPath:keyPath];
            }

            if (_displayLink) {
                // Safe to stop and release outright: the join at the top of this method already
                // guarantees no callback is executing and none can start, so it does not matter
                // whether CVDisplayLinkStop waits for one.
                CVDisplayLinkStop(_displayLink);
                CVDisplayLinkRelease(_displayLink);
                _displayLink = NULL;
            }

            if (locatorStream != NULL) {
                // The locator and its CStreamCallbacks adapter are this player's to free (see
                // -initWithURL:), and the locator does not own the adapter, so both go here.
                //
                // Why no resource-loading request can be dereferencing them: every read of
                // locatorStream lives in
                // -resourceLoader:shouldWaitForLoadingOfRequestedResource:, whose entire body is
                // held inside @synchronized(self) - the same monitor -dispose holds from its first
                // line to its last. A delegate call already inside that body therefore ran to
                // completion before -dispose could enter, and one that arrives afterwards blocks on
                // the monitor, then reads the NULL written below and returns NO before
                // dereferencing anything. The monitor covers every read and this teardown, not the
                // initial store in -initWithURL: - see the note there - but that store races only
                // against reading NULL, never against a pointer being freed.
                //
                // @synchronized is recursive per thread, so none of that would hold for a -dispose
                // nested inside a delegate call on the loader queue; no such path exists, as the
                // delegate methods only touch the loading request and the stream adapter, whose
                // upcalls read the Java connection holder and never dispose the player.
                //
                // Draining playerLoaderQueue with a synchronous dispatch instead would not just be
                // redundant, it can deadlock: whenever a delegate call is already in flight,
                // -dispose would block on that serial queue while holding the monitor that same
                // call is blocked acquiring. An idle queue returns, so the hang would be
                // intermittent rather than absent. -[AVAssetResourceLoader setDelegate:nil
                // queue:nil] has the same shape of hazard from inside the monitor and buys nothing
                // the monitor does not give.
                CLocatorStream *ls = locatorStream;
                locatorStream = NULL;
                AVFDestroyLocatorStream(ls, YES);
            }

            isDisposed = YES;
        }
    }
}

- (void) extractTrackInfo {
    // Reached only from -observeValueForKeyPath: above, on the KVO thread, and fenced the same way:
    // this advisory test to avoid building tracks for a player that is already gone, then the
    // monitor taken around each Send*TrackEvent and nowhere else. Everything between them is
    // AVFoundation - track.formatDescriptions, track.languageCode, track.naturalSize,
    // -hasMediaCharacteristic:, the audio mixer wiring - and every one of those is a property read
    // that AVFoundation may satisfy by loading, which for a jar:/jrt: source means a request to
    // -resourceLoader:shouldWaitForLoadingOfRequestedResource:, which takes this monitor on the
    // loader queue. Holding it across them would deadlock; see the rule in the caller.
    if (isDisposed) {
        return;
    }

#if DUMP_TRACK_INFO
    NSMutableString *trackLog = [[NSMutableString alloc] initWithFormat:
                                 @"Parsing tracks for player item %@:\n",
                                 _player.currentItem];
#endif
    NSArray *tracks = self.player.currentItem.tracks;
    int videoIndex = 1;
    int audioIndex = 1;
    int textIndex = 1;
    BOOL createVideo = NO;

    for (AVPlayerItemTrack *trackObj in tracks) {
        AVAssetTrack *track = trackObj.assetTrack;
        NSString *type = track.mediaType;
        NSString *name = nil;
        NSString *lang = @"und";
        CTrack::Encoding encoding = CTrack::CUSTOM;
        FourCharCode fcc = 0;

        CMFormatDescriptionRef desc = NULL;
        NSArray *formatDescList = track.formatDescriptions;
        if (formatDescList && formatDescList.count > 0) {
            desc = (__bridge CMFormatDescriptionRef)[formatDescList objectAtIndex:0];
            if (!desc) {
                TRACK_LOG(@"Can't get format description, skipping track");
                continue;
            }
            fcc = CMFormatDescriptionGetMediaSubType(desc);
            switch (fcc) {
                case 'hvc1':
                    encoding = CTrack::H265;
                    break;
                case 'avc1':
                    encoding = CTrack::H264;
                    break;
                case kAudioFormatLinearPCM:
                    encoding = CTrack::PCM;
                    break;
                case kAudioFormatMPEG4AAC:
                    encoding = CTrack::AAC;
                    break;
                case kAudioFormatMPEGLayer1:
                case kAudioFormatMPEGLayer2:
                    encoding = CTrack::MPEG1AUDIO;
                    break;
                case kAudioFormatMPEGLayer3:
                    encoding = CTrack::MPEG1LAYER3;
                    break;
                default:
                    // Everything else will show up as custom
                    break;
            }
        }

        if (track.languageCode) {
            lang = track.languageCode;
        }

        TRACK_LOG(@"Track %d (%@)", index, track.mediaType);
        TRACK_LOG(@"  enabled: %s", track.enabled ? "YES" : "NO");
        TRACK_LOG(@"  track ID: %d", track.trackID);
        TRACK_LOG(@"  language code: %@ (%sprovided)", lang, track.languageCode ? "" : "NOT ");
        TRACK_LOG(@"  encoding (FourCC): '%@' (JFX encoding %d)",
                  FourCCToNSString(fcc),
                  (int)encoding);

        // Tracks in AVFoundation don't have names, so we'll need to give them
        // sequential names based on their type, e.g., "Video Track 1"
        if ([type isEqualTo:AVMediaTypeVideo]) {
            int width = -1;
            int height = -1;
            float frameRate = -1.0;
            if ([track hasMediaCharacteristic:AVMediaCharacteristicVisual]) {
                width = (int)track.naturalSize.width;
                height = (int)track.naturalSize.height;
                frameRate = track.nominalFrameRate;
            }
            name = [NSString stringWithFormat:@"Video Track %d", videoIndex++];
            CVideoTrack *outTrack = new CVideoTrack((int64_t)track.trackID,
                                                   [name UTF8String],
                                                   encoding,
                                                   (bool)track.enabled,
                                                   width,
                                                   height,
                                                   frameRate,
                                                   false);

            TRACK_LOG(@"  track name: %@", name);
            TRACK_LOG(@"  video attributes:");
            TRACK_LOG(@"    width: %d", width);
            TRACK_LOG(@"    height: %d", height);
            TRACK_LOG(@"    frame rate: %2.2f", frameRate);

            @synchronized(self) {
                if (!isDisposed) {
                    eventHandler->SendVideoTrackEvent(outTrack);
                }
            }
            delete outTrack;

            // signal to create the video output when we're done
            createVideo = YES;
        } else if ([type isEqualTo:AVMediaTypeAudio]) {
            name = [NSString stringWithFormat:@"Audio Track %d", audioIndex++];
            TRACK_LOG(@"  track name: %@", name);

            // Set up audio processing
            if (_audioProcessor) {
                // Make sure the players volume is set to 1.0
                self.player.volume = 1.0;

                // set up the mixer
                _audioProcessor.audioTrack = track;
                self.player.currentItem.audioMix = _audioProcessor.mixer;
            }

            // We have to get the audio information from the format description
            const AudioStreamBasicDescription *asbd = CMAudioFormatDescriptionGetStreamBasicDescription(desc);
            size_t layoutSize;
            const AudioChannelLayout *layout = CMAudioFormatDescriptionGetChannelLayout(desc, &layoutSize);
            int channels = 2;
            int channelMask = CAudioTrack::FRONT_LEFT | CAudioTrack::FRONT_RIGHT;
            float sampleRate = 44100.0;

            TRACK_LOG(@"  audio attributes:");
            if (asbd) {
                sampleRate = (float)asbd->mSampleRate;
                TRACK_LOG(@"    sample rate: %2.2f", sampleRate);
            }
            if (layout) {
                channels = (int)AudioChannelLayoutTag_GetNumberOfChannels(layout->mChannelLayoutTag);

                TRACK_LOG(@"    channel count: %d", channels);
                TRACK_LOG(@"    channel mask: %02x", channelMask);
            }

            CAudioTrack *audioTrack = new CAudioTrack((int64_t)track.trackID,
                                   [name UTF8String],
                                   encoding,
                                   (bool)track.enabled,
                                   [lang UTF8String],
                                   channels, channelMask, sampleRate);
            @synchronized(self) {
                if (!isDisposed) {
                    eventHandler->SendAudioTrackEvent(audioTrack);
                }
            }
            delete audioTrack;
        } else if ([type isEqualTo:AVMediaTypeClosedCaption]) {
            name = [NSString stringWithFormat:@"Subtitle Track %d", textIndex++];
            TRACK_LOG(@"  track name: %@", name);
            CSubtitleTrack *subTrack = new CSubtitleTrack((int64_t)track.trackID,
                                                         [name UTF8String],
                                                         encoding,
                                                         (bool)track.enabled,
                                                         [lang UTF8String]);
            @synchronized(self) {
                if (!isDisposed) {
                    eventHandler->SendSubtitleTrackEvent(subTrack);
                }
            }
            delete subTrack;
        }
    }

#if DUMP_TRACK_INFO
    LOGGER_INFOMSG([trackLog UTF8String]);
#endif

    if (createVideo) {
        [self createVideoOutput];
    }
}

- (void) outputMediaDataWillChange:(AVPlayerItemOutput *)sender {
    _lastHostTime = CVGetCurrentHostTime();
    CVDisplayLinkStart(_displayLink);
    _hlsBugResetCount = 0;
}

- (void) outputSequenceWasFlushed:(AVPlayerItemOutput *)output {
    _hlsBugResetCount = 0;
    _lastHostTime = CVGetCurrentHostTime();
}

- (void) sendPixelBuffer:(CVPixelBufferRef)buf frameTime:(double)frameTime hostTime:(int64_t)hostTime {
    // An unlocked read, and by now a redundant one: this path is fenced by the display-link join in
    // -dispose, not by this test. displayLinkCallback below is the only caller, it reaches this
    // method only while holding a claim on the player's registry entry, and -dispose retires that
    // entry and waits for every outstanding claim before it goes any further.
    //
    // Note what that does and does not say about where -dispose is while this runs. The ordinary
    // interleaving is not that -dispose has not started: it has very likely zeroed the token,
    // entered AVFRetireDisplayLinkTarget, set retiring, and be sitting in pthread_cond_wait for
    // this very claim. What holds is that it is blocked there, which is *before* it takes
    // @synchronized(self) - and three things follow from that alone. The only write that ever turns
    // isDisposed YES is inside that monitor, so it is still NO (the initializer's isDisposed = NO is
    // the other write, and it runs before this player has a registry entry to claim). self cannot be
    // deallocated, because the
    // registry entry holds a reference until the join completes and the callback holds another.
    // eventHandler is still the live dispatcher, because -[OSXMediaPlayer dispose] deletes it only
    // after [player dispose] has returned, which cannot be until this claim is given back. The
    // registry block at the top of this file has the whole argument; the point here is that the
    // sends at the bottom of this method need no monitor and no isDisposed test to be safe, which
    // is why they have neither.
    //
    // What this test still buys is what it always bought - it costs one atomic load and it saves
    // building a frame for a player that is already gone - so it stays. What it no longer has to be,
    // and what the note that used to stand here correctly said it could never be, is the fence: the
    // caller touches the player outside this method, both before it is entered (self.playerOutput)
    // and after it returns (self.hlsBugResetCount, self.lastHostTime, self.player.rate,
    // -hlsBugReset), so no test placed inside it could ever have protected those. Taking
    // @synchronized(self) here is still the wrong answer for the reason it always was - -dispose
    // holds that monitor across the whole teardown - and is now also pointless, because the join
    // has already ruled out the window it would have covered.
    if (isDisposed) {
        return;
    }

    _lastHostTime = hostTime;
    CVVideoFrame *frame = NULL;
    try {
        // nothrow, because this runs on a thread CoreVideo owns and returns into a C frame: a
        // std::bad_alloc raised here would unwind out of displayLinkCallback with no handler in
        // sight and reach std::terminate, killing the process to report a dropped frame. With
        // nothrow the failure arrives as a NULL, which the existing test below already handles -
        // that test was dead code until now. The catch is unaffected: nothrow only changes what a
        // failing allocation does, and every throw in CVVideoFrame's constructor is a
        // const char * that still lands here.
        frame = new (std::nothrow) CVVideoFrame(buf, frameTime, _lastHostTime);
    } catch (const char *message) {
        // Check if the video format is supported, if not try our fallback format
        OSType format = CVPixelBufferGetPixelFormatType(buf);
        if (format == 0) {
            // Bad pixel format, possibly a bad frame or ???
            // This seems to happen when the stream is corrupt, so let's ignore
            // it and hope things recover
            return;
        }
        if (!CVVideoFrame::IsFormatSupported(format)) {
            LOGGER_DEBUGMSG(([[NSString stringWithFormat:@"Bad pixel format: '%@'",
                               FourCCToNSString(format)] UTF8String]));
            [self setFallbackVideoFormat];
            return;
        }
        // Can't use this frame, report an error and ignore it
        LOGGER_DEBUGMSG(message);
        return;
    }

    if (frame == NULL) {
        return;
    }

    if (previousWidth < 0 || previousHeight < 0
        || previousWidth != frame->GetWidth() || previousHeight != frame->GetHeight())
    {
        // Send/Queue frame size changed event
        previousWidth = frame->GetWidth();
        previousHeight = frame->GetHeight();
        eventHandler->SendFrameSizeChangedEvent(previousWidth, previousHeight);
    }
    eventHandler->SendNewFrameEvent(frame);
}

- (void) sendSpectrumEventDuration:(double)duration timestamp:(double)timestamp {
    if (eventHandler) {
        // Always true for queryTimestamp to avoid hang. See JDK-8240694.
        eventHandler->SendAudioSpectrumEvent(timestamp, duration, true);
    }
}

- (NSString*) getContentTypeFromURL:(NSString*) URL {
    if (URL == nil) {
        return nil;
    }

    NSString *lowercaseURL = [URL lowercaseString];
    if ([lowercaseURL hasSuffix:@"mp4"]) {
        return AVFileTypeMPEG4;
    } else if ([lowercaseURL hasSuffix:@"m4a"]) {
        return AVFileTypeMPEG4;
    } else if ([lowercaseURL hasSuffix:@"m4v"]) {
        return AVFileTypeMPEG4;
    } else if ([lowercaseURL hasSuffix:@"mp3"]) {
        return AVFileTypeMPEGLayer3;
    }

    return nil;
}

// AVAssetResourceLoaderDelegate
- (BOOL)resourceLoader:(AVAssetResourceLoader *)resourceLoader
        shouldWaitForLoadingOfRequestedResource:
        (AVAssetResourceLoadingRequest *)loadingRequest {
    @synchronized(self) {
        AVAssetResourceLoadingContentInformationRequest* contentRequest = loadingRequest.contentInformationRequest;
        AVAssetResourceLoadingDataRequest* dataRequest = loadingRequest.dataRequest;

        if (locatorStream == NULL) {
            return NO;
        }

        if (contentRequest != nil) {
            contentRequest.contentType = [self getContentTypeFromURL:loadingRequest.request.URL.absoluteString];
            contentRequest.contentLength = locatorStream->GetSizeHint();
            contentRequest.byteRangeAccessSupported = YES;
        }

        if (dataRequest != nil) {
            // If requestsAllDataToEndOfResource is YES, than requestedLength is
            // invalid and we need to provide all data to the end of file.
            long requestedLength = 0;
            if (dataRequest.requestsAllDataToEndOfResource) {
               int64_t sizeHint = locatorStream->GetSizeHint();
               requestedLength = sizeHint - dataRequest.requestedOffset;
            } else {
                requestedLength = dataRequest.requestedLength;
            }

            // Do not provide more then MAX_READ_SIZE at one call, otherwise
            // AVFoundation might fail if we provide too much data.
            // We will be requested again if not all data provided.
            if (requestedLength > MAX_READ_SIZE) {
                requestedLength = MAX_READ_SIZE;
            }

            NSMutableData* readData = nil;
            bool isRandomAccess = locatorStream->GetCallbacks()->IsRandomAccess();
            int64_t position = -1;
            if (isRandomAccess) {
                position = dataRequest.requestedOffset;
            } else {
                position = locatorStream->GetCallbacks()->Seek(dataRequest.requestedOffset);
                if (position != dataRequest.requestedOffset) {
                    return NO;
                }
            }

            // Why the fill loop stopped. The two ways out are reported to AVFoundation
            // differently: the end of the resource means everything it can ever have for this
            // range has been handed over, while a failed read or a short copy means the range was
            // never answered and must not be presented as if it had been.
            BOOL readFailed = NO;

            while (requestedLength > 0) {
                // Keep the count signed. It used to be read into an unsigned int, where both
                // negative codes became values close to UINT_MAX, so neither the end of stream nor
                // a read error was ever caught by the test below and both fell through into a copy
                // of data the stream had never staged.
                int blockSize = READ_EOS_CODE;
                if (isRandomAccess) {
                    blockSize = locatorStream->GetCallbacks()->ReadBlock(position, requestedLength);
                } else {
                    blockSize = locatorStream->GetCallbacks()->ReadNextBlock();
                }
                if (blockSize < 0) {
                    // -1 is a clean end of stream; -2, and by exclusion anything else negative, is
                    // a read that failed and staged nothing.
                    readFailed = (blockSize != READ_EOS_CODE);
                    break;
                }
                if (blockSize == 0) {
                    // Zero bytes ends the loop, and the request is then finished as complete
                    // below. That is right for the ReadBlock branch, where javasource's pull path
                    // makes the same call because a read at the very end of a file legitimately
                    // returns zero. It is not what the ReadNextBlock contract says:
                    // ConnectionHolder.readNextBlock returns "possibly zero" bytes without having
                    // reached the end of the stream, which is why javasource's push path refuses
                    // to equate the two, and equating them here would present a truncated prefix
                    // as the whole range. No holder in this tree can produce it on that branch:
                    // each of them fills a non-empty buffer - 4096 bytes by default - from a
                    // blocking channel, a FileChannel or a Channels.newChannel over an
                    // InputStream or the memory holder's own channel, and those return a positive
                    // count or -1, never 0. Looping instead of breaking is not the safer choice,
                    // because this loop holds @synchronized(self) on the resource loader queue, so
                    // a source that kept returning zero would spin there and block -dispose.
                    break;
                }

                // Clamp against what is left of this range, not against dataRequest.requestedLength:
                // that field is the raw 64-bit request length the code above already declares
                // invalid when requestsAllDataToEndOfResource is YES, and truncating it to
                // unsigned int both over-delivers and can come out zero. requestedLength is the
                // remaining count this loop decrements; it is > 0 here and MAX_READ_SIZE at most,
                // so readSize lands in 1..MAX_READ_SIZE and every iteration makes progress.
                // ReadNextBlock stages a whole buffer rather than just the remainder, so on that
                // branch the clamp can be shorter than blockSize; the staged tail is dropped and
                // read again, because every data request positions the stream at its own
                // requestedOffset first - ReadBlock takes the position, the other branch seeks.
                unsigned int readSize = ((long)blockSize > requestedLength) ?
                        (unsigned int)requestedLength : (unsigned int)blockSize;
                readData = [NSMutableData dataWithLength:readSize];

                int copied = locatorStream->GetCallbacks()->CopyBlock((void*)[readData bytes],
                                                                     readSize);
                if (copied != (int)readSize) {
                    // A short copy means the stream could not stage the bytes ReadBlock /
                    // ReadNextBlock promised, so readData holds no usable media data. That is a
                    // failed read rather than the end of the resource, so the request is failed
                    // below instead of being answered with the bytes delivered so far.
                    readFailed = YES;
                    break;
                }
                [loadingRequest.dataRequest respondWithData:readData];

                requestedLength -= readSize;
                position += readSize;
            }

            if (readFailed) {
                // The request has not been satisfied: whatever respondWithData: already took is a
                // prefix of the range asked for, and when the very first read failed it is nothing
                // at all. Finishing without an error would present that prefix as the whole
                // answer and leave AVFoundation to demux truncated media as if it were complete,
                // so fail the request instead.
                //
                // What failing it does to the player depends on when it happens, and only the
                // initial-load case is expected to reach HALTED. While the asset is still being
                // loaded, AVFoundation is expected to put the error on the AVPlayerItem and fail
                // its status, and -observeValueForKeyPath: above does observe
                // self.player.currentItem.status and map the failed status to kPlayerState_HALTED,
                // which is how every other unrecoverable failure of this player already surfaces.
                // Only that second half is this file's own code; the first is AVFoundation
                // behaviour that this call assumes rather than enforces. Once the item has reached
                // AVPlayerItemStatusReadyToPlay even the expectation goes: AVFoundation commonly
                // reports a mid-playback resource failure through
                // AVPlayerItemFailedToPlayToEndTimeNotification, which nothing here observes, or
                // simply stalls at the current position without moving status back to failed.
                // Failing the request is still the right answer in that case, because it keeps a
                // truncated range out of the demuxer, but the player may then neither halt nor
                // recover. What that leaves missing is a player-visible event, not a trace:
                // logNSError below writes at DEBUG, which Logger drops unless
                // -Djfxmedia.loglevel=debug is set, while the read or copy behind the failure has
                // usually already been reported at Logger.ERROR by JfxMediaNative's
                // read_next_block, read_block and copy_block targets - the exception being a -2
                // returned for a user id the registry no longer holds, which is silent. Reporting
                // the failure through eventHandler would give the Java layer the event it is
                // missing, but no failure path in this file uses that route today.
                NSDictionary *errorInfo =
                        @{NSLocalizedDescriptionKey: @"The media stream did not deliver the requested bytes"};
                NSError *readError = [NSError errorWithDomain:AVFMediaErrorDomain
                                                         code:ERROR_LOCATOR_CONNECTION_LOST
                                                     userInfo:errorInfo];
                [self logNSError:@"AVAssetResourceLoader" error:readError];
                [loadingRequest finishLoadingWithError:readError];
            } else {
                // Either the whole range was delivered or the resource ended, and in both cases
                // the stream has given up everything it has for this range, so the request is
                // complete. A resource that ends early is a shorter resource, not an error, and
                // AVFoundation can see how much of the range arrived.
                [loadingRequest finishLoading];
            }

            return YES;
        }

        return NO;
    }
}

- (BOOL)resourceLoader:(AVAssetResourceLoader *)resourceLoader
        shouldWaitForRenewalOfRequestedResource:
        (AVAssetResourceRenewalRequest *)renewalRequest {
     return NO;
}

- (void)resourceLoader:(AVAssetResourceLoader *)resourceLoader
        didCancelLoadingRequest:
        (AVAssetResourceLoadingRequest *)loadingRequest {
}

@end

static void SpectrumCallbackProc(void *context, double duration, double timestamp) {
    if (context) {
        AVFMediaPlayer *player = (__bridge AVFMediaPlayer*)context;
        [player sendSpectrumEventDuration:duration timestamp:timestamp];
    }
}

static CVReturn displayLinkCallback(CVDisplayLinkRef displayLink, const CVTimeStamp *inNow, const CVTimeStamp *inOutputTime, CVOptionFlags flagsIn, CVOptionFlags *flagsOut, void *displayLinkContext)
{
    // displayLinkContext is a registry token, not a pointer to the player: see the registry block at
    // the top of this file. Claiming it is what keeps self alive, and eventHandler valid, for the
    // whole of this function - including the two dereferences that sit outside -sendPixelBuffer:.
    // A token whose player has been disposed claims nothing and this returns at once, which is also
    // what a display link that -outputMediaDataWillChange: started after retirement gets: its
    // callbacks fire and do nothing until -dispose stops the link.
    //
    // The claim is released by the destructor, on every path out of this function including the two
    // early returns below and an exception unwinding out of -sendPixelBuffer:.
    AVFDisplayLinkCall displayLinkCall((uintptr_t)displayLinkContext);
    AVFMediaPlayer *self = (__bridge AVFMediaPlayer *)displayLinkCall.player();
    if (self == nil) {
        return kCVReturnSuccess;
    }

    AVPlayerItemVideoOutput *playerItemVideoOutput = self.playerOutput;

    // The displayLink calls back at every vsync (screen refresh)
    // Compute itemTime for the next vsync
    CMTime outputItemTime = [playerItemVideoOutput itemTimeForCVTimeStamp:*inOutputTime];
    if ([playerItemVideoOutput hasNewPixelBufferForItemTime:outputItemTime]) {
        CVPixelBufferRef pixBuff = [playerItemVideoOutput copyPixelBufferForItemTime:outputItemTime itemTimeForDisplay:NULL];
        // Copy the pixel buffer to be displayed next and add it to AVSampleBufferDisplayLayer for display
        double frameTime = CMTimeGetSeconds(outputItemTime);
        [self sendPixelBuffer:pixBuff frameTime:frameTime hostTime:inOutputTime->hostTime];
        self.hlsBugResetCount = 0;

        CVBufferRelease(pixBuff);
    } else {
        CMTime delta = CMClockMakeHostTimeFromSystemUnits(inNow->hostTime - self.lastHostTime);
        NSTimeInterval elapsedTime = CMTimeGetSeconds(delta);

        if (elapsedTime > FREEWHEELING_PERIOD_IN_SECONDS) {
            if (self.player.rate != 0.0) {
                if (self.hlsBugResetCount > 9) {
                    /*
                     * There is a bug in AVFoundation where if we're playing a HLS
                     * stream and it switches to a different bitrate, the video
                     * output will stop receiving frames. So far, the only workaround
                     * for this has been to remove then re-add the video output
                     * This causes the video to pause for a bit, but it's better
                     * than not playing at all, and this should not happen once
                     * the bug is fixed in AVFoundation.
                     */
                    [self hlsBugReset];
                    self.lastHostTime = inNow->hostTime;
                    return kCVReturnSuccess; // hlsBugReset() will stop display link
                } else {
                    self.hlsBugResetCount++;
                    self.lastHostTime = inNow->hostTime;
                    return kCVReturnSuccess;
                }
            }
            // No new images for a while.  Shut down the display link to conserve
            // power, but request a wakeup call if new images are coming.
            CVDisplayLinkStop(displayLink);
            [playerItemVideoOutput requestNotificationOfMediaDataChangeWithAdvanceInterval:ADVANCE_INTERVAL_IN_SECONDS];
        }
    }

    return kCVReturnSuccess;
}
