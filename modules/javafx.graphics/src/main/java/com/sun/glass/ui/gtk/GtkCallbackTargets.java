/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
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

package com.sun.glass.ui.gtk;

import com.sun.glass.events.KeyEvent;
import com.sun.glass.ui.Application;
import com.sun.glass.ui.Pixels;
import com.sun.glass.ui.Screen;
import com.sun.javafx.font.JniStringCodec;
import java.io.ByteArrayOutputStream;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.HashMap;
import static com.sun.glass.ui.gtk.GtkCallbackTables.DND_AS_BYTES;
import static com.sun.glass.ui.gtk.GtkCallbackTables.DND_AS_PIXBUF;
import static com.sun.glass.ui.gtk.GtkCallbackTables.DND_AS_RAW;
import static com.sun.glass.ui.gtk.GtkCallbackTables.DND_AS_STRING;
import static com.sun.glass.ui.gtk.GtkCallbackTables.DND_AS_STRINGS;
import static com.sun.glass.ui.gtk.GtkCallbackTables.DND_DATA_BYTES;
import static com.sun.glass.ui.gtk.GtkCallbackTables.DND_DATA_COUNT_OFFSET;
import static com.sun.glass.ui.gtk.GtkCallbackTables.DND_DATA_DATA_OFFSET;
import static com.sun.glass.ui.gtk.GtkCallbackTables.DND_DATA_KIND_OFFSET;
import static com.sun.glass.ui.gtk.GtkCallbackTables.DND_DATA_PIXBUF;
import static com.sun.glass.ui.gtk.GtkCallbackTables.DND_DATA_PIXBUF_OFFSET;
import static com.sun.glass.ui.gtk.GtkCallbackTables.DND_DATA_STRING;
import static com.sun.glass.ui.gtk.GtkCallbackTables.DND_DATA_STRINGS;
import static com.sun.glass.ui.gtk.GtkCallbackTables.UPCALL_OK;
import static com.sun.glass.ui.gtk.GtkCallbackTables.UPCALL_THREW;
import static com.sun.glass.ui.gtk.GtkGlassNative.bounded;
import static com.sun.glass.ui.gtk.GtkGlassNative.reportException;
import static com.sun.glass.ui.gtk.GtkPixelsCalls.G_MALLOC;
import static com.sun.glass.ui.gtk.GtkPixelsCalls.PIXELS_ATTACH_DATA;
import static com.sun.glass.ui.gtk.GtkRobotCalls.GDK_SCREEN_GET_DEFAULT;
import static com.sun.glass.ui.gtk.GtkScreenCalls.createScreen;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_CHAR_UNALIGNED;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * The targets of the 29 slots of the callback tables ({@link GtkCallbackTables#installCallbacks}). Each is the
 * Call*Method of the C with the peer the id names as the receiver, reached by a virtual call through the GtkWindow /
 * GtkView dispatch methods (View.notify* and Window.notify* are protected in com.sun.glass.ui: only subclass code
 * reaches them, JLS 6.6.2.1), so the overrides of GtkView and of any GtkWindow subclass run exactly as under JNI. Every
 * target:
 * <ul>
 * <li>catches every Throwable, because one that escapes an upcall stub terminates the JVM, and reports it as
 * check_and_clear_exception (glass_general.cpp) reported it - reportException - then answers UPCALL_THREW, on which
 * the C takes the early return or the continuation it took after a pending exception;</li>
 * <li>answers a NullPointerException for the id 0, which the C passes where it called Java on a NULL jwindow or jview
 * and HotSpot threw that exception for the NULL receiver (glass_gtk_api.h, IDENTITY);</li>
 * <li>does nothing for an id no peer is registered under any more, and answers UPCALL_OK;</li>
 * <li>writes its out-parameters only when the Java target returned normally.</li>
 * </ul>
 * They run on the thread that iterates GLib's default main context, and are re-entrant: a target may run a nested
 * main loop (the drop target's reads, enterNestedEventLoop) that dials any slot again. This class has no static
 * initializer, so an upcall that reached it first would run none.
 */
final class GtkCallbackTargets {

    static int onNotifyScreenSettingsChanged() {
        try {
            Screen.notifySettingsChanged();
            return UPCALL_OK;
        } catch (Throwable t) {
            reportException(t);
            return UPCALL_THREW;
        }
    }

    /**
     * {@code get_application_name} ({@code glass_general.cpp}): {@code Application.GetApplication().getName()} as
     * modified UTF-8 in a {@code g_malloc}'d, NUL-terminated block that the C owns - the {@code GetStringUTFChars}
     * bytes the JNI copied with {@code g_strdup}. A {@code null} name leaves {@code *outName} {@code NULL}; a
     * {@code null} application is the {@code NullPointerException} {@code CallObjectMethod} raised for it.
     */
    static int onGetApplicationName(MemorySegment outName) {
        try {
            Application application = Application.GetApplication();
            if (application == null) {
                throw new NullPointerException();
            }
            String name = application.getName();
            if (name != null) {
                outName.set(ADDRESS, 0, gMallocModifiedUtf8(name));
            }
            return UPCALL_OK;
        } catch (Throwable t) {
            reportException(t);
            return UPCALL_THREW;
        }
    }

    /**
     * Hot: every input event asks it before it is dispatched ({@code is_window_enabled_for_event},
     * {@code GlassApplication.cpp}); scalars only.
     */
    static int onIsEnabled(long windowId, MemorySegment outEnabled) {
        try {
            GtkWindow window = GtkWindow.peer(windowId);
            if (window != null) {
                outEnabled.set(JAVA_INT, 0, window.isEnabled() ? 1 : 0);
            }
            return UPCALL_OK;
        } catch (Throwable t) {
            reportException(t);
            return UPCALL_THREW;
        }
    }

    static int onNotifyStateChanged(long windowId, int state) {
        try {
            GtkWindow.dispatchStateChanged(windowId, state);
            return UPCALL_OK;
        } catch (Throwable t) {
            reportException(t);
            return UPCALL_THREW;
        }
    }

    static int onNotifyFocus(long windowId, int event) {
        try {
            GtkWindow.dispatchFocus(windowId, event);
            return UPCALL_OK;
        } catch (Throwable t) {
            reportException(t);
            return UPCALL_THREW;
        }
    }

    static int onNotifyFocusDisabled(long windowId) {
        try {
            GtkWindow.dispatchFocusDisabled(windowId);
            return UPCALL_OK;
        } catch (Throwable t) {
            reportException(t);
            return UPCALL_THREW;
        }
    }

    static int onNotifyFocusUngrab(long windowId) {
        try {
            GtkWindow.dispatchFocusUngrab(windowId);
            return UPCALL_OK;
        } catch (Throwable t) {
            reportException(t);
            return UPCALL_THREW;
        }
    }

    /**
     * {@code notify_destroy}: {@code Window.notifyDestroy()}, reported as {@code EXCEPTION_OCCURED} reported it, then
     * the registry entries go - where {@code WindowContextBase::process_destroy} deleted the global references of
     * the window and of its view, right after this call.
     */
    static int onNotifyDestroy(long windowId) {
        int status = UPCALL_OK;
        try {
            GtkWindow.dispatchDestroy(windowId);
        } catch (Throwable t) {
            reportException(t);
            status = UPCALL_THREW;
        }
        try {
            GtkWindow.releaseDestroyed(windowId);
        } catch (Throwable t) {
            reportException(t);
        }
        return status;
    }

    static int onNotifyClose(long windowId) {
        try {
            GtkWindow.dispatchClose(windowId);
            return UPCALL_OK;
        } catch (Throwable t) {
            reportException(t);
            return UPCALL_THREW;
        }
    }

    static int onNotifyWindowResize(long windowId, int type, int width, int height) {
        try {
            GtkWindow.dispatchResize(windowId, type, width, height);
            return UPCALL_OK;
        } catch (Throwable t) {
            reportException(t);
            return UPCALL_THREW;
        }
    }

    static int onNotifyMove(long windowId, int x, int y) {
        try {
            GtkWindow.dispatchMove(windowId, x, y);
            return UPCALL_OK;
        } catch (Throwable t) {
            reportException(t);
            return UPCALL_THREW;
        }
    }

    /**
     * {@code notify_move_to_another_screen}: {@code Window.notifyMoveToAnotherScreen(Screen)} with the {@code Screen}
     * of monitor {@code monitorIndex} of the default {@code GdkScreen}, built by {@link GtkScreenCalls#createScreen} -
     * the arithmetic of {@code createJavaScreen} ({@code glass_screen.cpp}), which the JNI ran here before the call.
     */
    static int onNotifyMoveToAnotherScreen(long windowId, int monitorIndex) {
        try {
            Screen screen = createScreen((MemorySegment) GDK_SCREEN_GET_DEFAULT.invokeExact(), monitorIndex);
            GtkWindow.dispatchMoveToAnotherScreen(windowId, screen);
            return UPCALL_OK;
        } catch (Throwable t) {
            reportException(t);
            return UPCALL_THREW;
        }
    }

    static int onNotifyLevelChanged(long windowId, int level) {
        try {
            GtkWindow.dispatchLevelChanged(windowId, level);
            return UPCALL_OK;
        } catch (Throwable t) {
            reportException(t);
            return UPCALL_THREW;
        }
    }

    static int onNonClientHitTest(long windowId, int x, int y, MemorySegment outResult) {
        try {
            GtkWindow window = GtkWindow.peer(windowId);
            if (window != null) {
                outResult.set(JAVA_INT, 0, GtkWindow.nonClientHitTest(window, x, y));
            }
            return UPCALL_OK;
        } catch (Throwable t) {
            reportException(t);
            return UPCALL_THREW;
        }
    }

    static int onNotifyView(long viewId, int type) {
        try {
            GtkView.dispatchView(viewId, type);
            return UPCALL_OK;
        } catch (Throwable t) {
            reportException(t);
            return UPCALL_THREW;
        }
    }

    static int onNotifyViewResize(long viewId, int width, int height) {
        try {
            GtkView.dispatchResize(viewId, width, height);
            return UPCALL_OK;
        } catch (Throwable t) {
            reportException(t);
            return UPCALL_THREW;
        }
    }

    static int onNotifyRepaint(long viewId, int x, int y, int width, int height) {
        try {
            GtkView.dispatchRepaint(viewId, x, y, width, height);
            return UPCALL_OK;
        } catch (Throwable t) {
            reportException(t);
            return UPCALL_THREW;
        }
    }

    /**
     * Hot: one per motion event; scalars only. The one call site that did not test the exception, the {@code EXIT}
     * of {@code WindowContextBase::set_view}, left it pending and {@code GtkWindow._setView} threw it after the C had
     * swapped the views: that call is recognised by {@link GtkWindow#claimSetViewExit}, and its exception is handed
     * back to the {@code _setView} in progress, which throws it, instead of being reported.
     */
    static int onNotifyMouse(long viewId, int type, int button, int x, int y, int xAbs, int yAbs, int modifiers,
                             int isPopupTrigger, int isSynthesized) {
        GtkWindow.SetViewCall setView = null;
        try {
            setView = GtkWindow.claimSetViewExit(type);
            GtkView.dispatchMouse(viewId, type, button, x, y, xAbs, yAbs, modifiers, isPopupTrigger != 0,
                    isSynthesized != 0);
            return UPCALL_OK;
        } catch (Throwable t) {
            if (setView != null) {
                setView.hold(t);
            } else {
                reportException(t);
            }
            return UPCALL_THREW;
        }
    }

    static int onNotifyMenu(long viewId, int x, int y, int xAbs, int yAbs, int isKeyboardTrigger) {
        try {
            GtkView.dispatchMenu(viewId, x, y, xAbs, yAbs, isKeyboardTrigger != 0);
            return UPCALL_OK;
        } catch (Throwable t) {
            reportException(t);
            return UPCALL_THREW;
        }
    }

    static int onNotifyScroll(long viewId, int x, int y, int xAbs, int yAbs, double deltaX, double deltaY,
                              int modifiers, int lines, int chars, int defaultLines, int defaultChars,
                              double xMultiplier, double yMultiplier) {
        try {
            GtkView.dispatchScroll(viewId, x, y, xAbs, yAbs, deltaX, deltaY, modifiers, lines, chars, defaultLines,
                    defaultChars, xMultiplier, yMultiplier);
            return UPCALL_OK;
        } catch (Throwable t) {
            reportException(t);
            return UPCALL_THREW;
        }
    }

    /**
     * The {@code char[]} {@code notify_key} built for the {@code PRESS} that returned last, and the address of the
     * code unit it was built from: {@code WindowContextBase::process_key} passed the same {@code jcharArray} to the
     * {@code PRESS} and to the {@code TYPED} that follows it, and hands the table the same {@code &key} for both. Only
     * touched on the thread that iterates the main context.
     */
    private static char[] pressedKeyChars;
    private static long pressedKeyAddress;

    /**
     * {@code notify_key}: {@code View.notifyKey(int, int, char[], int)} with the {@code keyCharCount} (0 or 1) UTF-16
     * code units at {@code keyChars} copied as {@code char}s - lone surrogates included, never a charset decode -
     * into a {@code char[]} of that length, never {@code null}. The {@code TYPED} that follows a {@code PRESS} gets
     * the very array that {@code PRESS} got, as under JNI; a nested key event dispatched inside the {@code PRESS}
     * records its own array only while it runs, so the outer pair still shares one.
     */
    static int onNotifyKey(long viewId, int type, int keyCode, MemorySegment keyChars, int keyCharCount,
                           int modifiers) {
        try {
            char[] chars;
            if (type == KeyEvent.TYPED && pressedKeyChars != null && pressedKeyChars.length == keyCharCount
                    && pressedKeyAddress == keyChars.address()) {
                chars = pressedKeyChars;
            } else {
                chars = utf16Units(keyChars, keyCharCount);
            }
            pressedKeyChars = null;
            try {
                GtkView.dispatchKey(viewId, type, keyCode, chars, modifiers);
            } finally {
                if (type == KeyEvent.PRESS) {
                    pressedKeyChars = chars;
                    pressedKeyAddress = keyChars.address();
                }
            }
            return UPCALL_OK;
        } catch (Throwable t) {
            reportException(t);
            return UPCALL_THREW;
        }
    }

    /**
     * {@code notify_input_method_preedit} ({@code on_preedit_changed}, {@code glass_window_ime.cpp}):
     * {@code GtkView.notifyInputMethodLinux(text, 0, cursorPos, (byte) attr)}, the text decoded from the
     * {@code textLen} bytes as {@code NewStringUTF} decoded them; {@code NULL} is a {@code null} string.
     */
    static int onNotifyInputMethodPreedit(long viewId, MemorySegment text, int textLen, int cursorPos, int attr) {
        try {
            GtkView.dispatchInputMethod(viewId, newStringUtf(text, textLen), 0, cursorPos, (byte) attr);
            return UPCALL_OK;
        } catch (Throwable t) {
            reportException(t);
            return UPCALL_THREW;
        }
    }

    /**
     * {@code notify_input_method_commit} ({@code WindowContextBase::commitIME}): the text decoded as
     * {@code NewStringUTF} decoded it, then {@code GtkView.notifyInputMethodLinux(text, n, n, (byte) 0)} with
     * {@code n} its UTF-16 length, the {@code GetStringLength} of the JNI.
     */
    static int onNotifyInputMethodCommit(long viewId, MemorySegment text, int textLen) {
        try {
            String committed = newStringUtf(text, textLen);
            int length = committed.length();
            GtkView.dispatchInputMethod(viewId, committed, length, length, (byte) 0);
            return UPCALL_OK;
        } catch (Throwable t) {
            reportException(t);
            return UPCALL_THREW;
        }
    }

    /**
     * {@code notify_input_method_candidate_pos_request} ({@code WindowContextBase::updateCaretPos}):
     * {@code GtkView.notifyInputMethodCandidateRelativePosRequest(int)}; elements 0 and 1 of the array it returns
     * go to {@code outXy} and 1 to {@code outValid}. A {@code null} array writes nothing, which makes the C skip
     * {@code gtk_im_context_set_cursor_location}; there, and after a throw, the JNI's
     * {@code GetDoubleArrayElements(NULL)} crashed the JVM (glass_gtk_api.h).
     */
    static int onNotifyInputMethodCandidatePosRequest(long viewId, int offset, MemorySegment outXy,
                                                      MemorySegment outValid) {
        try {
            double[] position = GtkView.dispatchCandidatePosRequest(viewId, offset);
            if (position != null) {
                double x = position[0];
                double y = position[1];
                outXy.setAtIndex(JAVA_DOUBLE, 0, x);
                outXy.setAtIndex(JAVA_DOUBLE, 1, y);
                outValid.set(JAVA_INT, 0, 1);
            }
            return UPCALL_OK;
        } catch (Throwable t) {
            reportException(t);
            return UPCALL_THREW;
        }
    }

    static int onNotifyDragEnter(long viewId, int x, int y, int xAbs, int yAbs, int recommendedAction,
                                 MemorySegment outAction) {
        try {
            Integer action = GtkView.dispatchDragEnter(viewId, x, y, xAbs, yAbs, recommendedAction);
            if (action != null) {
                outAction.set(JAVA_INT, 0, action);
            }
            return UPCALL_OK;
        } catch (Throwable t) {
            reportException(t);
            return UPCALL_THREW;
        }
    }

    static int onNotifyDragOver(long viewId, int x, int y, int xAbs, int yAbs, int recommendedAction,
                                MemorySegment outAction) {
        try {
            Integer action = GtkView.dispatchDragOver(viewId, x, y, xAbs, yAbs, recommendedAction);
            if (action != null) {
                outAction.set(JAVA_INT, 0, action);
            }
            return UPCALL_OK;
        } catch (Throwable t) {
            reportException(t);
            return UPCALL_THREW;
        }
    }

    static int onNotifyDragDrop(long viewId, int x, int y, int xAbs, int yAbs, int recommendedAction) {
        try {
            GtkView.dispatchDragDrop(viewId, x, y, xAbs, yAbs, recommendedAction);
            return UPCALL_OK;
        } catch (Throwable t) {
            reportException(t);
            return UPCALL_THREW;
        }
    }

    static int onNotifyDragLeave(long viewId) {
        try {
            GtkView.dispatchDragLeave(viewId);
            return UPCALL_OK;
        } catch (Throwable t) {
            reportException(t);
            return UPCALL_THREW;
        }
    }

    /**
     * {@code source_get_data}: the value the data map of the drag in progress
     * ({@link GtkDnDClipboard#dragInProgress()}) holds for the key, converted as {@code as} says - the
     * {@code Map.get} of {@code dnd_source_get_data} ({@code glass_dnd.cpp}) together with what its callers did with
     * the {@code jobject}. The key is decoded from the {@code mimeLen} bytes as {@code NewStringUTF} decoded it. The
     * C zero-filled {@code out}, so "no value" is {@code DND_DATA_NONE} without a write; the blocks written to
     * {@code data} are {@code g_malloc}'d and owned by the C.
     * <ul>
     * <li>{@code Map.get} threw: reported ({@code EXCEPTION_OCCURED}), kind {@code NONE}, {@code UPCALL_THREW};</li>
     * <li>no value, or one the conversion does not take: kind {@code NONE}, {@code UPCALL_OK};</li>
     * <li>the conversion threw ({@code ByteBuffer.array()}, {@code Pixels.attachData}): reported, the value's kind
     * with {@code data} {@code NULL}, {@code UPCALL_THREW}.</li>
     * </ul>
     */
    static int onSourceGetData(MemorySegment mime, int mimeLen, int as, MemorySegment out) {
        try {
            Object value;
            try {
                String key = newStringUtf(mime, mimeLen);
                HashMap<String, Object> data = GtkDnDClipboard.dragInProgress();
                if (data == null) {
                    throw new NullPointerException();
                }
                value = data.get(key);
            } catch (Throwable t) {
                reportException(t);
                return UPCALL_THREW;
            }
            return switch (as) {
                case DND_AS_STRING -> value instanceof String s ? putString(out, s) : UPCALL_OK;
                case DND_AS_BYTES -> value instanceof ByteBuffer b ? putBytes(out, b) : UPCALL_OK;
                case DND_AS_PIXBUF -> value instanceof Pixels p ? putPixbuf(out, p) : UPCALL_OK;
                case DND_AS_STRINGS -> value instanceof String[] a ? putStrings(out, a) : UPCALL_OK;
                case DND_AS_RAW -> value instanceof String s ? putString(out, s)
                        : value instanceof ByteBuffer b ? putBytes(out, b) : UPCALL_OK;
                default -> UPCALL_OK;
            };
        } catch (Throwable t) {
            reportException(t);
            return UPCALL_THREW;
        }
    }

    /** {@code GetStringUTFChars}: the modified UTF-8 of {@code s}, NUL-terminated, as a {@code STRING}. */
    private static int putString(MemorySegment out, String s) throws Throwable {
        byte[] bytes = JniStringCodec.toModifiedUtf8(s);
        out.set(ADDRESS, DND_DATA_DATA_OFFSET, gMalloc(bytes));
        out.set(JAVA_INT, DND_DATA_COUNT_OFFSET, bytes.length - 1);
        out.set(JAVA_INT, DND_DATA_KIND_OFFSET, DND_DATA_STRING);
        return UPCALL_OK;
    }

    /**
     * {@code ByteBuffer.array()} + {@code GetByteArrayElements}: the whole backing array, position and limit ignored,
     * as {@code BYTES}. A throw of {@code array()} - a read-only or a direct buffer - is reported and leaves
     * {@code BYTES} with no data.
     */
    private static int putBytes(MemorySegment out, ByteBuffer buffer) throws Throwable {
        out.set(JAVA_INT, DND_DATA_KIND_OFFSET, DND_DATA_BYTES);
        byte[] array;
        try {
            array = buffer.array();
        } catch (Throwable t) {
            reportException(t);
            return UPCALL_THREW;
        }
        out.set(ADDRESS, DND_DATA_DATA_OFFSET, gMalloc(array));
        out.set(JAVA_INT, DND_DATA_COUNT_OFFSET, array.length);
        return UPCALL_OK;
    }

    /**
     * {@code Pixels.attachData(&pixbuf)}, with the address of {@code out->pixbuf} as the JNI passed the address of
     * its local, so a pixbuf written before a throw is what the C sees; a throw is reported.
     */
    private static int putPixbuf(MemorySegment out, Pixels pixels) {
        out.set(JAVA_INT, DND_DATA_KIND_OFFSET, DND_DATA_PIXBUF);
        try {
            PIXELS_ATTACH_DATA.invokeExact(pixels, out.address() + DND_DATA_PIXBUF_OFFSET);
            return UPCALL_OK;
        } catch (Throwable t) {
            reportException(t);
            return UPCALL_THREW;
        }
    }

    /**
     * {@code GetArrayLength} + {@code GetStringUTFChars} of every element: the strings as modified UTF-8, each
     * NUL-terminated, back to back, as {@code STRINGS}. A {@code null} element, on which the JNI's
     * {@code GetStringUTFChars} crashed, is reported as the {@code NullPointerException} it is here and delivers no
     * strings.
     */
    private static int putStrings(MemorySegment out, String[] strings) throws Throwable {
        ByteArrayOutputStream block = new ByteArrayOutputStream();
        for (String s : strings) {
            if (s == null) {
                reportException(new NullPointerException());
                return UPCALL_THREW;
            }
            block.writeBytes(JniStringCodec.toModifiedUtf8(s));
        }
        out.set(ADDRESS, DND_DATA_DATA_OFFSET, gMalloc(block.toByteArray()));
        out.set(JAVA_INT, DND_DATA_COUNT_OFFSET, strings.length);
        out.set(JAVA_INT, DND_DATA_KIND_OFFSET, DND_DATA_STRINGS);
        return UPCALL_OK;
    }

    /**
     * {@code bytes} in a {@code g_malloc}'d block, which the C owns and {@code g_free}s: never {@code NULL}, one zero
     * byte for an empty array.
     */
    private static MemorySegment gMalloc(byte[] bytes) throws Throwable {
        int size = Math.max(1, bytes.length);
        MemorySegment block = bounded(((MemorySegment) G_MALLOC.invokeExact((long) size)), size);
        block.set(JAVA_BYTE, 0, (byte) 0);
        MemorySegment.copy(bytes, 0, block, JAVA_BYTE, 0, bytes.length);
        return block;
    }

    private static MemorySegment gMallocModifiedUtf8(String s) throws Throwable {
        return gMalloc(JniStringCodec.toModifiedUtf8(s));
    }

    /**
     * {@code NewStringUTF} of the {@code length} bytes at {@code text} (no terminator counted): {@code null} for
     * {@code NULL}, what {@code NewStringUTF} answered for one.
     */
    private static String newStringUtf(MemorySegment text, int length) {
        if (text.address() == 0) {
            return null;
        }
        return JniStringCodec.fromNewStringUtf(bounded(text, length).toArray(JAVA_BYTE));
    }

    private static char[] utf16Units(MemorySegment units, int count) {
        char[] chars = new char[count];
        if (count > 0) {
            MemorySegment.copy(bounded(units, count * JAVA_CHAR_UNALIGNED.byteSize()), JAVA_CHAR_UNALIGNED, 0,
                    chars, 0, count);
        }
        return chars;
    }

    private GtkCallbackTargets() {
    }
}
