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

#include "common.h"

#include "GlassAccessibleJni.h"
#include "glass_win_api.h"

/*
 * UI Automation's JNI plumbing (GlassAccessibleJni.h), moved here from Utils.cpp with one
 * change: javaIDs.Application.reportExceptionMID, the last javaIDs member anything read, is the file-static
 * s_reportExceptionMID. GetJVM is gone - its one reader, the test hook below, reads jvm directly.
 */

/*
 * Initialize the Java VM instance variable when the library is
 * first loaded
 */
static JavaVM *jvm;

JNIEnv* GetEnv()
{
    void* env;
    jvm->GetEnv(&env, JNI_VERSION_1_2);
    return (JNIEnv*)env;
}

/*
 * com.sun.glass.ui.Application as a GLOBAL ref, published once by InitExceptionReporting from inside a
 * JNI native method. Since the ABI 3 run-loop flip the UI Automation callbacks that report through
 * CheckAndClearException run inside the gwin_run_loop FFM downcall, where FindClass cannot see any
 * javafx.graphics class (GlassAccessibleJni.h) - so the class must come from here, not from a lookup at
 * report time.
 */
static void* volatile s_applicationClass = NULL;

/* Application.reportException's id: InitExceptionReporting writes it, or CheckAndClearException lazily. */
static jmethodID s_reportExceptionMID = NULL;

void InitExceptionReporting(JNIEnv* env)
{
    if (s_applicationClass != NULL) {
        return;
    }
    jclass cls = env->FindClass("com/sun/glass/ui/Application");
    if (cls == NULL || env->ExceptionCheck()) {
        env->ExceptionClear();
        return;
    }
    jmethodID mid = env->GetStaticMethodID(cls, "reportException", "(Ljava/lang/Throwable;)V");
    if (mid == NULL || env->ExceptionCheck()) {
        env->ExceptionClear();
        env->DeleteLocalRef(cls);
        return;
    }
    jobject global = env->NewGlobalRef(cls);
    env->DeleteLocalRef(cls);
    if (global == NULL) {
        env->ExceptionClear();
        return;
    }
    s_reportExceptionMID = mid;   // the same id CheckAndClearException's lazy fallback resolves
    if (::InterlockedCompareExchangePointer(&s_applicationClass, global, NULL) != NULL) {
        env->DeleteGlobalRef(global);   // another caller published first
    }
}

jboolean CheckAndClearException(JNIEnv* env)
{
    jthrowable t = env->ExceptionOccurred();
    if (!t) {
        return JNI_FALSE;
    }
    env->ExceptionClear();

    // FindClass is only the fallback for a JNI native running before any cache was taken; from inside
    // an FFM downcall it fails and the exception is lost, which is why the cached class comes first.
    jclass cls = (jclass) s_applicationClass;
    bool localClass = false;
    if (cls == NULL) {
        cls = env->FindClass("com/sun/glass/ui/Application");
        if (env->ExceptionOccurred()) {
            env->ExceptionClear();
            return JNI_TRUE;
        }
        localClass = true;
    }
    // Resolved here only if no JNI native has cached it (InitExceptionReporting). Same id either way.
    if (s_reportExceptionMID == NULL) {
        s_reportExceptionMID =
            env->GetStaticMethodID(cls, "reportException", "(Ljava/lang/Throwable;)V");
        if (env->ExceptionOccurred() || s_reportExceptionMID == NULL) {
            env->ExceptionClear();
            return JNI_TRUE;
        }
    }
    env->CallStaticVoidMethod(cls, s_reportExceptionMID, t);
    if (env->ExceptionOccurred()) {
        env->ExceptionClear();
        return JNI_TRUE;
    }
    if (localClass) {
        env->DeleteLocalRef(cls);   // never on the cached GLOBAL ref
    }

    return JNI_TRUE;
}

extern "C" {

/*
 * ---- The JNI exception sink under a downcall (glass_win_api.h; test hook) ----
 *
 * Throws a Java RuntimeException on the calling thread and hands it to CheckAndClearException, from inside
 * whatever frame called this - from Java that is an FFM downcall, the frame the UI Automation callbacks
 * run in. FindClass is safe for java/lang/RuntimeException even there: java.lang is boot-loaded, and the
 * boot loader is exactly what FindClass searches inside a downcall. It is the Application class that
 * must never be looked up this way (GlassAccessibleJni.h, InitExceptionReporting). Moved here from
 * glass_win_api.cpp, beside the code it tests.
 */
int32_t gwin_test_report_exception_in_downcall(void)
{
    try {
        if (jvm == NULL) {
            return GWIN_ERR_INVALID_ARG;   // not loaded through System.loadLibrary: no JNI_OnLoad
        }
        JNIEnv* env = GetEnv();
        if (env == NULL) {
            return GWIN_ERR_INVALID_ARG;   // this thread is not attached to the JVM
        }
        jclass rte = env->FindClass("java/lang/RuntimeException");
        if (rte != NULL) {
            env->ThrowNew(rte, "gwin_test_report_exception_in_downcall");
            env->DeleteLocalRef(rte);
        }
        int32_t handled = CheckAndClearException(env) ? 1 : 0;
        if (env->ExceptionCheck()) {
            env->ExceptionClear();   // nothing may be pending when a downcall returns
        }
        return handled;
    } catch (...) {
        return GWIN_ERR_INVALID_ARG;
    }
}

#ifdef STATIC_BUILD
JNIEXPORT jint JNICALL JNI_OnLoad_glass(JavaVM *vm, void *reserved)
#else
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved)
#endif
{
    jvm = vm;
    return JNI_VERSION_1_2;
}

} // extern "C"
