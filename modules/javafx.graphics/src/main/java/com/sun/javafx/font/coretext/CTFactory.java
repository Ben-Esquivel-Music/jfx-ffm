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

package com.sun.javafx.font.coretext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import com.sun.javafx.font.FontFallbackInfo;
import com.sun.javafx.font.FontResource;
import com.sun.javafx.font.PrismFontFactory;
import com.sun.javafx.font.PrismFontFile;
import com.sun.javafx.text.GlyphLayout;

public class CTFactory extends PrismFontFactory {

    public static PrismFontFactory getFactory() {
        return new CTFactory();
    }

    /* The key tells a file replaced by a rename from the file it replaced, which can keep its time and size. */
    private record FileVersion(Object key, FileTime modified, long size) {
    }

    /* A face name, lower-cased, and the file a run font of that name reports, null where it reports none. */
    private record FaceKey(String name, String file) {
    }

    /* The version a miss of a font that reports no file is kept with, which no file has. */
    private static final FileVersion NO_FILE = new FileVersion(null, null, -1);

    /*
     * The version of each file a lookup found no face in, NO_FILE for a font that reports no file. Two files can hold
     * faces of one name, so each keeps its own miss; a new version of a file replaces its miss.
     */
    private final ConcurrentHashMap<FaceKey, FileVersion> misses = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<FaceKey, FaceLookup> lookups = new ConcurrentHashMap<>();

    CTFactory() {
    }

    /*
     * False only where a lookup found no face of the name in file, and that file is still in the version it read; for
     * a font that reports no file, false once a lookup found none.
     */
    boolean mayFindFace(String name, String file) {
        FileVersion missed = misses.get(faceKey(name, file));
        if (missed == null) {
            return true;
        }
        return file != null && !missed.equals(versionOf(file));
    }

    /*
     * The lookup of the face in that file that is in flight, or null where lookup is now the one, which the caller
     * must end. A map that throws may have taken lookup in already, so lookup is ended before the throw.
     */
    FaceLookup beginLookup(FaceLookup lookup) {
        try {
            return lookups.putIfAbsent(lookup.key, lookup);
        } catch (Throwable t) {
            endLookup(lookup, null);
            throw t;
        }
    }

    /*
     * Removed before the waiters are woken, so that a caller that comes afterwards begins a lookup of its own. The key
     * was made with the lookup, so that nothing here allocates it.
     */
    void endLookup(FaceLookup lookup, FontResource font) {
        try {
            lookups.remove(lookup.key, lookup);
        } finally {
            lookup.finish(font);
        }
    }

    /*
     * This is needed for fonts where the name starts with "."
     * It isn't just the ".SF" "System Font", but related fallbacks which
     * layout returns and we need to add as a native fallback to a composite font.
     *
     * fontRef is a +1 reference that this consumes: the font file's disposer owns it
     * once the constructor returns, and on every other exit it is released here.
     * A zero reference is not released.
     * filename is the file the run font reports, null where it reports none.
     * A file that cannot be read throws, and nothing is remembered, so a later layout looks the face up again.
     */
    CTFontFile createFontFile(String name, String filename, long fontRef) throws Exception {
        CTFontFile font = null;
        try {
            FileVersion version = filename == null ? null : versionOf(filename);
            // A font that reports no file never will: it is remembered as a file without the face is.
            int fIndex = filename == null ? -1 : findFontIndex(name, filename);

            if (debugFonts) {
                System.err.println("createFontFile by ref name="+name + " filename="+filename+" index="+fIndex);
            }
            if (fIndex != -1) {
                font = new CTFontFile(name, filename, fIndex, fontRef);
            } else if (filename == null) {
                misses.put(faceKey(name, null), NO_FILE);
            } else if (version != null && version.equals(versionOf(filename))) {
                // A file that changed while it was read may hold the face in the version a later layout reads.
                misses.put(faceKey(name, filename), version);
            }
        } finally {
            if (font == null && fontRef != 0) {
                OS.CFRelease(fontRef);
            }
        }
        if (font != null) {
            // Store the lookup name in the map.
            // This should prevent us needing to create new instances.
            storeInMap(name, font);
        }
        return font;
    }

    private static FaceKey faceKey(String name, String file) {
        return new FaceKey(name.toLowerCase(Locale.ROOT), file);
    }

    /* Null where the file cannot be read, so that no miss is remembered for it and none remembered holds. */
    private static FileVersion versionOf(String file) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(Path.of(file), BasicFileAttributes.class);
            return new FileVersion(attributes.fileKey(), attributes.lastModifiedTime(), attributes.size());
        } catch (IOException | InvalidPathException _) {
            return null;
        }
    }

    /* A lookup of a fallback face in a file in flight; the callers that need it meanwhile wait for what it finds. */
    static final class FaceLookup {

        private final FaceKey key;
        private final CountDownLatch done = new CountDownLatch(1);
        // Written before the count-down, which publishes it to every caller the latch lets through.
        private FontResource font;

        /* file is the one the run font reports, null where it reports none. */
        FaceLookup(String name, String file) {
            key = faceKey(name, file);
        }

        /*
         * The font the lookup found, or null. An interrupt does not end the wait, which would lay the run out
         * without its face, and is set again after it.
         */
        FontResource await() {
            boolean interrupted = false;
            try {
                while (true) {
                    try {
                        done.await();
                        return font;
                    } catch (InterruptedException _) {
                        interrupted = true;
                    }
                }
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private void finish(FontResource found) {
            font = found;
            done.countDown();
        }
    }

    @Override
    protected PrismFontFile createFontFile(String name, String filename,
            int fIndex, boolean register, boolean embedded, boolean copy)
            throws Exception {
        return new CTFontFile(name, filename, fIndex, register,
                              embedded, copy);
    }

    @Override
    public GlyphLayout createGlyphLayout() {
        return new CTGlyphLayout();
    }

    @Override
    protected boolean registerEmbeddedFont(String path) {
        boolean result = CTFontFile.registerFont(path);
        if (debugFonts) {
            if (result) {
                System.err.println("[CoreText] Font registration succeeded:" + path);
            } else {
                System.err.println("[CoreText] Font registration failed:" + path);
            }
        }
        return result;
    }

    /*
     * This should prevent enumeration of special fonts, but also should
     * be used to prevent public lookup of these even if not enumerated
     */
    @Override
    public boolean isExcluded(String name) {
         return name.startsWith(".") || name.startsWith("System Font");
    }

    @Override
    public FontFallbackInfo getFallbacks(FontResource primaryResource) {
        FontFallbackInfo info = new FontFallbackInfo();
        ArrayList<String> names;
        ArrayList<String> files;
        ArrayList<String> fonts;

        // First add cascading font list.
        if (primaryResource instanceof CTFontFile ctff) {
           ctff.getCascadingInfo(info);
        }
        // ELSE: REMIND we can get the default cascading list for this case.
        // Do this only if we find we end up here, which I think unlikely today.

        String name = "System Regular"; // a default
        boolean bold = false;
        if (primaryResource != null) {
            name = primaryResource.getFullName();
            bold = primaryResource.isBold();
        }

        // The "." fonts for Japanese, Korean, Simplified Chinese, HK Chinese, TW Chinese
        // do not report a file, so out of caution, let's add some known fallbacks used
        // by Arial and Arial Bold for these scripts.
        // Note: even if "." fonts do report a file, macOS does not like apps looking
        // them up that way.
        if (name.startsWith("System ")) {
           if (!bold) {
               info.add("Hiragino Sans W3", "/System/Library/Fonts/ヒラギノ角ゴシック W3.ttc", null);
               info.add("Hiragino Sans GB W3", "/System/Library/Fonts/Hiragino Sans GB.ttc", null);
               info.add("Apple SD Gothic Neo Regular", "/System/Library/Fonts/AppleSDGothicNeo.ttc", null);
               info.add("PingFang SC Regular", "/System/Library/Fonts/PingFang.ttc", null);
               info.add("PingFang TC Regular", "/System/Library/Fonts/PingFang.ttc", null);
               info.add("PingFang HK Regular", "/System/Library/Fonts/PingFang.ttc", null);
           } else {
               info.add("Hiragino Sans W6", "/System/Library/Fonts/ヒラギノ角ゴシック W6.ttc", null);
               info.add("Hiragino Sans GB W6", "System/Library/Fonts/Hiragino Sans GB.ttc", null);
               info.add("Apple SD Gothic Neo Bold", "/System/Library/Fonts/AppleSDGothicNeo.ttc", null);
               info.add("PingFang SC Semibold", "/System/Library/Fonts/PingFang.ttc", null);
               info.add("PingFang TC Semibold", "/System/Library/Fonts/PingFang.ttc", null);
               info.add("PingFang HK Semibold", "/System/Library/Fonts/PingFang.ttc", null);
           }
        }

        // Now add hardwired fall backs in case of problems with the above.
        info.add("Apple Symbols", "/System/Library/Fonts/Apple Symbols.ttf", null);
        info.add("Apple Color Emoji", "/System/Library/Fonts/Apple Color Emoji.ttc", null);
        info.add("Arial Unicode MS", "/Library/Fonts/Arial Unicode.ttf", null);
        // Add CJK Ext B supplementary characters.
        info.add("Heiti SC Light", "/System/Library/Fonts/STHeiti Light.ttc", null);

        return info;
    }
}
