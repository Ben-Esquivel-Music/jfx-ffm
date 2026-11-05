/*
 * Copyright (c) 2013, 2026, Oracle and/or its affiliates. All rights reserved.
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

#import "GlassAccessible.h"
#import "GlassMacros.h"
#import "com_sun_glass_ui_mac_MacVariant.h"
#import "common.h"
#import "glass_mac_internal.h"

typedef id (*GlassElementMapper)(const void *elements, int32_t index);

static id idElement(const void *elements, int32_t index)
{
    return (id)((void *const *)elements)[index];
}

static id intNumberElement(const void *elements, int32_t index)
{
    return [NSNumber numberWithInt: ((const int32_t *)elements)[index]];
}

/* What the former jArrayToNSArray made of the count elements, a count below 0 standing for a null array. */
static NSMutableArray *arrayOfElements(const void *elements, int32_t count, GlassElementMapper mapper)
{
    if (count < 0) return NULL;
    NSMutableArray *array = [NSMutableArray arrayWithCapacity: count];
    for (int32_t index = 0; index < count; index++) {
        [array addObject: mapper(elements, index)];
    }
    return array;
}

static id variantToObject(const GmacVariant *variant);

/* What the former jArrayToNSArray made of the count MacVariants with jVariantToID. */
static NSMutableArray *arrayOfVariants(const GmacVariant *variants, int32_t count)
{
    if (count < 0) return NULL;
    NSMutableArray *array = [NSMutableArray arrayWithCapacity: count];
    for (int32_t index = 0; index < count; index++) {
        id value = variantToObject(&variants[index]);
        if (value) {
            [array addObject: value];
        }
    }
    return array;
}

/* What the former variantToID made of the MacVariant variant stands for. */
static id variantToObject(const GmacVariant *variant)
{
    switch (variant->type) {
    case com_sun_glass_ui_mac_MacVariant_NSArray_id:
        return arrayOfElements(variant->long_array, variant->long_array_count, idElement);
    case com_sun_glass_ui_mac_MacVariant_NSArray_int:
        return arrayOfElements(variant->int_array, variant->int_array_count, intNumberElement);
    case com_sun_glass_ui_mac_MacVariant_NSObject:
        return (id)(intptr_t)variant->long1;
    case com_sun_glass_ui_mac_MacVariant_NSString:
        return GlassNSStringOrNil(variant->chars, variant->chars_length);
    case com_sun_glass_ui_mac_MacVariant_NSDictionary: {
        NSArray* keys = arrayOfElements(variant->long_array, variant->long_array_count, idElement);
        NSArray* values = arrayOfVariants(variant->variant_array, variant->variant_array_count);
        if (keys != NULL && values != NULL && [keys count] == [values count]) {
            return [NSDictionary dictionaryWithObjects: values forKeys: keys];
        }
        return NULL;
    }
    case com_sun_glass_ui_mac_MacVariant_NSAttributedString: {
        NSString* nsString = GlassNSStringOrNil(variant->chars, variant->chars_length);
        if (nsString == NULL) return NULL;
        NSMutableAttributedString *attrString = [[NSMutableAttributedString alloc] initWithString:nsString];
        for (int32_t index = 0; index < variant->variant_array_count; index++) {
            const GmacVariant *style = &variant->variant_array[index];
            id value = variantToObject(style);
            if (value) {
                if (style->key) {
                    [attrString addAttribute: (NSString *)style->key value: value
                                       range: NSMakeRange(style->location, style->length)];
                }
            }
        }
        return [attrString autorelease];
    }
    case com_sun_glass_ui_mac_MacVariant_NSValue_point:
        return [NSValue valueWithPoint: NSMakePoint(variant->float1, variant->float2)];
    case com_sun_glass_ui_mac_MacVariant_NSValue_size:
        return [NSValue valueWithSize: NSMakeSize(variant->float1, variant->float2)];
    case com_sun_glass_ui_mac_MacVariant_NSValue_rectangle:
        return [NSValue valueWithRect: NSMakeRect(variant->float1, variant->float2, variant->float3, variant->float4)];
    case com_sun_glass_ui_mac_MacVariant_NSValue_range:
        return [NSValue valueWithRange: NSMakeRange(variant->int1, variant->int2)];
    case com_sun_glass_ui_mac_MacVariant_NSNumber_Boolean:
        return [NSNumber numberWithBool: variant->int1];
    case com_sun_glass_ui_mac_MacVariant_NSNumber_Double:
        return [NSNumber numberWithDouble: variant->double1];
    case com_sun_glass_ui_mac_MacVariant_NSNumber_Int:
        return [NSNumber numberWithInt: variant->int1];
    case com_sun_glass_ui_mac_MacVariant_NSDate:
        return [NSDate dateWithTimeIntervalSince1970: variant->long1];
    }
    return NULL;
}

static void nullVariant(GmacVariant *variant)
{
    memset(variant, 0, sizeof(GmacVariant));
    variant->long_array_count = -1;
    variant->int_array_count = -1;
    variant->variant_array_count = -1;
}

static void freeVariantBlocks(GmacVariant *variant)
{
    free(variant->long_array);
    free(variant->int_array);
    free(variant->chars);
    for (int32_t index = 0; index < variant->variant_array_count; index++) {
        freeVariantBlocks(&variant->variant_array[index]);
    }
    free(variant->variant_array);
}

static id takeVariant(GmacVariant *variant)
{
    id object = variantToObject(variant);
    freeVariantBlocks(variant);
    return object;
}

static NSMutableArray *takeIds(void **ids, int32_t count)
{
    NSMutableArray *array = arrayOfElements(ids, count, idElement);
    free(ids);
    return array;
}

static NSArray *dialIds(int32_t (*slot)(int64_t, void ***, int32_t *), int64_t peerId)
{
    void **ids = NULL;
    int32_t count = -1;
    if (slot != NULL) {
        slot(peerId, &ids, &count);
    }
    return takeIds(ids, count);
}

id GlassDialAttributeValue(const GmacAccessibleCallbacks *callbacks, int64_t peerId, NSString *attribute)
{
    GmacVariant value;
    nullVariant(&value);
    if (callbacks->attribute_value != NULL) {
        callbacks->attribute_value(peerId, attribute, &value);
    }
    return takeVariant(&value);
}

id GlassDialAttributeValueForParameter(const GmacAccessibleCallbacks *callbacks, int64_t peerId, NSString *attribute,
                                       id parameter)
{
    GmacVariant value;
    nullVariant(&value);
    if (callbacks->attribute_value_for_parameter != NULL) {
        callbacks->attribute_value_for_parameter(peerId, attribute, parameter, &value);
    }
    return takeVariant(&value);
}

BOOL GlassDialIsAttributeSettable(const GmacAccessibleCallbacks *callbacks, int64_t peerId, NSString *attribute)
{
    int32_t settable = 0;
    if (callbacks->is_attribute_settable != NULL) {
        callbacks->is_attribute_settable(peerId, attribute, &settable);
    }
    return settable != 0;
}

void GlassDialSetValue(const GmacAccessibleCallbacks *callbacks, int64_t peerId, id value, NSString *attribute)
{
    if (callbacks->set_value != NULL) {
        callbacks->set_value(peerId, value, attribute);
    }
}

int32_t GlassDialPerformAction(const GmacAccessibleCallbacks *callbacks, int64_t peerId, NSString *action)
{
    return callbacks->perform_action != NULL ? callbacks->perform_action(peerId, action) : GMAC_UPCALL_OK;
}

void GlassDialPeerReleased(const GmacAccessibleCallbacks *callbacks, int64_t peerId)
{
    if (!GlassIsMainJavaAttached()) return;
    if (callbacks->peer_released != NULL) {
        callbacks->peer_released(peerId);
    }
}

@implementation GlassAccessible

- (id)initWithPeerId:(int64_t)identifier
{
    self = [super init];
    if (self != nil) {
        self->peerId = identifier;
    }
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

/* Attributes */

- (NSArray *)accessibilityAttributeNames
{
    GLASS_WARN_IF_MAIN_JAVA_DETACHED;
    const GmacAccessibleCallbacks *callbacks = GlassAccessibleCallbacks();
    if (!GlassIsMainJavaAttached() || callbacks == NULL) return NULL;
    return dialIds(callbacks->attribute_names, self->peerId);
}

- (id)accessibilityAttributeValue:(NSString *)attribute
{
    GLASS_WARN_IF_MAIN_JAVA_DETACHED;
    const GmacAccessibleCallbacks *callbacks = GlassAccessibleCallbacks();
    if (!GlassIsMainJavaAttached() || callbacks == NULL) return NULL;
    return GlassDialAttributeValue(callbacks, self->peerId, attribute);
}

- (BOOL)accessibilityIsAttributeSettable:(NSString *)attribute
{
    GLASS_WARN_IF_MAIN_JAVA_DETACHED;
    const GmacAccessibleCallbacks *callbacks = GlassAccessibleCallbacks();
    if (!GlassIsMainJavaAttached() || callbacks == NULL) return FALSE;
    return GlassDialIsAttributeSettable(callbacks, self->peerId, attribute);
}

- (void)accessibilitySetValue:(id)value forAttribute:(NSString *)attribute
{
    GLASS_WARN_IF_MAIN_JAVA_DETACHED;
    const GmacAccessibleCallbacks *callbacks = GlassAccessibleCallbacks();
    if (!GlassIsMainJavaAttached() || callbacks == NULL) return;
    GlassDialSetValue(callbacks, self->peerId, value, attribute);
}

- (NSUInteger)accessibilityIndexOfChild:(id)child
{
    GLASS_WARN_IF_MAIN_JAVA_DETACHED;
    const GmacAccessibleCallbacks *callbacks = GlassAccessibleCallbacks();
    if (!GlassIsMainJavaAttached() || callbacks == NULL) return 0;
    int64_t index = 0;
    if (callbacks->index_of_child != NULL) {
        callbacks->index_of_child(self->peerId, child, &index);
    }
    if (index == -1) return [super accessibilityIndexOfChild: child];
    return index;
}

/*
- (BOOL)accessibilitySetOverrideValue:(id)value forAttribute:(NSString *)attribute
*/

- (NSUInteger)accessibilityArrayAttributeCount:(NSString *)attribute
{
    GLASS_WARN_IF_MAIN_JAVA_DETACHED;
    const GmacAccessibleCallbacks *callbacks = GlassAccessibleCallbacks();
    if (!GlassIsMainJavaAttached() || callbacks == NULL) return -1;
    int32_t count = 0;
    if (callbacks->array_attribute_count != NULL) {
        callbacks->array_attribute_count(self->peerId, attribute, &count);
    }
    if (count == -1) return [super accessibilityArrayAttributeCount: attribute];
    return count;
}

- (NSArray *)accessibilityArrayAttributeValues:(NSString *)attribute index:(NSUInteger)index maxCount:(NSUInteger)maxCount
{
    GLASS_WARN_IF_MAIN_JAVA_DETACHED;
    const GmacAccessibleCallbacks *callbacks = GlassAccessibleCallbacks();
    if (!GlassIsMainJavaAttached() || callbacks == NULL) return NULL;
    void **ids = NULL;
    int32_t count = -1;
    if (callbacks->array_attribute_values != NULL) {
        callbacks->array_attribute_values(self->peerId, attribute, (int32_t)index, (int32_t)maxCount, &ids, &count);
    }
    if (count < 0) return [super accessibilityArrayAttributeValues: attribute index: index maxCount: maxCount];
    return takeIds(ids, count);
}

/* Parameterized Attributes */

- (NSArray *)accessibilityParameterizedAttributeNames
{
    GLASS_WARN_IF_MAIN_JAVA_DETACHED;
    const GmacAccessibleCallbacks *callbacks = GlassAccessibleCallbacks();
    if (!GlassIsMainJavaAttached() || callbacks == NULL) return NULL;
    return dialIds(callbacks->parameterized_attribute_names, self->peerId);
}

- (id)accessibilityAttributeValue:(NSString *)attribute forParameter:(id)parameter
{
    GLASS_WARN_IF_MAIN_JAVA_DETACHED;
    const GmacAccessibleCallbacks *callbacks = GlassAccessibleCallbacks();
    if (!GlassIsMainJavaAttached() || callbacks == NULL) return NULL;
    return GlassDialAttributeValueForParameter(callbacks, self->peerId, attribute, parameter);
}

/* Actions */

- (NSArray *)accessibilityActionNames
{
    GLASS_WARN_IF_MAIN_JAVA_DETACHED;
    const GmacAccessibleCallbacks *callbacks = GlassAccessibleCallbacks();
    if (!GlassIsMainJavaAttached() || callbacks == NULL) return NULL;
    return dialIds(callbacks->action_names, self->peerId);
}

- (NSString *)accessibilityActionDescription:(NSString *)action
{
    GLASS_WARN_IF_MAIN_JAVA_DETACHED;
    const GmacAccessibleCallbacks *callbacks = GlassAccessibleCallbacks();
    if (!GlassIsMainJavaAttached() || callbacks == NULL) return NULL;
    uint16_t *chars = NULL;
    int32_t length = 0;
    if (callbacks->action_description != NULL) {
        callbacks->action_description(self->peerId, action, &chars, &length);
    }
    NSString *description = GlassNSStringOrNil(chars, length);
    free(chars);
    return description;
}

- (void)accessibilityPerformAction:(NSString *)action
{
    GLASS_WARN_IF_MAIN_JAVA_DETACHED;
    const GmacAccessibleCallbacks *callbacks = GlassAccessibleCallbacks();
    if (!GlassIsMainJavaAttached() || callbacks == NULL) return;
    GlassDialPerformAction(callbacks, self->peerId, action);
}

/* Querying Elements */

- (BOOL)accessibilityIsIgnored
{
    GLASS_WARN_IF_MAIN_JAVA_DETACHED;
    const GmacAccessibleCallbacks *callbacks = GlassAccessibleCallbacks();
    if (!GlassIsMainJavaAttached() || callbacks == NULL) return FALSE;
    int32_t ignored = 0;
    if (callbacks->is_ignored != NULL) {
        callbacks->is_ignored(self->peerId, &ignored);
    }
    return ignored != 0;
}

- (id)accessibilityHitTest:(NSPoint)point
{
    GLASS_WARN_IF_MAIN_JAVA_DETACHED;
    const GmacAccessibleCallbacks *callbacks = GlassAccessibleCallbacks();
    if (!GlassIsMainJavaAttached() || callbacks == NULL) return NULL;
    void *element = NULL;
    if (callbacks->hit_test != NULL) {
        callbacks->hit_test(self->peerId, (float)point.x, (float)point.y, &element);
    }
    return (id)element;
}

- (id)accessibilityFocusedUIElement
{
    GLASS_WARN_IF_MAIN_JAVA_DETACHED;
    const GmacAccessibleCallbacks *callbacks = GlassAccessibleCallbacks();
    if (!GlassIsMainJavaAttached() || callbacks == NULL) return NULL;
    void *element = NULL;
    if (callbacks->focused_ui_element != NULL) {
        callbacks->focused_ui_element(self->peerId, &element);
    }
    return (id)element;
}

- (BOOL)accessibilityNotifiesWhenDestroyed
{
    return YES;
}

@end

int32_t gmac_a11y_id_to_variant(void *object, int32_t type, GmacVariant *out)
{
    nullVariant(out);
    out->type = type;

    switch (type) {

    case com_sun_glass_ui_mac_MacVariant_NSNumber_Boolean: {
        NSNumber* n = (NSNumber*)object;
        BOOL value = [n boolValue];
        out->int1 = (int32_t)value;
        break;
    }
    case com_sun_glass_ui_mac_MacVariant_NSNumber_Double: {
        NSNumber* n = (NSNumber*)object;
        out->double1 = [n doubleValue];
        break;
    }
    case com_sun_glass_ui_mac_MacVariant_NSNumber_Int: {
        NSNumber* n = (NSNumber*)object;
        out->int1 = [n intValue];
        break;
    }
    case com_sun_glass_ui_mac_MacVariant_NSString: {
        NSString* n = (NSString*)object;
        const char *utf8 = [n UTF8String];
        if (utf8 != NULL && GlassCopyUtf8(utf8, &out->utf8, &out->utf8_length) != GMAC_OK) {
            return GMAC_ERR_NO_DATA;
        }
        break;
    }
    case com_sun_glass_ui_mac_MacVariant_NSArray_id: {
        NSArray* n = (NSArray*)object;
        NSUInteger count = [n count];
        void **data = count > 0 ? malloc(count * sizeof(void *)) : NULL;
        if (count > 0 && data == NULL) return GMAC_ERR_NO_DATA;
        for (NSUInteger index = 0; index < count; index++) {
            data[index] = [n objectAtIndex: index];
        }
        out->long_array = data;
        out->long_array_count = (int32_t)count;
        break;
    }
    case com_sun_glass_ui_mac_MacVariant_NSArray_int: {
        NSArray* n = (NSArray*)object;
        NSUInteger count = [n count];
        int32_t *data = count > 0 ? malloc(count * sizeof(int32_t)) : NULL;
        if (count > 0 && data == NULL) return GMAC_ERR_NO_DATA;
        for (NSUInteger index = 0; index < count; index++) {
            NSNumber* value = (NSNumber*)[n objectAtIndex: index];
            data[index] = [value intValue];
        }
        out->int_array = data;
        out->int_array_count = (int32_t)count;
        break;
    }
    case com_sun_glass_ui_mac_MacVariant_NSValue_point: {
        NSValue* n = (NSValue*)object;
        NSPoint value = [n pointValue];
        out->float1 = value.x;
        out->float2 = value.y;
        break;
    }
    case com_sun_glass_ui_mac_MacVariant_NSValue_size: {
        NSValue* n = (NSValue*)object;
        NSSize value = [n sizeValue];
        out->float1 = value.width;
        out->float2 = value.height;
        break;
    }
    case com_sun_glass_ui_mac_MacVariant_NSValue_rectangle: {
        NSValue* n = (NSValue*)object;
        NSRect value = [n rectValue];
        out->float1 = value.origin.x;
        out->float2 = value.origin.y;
        out->float3 = value.size.width;
        out->float4 = value.size.height;
        break;
    }
    case com_sun_glass_ui_mac_MacVariant_NSValue_range: {
        NSValue* n = (NSValue*)object;
        NSRange value = [n rangeValue];
        out->int1 = (int32_t)value.location;
        out->int2 = (int32_t)value.length;
        break;
    }
    }
    return GMAC_OK;
}

int32_t gmac_a11y_unignored_children(void *const *ids, int32_t count, void ***out_ids, int32_t *out_count)
{
    *out_ids = NULL;
    *out_count = 0;
    NSArray* children = arrayOfElements(ids, count, idElement);
    if (children == NULL) return GMAC_ERR_NO_DATA;
    NSArray* n = NSAccessibilityUnignoredChildren(children);
    NSUInteger total = [n count];
    void **data = total > 0 ? malloc(total * sizeof(void *)) : NULL;
    if (total > 0 && data == NULL) return GMAC_ERR_NO_DATA;
    for (NSUInteger index = 0; index < total; index++) {
        data[index] = [n objectAtIndex: index];
    }
    *out_ids = data;
    *out_count = (int32_t)total;
    return GMAC_OK;
}

int64_t gmac_a11y_peer_id(void *object)
{
    GlassAccessible* accessible = (GlassAccessible*)object;
    return accessible ? [accessible getPeerId] : 0;
}

int32_t gmac_test_a11y_variant_to_object(GmacVariant *variant, void **out_object)
{
    id object = nil;
    GLASS_POOL_ENTER;
    {
        object = [takeVariant(variant) retain];
    }
    GLASS_POOL_EXIT;
    *out_object = object;
    return object != nil ? GMAC_OK : GMAC_ERR_NO_DATA;
}

int32_t gmac_test_a11y_utf8_string(void *string, char **out_utf8, int32_t *out_length)
{
    int32_t status;
    GLASS_POOL_ENTER;
    {
        status = GlassCopyUtf8([(NSString *)string UTF8String], out_utf8, out_length);
    }
    GLASS_POOL_EXIT;
    return status;
}

int32_t gmac_test_a11y_is_equal_to_string(void *string1, void *string2)
{
    BOOL equal;
    GLASS_POOL_ENTER;
    {
        equal = [(NSString *)string1 isEqualToString: (NSString *)string2];
    }
    GLASS_POOL_EXIT;
    return equal ? 1 : 0;
}

/*
 * What the former _initEnum stored in the ptr of the enum constant called name: for an "AX" name a new NSString of
 * it, +1, which it never released, else the value of bundle's data symbol of that name. NO where _initEnum answered
 * false.
 */
static BOOL enumConstantPointer(CFBundleRef bundle, NSString *name, CFStringRef *out)
{
    NSRange range = [name rangeOfString: @"AX"];
    CFStringRef *data;
    if (range.location == 0) {
        name = [[NSString alloc] initWithString: name];
        data = (CFStringRef*)&name;
    } else {
        data = CFBundleGetDataPointerForName(bundle, (CFStringRef)name);
    }
    if (data == NULL) return NO;
    *out = *data;
    return YES;
}

int32_t gmac_test_a11y_enum_pointer(const uint16_t *name, int32_t length, void **out_pointer)
{
    BOOL found = NO;
    *out_pointer = NULL;
    GLASS_POOL_ENTER;
    {
        NSString *nsName = GlassNSStringOrNil(name, length);
        CFBundleRef bundle = CFBundleGetBundleWithIdentifier(CFSTR("com.apple.Cocoa"));
        CFStringRef pointer = NULL;
        if (nsName != NULL && bundle != NULL && enumConstantPointer(bundle, nsName, &pointer)) {
            *out_pointer = (void *)pointer;
            found = YES;
        }
    }
    GLASS_POOL_EXIT;
    return found ? 1 : 0;
}
