/*
 * Copyright (c) 2023, 2026, Oracle and/or its affiliates. All rights reserved.
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

#import "PlatformSupport.h"
#import "GlassMacros.h"
#import "glass_mac_internal.h"
#import <Network/Network.h>

// 44 colours, the colour array, the control tint, the scroller style and four booleans.
#define PREFERENCE_CAPACITY 51

// Until packPreferences, a COLOR_ARRAY entry's value is its own malloc'd array.
typedef struct {
    GmacPref entries[PREFERENCE_CAPACITY];
    int32_t count;
} PreferenceList;

static GmacPref *nextEntry(PreferenceList *preferences, const char *key, int32_t kind) {
    if (preferences->count >= PREFERENCE_CAPACITY) {
        return NULL;
    }
    GmacPref *entry = &preferences->entries[preferences->count++];
    entry->key = key;
    entry->kind = kind;
    return entry;
}

static GmacColor toGmacColor(NSColor *color) {
    NSColor* c = [color colorUsingColorSpace:[NSColorSpace sRGBColorSpace]];
    GmacColor result = {
        .r = (int)([c redComponent] * 255.0f),
        .g = (int)([c greenComponent] * 255.0f),
        .b = (int)([c blueComponent] * 255.0f),
        .reserved = 0,
        .a = (double)[c alphaComponent]
    };
    return result;
}

// One block: the entries, then the colours of every COLOR_ARRAY entry.
static GmacPref *packPreferences(PreferenceList *preferences, int32_t *count) {
    size_t colorCount = 0;
    for (int32_t i = 0; i < preferences->count; ++i) {
        if (preferences->entries[i].kind == GMAC_PREF_COLOR_ARRAY) {
            colorCount += (size_t)preferences->entries[i].count;
        }
    }

    size_t entryBytes = (size_t)preferences->count * sizeof(GmacPref);
    GmacPref *block = malloc(entryBytes + colorCount * sizeof(GmacColor));
    if (block != NULL) {
        memcpy(block, preferences->entries, entryBytes);
        GmacColor *colors = (GmacColor *)((char *)block + entryBytes);
        for (int32_t i = 0; i < preferences->count; ++i) {
            if (block[i].kind == GMAC_PREF_COLOR_ARRAY && block[i].count > 0) {
                memcpy(colors, block[i].value, (size_t)block[i].count * sizeof(GmacColor));
                block[i].value = colors;
                colors += block[i].count;
            }
        }
        *count = preferences->count;
    }

    for (int32_t i = 0; i < preferences->count; ++i) {
        if (preferences->entries[i].kind == GMAC_PREF_COLOR_ARRAY) {
            free((void *)preferences->entries[i].value);
        }
    }
    return block;
}

@implementation PlatformSupport {
    nw_path_monitor_t pathMonitor;
    bool currentPathConstrained;
    bool currentPathExpensive;
}

- (id)init {
    if (!(self = [super init])) {
        return nil;
    }

    self->currentPathConstrained = false;
    self->currentPathExpensive = false;

    [NSApp addObserver:self
           forKeyPath:@"effectiveAppearance"
           options:0
           context:nil];

    [[NSNotificationCenter defaultCenter]
        addObserver:self
        selector:@selector(updatePreferences)
        name:NSPreferredScrollerStyleDidChangeNotification
        object:nil];

    [[NSNotificationCenter defaultCenter]
        addObserver:self
        selector:@selector(updatePreferences)
        name:NSSystemColorsDidChangeNotification
        object:nil];

    [[[NSWorkspace sharedWorkspace] notificationCenter]
        addObserver:self
        selector:@selector(updatePreferences)
        name:NSWorkspaceAccessibilityDisplayOptionsDidChangeNotification
        object:nil];

    pathMonitor = nw_path_monitor_create();
    nw_path_monitor_set_update_handler(pathMonitor, ^(nw_path_t path) {
        self->currentPathConstrained = nw_path_is_constrained(path);
        self->currentPathExpensive = nw_path_is_expensive(path);
        [self updatePreferences];
    });

    nw_path_monitor_set_queue(pathMonitor, dispatch_get_main_queue());
    nw_path_monitor_start(pathMonitor);

    return self;
}

- (void)stopEventProcessing {
    [NSApp removeObserver:self forKeyPath:@"effectiveAppearance"];
    [[NSNotificationCenter defaultCenter] removeObserver:self];
    [[[NSWorkspace sharedWorkspace] notificationCenter] removeObserver:self];
    nw_path_monitor_cancel(pathMonitor);
}

- (void)observeValueForKeyPath:(NSString*)keyPath ofObject:(id)object change:(NSDictionary*)change context:(void*)c {
    [self updatePreferences];
}

- (GmacPref*)collectPreferenceEntries:(int32_t*)count {
    *count = 0;
    PreferenceList list;
    memset(&list, 0, sizeof(list));
    PreferenceList* preferences = &list;

    // The current appearance is set to the system appearance when the application is started.
    // Since the system appearance can change while the application is running, we need to set
    // the current appearance to the application's effective appearance before querying system
    // colors.
    NSAppearance* lastAppearance = [NSAppearance currentAppearance];
    [NSAppearance setCurrentAppearance:[NSApp effectiveAppearance]];
    [self queryNSColors:preferences];
    [NSAppearance setCurrentAppearance:lastAppearance];

    [self putBoolean:preferences
          key:"macOS.NSWorkspace.accessibilityDisplayShouldReduceMotion"
          value:[[NSWorkspace sharedWorkspace] accessibilityDisplayShouldReduceMotion]];

    [self putBoolean:preferences
          key:"macOS.NSWorkspace.accessibilityDisplayShouldReduceTransparency"
          value:[[NSWorkspace sharedWorkspace] accessibilityDisplayShouldReduceTransparency]];

    [self putString:preferences
          key:"macOS.NSScroller.preferredScrollerStyle"
          value:[NSScroller preferredScrollerStyle] == NSScrollerStyleOverlay
              ? "NSScrollerStyleOverlay" : "NSScrollerStyleLegacy"];

    [self putBoolean:preferences
          key:"macOS.NWPathMonitor.currentPathConstrained"
          value:currentPathConstrained];

    [self putBoolean:preferences
          key:"macOS.NWPathMonitor.currentPathExpensive"
          value:currentPathExpensive];

    return packPreferences(preferences, count);
}

- (void)queryNSColors:(PreferenceList*)preferences {
    // Label colors
    [self putColor:preferences key:"macOS.NSColor.labelColor" value:[NSColor labelColor]];
    [self putColor:preferences key:"macOS.NSColor.secondaryLabelColor" value:[NSColor secondaryLabelColor]];
    [self putColor:preferences key:"macOS.NSColor.tertiaryLabelColor" value:[NSColor tertiaryLabelColor]];
    [self putColor:preferences key:"macOS.NSColor.quaternaryLabelColor" value:[NSColor quaternaryLabelColor]];

    // Text colors
    [self putColor:preferences key:"macOS.NSColor.textColor" value:[NSColor textColor]];
    [self putColor:preferences key:"macOS.NSColor.placeholderTextColor" value:[NSColor placeholderTextColor]];
    [self putColor:preferences key:"macOS.NSColor.selectedTextColor" value:[NSColor selectedTextColor]];
    [self putColor:preferences key:"macOS.NSColor.textBackgroundColor" value:[NSColor textBackgroundColor]];
    [self putColor:preferences key:"macOS.NSColor.selectedTextBackgroundColor" value:[NSColor selectedTextBackgroundColor]];
    [self putColor:preferences key:"macOS.NSColor.keyboardFocusIndicatorColor" value:[NSColor keyboardFocusIndicatorColor]];
    [self putColor:preferences key:"macOS.NSColor.unemphasizedSelectedTextColor" value:[NSColor unemphasizedSelectedTextColor]];
    [self putColor:preferences key:"macOS.NSColor.unemphasizedSelectedTextBackgroundColor" value:[NSColor unemphasizedSelectedTextBackgroundColor]];

    // Content colors
    [self putColor:preferences key:"macOS.NSColor.linkColor" value:[NSColor linkColor]];
    [self putColor:preferences key:"macOS.NSColor.separatorColor" value:[NSColor separatorColor]];
    [self putColor:preferences key:"macOS.NSColor.selectedContentBackgroundColor" value:[NSColor selectedContentBackgroundColor]];
    [self putColor:preferences key:"macOS.NSColor.unemphasizedSelectedContentBackgroundColor" value:[NSColor unemphasizedSelectedContentBackgroundColor]];

    // Menu colors
    [self putColor:preferences key:"macOS.NSColor.selectedMenuItemTextColor" value:[NSColor selectedMenuItemTextColor]];

    // Table colors
    [self putColor:preferences key:"macOS.NSColor.gridColor" value:[NSColor gridColor]];
    [self putColor:preferences key:"macOS.NSColor.headerTextColor" value:[NSColor headerTextColor]];
    [self putColors:preferences key:"macOS.NSColor.alternatingContentBackgroundColors" value:[NSColor alternatingContentBackgroundColors]];

    // Control colors
    [self putColor:preferences key:"macOS.NSColor.controlAccentColor" value:[NSColor controlAccentColor]];
    [self putColor:preferences key:"macOS.NSColor.controlColor" value:[NSColor controlColor]];
    [self putColor:preferences key:"macOS.NSColor.controlBackgroundColor" value:[NSColor controlBackgroundColor]];
    [self putColor:preferences key:"macOS.NSColor.controlTextColor" value:[NSColor controlTextColor]];
    [self putColor:preferences key:"macOS.NSColor.disabledControlTextColor" value:[NSColor disabledControlTextColor]];
    [self putColor:preferences key:"macOS.NSColor.selectedControlColor" value:[NSColor selectedControlColor]];
    [self putColor:preferences key:"macOS.NSColor.selectedControlTextColor" value:[NSColor selectedControlTextColor]];
    [self putColor:preferences key:"macOS.NSColor.alternateSelectedControlTextColor" value:[NSColor alternateSelectedControlTextColor]];

    const char* controlTint = nil;
    switch ([NSColor currentControlTint]) {
        case NSDefaultControlTint: controlTint = "NSDefaultControlTint"; break;
        case NSGraphiteControlTint: controlTint = "NSGraphiteControlTint"; break;
        case NSBlueControlTint: controlTint = "NSBlueControlTint"; break;
        case NSClearControlTint: controlTint = "NSClearControlTint"; break;
    }
    if (controlTint != nil) {
        [self putString:preferences key:"macOS.NSColor.currentControlTint" value:controlTint];
    }

    // Window colors
    [self putColor:preferences key:"macOS.NSColor.windowBackgroundColor" value:[NSColor windowBackgroundColor]];
    [self putColor:preferences key:"macOS.NSColor.windowFrameTextColor" value:[NSColor windowFrameTextColor]];
    [self putColor:preferences key:"macOS.NSColor.underPageBackgroundColor" value:[NSColor underPageBackgroundColor]];

    // Highlights and shadows
    [self putColor:preferences key:"macOS.NSColor.findHighlightColor" value:[NSColor findHighlightColor]];
    [self putColor:preferences key:"macOS.NSColor.highlightColor" value:[NSColor highlightColor]];
    [self putColor:preferences key:"macOS.NSColor.shadowColor" value:[NSColor shadowColor]];

    // Adaptable system colors
    [self putColor:preferences key:"macOS.NSColor.systemBlueColor" value:[NSColor systemBlueColor]];
    [self putColor:preferences key:"macOS.NSColor.systemBrownColor" value:[NSColor systemBrownColor]];
    [self putColor:preferences key:"macOS.NSColor.systemGrayColor" value:[NSColor systemGrayColor]];
    [self putColor:preferences key:"macOS.NSColor.systemGreenColor" value:[NSColor systemGreenColor]];
    [self putColor:preferences key:"macOS.NSColor.systemIndigoColor" value:[NSColor systemIndigoColor]];
    [self putColor:preferences key:"macOS.NSColor.systemOrangeColor" value:[NSColor systemOrangeColor]];
    [self putColor:preferences key:"macOS.NSColor.systemPinkColor" value:[NSColor systemPinkColor]];
    [self putColor:preferences key:"macOS.NSColor.systemPurpleColor" value:[NSColor systemPurpleColor]];
    [self putColor:preferences key:"macOS.NSColor.systemRedColor" value:[NSColor systemRedColor]];
    [self putColor:preferences key:"macOS.NSColor.systemTealColor" value:[NSColor systemTealColor]];
    [self putColor:preferences key:"macOS.NSColor.systemYellowColor" value:[NSColor systemYellowColor]];
}

/**
 * Collect all platform preferences and notify the JavaFX application when a preference has changed.
 * The change notification includes all preferences, not only the changed preferences.
 */
- (void)updatePreferences {
    const GmacAppCallbacks *callbacks = GlassAppCallbacks();
    if (callbacks != NULL && callbacks->notify_preferences_changed != NULL) {
        int32_t count = 0;
        GmacPref *entries = [self collectPreferenceEntries:&count];
        if (entries != NULL) {
            callbacks->notify_preferences_changed(entries, count);
            free(entries);
        }
    }
}

- (void)putBoolean:(PreferenceList*)preferences key:(const char*)key value:(bool)value {
    GmacPref *entry = nextEntry(preferences, key, GMAC_PREF_BOOLEAN);
    if (entry != NULL) {
        entry->count = value ? 1 : 0;
    }
}

- (void)putString:(PreferenceList*)preferences key:(const char*)key value:(const char*)value {
    GmacPref *entry = nextEntry(preferences, key, GMAC_PREF_STRING);
    if (entry != NULL) {
        entry->count = value != NULL ? (int32_t)strlen(value) : 0;
        entry->value = value;
    }
}

- (void)putColor:(PreferenceList*)preferences key:(const char*)colorName value:(NSColor*)color {
    GmacColor value = toGmacColor(color);
    GmacPref *entry = nextEntry(preferences, colorName, GMAC_PREF_COLOR);
    if (entry != NULL) {
        entry->color = value;
    }
}

- (void)putColors:(PreferenceList*)preferences key:(const char*)colorName value:(NSArray*)colors {
    int count = [colors count];
    if (count < 0) {
        return;
    }

    GmacColor *values = NULL;
    if (count > 0) {
        values = malloc((size_t)count * sizeof(GmacColor));
        if (values == NULL) {
            return;
        }
    }
    for (int i = 0; i < count; ++i) {
        values[i] = toGmacColor(colors[i]);
    }

    GmacPref *entry = nextEntry(preferences, colorName, GMAC_PREF_COLOR_ARRAY);
    if (entry == NULL) {
        free(values);
        return;
    }
    entry->count = count;
    entry->value = values;
}

@end
