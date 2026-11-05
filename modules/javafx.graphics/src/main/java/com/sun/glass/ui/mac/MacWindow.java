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

import com.sun.glass.events.WindowEvent;
import com.sun.glass.ui.Cursor;
import com.sun.glass.ui.HeaderButtonMetrics;
import com.sun.glass.ui.Pixels;
import com.sun.glass.ui.Screen;
import com.sun.glass.ui.View;
import com.sun.glass.ui.Window;
import javafx.geometry.Dimension2D;
import javafx.scene.layout.HeaderBar;
import java.nio.ByteBuffer;

/**
 * MacOSX platform implementation class for Window.
 */
final class MacWindow extends Window {

    static {
        MacWindowCallbacks.install();
    }

    protected MacWindow(Window owner, Screen screen, int styleMask) {
        super(owner, screen, styleMask);

        if (isExtendedWindow()) {
            headerButtonHeightProperty().subscribe(this::onHeaderButtonHeightChanged);
            headerButtonDarkStyleProperty().subscribe(this::onHeaderButtonDarkStyleChanged);
        }
    }

    @Override
    protected long _createWindow(long ownerPtr, long screenPtr, int mask) {
        return MacWindowCalls.create(new CallbackTarget(this), ownerPtr, screenPtr, mask);
    }

    @Override
    protected boolean _close(long ptr) {
        return MacWindowCalls.close(ptr);
    }

    /**
     * The {@code GlassView3D*} the JNI read from {@code View.ptr}, read when {@code MacWindowCalls.setView} asks for
     * it. A {@code View} is a {@code MacView} on this platform ({@code MacApplication.createView}); any other crosses
     * as no view.
     */
    @Override
    protected boolean _setView(long ptr, View view) {
        return MacWindowCalls.setView(ptr, () -> view instanceof MacView macView ? macView.nativeHandle() : 0L);
    }

    @Override
    protected boolean _setMenubar(long ptr, long menubarPtr) {
        return MacWindowCalls.setMenubar(ptr, menubarPtr);
    }

    @Override
    protected boolean _minimize(long ptr, boolean minimize) {
        return MacWindowCalls.minimize(ptr, minimize);
    }

    @Override
    protected boolean _maximize(long ptr, boolean maximize, boolean wasMaximized) {
        return MacWindowCalls.maximize(ptr, maximize, wasMaximized);
    }

    // empty - not needed by this implementation
    @Override protected void _updateViewSize(long ptr) {}
    @Override protected void _setBounds(long ptr,
                                        int x, int y, boolean xSet, boolean ySet,
                                        int w, int h, int cw, int ch,
                                        float xGravity, float yGravity)
    {
        float sx = getPlatformScaleX();
        float sy = getPlatformScaleY();
        if (xSet)    x = Math.round( x / sx);
        if (ySet)    y = Math.round( y / sy);
        if ( w > 0)  w = Math.round( w / sx);
        if ( h > 0)  h = Math.round( h / sy);
        if (cw > 0) cw = Math.round(cw / sx);
        if (ch > 0) ch = Math.round(ch / sy);
        MacWindowCalls.setBounds(ptr, x, y, xSet, ySet, w, h, cw, ch, xGravity, yGravity);
    }

    @Override
    protected boolean _setVisible(long ptr, boolean visible) {
        return MacWindowCalls.setVisible(ptr, visible);
    }

    @Override
    protected boolean _setResizable(long ptr, boolean resizable) {
        return MacWindowCalls.setResizable(ptr, resizable);
    }

    @Override protected boolean _requestFocus(long ptr, int event) {
        //TODO: provide reasonable impl for all possible events
        if (event != WindowEvent.FOCUS_LOST) {
            return MacWindowCalls.requestFocus(ptr);
        }
        return false;
    }

    @Override
    protected void _setFocusable(long ptr, boolean isFocusable) {
        MacWindowCalls.setFocusable(ptr, isFocusable);
    }

    @Override
    protected boolean _setTitle(long ptr, String title) {
        return MacWindowCalls.setTitle(ptr, title);
    }

    @Override
    protected void _setLevel(long ptr, int level) {
        MacWindowCalls.setLevel(ptr, level);
    }

    @Override
    protected void _setAlpha(long ptr, float alpha) {
        MacWindowCalls.setAlpha(ptr, alpha);
    }

    @Override
    protected boolean _setBackground(long ptr, float r, float g, float b) {
        return MacWindowCalls.setBackground(ptr, r, g, b);
    }

    @Override
    protected void _setEnabled(long ptr, boolean enabled) {
        MacWindowCalls.setEnabled(ptr, enabled);
    }

    @Override
    protected boolean _setMinimumSize(long ptr, int width, int height) {
        return MacWindowCalls.setMinimumSize(ptr, width, height);
    }

    @Override
    protected boolean _setMaximumSize(long ptr, int width, int height) {
        return MacWindowCalls.setMaximumSize(ptr, width, height);
    }

    private ByteBuffer iconBuffer;

    @Override protected void _setIcon(long ptr, Pixels pixels) {

        if (pixels != null) {
            iconBuffer = pixels.asByteBuffer();
            MacWindowCalls.setIcon(ptr, iconBuffer, pixels.getWidth(), pixels.getHeight());
        } else {
            iconBuffer = null;
            MacWindowCalls.setIcon(ptr, null, 0, 0);
        }
    }

    @Override
    public void setDarkFrame(boolean value) {
        MacWindowCalls.setDarkFrame(getRawHandle(), value);
    }

    @Override
    protected void _toFront(long ptr) {
        MacWindowCalls.toFront(ptr);
    }

    @Override
    protected void _toBack(long ptr) {
        MacWindowCalls.toBack(ptr);
    }

    @Override
    protected boolean _grabFocus(long ptr) {
        return MacWindowCalls.grabFocus(ptr);
    }

    @Override
    protected void _ungrabFocus(long ptr) {
        MacWindowCalls.ungrabFocus(ptr);
    }

    @Override
    protected void notifyResize(int type, int width, int height) {
        width  = Math.round( width * getPlatformScaleX());
        height = Math.round(height * getPlatformScaleY());
        super.notifyResize(type, width, height);
    }

    protected void notifyMove(final int x, final int y, boolean isMaximized) {
        if (isMaximized() != isMaximized && !isMinimized()) {
            setState(isMaximized ? State.MAXIMIZED : State.NORMAL);
            handleWindowEvent(System.nanoTime(),
                    isMaximized
                            ? WindowEvent.MAXIMIZE
                            : WindowEvent.RESTORE);
        }
        notifyMove(x, y);
    }

    @Override
    protected void _setCursor(long ptr, Cursor cursor) {
        ((MacCursor)cursor).set();
    }

    @Override
    protected void _requestInput(long ptr, String text, int type, double width, double height,
                                    double Mxx, double Mxy, double Mxz, double Mxt,
                                    double Myx, double Myy, double Myz, double Myt,
                                    double Mzx, double Mzy, double Mzz, double Mzt) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    protected void _releaseInput(long ptr) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    public void performWindowDrag() {
        MacWindowCalls.performDrag(getRawHandle());
    }

    public void performTitleBarDoubleClickAction() {
        MacWindowCalls.performTitleBarDoubleClickAction(getRawHandle());
    }

    private void onHeaderButtonHeightChanged(Number height) {
        double h = height != null ? height.doubleValue() : HeaderBar.USE_DEFAULT_SIZE;
        var toolbarStyle = NSWindowToolbarStyle.ofHeight(h);
        MacWindowCalls.setButtonStyle(getRawHandle(), toolbarStyle.style, h != 0);
        updateHeaderButtonMetrics(toolbarStyle, h);
    }

    private void onHeaderButtonDarkStyleChanged(Boolean dark) {
        MacWindowCalls.setButtonAppearance(getRawHandle(), dark == Boolean.TRUE);
    }

    private void updateHeaderButtonMetrics(NSWindowToolbarStyle toolbarStyle, double buttonHeight) {
        double minHeight = NSWindowToolbarStyle.SMALL.size.getHeight();
        var empty = new Dimension2D(0, 0);
        var size = isUtilityWindow() ? toolbarStyle.utilitySize : toolbarStyle.size;

        HeaderButtonMetrics metrics = buttonHeight != 0
            ? MacWindowCalls.isRightToLeftLayoutDirection()
                ? new HeaderButtonMetrics(empty, size, minHeight)
                : new HeaderButtonMetrics(size, empty, minHeight)
            : new HeaderButtonMetrics(empty, empty, minHeight);

        headerButtonMetrics.set(metrics);
    }

    /**
     * Where the window slots of {@code GmacWindowCallbacks} reach this window: the methods the JNI called on its
     * global reference, through the IDs {@code _initIDs} looked up on {@code MacWindow}.
     */
    private record CallbackTarget(MacWindow window) implements MacWindowCallbacks.Receiver {

        @Override
        public void notifyMove(int x, int y, boolean isZoomed) {
            window.notifyMove(x, y, isZoomed);
        }

        @Override
        public void notifyResize(int type, int width, int height) {
            window.notifyResize(type, width, height);
        }

        @Override
        public void notifyMoveToAnotherScreen(Screen screen) {
            window.notifyMoveToAnotherScreen(screen);
        }

        @Override
        public void notifyScaleChanged(float platformScaleX, float platformScaleY, float outputScaleX,
                                       float outputScaleY) {
            window.notifyScaleChanged(platformScaleX, platformScaleY, outputScaleX, outputScaleY);
        }

        @Override
        public void notifyClose() {
            window.notifyClose();
        }

        @Override
        public void notifyFocus(int event) {
            window.notifyFocus(event);
        }

        @Override
        public void notifyFocusUngrab() {
            window.notifyFocusUngrab();
        }

        @Override
        public void notifyFocusDisabled() {
            window.notifyFocusDisabled();
        }

        @Override
        public void notifyDestroy() {
            window.notifyDestroy();
        }
    }

    private enum NSWindowToolbarStyle {
        SMALL(68, 28, 1), // NSWindowToolbarStyleExpanded
        MEDIUM(78, 38, 4), // NSWindowToolbarStyleUnifiedCompact
        LARGE(90, 52, 3); // NSWindowToolbarStyleUnified

        NSWindowToolbarStyle(double width, double height, int style) {
            this.size = new Dimension2D(width, height);
            this.utilitySize = new Dimension2D(height, height); // width intentionally set to height
            this.style = style;
        }

        final Dimension2D size;
        final Dimension2D utilitySize;
        final int style;

        static NSWindowToolbarStyle ofHeight(double height) {
            if (height >= LARGE.size.getHeight()) return LARGE;
            if (height >= MEDIUM.size.getHeight()) return MEDIUM;
            return SMALL;
        }
    }
}

