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
package com.sun.glass.ui.mac;

import java.util.HashMap;

final class MacPasteboard {

    final static public int General = 1;
    final static public int DragAndDrop = 2;

    final static public int UtfIndex = 0;
    final static public int ObjectIndex = 1;

    final static public String UtfString = "public.utf8-plain-text";
    final static public String UtfPdf = "com.adobe.pdf";
    final static public String UtfTiff = "public.tiff";
    final static public String UtfPng = "public.png";
    final static public String UtfRtf = "public.rtf";
    final static public String UtfRtfd = "com.apple.flat-rtfd";
    final static public String UtfHtml = "public.html";
    final static public String UtfTabularText = "public.utf8-tab-separated-values-text";
    final static public String UtfFont = "com.apple.cocoa.pasteboard.character-formatting";
    final static public String UtfColor = "com.apple.cocoa.pasteboard.color";
    final static public String UtfSound = "com.apple.cocoa.pasteboard.sound";
    final static public String UtfMultipleTextSelection = "com.apple.cocoa.pasteboard.multiple-text-selection";
    final static public String UtfFindPanelSearchOptions = "com.apple.cocoa.pasteboard.find-panel-search-options";
    final static public String UtfUrl = "public.url";
    final static public String UtfFileUrl = "public.file-url";
    final static public String UtfRawImageType = "application.x-java-rawimage";
    final static public String UtfDragImageType = "application.x-java-drag-image";
    final static public String UtfDragImageOffset = "application.x-java-drag-image-offset";

    // Native object handle (NSPasteboard*, etc.)
    private long ptr = 0L;

    // user pasteboard should be released when done (system one, however, must not be released)
    private boolean user;

    // creates system platform pasteboard for the given type
    public MacPasteboard(int type) {
        this.user = false;
        this.ptr = MacPasteboardCalls.createSystem(type);
    }

    // creates user platform pasteboard for the given name
    public MacPasteboard(String name) {
        this.user = true;
        this.ptr = MacPasteboardCalls.createUser(name);
    }

    /** Returns native pasteboard pointer (NSPasteboard*)
     */
    public long getNativePasteboard() {
        assertValid();
        return this.ptr;
    }

    // returns name
    public String getName() {
        assertValid();
        return MacPasteboardCalls.name(this.ptr);
    }

    // returns an array of String arrays with multiple UTF types for each item on the Pasteboard
    // (allows for non homogenous items)
    // the length of the array is the count of available items
    public String[][] getUTFs() {
        assertValid();
        return MacPasteboardCalls.utis(this.ptr);
    }

    // get the item representation for the given index as raw pixels bytes suitable for Pixels
    // the platform will try to find the best representation
    public byte[] getItemAsRawImage(int index) {
        assertValid();
        return MacPasteboardCalls.rawImage(this.ptr, index);
    }

    // get the item representation for the given utf type as String
    public String getItemStringForUTF(int index, String utf) {
        assertValid();
        return MacPasteboardCalls.itemString(this.ptr, index, utf);
    }

    // get the item representation for the given utf type as byte array
    public byte[] getItemBytesForUTF(int index, String utf) {
        assertValid();
        return MacPasteboardCalls.itemBytes(this.ptr, index, utf);
    }

    // paste the items with their corresponding representations
    // returns seed
    //
    // ex of 2 items:
    // 1st item has 2 representations
    // 2nd item has 1 representation
    //  {
    //      {
    //          {UtfString, "/images/image.png"},
    //          {UtfFileUrl, "file:/images/image.png"}
    //      },
    //      {
    //          {UtfString, "text"}
    //      },
    //  }
    public long putItemsFromArray(Object[] items, int supportedActions) {
        return MacPasteboardCalls.putItems(this.ptr, items, supportedActions);
    }
    private Object[] hashMapToArray(HashMap<String, Object> hashmap) {
        Object[] array = null;
        if ((hashmap != null) && (hashmap.size() > 0)) {
            array = new Object[hashmap.size()];
            int index = 0;
            for (String utf : hashmap.keySet()) {
                Object item[] = new Object[2];
                item[MacPasteboard.UtfIndex] = utf;
                item[MacPasteboard.ObjectIndex] = hashmap.get(utf);
                array[index++] = item;
            }
        }
        return array;
    }
    public long putItems(HashMap<String,Object>[] items, int supportedActions) {
        assertValid();
        Object array[] = new Object[items.length];
        for (int i = 0; i < items.length; i++) {
            array[i] = hashMapToArray(items[i]);
        }
        return putItemsFromArray(array, supportedActions);
    }

    // clears pasteboard
    // returns seed
    public long clear() {
        assertValid();
        return MacPasteboardCalls.clear(this.ptr);
    }

    // retrieve pasteboard seed
    public long getSeed() {
        assertValid();
        return MacPasteboardCalls.seed(this.ptr);
    }

    // synthesized API
    public int getAllowedOperation() {
        assertValid();
        return MacPasteboardCalls.allowedOperation(this.ptr);
    }

    public void release() {
        assertValid();
        if ((this.ptr != 0L) && (this.user)) {
            MacPasteboardCalls.release(ptr);
        }
        this.ptr = 0L;
    }

    private void assertValid() {
        if (this.ptr == 0L) {
            throw new IllegalStateException("The MacPasteboard is not valid");
        }
    }
}
