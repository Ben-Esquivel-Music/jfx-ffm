/*
 * Copyright (c) 2012, 2024, Oracle and/or its affiliates. All rights reserved.
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

#include <jni.h>
#include <stdlib.h>
#include <assert.h>
#include <stdio.h>
#include <string.h>
#include <math.h>

#include "../PrismES2Defs.h"

#ifdef STATIC_BUILD
JNIEXPORT jint JNICALL JNI_OnLoad_prism_es2(JavaVM *vm, void * reserved) {
#ifdef JNI_VERSION_1_8
    //min. returned JNI_VERSION required by JDK8 for builtin libraries
    JNIEnv *env;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_8) != JNI_OK) {
        return JNI_VERSION_1_4;
    }
    return JNI_VERSION_1_8;
#else
    return JNI_VERSION_1_4;
#endif // JNI_VERSION_1_8
}
#endif // STATIC_BUILD

PIXELFORMATDESCRIPTOR getPFD(jint* attrArr) {

    static PIXELFORMATDESCRIPTOR pfd = {
        sizeof (PIXELFORMATDESCRIPTOR),
        1, /* Version number */
        PFD_SUPPORT_OPENGL,
        PFD_TYPE_RGBA,
        24, /* 24 bit color depth */
        0, 0, 0, /* RGB bits and pixel sizes */
        0, 0, 0, /* Do not care about them */
        0, 0, /* no alpha buffer info */
        0, 0, 0, 0, 0, /* no accumulation buffer */
        24, /* 24 bit depth buffer */
        0, /* no stencil buffer */
        0, /* no auxiliary buffers */
        PFD_MAIN_PLANE, /* layer type */
        0, /* reserved, must be 0 */
        0, /* no layer mask */
        0, /* no visible mask */
        0 /* no damage mask */
    };

    if (attrArr[ONSCREEN] != 0) {
        pfd.dwFlags |= PFD_DRAW_TO_WINDOW;
    }
    if (attrArr[DOUBLEBUFFER] != 0) {
        pfd.dwFlags |= PFD_DOUBLEBUFFER;
    }
    pfd.cDepthBits = (BYTE) attrArr[DEPTH_SIZE];
    pfd.cColorBits = (BYTE) (attrArr[RED_SIZE] + attrArr[GREEN_SIZE]
            + attrArr[BLUE_SIZE] + attrArr[ALPHA_SIZE]);
    pfd.cRedBits = (BYTE) attrArr[RED_SIZE];
    pfd.cGreenBits = (BYTE) attrArr[GREEN_SIZE];
    pfd.cBlueBits = (BYTE) attrArr[BLUE_SIZE];
    pfd.cAlphaBits = (BYTE) attrArr[ALPHA_SIZE];

    return pfd;
}

LONG WINAPI WndProc(HWND hWnd, UINT msg,
        WPARAM wParam, LPARAM lParam) {

    /* This function handles any messages that we didn't. */
    /* (Which is most messages) It belongs to the OS. */
    return (LONG) DefWindowProc(hWnd, msg, wParam, lParam);
}

HWND createDummyWindow(LPCTSTR szAppName) {
    static LPCTSTR szTitle = L"Dummy Window";
    WNDCLASS wc; /* windows class structure */

    HWND hWnd;

    /* Fill in window class structure with parameters that */
    /*  describe the main window. */
    wc.style = CS_HREDRAW | CS_VREDRAW; /* Class style(s). */
    wc.lpfnWndProc = (WNDPROC) WndProc; /* Window Procedure */
    wc.cbClsExtra = 0; /* No per-class extra data. */
    wc.cbWndExtra = 0; /* No per-window extra data. */
    wc.hInstance = NULL; /* Owner of this class */
    wc.hIcon = NULL; /* Icon name */
    wc.hCursor = NULL; /* Cursor */
    wc.hbrBackground = (HBRUSH) (COLOR_WINDOW + 1); /* Default color */
    wc.lpszMenuName = NULL; /* Menu from .RC */
    wc.lpszClassName = szAppName; /* Name to register as */

    /* Register the window class */
    if (RegisterClass(&wc) == 0) {
        fprintf(stderr, "createDummyWindow: couldn't register class\n");
        return NULL;
    }

    /* Create a main window for this application instance. */
    hWnd = CreateWindow(
            szAppName, /* app name */
            szTitle, /* Text for window title bar */
            WS_OVERLAPPEDWINDOW/* Window style */
            /* NEED THESE for OpenGL calls to work!*/
            | WS_CLIPCHILDREN | WS_CLIPSIBLINGS,
            0, 0, 1, 1, /* x, y, width, height */
            NULL, /* no parent window */
            NULL, /* Use the window class menu.*/
            NULL, /* This instance owns this window */
            NULL /* We don't use any extra data */
            );

    /* If window could not be created, return zero */
    if (!hWnd) {
        fprintf(stderr, "createDummyWindow: couldn't create window\n");
        UnregisterClass(szAppName, (HINSTANCE) NULL);
        return NULL;
    }
    return hWnd;
}

void printAndReleaseResources(HWND hwnd, HGLRC hglrc, HDC hdc,
        LPCTSTR szAppName, char *message) {
    if (message != NULL) {
        fprintf(stderr, "%s\n", message);
    }
    wglMakeCurrent(NULL, NULL);
    if (hglrc != NULL) {
        wglDeleteContext(hglrc);
    }
    if ((hdc != NULL) && (hwnd != NULL)) {
        ReleaseDC(hwnd, hdc);
    }
    if (hdc != NULL) {
        DeleteObject(hdc);
    }
    if (hwnd != NULL) {
        DestroyWindow(hwnd);
        UnregisterClass(szAppName, (HINSTANCE) NULL);
    }
}

