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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.LockSupport;

import com.sun.javafx.font.coretext.CFNative;
import com.sun.javafx.font.coretext.CTNative;
import com.sun.javafx.font.coretext.ObjCNative;

/**
 * The macOS system fonts, font files and cascade lists, from CoreText; once JNI natives in {@code MacFontFinder.c}.
 * "The C" here is that file at commit {@code 28b02ef511}
 * ({@code git show 28b02ef511:modules/javafx.graphics/src/main/native-font/MacFontFinder.c}).
 */
public class MacFontFinder {

    private static final int SystemFontType = 2; /*kCTFontSystemFontType*/
    private static final int MonospacedFontType = 1; /*kCTFontUserFixedPitchFontType*/
    // PrismFontFactory's size on the other desktops: its -1 means "not asked yet" and 0 would be cached as the size.
    private static final float SIZE_WITHOUT_A_UI_FONT = 13f;

    /** The tasks of the one platform thread that makes the cascade lists of virtual callers, run in their order. */
    private static final ConcurrentLinkedQueue<Runnable> CASCADE_LIST_TASKS = new ConcurrentLinkedQueue<>();
    private static final Object CASCADE_LIST_WORKER_LOCK = new Object();
    private static volatile Thread cascadeListWorker;

    private static String getFont(int type) {
        MemorySegment font = CTNative.CTFontCreateUIFontForLanguage(type, 0, MemorySegment.NULL);
        if (font.address() == 0) {
            return null;
        }
        try {
            return familyName(CTNative.CTFontCopyFamilyName(font));
        } finally {
            CFNative.CFRelease(font);
        }
    }

    /** The string of the +1 family name of a font, which this releases; null when the font has none. */
    private static String familyName(MemorySegment family) {
        if (family.address() == 0) {
            return null;
        }
        try {
            return CFNative.cfToString(family);
        } finally {
            CFNative.CFRelease(family);
        }
    }

    public static String getSystemFont() {
        return getFont(SystemFontType);
    }

    public static String getMonospacedFont() {
        return getFont(MonospacedFontType);
    }

    static float getSystemFontSize() {
        return sizeOf(CTNative.CTFontCreateUIFontForLanguage(CTNative.kCTFontUIFontSystem, 0.0, MemorySegment.NULL));
    }

    /** The size of the +1 UI font, which this releases; {@link #SIZE_WITHOUT_A_UI_FONT} when there is none. */
    private static float sizeOf(MemorySegment font) {
        if (font.address() == 0) {
            return SIZE_WITHOUT_A_UI_FONT;
        }
        try {
            return (float) CTNative.CTFontGetSize(font);
        } finally {
            CFNative.CFRelease(font);
        }
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
        return fontData(matchingDescriptors(collection));
    }

    /** The +1 descriptors of the +1 collection, which this releases; NULL for a NULL collection. */
    private static MemorySegment matchingDescriptors(MemorySegment collection) {
        if (collection.address() == 0) {
            return MemorySegment.NULL;
        }
        try {
            return CTNative.CTFontCollectionCreateMatchingFontDescriptors(collection);
        } finally {
            CFNative.CFRelease(collection);
        }
    }

    /**
     * Name, family and file per font of the +1 descriptor array, which this releases, then of the two UI fonts,
     * which the collection does not list; a NULL array lists the UI fonts alone.
     */
    private static String[] fontData(MemorySegment fonts) {
        String[] result;
        int index = 0;
        try {
            long count = fonts.address() == 0 ? 0 : CFNative.CFArrayGetCount(fonts);
            result = new String[(int) ((count + 2) * 3)];
            for (long i = 0; i < count; i++) {
                index = addDescriptor(CFNative.CFArrayGetValueAtIndex(fonts, i), result, index);
            }
        } finally {
            release(fonts);
        }
        for (int type : new int[] {CTNative.kCTFontUIFontSystem, CTNative.kCTFontUIFontEmphasizedSystem}) {
            index = addUiFont(CTNative.CTFontCreateUIFontForLanguage(type, 0, MemorySegment.NULL), result, index);
        }
        return result;
    }

    /** {@link #addDescriptor} for the descriptor of the +1 UI font, which this releases; NULL adds nothing. */
    private static int addUiFont(MemorySegment font, String[] result, int index) {
        if (font.address() == 0) {
            return index;
        }
        try {
            return addOwnedDescriptor(CTNative.CTFontCopyFontDescriptor(font), result, index);
        } finally {
            CFNative.CFRelease(font);
        }
    }

    /** {@link #addDescriptor} for a +1 descriptor, which this releases; NULL adds nothing. */
    private static int addOwnedDescriptor(MemorySegment descriptor, String[] result, int index) {
        if (descriptor.address() == 0) {
            return index;
        }
        try {
            return addDescriptor(descriptor, result, index);
        } finally {
            CFNative.CFRelease(descriptor);
        }
    }

    private static int addDescriptor(MemorySegment descriptor, String[] result, int index) {
        if (descriptor.address() == 0) {
            return index;
        }
        MemorySegment name = MemorySegment.NULL;
        MemorySegment family = MemorySegment.NULL;
        MemorySegment url = MemorySegment.NULL;
        MemorySegment file = MemorySegment.NULL;
        try {
            name = CTNative.CTFontDescriptorCopyAttribute(descriptor, CTNative.kCTFontDisplayNameAttribute);
            family = CTNative.CTFontDescriptorCopyAttribute(descriptor, CTNative.kCTFontFamilyNameAttribute);
            url = CTNative.CTFontDescriptorCopyAttribute(descriptor, CTNative.kCTFontURLAttribute);
            if (url.address() != 0) {
                file = CFNative.CFURLCopyFileSystemPath(url, CFNative.kCFURLPOSIXPathStyle);
            }
            int next = index;
            if (name.address() != 0 && family.address() != 0 && file.address() != 0) {
                result[next++] = CFNative.cfToString(name);
                result[next++] = CFNative.cfToString(family);
                result[next++] = CFNative.cfToString(file);
            }
            return next;
        } finally {
            release(name);
            release(family);
            release(url);
            release(file);
        }
    }

    /**
     * Display name and file per cascade entry; none for a zero font or when CoreText gives no list. What CoreText
     * autoreleases during the call is drained before it returns, on any thread. A virtual caller that cannot start the
     * platform thread that makes its list gets that error, and the next virtual caller tries to start it again.
     */
    public static String[] getCascadeList(long fontRef) {
        if (fontRef == 0) {
            return new String[0];
        }
        MemorySegment font = ptr(fontRef);
        if (Thread.currentThread().isVirtual()) {
            return cascadeListOnAPlatformThread(font);
        }
        return pooledCascadeList(font);
    }

    /**
     * {@link #cascadeList} of the borrowed font, with every downcall and release inside an autorelease pool of this
     * thread; only Java strings leave the pool.
     */
    private static String[] pooledCascadeList(MemorySegment font) {
        long pool = ObjCNative.objc_autoreleasePoolPush();
        try {
            MemorySegment codes = CFNative.CFLocaleCopyISOLanguageCodes();
            return cascadeList(font, codes);
        } finally {
            ObjCNative.objc_autoreleasePoolPop(pool);
        }
    }

    /**
     * {@link #pooledCascadeList} on the one platform thread that makes the lists of virtual callers, waited for. A
     * pool must be popped on the thread that pushed it, and a virtual thread can move to another carrier between the
     * downcalls. The task holds a reference of its own to the font, so the caller may release the font it lent
     * however its wait ends.
     */
    private static String[] cascadeListOnAPlatformThread(MemorySegment font) {
        Thread worker = startCascadeListWorker();
        CascadeListTask task = new CascadeListTask(font);
        queueCascadeListTask(worker, task, font);
        return task.awaitList();
    }

    /**
     * The thread of {@link #runCascadeListTasks}, started unless it runs; what starting it throws if it cannot, with
     * no thread recorded, so that the next caller tries again.
     */
    private static Thread startCascadeListWorker() {
        Thread worker = cascadeListWorker;
        if (worker != null) {
            return worker;
        }
        synchronized (CASCADE_LIST_WORKER_LOCK) {
            if (cascadeListWorker == null) {
                Thread started = Thread.ofPlatform().name("JavaFX font cascade list").daemon()
                        .inheritInheritableThreadLocals(false).unstarted(MacFontFinder::runCascadeListTasks);
                started.start();
                cascadeListWorker = started;
            }
            return cascadeListWorker;
        }
    }

    /**
     * Queues {@code task} for {@code worker} and wakes it, with a reference to {@code font} taken for the task, which
     * releases it once it has run; {@code font} is NULL for a task that holds none. If the task does not enter the
     * queue, that reference is released here and what the queue threw is rethrown; once it has entered, it is the
     * task's.
     */
    private static void queueCascadeListTask(Thread worker, Runnable task, MemorySegment font) {
        if (font.address() != 0) {
            CFNative.CFRetain(font);
        }
        try {
            CASCADE_LIST_TASKS.add(task);
        } catch (Throwable notQueued) {
            // No pool is needed: the caller still holds the font it lent, so this reference is never the last.
            release(font);
            throw notQueued;
        }
        LockSupport.unpark(worker);
    }

    /** The loop of the cascade-list thread, for the life of the process. */
    private static void runCascadeListTasks() {
        while (true) {
            Runnable task = CASCADE_LIST_TASKS.poll();
            if (task == null) {
                LockSupport.park();
                // Virtual callers wait on this thread: an interrupt may neither end the loop nor keep it from parking.
                Thread.interrupted();
            } else {
                try {
                    task.run();
                } catch (Throwable _) {
                    // A task keeps what it throws for its caller; one that did not may not end the thread every
                    // later virtual caller waits for.
                }
            }
        }
    }

    /**
     * Display name and file per cascade entry of the borrowed font for the +1 language codes, which this releases;
     * none for NULL codes or a NULL font, without asking CoreText.
     */
    private static String[] cascadeList(MemorySegment font, MemorySegment codes) {
        if (codes.address() == 0) {
            return new String[0];
        }
        MemorySegment descriptors = MemorySegment.NULL;
        try {
            if (font.address() != 0) {
                descriptors = CTNative.CTFontCopyDefaultCascadeListForLanguages(font, codes);
            }
        } finally {
            CFNative.CFRelease(codes);
        }
        if (descriptors.address() == 0) {
            return new String[0];
        }
        try {
            long count = CFNative.CFArrayGetCount(descriptors);
            String[] names = new String[(int) (count * 2)];
            for (int i = 0; i < count; i++) {
                addCascadeEntry(CFNative.CFArrayGetValueAtIndex(descriptors, i), names, 2 * i);
            }
            return names;
        } finally {
            CFNative.CFRelease(descriptors);
        }
    }

    private static void addCascadeEntry(MemorySegment descriptor, String[] names, int index) {
        MemorySegment name = MemorySegment.NULL;
        MemorySegment displayName = MemorySegment.NULL;
        MemorySegment url = MemorySegment.NULL;
        MemorySegment file = MemorySegment.NULL;
        try {
            name = CTNative.CTFontDescriptorCopyAttribute(descriptor, CTNative.kCTFontNameAttribute);
            displayName = CTNative.CTFontDescriptorCopyAttribute(descriptor, CTNative.kCTFontDisplayNameAttribute);
            url = CTNative.CTFontDescriptorCopyAttribute(descriptor, CTNative.kCTFontURLAttribute);
            if (url.address() != 0) {
                file = CFNative.CFURLCopyFileSystemPath(url, CFNative.kCFURLPOSIXPathStyle);
            }
            names[index] = CFNative.cfToString(displayName);
            names[index + 1] = CFNative.cfToString(file);
        } finally {
            release(displayName);
            release(file);
            release(url);
            release(name);
        }
    }

    private static void release(MemorySegment object) {
        if (object.address() != 0) {
            CFNative.CFRelease(object);
        }
    }

    private static MemorySegment ptr(long address) {
        return MemorySegment.ofAddress(address);
    }

    /**
     * A virtual caller's cascade list, made on the cascade-list thread from the reference to the font that
     * {@link #queueCascadeListTask} took for it.
     */
    private static final class CascadeListTask implements Runnable {

        private final Thread caller = Thread.currentThread();
        private final MemorySegment font;
        private String[] list;
        private Throwable failure;
        private volatile boolean done;

        CascadeListTask(MemorySegment font) {
            this.font = font;
        }

        /** Throws nothing: what the read or the release throws is kept for the caller, who is woken either way. */
        @Override
        public void run() {
            try {
                list = pooledCascadeList(font);
            } catch (Throwable t) {
                failure = t;
            }
            try {
                releaseFont();
            } catch (Throwable t) {
                if (failure == null) {
                    failure = t;
                } else if (failure != t) {
                    try {
                        failure.addSuppressed(t);
                    } catch (Throwable _) {
                        // The read's failure is the one the caller needs; attaching this one may not stop the wake-up.
                    }
                }
            }
            // After the release, so that a caller that still waits holds the font when this reference goes.
            done = true;
            LockSupport.unpark(caller);
        }

        private void releaseFont() {
            // A caller whose wait failed may have released the font already; what freeing it here autoreleases
            // would otherwise stay on this thread for the life of the process.
            long pool = ObjCNative.objc_autoreleasePoolPush();
            try {
                CFNative.CFRelease(font);
            } finally {
                ObjCNative.objc_autoreleasePoolPop(pool);
            }
        }

        /** The list, or what making it threw; an interrupt does not end the wait, and is set again after it. */
        String[] awaitList() {
            boolean interrupted = false;
            try {
                while (!done) {
                    LockSupport.park(this);
                    // Recorded before it is cleared, so that no error inside the clearing can lose it.
                    if (Thread.currentThread().isInterrupted()) {
                        interrupted = true;
                        Thread.interrupted();
                    }
                }
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            if (failure instanceof Error error) {
                throw error;
            }
            if (failure != null) {
                throw (RuntimeException) failure;
            }
            return list;
        }
    }
}

