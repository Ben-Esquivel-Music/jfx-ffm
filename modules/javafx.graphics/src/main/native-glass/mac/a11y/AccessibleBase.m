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

#import "AccessibleBase.h"
#import "GlassMacros.h"
#import "GlassAccessible.h"
#import "com_sun_glass_ui_mac_MacVariant.h"
#import "common.h"

static NSMutableDictionary * rolesMap;

@implementation AccessibleBase

+ (void) initializeRolesMap {
    /*
     * Here we should keep all the mapping between the accessibility roles and implementing classes.
     * All JavaFX roles and corresponding available properties are defined in
     * enum javafx.scene.AccessibleRole
     */
    rolesMap = [[NSMutableDictionary alloc] initWithCapacity:25];

    [rolesMap setObject:@"JFXButtonAccessibility" forKey:@"BUTTON"];
    [rolesMap setObject:@"JFXCheckboxAccessibility" forKey:@"CHECK_BOX"];
    [rolesMap setObject:@"JFXMenuItemAccessibility" forKey:@"CHECK_MENU_ITEM"];
    [rolesMap setObject:@"JFXButtonAccessibility" forKey:@"DECREMENT_BUTTON"];
    [rolesMap setObject:@"JFXLinkAccessibility" forKey:@"HYPERLINK"];
    [rolesMap setObject:@"JFXImageAccessibility" forKey:@"IMAGE"];
    [rolesMap setObject:@"JFXImageAccessibility" forKey:@"IMAGE_VIEW"];
    [rolesMap setObject:@"JFXButtonAccessibility" forKey:@"INCREMENT_BUTTON"];
    [rolesMap setObject:@"JFXMenuItemAccessibility" forKey:@"MENU"];
    [rolesMap setObject:@"JFXMenuBarAccessibility" forKey:@"MENU_BAR"];
    [rolesMap setObject:@"JFXMenuItemAccessibility" forKey:@"MENU_ITEM"];
    [rolesMap setObject:@"JFXRadiobuttonAccessibility" forKey:@"PAGE_ITEM"];
    [rolesMap setObject:@"JFXTabGroupAccessibility" forKey:@"PAGINATION"];
    [rolesMap setObject:@"JFXProgressIndicatorAccessibility" forKey:@"PROGRESS_INDICATOR"];
    [rolesMap setObject:@"JFXRadiobuttonAccessibility" forKey:@"RADIO_BUTTON"];
    [rolesMap setObject:@"JFXMenuItemAccessibility" forKey:@"RADIO_MENU_ITEM"];
    [rolesMap setObject:@"JFXSliderAccessibility" forKey:@"SLIDER"];
    [rolesMap setObject:@"JFXStepperAccessibility" forKey:@"SPINNER"];
    [rolesMap setObject:@"JFXButtonAccessibility" forKey:@"SPLIT_MENU_BUTTON"];
    [rolesMap setObject:@"JFXRadiobuttonAccessibility" forKey:@"TAB_ITEM"];
    [rolesMap setObject:@"JFXTabGroupAccessibility" forKey:@"TAB_PANE"];
    [rolesMap setObject:@"JFXStaticTextAccessibility" forKey:@"TEXT"];
    [rolesMap setObject:@"JFXNavigableTextAccessibility" forKey:@"TEXT_AREA"];
    [rolesMap setObject:@"JFXNavigableTextAccessibility" forKey:@"TEXT_FIELD"];
    [rolesMap setObject:@"JFXCheckboxAccessibility" forKey:@"TOGGLE_BUTTON"];

}

+ (Class) getComponentAccessibilityClass:(NSString *)role
{
    if (rolesMap == nil) {
        [self initializeRolesMap];
    }

    NSString *className = [rolesMap objectForKey:role];
    if (className != nil) {
        return NSClassFromString(className);
    }
    return [GlassAccessible class];
}

- (id)initWithPeerId:(int64_t)identifier
{
    self = [super init];
    if (self != nil) {
        self->peerId = identifier;
    }
    self->parent = nil;
    return self;
}

- (void)dealloc
{
    assert(pthread_main_np() == 1);
    const GmacAccessibleCallbacks *callbacks = GlassAccessibleCallbacks();
    if (callbacks != NULL) {
        GlassDialPeerReleased(callbacks, self->peerId);
    }
    [super dealloc];
}

- (int64_t)getPeerId
{
    return self->peerId;
}

- (NSString *)getJavaRole
{
    return self->jRole;
}

- (void)setJavaRole:(NSString *)newRole
{
    self->jRole = [newRole copy];
}

/*
 * Request accessibility attribute by name from JavaFX Node. Returns attribute value
 * converted to the native format or NULL if attribute with that name does not exist.
 * Code that uses this function needs to convert NULL to the default value of a certain type where required.
 */
- (id)requestNodeAttribute:(NSString *)attribute
{
    GLASS_WARN_IF_MAIN_JAVA_DETACHED;
    const GmacAccessibleCallbacks *callbacks = GlassAccessibleCallbacks();
    if (!GlassIsMainJavaAttached() || callbacks == NULL) return NULL;
    return GlassDialAttributeValue(callbacks, self->peerId, attribute);
}

/*
 * Request accessibility attribute by name from JavaFX Node providing a specified parameter.
 * Returns attribute value converted to the native format or NULL if attribute with that name does not exist.
 * Code that uses this function needs to convert NULL to the default value of a certain type where required.
 */

- (id)requestNodeAttribute:(NSString *)attribute forParameter:(id)parameter
{
    GLASS_WARN_IF_MAIN_JAVA_DETACHED;
    const GmacAccessibleCallbacks *callbacks = GlassAccessibleCallbacks();
    if (!GlassIsMainJavaAttached() || callbacks == NULL) return NULL;
    return GlassDialAttributeValueForParameter(callbacks, self->peerId, attribute, parameter);
}

/*
 * Check whether an accessibility attribute on the JavaFX Node can be set.
 */
- (BOOL)isNodeAttributeSettable:(NSString *)attribute
{
    GLASS_WARN_IF_MAIN_JAVA_DETACHED;
    const GmacAccessibleCallbacks *callbacks = GlassAccessibleCallbacks();
    if (!GlassIsMainJavaAttached() || callbacks == NULL) return FALSE;
    return GlassDialIsAttributeSettable(callbacks, self->peerId, attribute);
}

/*
 * Set accessibility attribute by name on the JavaFX Node.
 */
- (void)setNodeAttribute:(id)value forAttribute:(NSString *)attribute
{
    GLASS_WARN_IF_MAIN_JAVA_DETACHED;
    const GmacAccessibleCallbacks *callbacks = GlassAccessibleCallbacks();
    if (!GlassIsMainJavaAttached() || callbacks == NULL) return;
    GlassDialSetValue(callbacks, self->peerId, value, attribute);
}

- (id)accessibilityValue
{
    return [self requestNodeAttribute:@"AXValue"];
}

- (id)accessibilityMinValue
{
    return [self requestNodeAttribute:@"AXMinValue"];
}

- (id)accessibilityMaxValue
{
    return [self requestNodeAttribute:@"AXMaxValue"];
}

- (NSString *)accessibilityLabel
{
    // Use the same value that is set for accessibilityTitle - some components
    // do not have titles and request it as a label
    return [self accessibilityTitle];
}

- (id)accessibilityParent
{
    if (parent == nil) {
        parent = [self requestNodeAttribute:@"AXParent"];
    }
    return parent;
}

- (id)accessibilityTitle
{
    return [self requestNodeAttribute:@"AXTitle"];
}

- (id)accessibilityTitleUIElement
{
    return [self requestNodeAttribute:@"AXTitleUIElement"];
}

- (NSString *)accessibilityPlaceholderValue
{
    return [self requestNodeAttribute:@"AXPlaceholderValue"];
}

- (NSArray *)accessibilityChildren
{
    return [self requestNodeAttribute:@"AXChildren"];
}

- (id)accessibilityRoleDescription
{
    return [self requestNodeAttribute:@"AXRoleDescription"];
}


// Actions support
- (BOOL)performAccessibleAction:(NSString *)action
{
    GLASS_WARN_IF_MAIN_JAVA_DETACHED;
    const GmacAccessibleCallbacks *callbacks = GlassAccessibleCallbacks();
    if (!GlassIsMainJavaAttached() || callbacks == NULL) return FALSE;
    return GlassDialPerformAction(callbacks, self->peerId, action) != GMAC_UPCALL_THREW;
}


- (NSRect)accessibilityFrame
{
    id p = [self requestNodeAttribute:@"AXPosition"];
    id s = [self requestNodeAttribute:@"AXSize"];
    if (p == NULL || s == NULL) {
        return NSZeroRect;
    }
    NSPoint position = [p pointValue];
    NSSize size = [s sizeValue];
    return NSMakeRect(position.x, position.y, size.width, size.height);
}


- (BOOL)isAccessibilityElement
{
    return YES;
}

- (void)clearParent
{
    parent = nil;
}

- (BOOL)isAccessibilityFocused
{
    id retval = [self requestNodeAttribute:@"AXFocused"];
    if (retval == NULL) {
        return NO;
    } else {
        return [retval boolValue];
    }
}

- (void)setAccessibilityFocused:(BOOL)value
{
    [self setNodeAttribute:[NSNumber numberWithBool:value] forAttribute:@"AXFocused"];
}

@end

void *gmac_a11y_create_peer(const uint16_t *role, int32_t role_length, int64_t peer_id)
{
    NSString *roleName = GlassNSStringOrNil(role, role_length);
    Class classType = [AccessibleBase getComponentAccessibilityClass:roleName];
    NSObject* accessible = NULL;
    accessible = [[classType alloc] initWithPeerId: peer_id];
    if ([accessible isKindOfClass:[AccessibleBase class]]) {
        [(AccessibleBase *) accessible setJavaRole:roleName];
    }
    return accessible;
}

void gmac_a11y_invalidate_parent(void *peer)
{
    NSObject* accessible = (NSObject*)peer;
    if ([accessible isKindOfClass: [AccessibleBase class]]) {
        [((AccessibleBase*) accessible) clearParent];
    }
}
