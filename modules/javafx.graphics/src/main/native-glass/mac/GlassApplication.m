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
#import "com_sun_glass_events_KeyEvent.h"

#import "GlassMacros.h"
#import "GlassApplication.h"
#import "GlassAccessible.h"
#import "GlassHelper.h"
#import "GlassKey.h"
#import "GlassScreen.h"
#import "GlassWindow.h"
#import "PlatformSupport.h"
#import "glass_mac_api.h"
#import "glass_mac_internal.h"

#import "ProcessInfo.h"
#import <Security/SecRequirement.h>
#import <Carbon/Carbon.h>

#pragma clang diagnostic ignored "-Wdeprecated-declarations"

//#define VERBOSE
#ifndef VERBOSE
    #define LOG(MSG, ...)
#else
    #define LOG(MSG, ...) GLASS_LOG(MSG, ## __VA_ARGS__);
#endif

static BOOL shouldKeepRunningNestedLoop = YES;
static BOOL isFullScreenExitingLoop = NO;
static NSMutableDictionary * keyCodeForCharMap = nil;
static BOOL isEmbedded = NO;
static BOOL requiresActivation = NO;
static BOOL triggerReactivation = NO;
static BOOL disableSyncRendering = NO;
static BOOL firstActivation = YES;
static BOOL shouldReactivate = NO;

// Custom NSRunLoopMode constant that matches the one used by AWT in its
// doAWTRunLoopImpl method. This is not formally documented yet, but all
// versions of the JDK use it. We might consider a request to document it.
static NSString* JavaRunLoopMode = @"AWTRunLoopMode";

// List of allowable runLoop modes that Java runnables can run in.
// This is used when calling performSelectorOnMainThread from
// gmac_app_invoke_and_wait and gmac_app_invoke_later.
// We include JavaRunLoopMode in the list of allowable run modes in case
// we are running on the AWT event thread. This will allow JavaFX
// runnables to be scheduled when AWT is running doAWTRunLoopImpl
// on the AppKit thread (which it does for IME callbacks) so that we
// don't deadlock.
static NSArray<NSString*> *runLoopModes = nil;

// Custom event that is provided by AWT to allow libraries like
// JavaFX to forward native events to AWT even if AWT runs in
// embedded mode.
static NSString* awtEmbeddedEvent = @"AWTEmbeddedEvent";

#pragma mark --- GlassRunnable

@interface GlassRunnable : NSObject
{
    int64_t runnableId;
}

- (id)initWithRunnableId:(int64_t)identifier;
- (void)maybeRun;
- (void)run;

@end

static NSMutableArray<GlassRunnable*> *deferredRunnables = nil;

@implementation GlassRunnable

- (id)initWithRunnableId:(int64_t)identifier
{
    self->runnableId = identifier;
    return self;
}

- (void)run
{
    NSAutoreleasePool *pool = [[NSAutoreleasePool alloc] init];
    {
        assert(pthread_main_np() == 1);
        const GmacAppCallbacks *callbacks = GlassAppCallbacks();
        if (GlassIsMainJavaAttached() && callbacks != NULL && callbacks->run_runnable != NULL)
        {
            callbacks->run_runnable(self->runnableId);
        }

        [self release];
    }
    [pool drain];
}

- (void)maybeRun
{
    if (isFullScreenExitingLoop) {
        if (deferredRunnables == nil) {
            deferredRunnables = [[NSMutableArray alloc] initWithCapacity: 2];
        }
        [deferredRunnables addObject: self];
    } else {
        [self run];
    }
}

+ (void)rescheduleDeferredRunnables
{
    if (deferredRunnables != nil) {
        for (GlassRunnable *runnable in deferredRunnables) {
            [runnable performSelectorOnMainThread:@selector(maybeRun) withObject:nil waitUntilDone:NO modes:runLoopModes];
        }
        [deferredRunnables removeAllObjects];
    }
}
@end

@implementation NSApplicationFX
@end

static void dialAppSlot(int32_t (*slot)(void))
{
    if (slot != NULL)
    {
        slot();
    }
}

// NULL while Java is detached from the main thread, after [NSApp run] returned: then no slot runs.
static const GmacAppCallbacks *attachedAppCallbacks(void)
{
    return GlassIsMainJavaAttached() ? GlassAppCallbacks() : NULL;
}

// NO where the JNI's NewObjectArray of the String[] failed.
static BOOL dialOpenFiles(const GmacAppCallbacks *callbacks, NSArray *filenames, NSUInteger count)
{
    if (callbacks->notify_open_files == NULL)
    {
        return YES;
    }
    const char **names = calloc(count > 0 ? count : 1, sizeof(char *));
    int32_t *lengths = calloc(count > 0 ? count : 1, sizeof(int32_t));
    BOOL built = names != NULL && lengths != NULL;
    if (built)
    {
        for (NSUInteger i = 0; i < count; i++)
        {
            NSString *file = [filenames objectAtIndex:i];
            names[i] = file != nil ? [file UTF8String] : NULL;
            lengths[i] = names[i] != NULL ? (int32_t)strlen(names[i]) : 0;
        }
        built = callbacks->notify_open_files(names, lengths, (int32_t)count) != GMAC_UPCALL_THREW;
    }
    free(names);
    free(lengths);
    return built;
}

#pragma mark --- GlassApplication

@implementation GlassApplication {
    PlatformSupport* platformSupport;
}

- (id)initWithTaskbarApplication:(BOOL)isTaskbarApplication launchable:(BOOL)launchable
{
    self = [super init];
    if (self != nil)
    {
        self->started = NO;
        self->taskbarApplication = isTaskbarApplication;
        self->hasLaunchable = launchable;
    }
    return self;
}

- (void)dealloc {
    if (platformSupport) {
        [platformSupport stopEventProcessing];
        [platformSupport release];
    }

    [super dealloc];
}

#pragma mark --- delegate methods

- (void)GlassApplicationDidChangeScreenParameters
{
    LOG("GlassApplicationDidChangeScreenParameters");

    assert(pthread_main_np() == 1);
    if (GlassIsMainJavaAttached())
    {
        GlassScreenDidChangeScreenParameters();
    }
}

- (void)observeScreenParameterChanges
{
    [[NSNotificationCenter defaultCenter] addObserver:self
                                             selector:@selector(GlassApplicationDidChangeScreenParameters)
                                                 name:NSApplicationDidChangeScreenParametersNotification
                                               object:nil];
}

- (void)applicationWillFinishLaunching:(NSNotification *)aNotification
{
    LOG("GlassApplication:applicationWillFinishLaunching");

    GLASS_WARN_IF_MAIN_JAVA_DETACHED;
    NSAutoreleasePool *pool = [[NSAutoreleasePool alloc] init];
    {
        // unblock main thread. Glass is started at this point.
        self->platformSupport = [[PlatformSupport alloc] init];
        self->started = YES;

        const GmacAppCallbacks *callbacks = attachedAppCallbacks();
        if (callbacks != NULL)
        {
            if (self->hasLaunchable)
            {
                int32_t status = callbacks->run_launchable != NULL ? callbacks->run_launchable() : GMAC_UPCALL_OK;
                if (status == GMAC_UPCALL_OK)
                {
                    [self observeScreenParameterChanges];

                    // localMonitor = [NSEvent addLocalMonitorForEventsMatchingMask: NSRightMouseDownMask
                    //                                                      handler:^(NSEvent *incomingEvent) {
                    //                                                          NSEvent *result = incomingEvent;
                    //                                                          NSWindow *targetWindowForEvent = [incomingEvent window];
                    //                                                          LOG("NSRightMouseDownMask local");
                    //                                                          return result;
                    //                                                      }];
                    //
                    // globalMonitor = [NSEvent addGlobalMonitorForEventsMatchingMask: NSRightMouseDownMask
                    //                                                      handler:^(NSEvent *incomingEvent) {
                    //                                                          NSEvent *result = incomingEvent;
                    //                                                          NSWindow *targetWindowForEvent = [incomingEvent window];
                    //                                                          NSWindow *window = [[NSApplication sharedApplication]
                    //                                                                       windowWithWindowNumber:[incomingEvent windowNumber]];
                    //                                                          NSWindow *appWindow = [[NSApplication sharedApplication] mainWindow];
                    //                                                          LOG("NSRightMouseDownMask global: %p num %d win %p appwin %p",
                    //                                                              targetWindowForEvent, [incomingEvent windowNumber], window,
                    //                                                              [[NSApplication sharedApplication] mainWindow]);
                    //                                                     }];
                }
            }

            dialAppSlot(callbacks->notify_will_finish_launching);
        }
    }
    [pool drain];
}

- (BOOL)applicationSupportsSecureRestorableState:(NSApplication *)app {
    return YES;
}

- (void)applicationDidFinishLaunching:(NSNotification *)aNotification
{
    LOG("GlassApplication:applicationDidFinishLaunching");

    GLASS_WARN_IF_MAIN_JAVA_DETACHED;
    NSAutoreleasePool *pool = [[NSAutoreleasePool alloc] init];
    {
        const GmacAppCallbacks *callbacks = attachedAppCallbacks();
        if (callbacks != NULL)
        {
            dialAppSlot(callbacks->notify_did_finish_launching);
        }
    }
    [pool drain];

    if (!NSApp.isActive && requiresActivation) {
        // As of macOS 14, application gets to the foreground,
        // but it doesn't get activated, so this is needed:
        LOG("-> need to active application");
        dispatch_async(dispatch_get_main_queue(), ^{
            [NSApp activateIgnoringOtherApps:YES];
        });
    }
}

- (void)applicationWillBecomeActive:(NSNotification *)aNotification
{
    LOG("GlassApplication:applicationWillBecomeActive");

    GLASS_WARN_IF_MAIN_JAVA_DETACHED;
    NSAutoreleasePool *pool = [[NSAutoreleasePool alloc] init];
    {
        const GmacAppCallbacks *callbacks = attachedAppCallbacks();
        if (callbacks != NULL)
        {
            dialAppSlot(callbacks->notify_will_become_active);
        }
    }
    [pool drain];
}

- (void)applicationDidBecomeActive:(NSNotification *)aNotification
{
    LOG("GlassApplication:applicationDidBecomeActive");

    GLASS_WARN_IF_MAIN_JAVA_DETACHED;
    NSAutoreleasePool *pool = [[NSAutoreleasePool alloc] init];
    {
        const GmacAppCallbacks *callbacks = attachedAppCallbacks();
        if (callbacks != NULL)
        {
            dialAppSlot(callbacks->notify_did_become_active);
        }
    }
    [pool drain];

    if (triggerReactivation && firstActivation) {
        LOG("-> deactivate (hide)  app");
        firstActivation = NO;
        shouldReactivate = YES;
        [NSApp hide:NSApp];
    }
}

- (void)applicationWillResignActive:(NSNotification *)aNotification
{
    LOG("GlassApplication:applicationWillResignActive");

    GLASS_WARN_IF_MAIN_JAVA_DETACHED;
    NSAutoreleasePool *pool = [[NSAutoreleasePool alloc] init];
    {
        const GmacAppCallbacks *callbacks = attachedAppCallbacks();
        if (callbacks != NULL)
        {
            dialAppSlot(callbacks->notify_will_resign_active);
        }
    }
    [pool drain];
}

- (void)applicationDidResignActive:(NSNotification *)aNotification
{
    LOG("GlassApplication:applicationDidResignActive");

    GLASS_WARN_IF_MAIN_JAVA_DETACHED;
    NSAutoreleasePool *pool = [[NSAutoreleasePool alloc] init];
    {
        const GmacAppCallbacks *callbacks = attachedAppCallbacks();
        if (callbacks != NULL)
        {
            dialAppSlot(callbacks->notify_did_resign_active);
        }
    }
    [pool drain];

    if (triggerReactivation && shouldReactivate) {
        LOG("-> reactivate  app");
        shouldReactivate = NO;
        [NSApp activateIgnoringOtherApps:YES];
    }
}

- (void)applicationWillHide:(NSNotification *)aNotification
{
    LOG("GlassApplication:applicationWillHide");

    GLASS_WARN_IF_MAIN_JAVA_DETACHED;
    NSAutoreleasePool *pool = [[NSAutoreleasePool alloc] init];
    {
        const GmacAppCallbacks *callbacks = attachedAppCallbacks();
        if (callbacks != NULL)
        {
            dialAppSlot(callbacks->notify_will_hide);
        }
    }
    [pool drain];
}

- (void)applicationDidHide:(NSNotification *)aNotification
{
    LOG("GlassApplication:applicationDidHide");

    GLASS_WARN_IF_MAIN_JAVA_DETACHED;
    NSAutoreleasePool *pool = [[NSAutoreleasePool alloc] init];
    {
        const GmacAppCallbacks *callbacks = attachedAppCallbacks();
        if (callbacks != NULL)
        {
            dialAppSlot(callbacks->notify_did_hide);
        }
    }
    [pool drain];
}

- (void)applicationWillUnhide:(NSNotification *)aNotification
{
    LOG("GlassApplication:applicationWillUnhide");

    GLASS_WARN_IF_MAIN_JAVA_DETACHED;
    NSAutoreleasePool *pool = [[NSAutoreleasePool alloc] init];
    {
        const GmacAppCallbacks *callbacks = attachedAppCallbacks();
        if (callbacks != NULL)
        {
            dialAppSlot(callbacks->notify_will_unhide);
        }
    }
    [pool drain];
}

- (void)applicationDidUnhide:(NSNotification *)aNotification
{
    LOG("GlassApplication:applicationDidUnhide");

    GLASS_WARN_IF_MAIN_JAVA_DETACHED;
    NSAutoreleasePool *pool = [[NSAutoreleasePool alloc] init];
    {
        const GmacAppCallbacks *callbacks = attachedAppCallbacks();
        if (callbacks != NULL)
        {
            dialAppSlot(callbacks->notify_did_unhide);
        }
    }
    [pool drain];
}

- (void)application:(NSApplication *)theApplication openFiles:(NSArray *)filenames
{
    LOG("GlassApplication:application:openFiles");

    GLASS_WARN_IF_MAIN_JAVA_DETACHED;
    NSApplicationDelegateReply reply = NSApplicationDelegateReplySuccess;
    NSAutoreleasePool *pool = [[NSAutoreleasePool alloc] init];
    {
        NSUInteger count = [filenames count];
        const GmacAppCallbacks *callbacks = attachedAppCallbacks();
        if (callbacks != NULL)
        {
            if (!dialOpenFiles(callbacks, filenames, count))
            {
                fprintf(stderr, "NewObjectArray failed in GlassApplication_application\n");
                reply = NSApplicationDelegateReplyFailure;
            }
        }
    }
    [pool drain];

    [theApplication replyToOpenOrPrint:reply];
}

- (BOOL)application:(NSApplication *)theApplication openFile:(NSString *)filename
{
    LOG("GlassApplication:application:openFile");

    // controlled by Info.plist -NSOpenfileName
    // http://developer.apple.com/library/mac/#documentation/MacOSX/Conceptual/BPRuntimeConfig/Articles/ConfigApplications.html
    [self application:theApplication openFiles:[NSArray arrayWithObject:filename]];

    return YES;
}

- (BOOL)application:(id)theApplication openFileWithoutUI:(NSString *)filename
{
    LOG("GlassApplication:application:openFileWithoutUI");

    // programmaticaly called by the client (even though GlassApplication does not currently call it, let's wire it in just in case)
    [self application:theApplication openFiles:[NSArray arrayWithObject:filename]];

    return YES;
}

- (BOOL)application:(NSApplication *)theApplication openTempFile:(NSString *)filename
{
    LOG("GlassApplication:application:openTempFile");

    // controlled by Info.plist -NSOpenTempfileName
    // http://developer.apple.com/library/mac/#documentation/MacOSX/Conceptual/BPRuntimeConfig/Articles/ConfigApplications.html
    // NOP

    return YES;
}

- (void) application:(NSApplication *)theApplication openURLs:(NSArray<NSURL *> *)urls
{
    for (NSURL* url in urls) {
         NSDictionary *userInfo = @{
            @"name": @"openURL",
            @"url": url.absoluteString
        };

        [[NSNotificationCenter defaultCenter]
                postNotificationName:awtEmbeddedEvent
                object:nil
                userInfo:userInfo];
    }
}

- (BOOL)applicationShouldOpenUntitledFile:(NSApplication *)sender
{
    LOG("GlassApplication:applicationShouldOpenUntitledFile");

    // don't want

    return NO;
}

- (NSApplicationTerminateReply)applicationShouldTerminate:(NSApplication *)sender
{
    LOG("GlassApplication:applicationShouldTerminate");

    GLASS_WARN_IF_MAIN_JAVA_DETACHED;
    NSAutoreleasePool *pool = [[NSAutoreleasePool alloc] init];
    {
        const GmacAppCallbacks *callbacks = attachedAppCallbacks();
        if (callbacks != NULL)
        {
            dialAppSlot(callbacks->notify_will_quit);
        }
    }
    [pool drain];

    return NSTerminateCancel;
}


- (BOOL)applicationOpenUntitledFile:(NSApplication *)theApplication
{
    LOG("GlassApplication:applicationOpenUntitledFile");

    // NOP (should never be called because applicationShouldOpenUntitledFile returns NO)

    return YES;
}

#pragma mark --- Glass support

- (void)runLoop:(id)selector
{
    LOG("GlassApplication:runLoop ENTER");

    NSAutoreleasePool *pool1 = [[NSAutoreleasePool alloc] init];

    // The first upcall, set_event_thread below, attaches the main thread to Java: nothing before it runs Java.
    GlassSetMainJavaAttached(1);
    {
        NSAutoreleasePool *pool2 = [[NSAutoreleasePool alloc] init];

        if ([[NSThread currentThread] name] == nil)
        {
            [[NSThread currentThread] setName:@"Main Cocoa (UI) Thread"];
        }

        GlassApplication *glassApp = (GlassApplication *)selector;
        const GmacAppCallbacks *callbacks = GlassAppCallbacks();

        if (callbacks != NULL)
        {
            dialAppSlot(callbacks->set_event_thread);
        }

        NSBundle *mainBundle = [NSBundle mainBundle];
        {
            NSString *appName = [mainBundle objectForInfoDictionaryKey:@"CFBundleDisplayName"];

            if (appName == nil) {
                appName = [mainBundle objectForInfoDictionaryKey:@"CFBundleName"];
            }

            if (appName) {
                // make the name available to Java side, before Launchable.fnishLaunching callback
                if (callbacks != NULL && callbacks->set_name != NULL)
                {
                    const char *name = [appName UTF8String];
                    callbacks->set_name(name, name != NULL ? (int32_t)strlen(name) : 0);
                }
            }
        }

        // List of RunLoopModes in which we will run runnables passed
        // to _invokeAndWait and _submitForLaterInvocation. This includes
        // JavaRunLoopMode to avoid a possible deadlock with AWT.
        // There is no harm always adding it, since it will have no
        // effect if the run loop that receives this message does not
        // specify it.
        runLoopModes = [[NSArray alloc] initWithObjects:
            NSDefaultRunLoopMode,
            NSModalPanelRunLoopMode,
            NSEventTrackingRunLoopMode,
            JavaRunLoopMode,
            nil];

        // Determine if we're running embedded (in AWT, SWT, elsewhere)
        NSApplication *app = [NSApplicationFX sharedApplication];
        isEmbedded = ![app isKindOfClass:[NSApplicationFX class]];

        if (!isEmbedded)
        {
            // Not embedded in another toolkit, so disable automatic tabbing for all windows
            // We use a guarded call to preserve the ability to run on 10.10 or 10.11.
            // Using a guard, instead of reflection, assumes the Xcode used to
            // build includes MacOSX SDK 10.12 or later
            if (@available(macOS 10.12, *)) {
                [NSWindow setAllowsAutomaticWindowTabbing:NO];
            }
            if (self->taskbarApplication)
            {
                triggerReactivation = YES;

                // The workaround of deactivating and reactivating
                // the application so that the system menu bar works
                // correctly is no longer needed (and no longer works
                // anyway) as of macOS 14
                if (@available(macOS 14.0, *)) {
                    triggerReactivation = NO;
                    requiresActivation = YES;
                }

                // move process from background only to full on app with visible Dock icon
                ProcessSerialNumber psn;
                if (GetCurrentProcess(&psn) == noErr)
                {
                    TransformProcessType(&psn, kProcessTransformToForegroundApplication);
                }

                NSString *CFBundleIconFile = [mainBundle objectForInfoDictionaryKey:@"CFBundleIconFile"];
                NSString *iconPath = nil;
                if (CFBundleIconFile != nil)
                {
                    iconPath = [mainBundle pathForResource:[CFBundleIconFile stringByDeletingPathExtension] ofType:[CFBundleIconFile pathExtension]];
                }

                // -Xdock:icon can override CFBundleIconFile (but only if it actually points to a valid icon)
                NSString *property = [NSString stringWithFormat:@"APP_ICON_%d", [[NSProcessInfo processInfo] processIdentifier]];
                char *path = getenv([property UTF8String]);
                if (path != NULL)
                {
                    BOOL isFolder = NO;
                    NSString *overridenPath = [NSString stringWithFormat:@"%s", path];
                    if ([[NSFileManager defaultManager] fileExistsAtPath:overridenPath isDirectory:&isFolder] && !isFolder)
                    {
                        iconPath = overridenPath;
                    }
                }

                NSImage *image = nil;
                {
                    BOOL isFolder = NO;
                    if ([[NSFileManager defaultManager] fileExistsAtPath:iconPath isDirectory:&isFolder] && !isFolder)
                    {
                        image = [[NSImage alloc] initWithContentsOfFile:iconPath];
                    }
                    if (image == nil)
                    {
                        // last resort - if still no icon, then ask for an empty standard app icon, which is guaranteed to exist
                        image = [[NSImage imageNamed:@"NSImageNameApplicationIcon"] retain];
                    }
                }
                [app setApplicationIconImage:image];
                [image release];

                // Install a hidden Window menu. This allows the dock icon
                // menu to show the list of open windows (NSWindow instances)
                NSMenu *myMenu = [[NSMenu alloc] initWithTitle:@"Window"];
                [app setWindowsMenu:myMenu];
                [myMenu release];

                [app setDelegate:self];

                // [app activateIgnoringOtherApps:YES] won't activate the menu bar on OS X 10.9, so instead we do this:
                [[NSRunningApplication currentApplication] activateWithOptions:(NSApplicationActivateIgnoringOtherApps | NSApplicationActivateAllWindows)];
            }
            else
            {
                // allow background processes to change the cursor (10.8 only API so we'll have to dynamically call it if available)
                {
                    BOOL yes = YES;
                    [GlassHelper InvokeSelectorIfAvailable:@selector(javaSetAllowsCursorSetInBackground:) forClass:[NSCursor class] withArgument:&yes withReturnValue:NULL];
                }

                // http://developer.apple.com/library/ios/#documentation/DeveloperTools/Conceptual/cross_development/Using/using.html
                if (floor(NSAppKitVersionNumber) >= 1138) // NSAppKitVersionNumber10_7
                {
                    // 10.7 or later: move process from background only process to a limited app with active windows,
                    // but no Dock icon
                    ProcessSerialNumber psn;
                    if (GetCurrentProcess(&psn) == noErr)
                    {
                        TransformProcessType(&psn, 4); // kProcessTransformToUIElementApplication
                    }
                }
                [app setDelegate:self];
            }

            // drain the pool before entering runloop
            [pool2 drain];

            // enter runloop, this will not return until terminated
            [NSApp run];

            if (callbacks != NULL)
            {
                dialAppSlot(callbacks->notify_did_terminate);
            }

            GlassSetMainJavaAttached(0);
        }
        else // event loop is not started
        {
            if ([NSThread isMainThread] == YES) {
                // The NSNotification is ignored but the compiler insists on a non-NULL argument.
                NSNotification* notification = [NSNotification notificationWithName: NSApplicationWillFinishLaunchingNotification
                    object: NSApp
                    userInfo: nil];
                [glassApp applicationWillFinishLaunching: notification];
            } else {
                [glassApp performSelectorOnMainThread:@selector(applicationWillFinishLaunching:) withObject:NULL waitUntilDone:NO];
            }

            [pool2 drain];
        }
    }

    [pool1 drain];

    LOG("GlassApplication:runLoop EXIT");
}

- (BOOL)started
{
    return self->started;
}

- (GmacPref *)collectPreferenceEntries:(int32_t *)count
{
    *count = 0;
    return platformSupport != nil ? [platformSupport collectPreferenceEntries:count] : NULL;
}

static void runNestedEventLoop(void)
{
    NSRunLoop *theRL = [NSRunLoop currentRunLoop];
    NSApplication * app = [NSApplication sharedApplication];
    shouldKeepRunningNestedLoop = YES;
    // Cannot use [NSDate distantFuture] because the period is big the app could hang in a runloop
    // if the event came before entering the RL
    while (shouldKeepRunningNestedLoop && [theRL runMode:NSDefaultRunLoopMode
                                              beforeDate:[NSDate dateWithTimeIntervalSinceNow:0.010]])
    {
        NSEvent * event = [app nextEventMatchingMask: 0xFFFFFFFF untilDate:nil inMode:NSDefaultRunLoopMode dequeue:YES];

        if (event != nil) {
            [app sendEvent: event];
        }
    }

    shouldKeepRunningNestedLoop = YES;
}

+ (void)enterFullScreenExitingLoop
{
    if (isFullScreenExitingLoop) {
        return;
    }
    isFullScreenExitingLoop = YES;
    GLASS_WARN_IF_MAIN_JAVA_DETACHED;
    const GmacAppCallbacks *callbacks = attachedAppCallbacks();
    if (callbacks != NULL) {
        dialAppSlot(callbacks->enter_nested_event_loop);
    }
    isFullScreenExitingLoop = NO;
    [GlassRunnable rescheduleDeferredRunnables];
}

+ (void)leaveFullScreenExitingLoopIfNeeded
{
    if (!isFullScreenExitingLoop) {
        return;
    }
    GLASS_WARN_IF_MAIN_JAVA_DETACHED;
    const GmacAppCallbacks *callbacks = attachedAppCallbacks();
    if (callbacks != NULL) {
        dialAppSlot(callbacks->leave_nested_event_loop);
    }
}

static void inputDidChangeCallback(CFNotificationCenterRef center, void *observer, CFNotificationName name, const void *object, CFDictionaryRef userInfo)
{
    if (keyCodeForCharMap != nil) {
        [keyCodeForCharMap removeAllObjects];
    }
}

+ (void)registerKeyEvent:(NSEvent*)event
{
    if (!keyCodeForCharMap) {
        keyCodeForCharMap = [[NSMutableDictionary alloc] initWithCapacity:100];
        // Note: it is never released.
        // To avoid stale entries when the user switches layout
        CFNotificationCenterRef center = CFNotificationCenterGetDistributedCenter();
        CFNotificationCenterAddObserver(center, NULL, inputDidChangeCallback,
                                        kTISNotifySelectedKeyboardInputSourceChanged,
                                        NULL, CFNotificationSuspensionBehaviorCoalesce);
    }

    // Add the character the user typed to the map.
    NSNumber* mapObject = [NSNumber numberWithUnsignedShort:[event keyCode]];
    [keyCodeForCharMap setObject:mapObject forKey:[event characters]];
    // getKeyCodeForChar should not just match against a character the user types
    // directly but any other character printed on the same key.
    NSString* unshifted = GetStringForMacKey(event.keyCode, false);
    if (unshifted != nil) {
        [keyCodeForCharMap setObject:mapObject forKey:unshifted];
    }
    NSString* shifted = GetStringForMacKey(event.keyCode, true);
    if (shifted != nil) {
        [keyCodeForCharMap setObject:mapObject forKey:shifted];
    }
    // On some European keyboards there are useful symbols which are only
    // accessible via the Option key. We don't query for the Option key
    // character because on most layouts just about every key has some
    // random symbol assigned to that modifier which the user probably
    // isn't even aware of. The user can get that character into the map
    // by typing it directly.
}

+ (int32_t)getKeyCodeForChar:(uint16_t)c;
{
    id v = [keyCodeForCharMap objectForKey:[NSString stringWithCharacters: (UniChar *)&c length:1]];
    if (!v) {
        return com_sun_glass_events_KeyEvent_VK_UNDEFINED;
    } else {
        return GetJavaKeyCodeFor([v unsignedShortValue]);
    }
}

+ (BOOL)syncRenderingDisabled {
    return disableSyncRendering;
}

+ (BOOL)isEmbedded {
    return isEmbedded;
}

// Inside the implementation, which is what lets a function read the ivars.
int32_t gmac_test_inspect_and_release_app_delegate(void *app)
{
    if (app == NULL)
    {
        return -1;
    }
    GlassApplication *glass = (GlassApplication *)app;
    int32_t state = (glass->taskbarApplication ? 1 : 0) | (glass->hasLaunchable ? 2 : 0);
    [glass release];
    return state;
}

@end

#pragma mark --- C ABI

int32_t gmac_app_init(int32_t disable_sync_rendering)
{
    // Check minimum OS version
    NSOperatingSystemVersion osVer;
    osVer = [[NSProcessInfo processInfo] operatingSystemVersion];
    NSInteger osVerMajor = osVer.majorVersion;
    NSInteger osVerMinor = osVer.minorVersion;

    // Map 10.16 to 11.0, since macOS will return 10.16 by default (for compatibility)
    if (osVerMajor == 10 && osVerMinor >= 16) {
        // FIXME: if we ever need to know which minor version of macOS 11.x we
        // are running on, we will need to look it up using a similar technique
        // to what the JDK does.
        osVerMajor = 11;
        osVerMinor = 0;
    }

    if (osVerMajor < MACOS_MIN_VERSION_MAJOR ||
            (osVerMajor == MACOS_MIN_VERSION_MAJOR &&
             osVerMinor < MACOS_MIN_VERSION_MINOR))
    {
        NSLog(@"ERROR: macOS version is %d.%d, which is below the minimum of %d.%d",
              (int)osVerMajor, (int)osVerMinor, MACOS_MIN_VERSION_MAJOR, MACOS_MIN_VERSION_MINOR);
        return GMAC_ERR_UNSUPPORTED_OS;
    }

    disableSyncRendering = disable_sync_rendering ? YES : NO;
    return GMAC_OK;
}

void *gmac_app_create_delegate(int32_t taskbar_application, int32_t has_launchable)
{
    return [[GlassApplication alloc] initWithTaskbarApplication:taskbar_application == 1
                                                     launchable:has_launchable != 0];
}

void gmac_app_run_loop(void *app)
{
    NSAutoreleasePool *glasspool = [[NSAutoreleasePool alloc] init];
    {
        if ([NSThread isMainThread] == YES)
        {
            //            fprintf(stderr, "\nWARNING: Glass was started on 1st thread and will block this thread.\nYou most likely do not want to do this - please remove \"-XstartOnFirstThread\" from VM arguments.\n\n");
        }
        else
        {
            if ([[NSThread currentThread] name] == nil)
            {
                [[NSThread currentThread] setName:@"Main Java Thread"];
            }
        }

        GlassApplication* glass = (GlassApplication*)app;
        if ([NSThread isMainThread] == YES) {
            [glass runLoop: glass];
        } else {
            [glass performSelectorOnMainThread:@selector(runLoop:) withObject:glass waitUntilDone:[NSThread isMainThread]];

            // wait for Cocoa to enter its UI runloop
            while ([glass started] == NO)
            {
                LOG("        waiting for [glass started]");
                usleep(10000);
            }
        }

        // at this point Java main thread is allowed to proceed, but Cocoa's UI thread entered its runloop, so the VM will not quit
    }
    [glasspool drain]; glasspool=nil;
}

void gmac_app_finish_terminating(void *app)
{
    if (app) {
        [(GlassApplication*)app release];
    }

    if (isEmbedded) {
        return;
    }

    NSAutoreleasePool *glasspool = [[NSAutoreleasePool alloc] init];
    {
        [NSApp stop:nil];
        [NSApp hide:nil];

        // wake up the runloop one last time so that it can process the stop:
        // request, even if the app is inactive currently
        NSTimeInterval dummyEventTimestamp = [NSProcessInfo processInfo].systemUptime;
        NSEvent* event = [NSEvent otherEventWithType: NSApplicationDefined
                                            location: NSMakePoint(0,0)
                                       modifierFlags: 0
                                           timestamp: dummyEventTimestamp
                                        windowNumber: 0
                                             context: nil
                                             subtype: 0
                                               data1: 0
                                               data2: 0];
        [NSApp postEvent: event atStart: NO];
    }
    [glasspool drain]; glasspool=nil;
}

void gmac_app_enter_nested_loop(void)
{
    NSAutoreleasePool *glasspool = [[NSAutoreleasePool alloc] init];
    {
        runNestedEventLoop();
    }
    [glasspool drain]; glasspool=nil;
}

void gmac_app_leave_nested_loop(void)
{
    NSAutoreleasePool *glasspool = [[NSAutoreleasePool alloc] init];
    {
        shouldKeepRunningNestedLoop = NO;
    }
    [glasspool drain]; glasspool=nil;
}

static void postRunnable(GlassRunnable *runnable)
{
    [runnable performSelectorOnMainThread:@selector(maybeRun) withObject:nil waitUntilDone:NO modes:runLoopModes];
}

static void runRunnableAndWait(GlassRunnable *runnable)
{
    [runnable performSelectorOnMainThread:@selector(run) withObject:nil waitUntilDone:YES modes:runLoopModes];
}

static int32_t droppedRunnableStatus(void)
{
    return pthread_main_np() == 0 ? GMAC_ERR_DETACHED_OFF_MAIN : GMAC_ERR_NOT_ATTACHED;
}

int32_t gmac_app_invoke_later(int64_t runnable_id)
{
    if (!GlassIsMainJavaAttached())
    {
        return droppedRunnableStatus();
    }
    postRunnable([[GlassRunnable alloc] initWithRunnableId:runnable_id]);
    return GMAC_OK;
}

int32_t gmac_app_invoke_and_wait(int64_t runnable_id)
{
    if (!GlassIsMainJavaAttached())
    {
        return droppedRunnableStatus();
    }
    runRunnableAndWait([[GlassRunnable alloc] initWithRunnableId:runnable_id]);
    return GMAC_OK;
}

double gmac_screen_video_refresh_period(void)
{
    if (GlassDisplayLink != NULL)
    {
        double outRefresh = CVDisplayLinkGetActualOutputVideoRefreshPeriod(GlassDisplayLink);
        LOG("CVDisplayLinkGetActualOutputVideoRefreshPeriod: %f", outRefresh);
        return (outRefresh * 1000.0); // to millis
    }
    else
    {
        return 0.0;
    }
}

int32_t gmac_app_is_trigger_reactivation(void)
{
    return triggerReactivation ? 1 : 0;
}

void gmac_app_hide(void)
{
    [NSApp hide:NSApp];
}

void gmac_app_hide_other_applications(void)
{
    [NSApp hideOtherApplications:NSApp];
}

void gmac_app_unhide_all_applications(void)
{
    [NSApp unhideAllApplications:NSApp];
}

int32_t gmac_app_data_directory(char **out_utf8, int32_t *out_length)
{
    int32_t status = GMAC_ERR_NO_DATA;
    *out_utf8 = NULL;
    *out_length = 0;

    GLASS_POOL_ENTER;
    {
    NSArray *paths = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, YES);
    if (paths && [paths count] > 0) {
        status = GlassCopyUtf8([[paths lastObject] UTF8String], out_utf8, out_length);
    }
    }
    GLASS_POOL_EXIT;

    return status;
}

int32_t gmac_key_java_to_mac(int32_t java_key_code)
{
    unsigned short macCode = 0;
    GetMacKey(java_key_code, &macCode);
    return (macCode & 0xFFFF);
}

int32_t gmac_app_class_name(char **out_utf8, int32_t *out_length)
{
    NSString* className = NSStringFromClass([[NSApplicationFX sharedApplication] class]);
    return GlassCopyUtf8([className UTF8String], out_utf8, out_length);
}

int32_t gmac_app_collect_preferences(void *app, GmacPref **out_entries, int32_t *out_count)
{
    *out_count = 0;
    *out_entries = app != NULL ? [(GlassApplication *)app collectPreferenceEntries:out_count] : NULL;
    return *out_entries != NULL ? GMAC_OK : GMAC_ERR_NO_DATA;
}

int32_t gmac_app_open_uri(const uint16_t *uri, int32_t length, int32_t *out_completed)
{
    __block OSStatus status = noErr;
    *out_completed = 0;

    GLASS_POOL_ENTER;

    NSURL *url = [NSURL URLWithString:GlassNSStringOrNil(uri, length)];

    NSURL *httpsURL = [NSURL URLWithString:@"https://"];
    NSWorkspace *workspace = [NSWorkspace sharedWorkspace];
    NSURL *defaultBrowser = [workspace URLForApplicationToOpenURL:httpsURL];
    if (defaultBrowser == nil) {
        NSLog(@"Default browser not set");
        return -1;
    }
    NSArray<NSURL *> *urls = @[url];
    NSWorkspaceOpenConfiguration *configuration = [NSWorkspaceOpenConfiguration configuration];
    configuration.activates = YES;
    configuration.promptsUserIfNeeded = YES;
    dispatch_semaphore_t semaphore = dispatch_semaphore_create(0);
    dispatch_time_t timeout = dispatch_time(DISPATCH_TIME_NOW, (int64_t)(NSEC_PER_SEC)); // 1 second timeout

    dispatch_retain(semaphore);
    [workspace     openURLs:urls
       withApplicationAtURL:defaultBrowser
              configuration:configuration
           completionHandler:^(NSRunningApplication *app, NSError *error) {
               if (error) {
                   NSLog(@"Error opening URI %@ : %@", url, error.localizedDescription);
                   status = (OSStatus) error.code;
               }
               dispatch_semaphore_signal(semaphore);
               dispatch_release(semaphore);
           }];

    dispatch_semaphore_wait(semaphore, timeout);
    dispatch_release(semaphore);

    GLASS_POOL_EXIT;
    *out_completed = 1;

    return status;
}
