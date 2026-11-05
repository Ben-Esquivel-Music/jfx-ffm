/*
 * Copyright (c) 2010, 2026, Oracle and/or its affiliates. All rights reserved.
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

import com.sun.glass.events.MouseEvent;
import com.sun.glass.ui.Pixels;
import com.sun.glass.ui.View;
import com.sun.glass.ui.Window;
import com.sun.javafx.tk.HeaderAreaType;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Map;
import java.util.TreeSet;

/**
 * MacOSX platform implementation class for View.
 */
final class MacView extends View {

    static {
        MacViewCallbacks.install();
        multiClickTime = MacViewCalls.multiClickTime();
    }

    // Constants
    private static final long multiClickTime;
    private static final int multiClickMaxX = MacViewCalls.MULTI_CLICK_MAX;
    private static final int multiClickMaxY = MacViewCalls.MULTI_CLICK_MAX;

    static long getMultiClickTime_impl() {
        return multiClickTime;
    }

    static int getMultiClickMaxX_impl() {
        return multiClickMaxX;
    }

    static int getMultiClickMaxY_impl() {
        return multiClickMaxY;
    }

    @Override
    protected long _getNativeFrameBuffer(long ptr) {
        return MacViewCalls.getNativeFrameBuffer(ptr);
    }

    @Override
    protected long _create(Map caps) {
        return MacViewCalls.create(new CallbackTarget(this), caps);
    }

    @Override
    protected int _getX(long ptr) {
        return MacViewCalls.getX(ptr);
    }

    @Override
    protected int _getY(long ptr) {
        return MacViewCalls.getY(ptr);
    }

    @Override
    protected void _setParent(long ptr, long parentPtr) {
        MacViewCalls.setParent(ptr);
    }

    @Override
    protected boolean _close(long ptr) {
        return MacViewCalls.close(ptr);
    }

    @Override
    protected void _scheduleRepaint(long ptr) {
        MacViewCalls.scheduleRepaint(ptr);
    }

    @Override
    protected void _begin(long ptr) {
        MacViewCalls.begin(ptr);
    }

    @Override
    protected void _end(long ptr) {
        MacViewCalls.end(ptr);
    }

    @Override
    protected boolean _enterFullscreen(long ptr, boolean animate, boolean keepRatio, boolean hideCursor) {
        return MacViewCalls.enterFullscreen(ptr, animate, keepRatio, hideCursor);
    }

    @Override
    protected void _exitFullscreen(long ptr, boolean animate) {
        MacViewCalls.exitFullscreen(ptr, animate);
    }

    @Override
    protected void _enableInputMethodEvents(long ptr, boolean enable) {
        MacViewCalls.enableInputMethodEvents(ptr, enable);
    }

    @Override
    protected void _finishInputMethodComposition(long ptr) {
        MacViewCalls.finishInputMethodComposition(ptr);
    }

    @Override protected void _uploadPixels(long ptr, Pixels pixels) {
        Buffer data = pixels.getPixels();
        if (data.isDirect() == true) {
            MacViewCalls.uploadPixelsDirect(ptr, data, pixels.getWidth(), pixels.getHeight(), pixels.getScaleX(),
                                            pixels.getScaleY());
        } else if (data.hasArray() == true) {
            if (pixels.getBytesPerComponent() == 1) {
                ByteBuffer bytes = (ByteBuffer)data;
                MacViewCalls.uploadPixelsBytes(ptr, bytes.array(), bytes.arrayOffset(),
                                               pixels.getWidth(), pixels.getHeight(), pixels.getScaleX(),
                                               pixels.getScaleY());
            } else {
                IntBuffer ints = (IntBuffer)data;
                MacViewCalls.uploadPixelsInts(ptr, ints.array(), ints.arrayOffset(),
                                              pixels.getWidth(), pixels.getHeight(), pixels.getScaleX(),
                                              pixels.getScaleY());
            }
        } else {
            // gznote: what are the circumstances under which this can happen?
            MacViewCalls.uploadPixelsDirect(ptr, pixels.asByteBuffer(),
                                            pixels.getWidth(), pixels.getHeight(), pixels.getScaleX(),
                                            pixels.getScaleY());
        }
    }

    @Override
    protected void notifyResize(int width, int height) {
        Window w = getWindow();
        float sx = (w == null) ? 1.0f : w.getPlatformScaleX();
        float sy = (w == null) ? 1.0f : w.getPlatformScaleY();
        width = Math.round(width * sx);
        height = Math.round(height * sy);
        super.notifyResize(width, height);
    }

    @Override
    protected void notifyMouse(int type, int button, int x, int y, int xAbs,
                               int yAbs, int modifiers, boolean isPopupTrigger,
                               boolean isSynthesized) {
        Window w = getWindow();
        float sx = (w == null) ? 1.0f : w.getPlatformScaleX();
        float sy = (w == null) ? 1.0f : w.getPlatformScaleY();
        x = Math.round(x * sx);
        y = Math.round(y * sy);
        xAbs = Math.round(xAbs * sx);
        yAbs = Math.round(yAbs * sy);
        super.notifyMouse(type, button, x, y, xAbs, yAbs, modifiers,
                          isPopupTrigger, isSynthesized);
    }

    @Override protected long _getNativeView(long ptr) {
        return ptr;
    }

    /**
     * {@code View.ptr}, the {@code GlassView3D*} of this view, which {@code GlassWindow.m}'s {@code _setView} read
     * with {@code GetLongField}; 0 once closed. Package-private for {@code MacWindow}, which is not a {@code View}
     * subclass and cannot read the protected accessor itself.
     */
    long nativeHandle() {
        return getRawHandle();
    }

    protected void notifyInputMethodMac(String str, int attrib, int length,
                                            int cursor, int selStart, int selLength) {
        byte atts[] = new byte[1];
        atts[0] = (byte) attrib;
        int attBounds[] = new int[2];
        attBounds[0] = 0;
        attBounds[1] = length;
        if(attrib == 4) {
            // attrib == 4 means we are going to commit changes, so commitLength should be non-zero
            notifyInputMethod(str, null, attBounds, atts, length, cursor, 0);
        } else {
            // all other cases = just an update, update preview text but do not commit it
            if (selLength > 0
                    && str != null && str.length() > 0
                    && selStart >= 0
                    && selLength + selStart <= str.length()) {

                TreeSet<Integer> b = new TreeSet<>();
                b.add(0);
                b.add(selStart);
                b.add(selStart + selLength);
                b.add(str.length());

                int[] boundary = new int[b.size()];
                int i = 0;
                for (int e : b) {
                    boundary[i] = e;
                    i++;
                }

                byte[] values = new byte[boundary.length - 1];

                for (i = 0; i < boundary.length - 1; i++) {
                    values[i] = (boundary[i] == selStart)
                                        ? IME_ATTR_TARGET_CONVERTED
                                        : IME_ATTR_CONVERTED;
                }

                notifyInputMethod(str, boundary, boundary, values, 0, cursor, 0);
            } else {
                notifyInputMethod(str, null, attBounds, atts, 0, cursor, 0);
            }
        }
    }

    @Override
    protected boolean handleNonClientMouseEvent(long time, int type, int button, int x, int y, int xAbs, int yAbs,
                                                int modifiers, int clickCount) {
        if (shouldHandleEvent() && type == MouseEvent.DOWN) {
            var window = (MacWindow)getWindow();
            double wx = x / window.getPlatformScaleX();
            double wy = y / window.getPlatformScaleY();

            View.EventHandler eventHandler = getEventHandler();
            if (eventHandler != null && eventHandler.pickHeaderArea(wx, wy) == HeaderAreaType.DRAGBAR) {
                if (clickCount == 2) {
                    window.performTitleBarDoubleClickAction();
                } else if (clickCount == 1) {
                    window.performWindowDrag();
                }
            }
        }

        return false;
    }

    /**
     * Where the slots of {@code GmacViewCallbacks} and {@code GmacGestureCallbacks} reach this view: the methods the
     * JNI called on its global reference, through the IDs {@code _initIDs} looked up on {@code MacView}, and the
     * static methods of {@code MacGestureSupport} it called with the view.
     */
    private record CallbackTarget(MacView view) implements MacViewCallbacks.Receiver {

        @Override
        public void notifyView(int type) {
            view.notifyView(type);
        }

        @Override
        public void notifyResize(int width, int height) {
            view.notifyResize(width, height);
        }

        @Override
        public void notifyRepaint(int x, int y, int width, int height) {
            view.notifyRepaint(x, y, width, height);
        }

        @Override
        public void notifyMenu(int x, int y, int xAbs, int yAbs, boolean isKeyboardTrigger) {
            view.notifyMenu(x, y, xAbs, yAbs, isKeyboardTrigger);
        }

        @Override
        public void notifyMouse(int type, int button, int x, int y, int xAbs, int yAbs, int modifiers,
                                boolean isPopupTrigger, boolean isSynthesized) {
            view.notifyMouse(type, button, x, y, xAbs, yAbs, modifiers, isPopupTrigger, isSynthesized);
        }

        @Override
        public boolean notifyKeyAndReturnConsumed(int type, int keyCode, char[] keyChars, int modifiers) {
            return view.notifyKeyAndReturnConsumed(type, keyCode, keyChars, modifiers);
        }

        @Override
        public void notifyInputMethodMac(String text, int attr, int length, int cursor, int selStart,
                                         int selLength) {
            view.notifyInputMethodMac(text, attr, length, cursor, selStart, selLength);
        }

        @Override
        public double[] notifyInputMethodCandidatePosRequest(int pos) {
            return view.notifyInputMethodCandidatePosRequest(pos);
        }

        @Override
        public int notifyDragEnter(int x, int y, int xAbs, int yAbs, int recommendedAction) {
            return view.notifyDragEnter(x, y, xAbs, yAbs, recommendedAction);
        }

        @Override
        public int notifyDragOver(int x, int y, int xAbs, int yAbs, int recommendedAction) {
            return view.notifyDragOver(x, y, xAbs, yAbs, recommendedAction);
        }

        @Override
        public int notifyDragDrop(int x, int y, int xAbs, int yAbs, int recommendedAction) {
            return view.notifyDragDrop(x, y, xAbs, yAbs, recommendedAction);
        }

        @Override
        public void notifyDragLeave() {
            view.notifyDragLeave();
        }

        @Override
        public void notifyDragEnd(int performedAction) {
            view.notifyDragEnd(performedAction);
        }

        /** {@code View.getAccessible()}, which is package-private to {@code com.sun.glass.ui}. */
        @Override
        public long getAccessible() {
            return view.getAccessibleForNative();
        }

        @Override
        public void rotateGesturePerformed(int modifiers, int x, int y, int xAbs, int yAbs, float rotation)
                throws Throwable {
            GestureSupportMethods.ROTATE_GESTURE_PERFORMED.invokeExact((View) view, modifiers, x, y, xAbs, yAbs,
                    rotation);
        }

        @Override
        public void scrollGesturePerformed(int modifiers, int sender, int x, int y, int xAbs, int yAbs, float dx,
                                           float dy) throws Throwable {
            GestureSupportMethods.SCROLL_GESTURE_PERFORMED.invokeExact((View) view, modifiers, sender, x, y, xAbs,
                    yAbs, dx, dy);
        }

        @Override
        public void swipeGesturePerformed(int modifiers, int dir, int x, int y, int xAbs, int yAbs)
                throws Throwable {
            GestureSupportMethods.SWIPE_GESTURE_PERFORMED.invokeExact((View) view, modifiers, dir, x, y, xAbs, yAbs);
        }

        @Override
        public void magnifyGesturePerformed(int modifiers, int x, int y, int xAbs, int yAbs, float scale)
                throws Throwable {
            GestureSupportMethods.MAGNIFY_GESTURE_PERFORMED.invokeExact((View) view, modifiers, x, y, xAbs, yAbs,
                    scale);
        }

        @Override
        public void gestureFinished(int modifiers, int x, int y, int xAbs, int yAbs) throws Throwable {
            GestureSupportMethods.GESTURE_FINISHED.invokeExact((View) view, modifiers, x, y, xAbs, yAbs);
        }
    }

    /**
     * The static methods of {@code MacGestureSupport} the gesture slots reach, found when the first gesture arrives.
     * {@code findStatic}, like the JNI's {@code GetStaticMethodID}, does not initialize the class: the first
     * invocation does, as the JNI's {@code Class.forName} on the first gesture event did.
     */
    private static final class GestureSupportMethods {

        static final MethodHandle ROTATE_GESTURE_PERFORMED = find("rotateGesturePerformed", int.class, int.class,
                int.class, int.class, int.class, float.class);
        static final MethodHandle SCROLL_GESTURE_PERFORMED = find("scrollGesturePerformed", int.class, int.class,
                int.class, int.class, int.class, int.class, float.class, float.class);
        static final MethodHandle SWIPE_GESTURE_PERFORMED = find("swipeGesturePerformed", int.class, int.class,
                int.class, int.class, int.class, int.class);
        static final MethodHandle MAGNIFY_GESTURE_PERFORMED = find("magnifyGesturePerformed", int.class, int.class,
                int.class, int.class, int.class, float.class);
        static final MethodHandle GESTURE_FINISHED = find("gestureFinished", int.class, int.class, int.class,
                int.class, int.class);

        private static MethodHandle find(String name, Class<?>... parametersAfterView) {
            MethodType type = MethodType.methodType(void.class, View.class).appendParameterTypes(parametersAfterView);
            try {
                return MethodHandles.lookup().findStatic(MacGestureSupport.class, name, type);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
    }
}
