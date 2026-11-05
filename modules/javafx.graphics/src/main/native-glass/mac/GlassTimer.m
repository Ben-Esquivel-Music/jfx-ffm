/*
 * Copyright (c) 2011, 2026, Oracle and/or its affiliates. All rights reserved.
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

#import "common.h"

#import "ProcessInfo.h"
#import "GlassMacros.h"
#import "GlassHelper.h"
#import "GlassTimer.h"
#import "glass_mac_api.h"
#import "glass_mac_internal.h"

#include <sys/time.h>

//#define VERBOSE
#ifndef VERBOSE
    #define LOG(MSG, ...)
#else
    #define LOG(MSG, ...) GLASS_LOG(MSG, ## __VA_ARGS__);
#endif

#define HIGH_PRIORITY_TIMER         1

static BOOL hasTimerId(GlassTimer *timer)
{
    return __atomic_load_n(&timer->_timerIdReady, __ATOMIC_ACQUIRE) != 0;
}

static void dialTick(GlassTimer *timer)
{
    const GmacTimerCallbacks *callbacks = GlassTimerCallbacks();
    if (callbacks != NULL && callbacks->tick != NULL)
    {
        callbacks->tick(timer->_timerId);
    }
}

static void *_GlassTimerTask(void *data)
{
    pthread_setname_np("Glass Timer Thread");

    GlassTimer *timer = (GlassTimer*)data;
    if (timer != NULL)
    {
        timer->_running = YES;
        while (timer->_running == YES)
        {
            int64_t now = getTimeMicroseconds();
            if (timer->_paused == NO)
            {
                if (hasTimerId(timer))
                {
                    NSAutoreleasePool *pool = [[NSAutoreleasePool alloc] init];
                    {
                        dialTick(timer);
                    }
                    [pool drain];
                }
            }
            int64_t duration = (getTimeMicroseconds() - now);
            int64_t sleep = (timer->_period - duration);
            if ((sleep < 0) || (sleep > timer->_period))
            {
#if 1
                sched_yield();
#else
                pthread_yield_np();
#endif
            }
            else
            {
                usleep((useconds_t)sleep);
            }
        }

        NSAutoreleasePool *pool = [[NSAutoreleasePool alloc] init];
        {
            [timer release];
        }
        [pool drain];
    }

    return NULL;
}

CVReturn CVOutputCallback(CVDisplayLinkRef displayLink,
                          const CVTimeStamp *inNow, const CVTimeStamp *inOutputTime,
                          CVOptionFlags flagsIn, CVOptionFlags *flagsOut,
                          void *displayLinkContext) {

    NSAutoreleasePool *pool = [[NSAutoreleasePool alloc] init];

    if (displayLinkContext != NULL)
    {
        GlassTimer *timer = (GlassTimer*)displayLinkContext;

        // The upcall attaches each CVDisplayLink thread once. The tick target names it as the JNI's attach did and,
        // after a tick that left the timer paused, hands a held exception to the thread's uncaught-exception
        // handler as the JNI's detach did (GmacTimerCallbacks).
        if (hasTimerId(timer))
        {
            dialTick(timer);
        }
    }

    [pool release];
    return kCVReturnSuccess;
}


@implementation GlassTimer

// Where the JNI took its global reference to the Runnable: a tick before this runs nothing (latent bug 6).
- (void)publishTimerId:(int64_t)timerId
{
    self->_timerId = timerId;
    __atomic_store_n(&self->_timerIdReady, 1, __ATOMIC_RELEASE);
}

- (id)initWithTimerId:(int64_t)timerId
{
    self = [super init];
    if (self != nil)
    {
        self->_paused = NO;
        CVReturn err = CVDisplayLinkSetOutputCallback(GlassDisplayLink, &CVOutputCallback, self);
        if (err != kCVReturnSuccess)
        {
            NSLog(@"CVDisplayLinkSetOutputCallback error: %d", err);
        }
        else
        {
            [self publishTimerId:timerId];
        }
    }
    return self;
}

- (id)initWithTimerId:(int64_t)timerId withPeriod:(int32_t)period
{
    self = [super init];
    if (self != nil)
    {
        self->_thread = NULL;
        self->_running = NO;
        self->_paused = NO;
        self->_period = (1000 * period); // ms --> us

        pthread_attr_t attr;
        pthread_attr_init(&attr);
        pthread_attr_setinheritsched(&attr, PTHREAD_EXPLICIT_SCHED);
        pthread_attr_setschedpolicy(&attr, SCHED_FIFO);

#if HIGH_PRIORITY_TIMER
        struct sched_param param;
        memset(&param, 0x00, sizeof(param));
        param.sched_priority = sched_get_priority_max(SCHED_FIFO)-1; // notice: 2nd highest priority (gznote: do we really want that?)
        pthread_attr_setschedparam(&attr, &param);
#endif

        int err = pthread_create(&self->_thread, &attr, _GlassTimerTask, self);
        if (err == 0)
        {
            // detach thread, so its resources can be reclaimed as soon as it's finished
            pthread_detach(self->_thread);

            [self publishTimerId:timerId];
        }
        else
        {
            NSLog(@"GlassTimer error: pthread_create returned %d", err);

            self = nil;
        }
    }
    return self;
}

@end

void *gmac_timer_start_period(int64_t timer_id, int32_t period)
{
    GlassTimer *timer = nil;
    GLASS_POOL_ENTER;
    {
        timer = [[GlassTimer alloc] initWithTimerId:timer_id withPeriod:period];
    }
    GLASS_POOL_EXIT;
    return timer;
}

void *gmac_timer_start_vsync(int64_t timer_id)
{
    GlassTimer *timer = nil;
    GLASS_POOL_ENTER;
    {
        timer = [[GlassTimer alloc] initWithTimerId:timer_id];
    }
    GLASS_POOL_EXIT;
    return timer;
}

void gmac_timer_stop(void *timer_ptr)
{
    GLASS_POOL_ENTER;
    {
        GlassTimer *timer = (GlassTimer*)timer_ptr;
        if (timer->_period == 0)
        {
            // stop the display link
            if (GlassDisplayLink != NULL && CVDisplayLinkIsRunning(GlassDisplayLink)) {
                CVDisplayLinkStop(GlassDisplayLink);
                CVDisplayLinkRelease(GlassDisplayLink);
            }
        }
        else
        {
            timer->_running = NO;
        }
    }
    GLASS_POOL_EXIT;
}

void gmac_timer_pause(void *timer_ptr)
{
    GLASS_POOL_ENTER;
    {
        GlassTimer *timer = (GlassTimer*)timer_ptr;
        if (timer->_period == 0)
        {
            // Stop the CVDisplayLink thread. There is no API to pause the CVDisplayLink
            // thread. CVDisplayLinkStop() stops the CVDisplayLink thread.
            if (GlassDisplayLink != NULL && CVDisplayLinkIsRunning(GlassDisplayLink))
            {
                CVReturn ret = CVDisplayLinkStop(GlassDisplayLink);
                if (ret == kCVReturnSuccess)
                {
                    timer->_paused = YES;
                }
                else
                {
                    NSLog(@"ERROR: MacTimer__1pause(): CVDisplayLinkStop() error: %d\n", (int)ret);
                }
            }
            else
            {
                NSLog(@"ERROR: MacTimer__1pause(): GlassDisplayLink is NULL or GlassDisplayLink is not running.");
            }
        }
        else
        {
            timer->_paused = YES;
        }
    }
    GLASS_POOL_EXIT;
}

void gmac_timer_resume(void *timer_ptr)
{
    GLASS_POOL_ENTER;
    {
        GlassTimer *timer = (GlassTimer*)timer_ptr;
        if (timer->_period == 0)
        {
            // Start the CVDisplayLink thread. There is no API to resume the CVDisplayLink
            // thread. CVDisplayLinkStart() starts a new CVDisplayLink thread.
            if (GlassDisplayLink != NULL && !CVDisplayLinkIsRunning(GlassDisplayLink))
            {
                CVReturn ret = CVDisplayLinkStart(GlassDisplayLink);
                if (ret == kCVReturnSuccess)
                {
                    timer->_paused = NO;
                }
                else
                {
                    NSLog(@"ERROR: MacTimer__1resume(): CVDisplayLinkStart error: %d\n", (int)ret);
                }
            }
        }
        else
        {
            timer->_paused = NO;
        }
    }
    GLASS_POOL_EXIT;
}

int32_t gmac_timer_paused(void *timer_ptr)
{
    return ((GlassTimer*)timer_ptr)->_paused == YES ? 1 : 0;
}

void gmac_timer_init(void)
{
    if (GlassDisplayLink == NULL)
    {
        CVReturn err = CVDisplayLinkCreateWithActiveCGDisplays(&GlassDisplayLink);
        if (err != kCVReturnSuccess)
        {
            NSLog(@"CVDisplayLinkCreateWithActiveCGDisplays error: %d", err);
        }
        err = CVDisplayLinkSetCurrentCGDisplay(GlassDisplayLink, kCGDirectMainDisplay);
        if (err != kCVReturnSuccess)
        {
            NSLog(@"CVDisplayLinkSetCurrentCGDisplay error: %d", err);
        }
        /*
         * set a null callback and start the link to prep for GlassTimer initialization
         */
        err = CVDisplayLinkSetOutputCallback(GlassDisplayLink, &CVOutputCallback, NULL);
        if (err != kCVReturnSuccess)
        {
            NSLog(@"CVDisplayLinkSetOutputCallback error: %d", err);
        }
        err = CVDisplayLinkStart(GlassDisplayLink);
        if (err != kCVReturnSuccess)
        {
            NSLog(@"CVDisplayLinkStart error: %d", err);
        }
    }
}
