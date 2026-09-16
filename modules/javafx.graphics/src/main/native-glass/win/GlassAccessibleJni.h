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

#ifndef _GLASS_ACCESSIBLE_JNI_
#define _GLASS_ACCESSIBLE_JNI_

#include <jni.h>

/*
 * The JNI plumbing UI Automation still needs, and nothing else in glass.dll uses (moved
 * from Utils.h / Utils.cpp): GetEnv for the GlassAccessible / GlassTextRangeProvider COM objects,
 * CheckAndClearException for their 74 report sites, InitExceptionReporting for WinAccessible._initIDs,
 * and - in GlassAccessibleJni.cpp - JNI_OnLoad, which stores the JavaVM GetEnv reads. Every other peer
 * reaches the library through glass_win_api.h and holds no JNI state.
 */

JNIEnv* GetEnv();

// Returns JNI_TRUE if there are exceptions
jboolean CheckAndClearException(JNIEnv *env);

/*
 * Caches a global ref to com.sun.glass.ui.Application and its reportException id for
 * CheckAndClearException. CALL ONLY FROM A JNI NATIVE METHOD (WinAccessible._initIDs, the only caller
 * now that WinApplication.initIDs is gone): FindClass resolves against the loader of the
 * nearest Java frame, which inside an FFM downcall is java.base's DowncallStub - the boot loader, which
 * cannot see javafx.graphics. Idempotent and thread-safe: the first successful caller wins. Leaves no
 * exception pending. Not from JNI_OnLoad either: FindClass initialises the class, out of Glass's order.
 */
void InitExceptionReporting(JNIEnv *env);

#endif // _GLASS_ACCESSIBLE_JNI_
