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

package com.sun.javafx.font;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;

import com.sun.javafx.font.coretext.CFNative;
import com.sun.javafx.font.coretext.CTNative;

/**
 * The macOS system fonts, font files and cascade lists, from CoreText; once JNI natives in {@code MacFontFinder.c}.
 * "The C" here is that file at commit {@code 28b02ef511}
 * ({@code git show 28b02ef511:modules/javafx.graphics/src/main/native-font/MacFontFinder.c}).
 */
public class MacFontFinder {

    private static final int SystemFontType = 2; /*kCTFontSystemFontType*/
    private static final int MonospacedFontType = 1; /*kCTFontUserFixedPitchFontType*/
    private static String getFont(int type) {
        MemorySegment font = CTNative.CTFontCreateUIFontForLanguage(type, 0, MemorySegment.NULL);
        MemorySegment family = CTNative.CTFontCopyFamilyName(font);
        String name = CFNative.cfToString(family);
        CFNative.CFRelease(family);
        CFNative.CFRelease(font);
        return name;
    }

    public static String getSystemFont() {
        return getFont(SystemFontType);
    }

    public static String getMonospacedFont() {
        return getFont(MonospacedFontType);
    }

    static float getSystemFontSize() {
        MemorySegment font = CTNative.CTFontCreateUIFontForLanguage(CTNative.kCTFontUIFontSystem, 0.0,
                                                                    MemorySegment.NULL);
        float size = (float) CTNative.CTFontGetSize(font);
        CFNative.CFRelease(font);
        return size;
    }

    public static boolean populateFontFileNameMap(
            HashMap<String,String> fontToFileMap,
            HashMap<String,String> fontToFamilyNameMap,
            HashMap<String,ArrayList<String>> familyToFontListMap,
            Locale locale) {

        if (fontToFileMap == null ||
            fontToFamilyNameMap == null ||
            familyToFontListMap == null) {
            return false;
        }
        if (locale == null) {
            locale = Locale.ENGLISH;
        }
        String[] fontData = getFontData();
        if (fontData == null) return false;

        int i = 0;
        while (i < fontData.length) {
            String name = fontData[i++];
            String family = fontData[i++];
            String file = fontData[i++];

            if (PrismFontFactory.debugFonts) {
                System.err.println("[MacFontFinder] Name=" + name);
                System.err.println("\tFamily=" + family);
                System.err.println("\tFile=" + file);
            }

            if (name == null || family == null || file == null) {
                continue;
            }
            String lcName = name.toLowerCase(locale);
            String lcFamily = family.toLowerCase(locale);
            fontToFileMap.put(lcName, file);
            fontToFamilyNameMap.put(lcName, family);
            ArrayList<String> list = familyToFontListMap.get(lcFamily);
            if (list == null) {
                list = new ArrayList<>();
                familyToFontListMap.put(lcFamily, list);
            }
            list.add(name);
        }
        return true;
    }
    /*
     *
     * @param familyName
     * @return array of post-script font names
     */
    private static String[] getFontData() {
        MemorySegment collection = CTNative.CTFontCollectionCreateFromAvailableFonts(MemorySegment.NULL);
        MemorySegment fonts = CTNative.CTFontCollectionCreateMatchingFontDescriptors(collection);
        CFNative.CFRelease(collection);
        long count = CFNative.CFArrayGetCount(fonts);
        String[] result = new String[(int) ((count + 2) * 3)];
        int index = 0;
        for (long i = 0; i < count; i++) {
            index = addDescriptor(CFNative.CFArrayGetValueAtIndex(fonts, i), result, index);
        }
        CFNative.CFRelease(fonts);
        for (int type : new int[] {CTNative.kCTFontUIFontSystem, CTNative.kCTFontUIFontEmphasizedSystem}) {
            MemorySegment font = CTNative.CTFontCreateUIFontForLanguage(type, 0, MemorySegment.NULL);
            MemorySegment descriptor = CTNative.CTFontCopyFontDescriptor(font);
            index = addDescriptor(descriptor, result, index);
            CFNative.CFRelease(descriptor);
            CFNative.CFRelease(font);
        }
        return result;
    }

    private static int addDescriptor(MemorySegment descriptor, String[] result, int index) {
        if (descriptor.address() == 0) {
            return index;
        }
        MemorySegment name = CTNative.CTFontDescriptorCopyAttribute(descriptor, CTNative.kCTFontDisplayNameAttribute);
        MemorySegment family = CTNative.CTFontDescriptorCopyAttribute(descriptor, CTNative.kCTFontFamilyNameAttribute);
        MemorySegment url = CTNative.CTFontDescriptorCopyAttribute(descriptor, CTNative.kCTFontURLAttribute);
        MemorySegment file = url.address() == 0 ? MemorySegment.NULL
                : CFNative.CFURLCopyFileSystemPath(url, CFNative.kCFURLPOSIXPathStyle);
        int next = index;
        if (name.address() != 0 && family.address() != 0 && file.address() != 0) {
            result[next++] = CFNative.cfToString(name);
            result[next++] = CFNative.cfToString(family);
            result[next++] = CFNative.cfToString(file);
        }
        for (MemorySegment object : new MemorySegment[] {name, family, url, file}) {
            if (object.address() != 0) {
                CFNative.CFRelease(object);
            }
        }
        return next;
    }

    /** Display name and file per cascade entry; the name and URL copies are leaked, as the C leaked them. */
    public static String[] getCascadeList(long fontRef) {
        MemorySegment codes = CFNative.CFLocaleCopyISOLanguageCodes();
        MemorySegment descriptors = CTNative.CTFontCopyDefaultCascadeListForLanguages(ptr(fontRef), codes);
        CFNative.CFRelease(codes);
        long count = CFNative.CFArrayGetCount(descriptors);
        String[] names = new String[(int) (count * 2)];
        for (int i = 0; i < count; i++) {
            MemorySegment descriptor = CFNative.CFArrayGetValueAtIndex(descriptors, i);
            CTNative.CTFontDescriptorCopyAttribute(descriptor, CTNative.kCTFontNameAttribute);
            MemorySegment displayName = CTNative.CTFontDescriptorCopyAttribute(descriptor,
                                                                               CTNative.kCTFontDisplayNameAttribute);
            MemorySegment url = CTNative.CTFontDescriptorCopyAttribute(descriptor, CTNative.kCTFontURLAttribute);
            MemorySegment file = url.address() == 0 ? MemorySegment.NULL
                    : CFNative.CFURLCopyFileSystemPath(url, CFNative.kCFURLPOSIXPathStyle);
            names[2 * i] = CFNative.cfToString(displayName);
            CFNative.CFRelease(displayName);
            names[2 * i + 1] = CFNative.cfToString(file);
            if (file.address() != 0) {
                CFNative.CFRelease(file);
            }
        }
        CFNative.CFRelease(descriptors);
        return names;
    }

    private static MemorySegment ptr(long address) {
        return MemorySegment.ofAddress(address);
    }
}

